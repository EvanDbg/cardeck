package io.github.shaonialife.cardeck.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * 使用实际通道和可同步回调的假原生会话，验证宿主锁序/句柄寿命，不模拟车机协议。
 * Semaphore 故意非重入：对应固件会话表锁，不能用 JVM 可重入 monitor 掩盖回调重入。
 */
class Iap2LinkChannelLifecycleTest {
    @Test
    fun synchronousWriteFailureReturnsFromNativeBeforeDestroyAndAllowsNewChannel() {
        val table = NativeTable()
        val native = FakeNative(table).apply { sendFrom = "attach" }
        val stream = TestStream().apply { writeFailure = IOException("synthetic write failure") }
        val logs = CopyOnWriteArrayList<String>()
        val channel = channel(stream, native, logs)

        await(native.attachedReturned)
        await(native.destroyed)
        assertFalse(channel.isAlive)
        assertEquals(1, stream.closeCount.get())
        assertEquals(1, native.destroyCount.get())
        assertTrue(indexOf(logs, "write failed") < indexOf(logs, "destroy enter"))
        assertEquals(0, table.invalidCalls.get())

        val replacementNative = FakeNative(table)
        val replacement = AtomicReference<Iap2LinkChannel>()
        val created = launch { replacement.set(channel(TestStream(), replacementNative)) }
        await(created)
        assertTrue(replacement.get().isAlive)
        replacement.get().close()
        await(replacementNative.destroyed)
        assertEquals(2L, table.nextHandle.get() - 1)
    }

    @Test
    fun closeWhileNativeIsAboutToSendDoesNotInvertLocksOrBlockWaiters() {
        val allowSend = CountDownLatch(1)
        val native =
            FakeNative().apply {
                sendFrom = "attach"
                beforeSend = { awaitUninterruptibly(allowSend) }
            }
        val stream = TestStream()
        val channel = channel(stream, native)
        await(native.attachedEntered)
        val ready = AtomicReference<Boolean>()
        val readyWaiter = launch { ready.set(channel.awaitReady(0)) }
        val flow = AtomicReference<Iap2LinkChannel.FlowEvent>()
        val flowWaiter = launch { flow.set(channel.pollFlowEvent(60_000)) }
        try {
            await(launch { channel.close() })
            await(readyWaiter)
            await(flowWaiter)
            assertEquals(false, ready.get())
            assertNull(flow.get())
            assertEquals(1, stream.closeCount.get())
            assertEquals(0, native.destroyCount.get())
        } finally {
            allowSend.countDown()
        }
        await(native.attachedReturned)
        await(native.destroyed)
        assertEquals(0, stream.writeCount.get())
    }

    @Test
    fun closeUnblocksWriteWithoutWaitingForWriteOrNativeLock() {
        val native = FakeNative().apply { sendFrom = "attach" }
        val stream = TestStream().apply { blockWrite = true }
        val channel = channel(stream, native)
        await(stream.writeEntered)
        val result = AtomicReference<Boolean>()
        val waiter = launch { result.set(channel.awaitReady(0)) }

        await(launch { channel.close() })
        await(waiter)
        await(native.attachedReturned)
        await(native.destroyed)
        assertEquals(false, result.get())
        assertEquals(1, stream.closeCount.get())
        assertEquals(1, native.destroyCount.get())
    }

    @Test
    fun closeUnblocksReadAndLateReadCannotFeedDestroyedHandle() {
        val native = FakeNative()
        val stream = TestStream().apply { returnBytesAfterClose = true }
        val channel = channel(stream, native)
        await(stream.readEntered)

        await(launch { channel.close() })
        await(stream.readReturned)
        await(native.destroyed)
        assertEquals(0, native.feedCount.get())
        assertEquals(0, native.table.invalidCalls.get())
    }

    @Test
    fun concurrentCloseDestroysOnceAndLateCallbacksAreIgnored() {
        val native = FakeNative()
        val stream = TestStream()
        val channel = channel(stream, native)
        await(stream.readEntered)
        val start = CountDownLatch(1)
        val closes =
            List(8) {
                launch {
                    awaitUninterruptibly(start)
                    channel.close()
                }
            }
        start.countDown()
        closes.forEach(::await)
        await(native.destroyed)

        native.sender.get()!!.invoke(byteArrayOf(1))
        native.sink.get()!!.invoke(-1, byteArrayOf(2))
        assertFalse(channel.awaitReady(1))
        assertNull(channel.peerMaxControlPayload())
        assertNull(channel.pollFlowEvent(1))
        assertEquals(0, stream.writeCount.get())
        assertEquals(1, stream.closeCount.get())
        assertEquals(1, native.destroyCount.get())
        assertEquals(0, native.table.invalidCalls.get())
    }

    @Test
    fun failedRegistrationClosesTransportAndDestroysCreatedHandleOnce() {
        val native = FakeNative().apply { failRegistration = true }
        val stream = TestStream()
        val logs = CopyOnWriteArrayList<String>()
        try {
            channel(stream, native, logs)
            fail("registration must fail")
        } catch (_: IOException) {
        }
        await(native.destroyed)
        assertEquals(1, stream.closeCount.get())
        assertEquals(1, native.destroyCount.get())
        assertEquals(1L, native.attachedEntered.count)
        assertTrue(indexOf(logs, "create/register failed") < indexOf(logs, "destroy enter"))
    }

    @Test
    fun failedTickClosesChannelAndWakesReadyWaiter() {
        val native = FakeNative().apply { failTick = true }
        val stream = TestStream()
        val logs = CopyOnWriteArrayList<String>()
        val channel = channel(stream, native, logs)

        assertFalse(channel.awaitReady(2_000))
        await(native.destroyed)
        assertFalse(channel.isAlive)
        assertEquals(1, stream.closeCount.get())
        assertEquals(1, native.destroyCount.get())
        assertTrue(indexOf(logs, "pacer failed") < indexOf(logs, "destroy enter"))
    }

    @Test
    fun linkReadyCanSynchronouslySendAndFailWithoutReportingReady() {
        val native =
            FakeNative().apply {
                sendFrom = "ready"
                ready = true
            }
        val stream = TestStream().apply { writeFailure = IOException("ready flush write failure") }
        val channel = channel(stream, native)

        assertFalse(channel.awaitReady(2_000))
        await(native.readyReturned)
        await(native.destroyed)
        assertEquals(1, native.destroyCount.get())
        assertEquals(0, native.table.invalidCalls.get())
    }

    @Test
    fun closePreventsQueuedNativeCallsAndLateReadySuccess() {
        val allowReady = CountDownLatch(1)
        val native =
            FakeNative().apply {
                ready = true
                beforeReady = { awaitUninterruptibly(allowReady) }
            }
        val channel = channel(TestStream(), native)
        await(native.readyEntered)
        val flowResult = AtomicReference<Throwable>()
        val flowCaller =
            launch {
                try {
                    channel.startFlow(byteArrayOf(), byteArrayOf(), byteArrayOf(), 0, 0, 1, 0)
                } catch (error: Throwable) {
                    flowResult.set(error)
                }
            }
        val peer = AtomicReference<Int>()
        val peerCaller = launch { peer.set(channel.peerMaxControlPayload()) }
        val readyResult = AtomicReference<Boolean>()
        val readyWaiter = launch { readyResult.set(channel.awaitReady(0)) }
        try {
            await(launch { channel.close() })
            await(readyWaiter)
            assertEquals(false, readyResult.get())
        } finally {
            allowReady.countDown()
        }
        await(flowCaller)
        await(peerCaller)
        await(native.readyReturned)
        await(native.destroyed)
        assertTrue(flowResult.get() is IOException)
        assertNull(peer.get())
        assertEquals(0, native.flowCount.get())
        assertEquals(0, native.peerCount.get())
        assertFalse(channel.awaitReady(1))
        assertEquals(0, native.table.invalidCalls.get())
    }

    @Test
    fun failedTransportCloseStillDestroysAndLogsFailureAccurately() {
        val native = FakeNative()
        val stream = TestStream().apply { failClose = true }
        val logs = CopyOnWriteArrayList<String>()
        val channel = channel(stream, native, logs)

        await(launch { channel.close() })
        await(native.destroyed)
        assertTrue(logs.any { it.contains("transport close failed") })
        assertFalse(logs.any { it.contains("transport close returned") })
        assertEquals(1, native.destroyCount.get())
    }

    @Test
    fun closeAfterReadyBeforeFlowStartIsReportedAsIoCancellation() {
        val native = FakeNative().apply { ready = true }
        val channel = channel(TestStream(), native)
        assertTrue(channel.awaitReady(2_000))
        channel.close()
        try {
            channel.startFlow(byteArrayOf(), byteArrayOf(), byteArrayOf(), 0, 0, 1, 0)
            fail("closed channel must reject flow start")
        } catch (_: IOException) {
        }
        await(native.destroyed)
        assertEquals(0, native.flowCount.get())
        assertFalse(channel.awaitReady(1))
    }

    @Test
    fun delayedOldCleanupCannotDestroyNewChannelHandle() {
        val table = NativeTable()
        val destroyEntered = CountDownLatch(1)
        val allowDestroy = CountDownLatch(1)
        val oldNative =
            FakeNative(table).apply {
                beforeDestroy = {
                    destroyEntered.countDown()
                    awaitUninterruptibly(allowDestroy)
                }
            }
        val oldChannel = channel(TestStream(), oldNative)
        oldChannel.close()
        await(destroyEntered)
        val nextNative = FakeNative(table).apply { ready = true }
        val replacement = AtomicReference<Iap2LinkChannel>()
        try {
            await(launch { replacement.set(channel(TestStream(), nextNative)) })
            assertTrue(replacement.get().awaitReady(2_000))
        } finally {
            allowDestroy.countDown()
        }
        await(oldNative.destroyed)
        assertTrue(replacement.get().isAlive)
        assertEquals(1024, replacement.get().peerMaxControlPayload())
        assertEquals(1, table.handles.size)
        assertEquals(0, nextNative.destroyCount.get())
        replacement.get().close()
        await(nextNative.destroyed)
        assertEquals(0, table.invalidCalls.get())
    }

    @Test
    fun nativeDestroyFailureIsNotLoggedAsSuccessfulReturnAndIsNotRetried() {
        val native = FakeNative().apply { failDestroy = true }
        val logs = CopyOnWriteArrayList<String>()
        val loggedFailure = CountDownLatch(1)
        val channel =
            Iap2LinkChannel(TestStream(), "lifecycle-test", {
                logs += it
                if (it.contains("destroy failed")) loggedFailure.countDown()
            }, native)

        await(launch { channel.close() })
        await(loggedFailure)
        channel.close()
        assertEquals(1, native.destroyCount.get())
        assertFalse(logs.any { it.contains("destroy returned") })
    }

    private fun channel(
        stream: TestStream,
        native: FakeNative,
        logs: MutableList<String> = CopyOnWriteArrayList(),
    ): Iap2LinkChannel = Iap2LinkChannel(stream, "lifecycle-test", { logs += it }, native)

    private fun indexOf(
        logs: List<String>,
        text: String,
    ): Int {
        val index = logs.indexOfFirst { it.contains(text) }
        assertTrue("missing diagnostic: $text", index >= 0)
        return index
    }

    private class TestStream : DuplexStream {
        val readEntered = CountDownLatch(1)
        val readReturned = CountDownLatch(1)
        val writeEntered = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val closeCount = AtomicInteger()
        val writeCount = AtomicInteger()
        var writeFailure: IOException? = null
        var blockWrite = false
        var returnBytesAfterClose = false
        var failClose = false

        override fun read(buffer: ByteArray): Int {
            readEntered.countDown()
            awaitUninterruptibly(closed)
            readReturned.countDown()
            return if (returnBytesAfterClose) 1 else -1
        }

        override fun write(bytes: ByteArray) {
            writeCount.incrementAndGet()
            writeEntered.countDown()
            writeFailure?.let { throw it }
            if (blockWrite) {
                awaitUninterruptibly(closed)
                throw IOException("write released by close")
            }
        }

        override fun close() {
            closeCount.incrementAndGet()
            closed.countDown()
            if (failClose) throw IOException("synthetic close failure")
        }
    }

    private class NativeTable {
        val mutex = Semaphore(1)
        val nextHandle = AtomicLong(1)
        val handles = ConcurrentHashMap.newKeySet<Long>()
        val invalidCalls = AtomicInteger()
    }

    private class FakeNative(
        val table: NativeTable = NativeTable(),
    ) : Iap2NativeSession {
        val sender = AtomicReference<(ByteArray) -> Unit>()
        val sink = AtomicReference<(Int, ByteArray) -> Unit>()
        val attachedEntered = CountDownLatch(1)
        val attachedReturned = CountDownLatch(1)
        val readyEntered = CountDownLatch(1)
        val readyReturned = CountDownLatch(1)
        val destroyed = CountDownLatch(1)
        val destroyCount = AtomicInteger()
        val feedCount = AtomicInteger()
        val flowCount = AtomicInteger()
        val peerCount = AtomicInteger()
        var sendFrom: String? = null
        var beforeSend: () -> Unit = {}
        var beforeReady: () -> Unit = {}
        var beforeDestroy: () -> Unit = {}
        var failRegistration = false
        var failTick = false
        var failDestroy = false
        var ready = false
        private val sent = AtomicBoolean()

        private fun <T> locked(
            handle: Long? = null,
            action: () -> T,
        ): T {
            table.mutex.acquireUninterruptibly()
            try {
                if (handle != null && !table.handles.contains(handle)) table.invalidCalls.incrementAndGet()
                return action()
            } finally {
                table.mutex.release()
            }
        }

        private fun maybeSend(origin: String) {
            if (sendFrom == origin && sent.compareAndSet(false, true)) {
                beforeSend()
                sender.get()!!.invoke(byteArrayOf(1))
            }
        }

        override fun ensureLoaded(): Boolean = true

        override fun create(deviceEndpoint: Long): Long =
            locked {
                table.nextHandle.getAndIncrement().also { table.handles.add(it) }
            }

        override fun setTransport(
            handle: Long,
            sender: (ByteArray) -> Unit,
        ) = locked(handle) {
            this.sender.set(sender)
        }

        override fun setEventSink(
            handle: Long,
            sink: (Int, ByteArray) -> Unit,
        ) = locked(handle) {
            if (failRegistration) throw IOException("synthetic registration failure")
            this.sink.set(sink)
        }

        override fun attach(handle: Long) =
            locked(handle) {
                attachedEntered.countDown()
                maybeSend("attach")
                attachedReturned.countDown()
            }

        override fun feed(
            handle: Long,
            bytes: ByteArray,
        ): Int = locked(handle) { feedCount.incrementAndGet() }

        override fun tick(handle: Long): Int =
            locked(handle) {
                if (failTick) throw IOException("synthetic tick failure")
                20
            }

        override fun takeEvents(
            handle: Long,
            kind: Int,
        ): String? = locked(handle) { null }

        override fun linkReady(handle: Long): Int =
            locked(handle) {
                readyEntered.countDown()
                beforeReady()
                maybeSend("ready")
                readyReturned.countDown()
                if (ready) 1 else 0
            }

        override fun peerMaxControl(handle: Long): Int =
            locked(handle) {
                peerCount.incrementAndGet()
                1024
            }

        override fun startFlow(
            handle: Long,
            deviceName: ByteArray,
            bluetoothMac: ByteArray,
            language: ByteArray,
            tzOffsetMinutes: Int,
            dst: Int,
            wirelessAllowed: Int,
            carplayPaired: Int,
        ) = locked(handle) {
            flowCount.incrementAndGet()
            Unit
        }

        override fun destroy(handle: Long) {
            beforeDestroy()
            locked(handle) {
                destroyCount.incrementAndGet()
                table.handles.remove(handle)
                destroyed.countDown()
                if (failDestroy) throw IOException("synthetic destroy failure")
            }
        }
    }

    private companion object {
        fun await(latch: CountDownLatch) {
            assertTrue("operation failed to complete", latch.await(2, TimeUnit.SECONDS))
        }

        fun launch(action: () -> Unit): CountDownLatch {
            val done = CountDownLatch(1)
            Thread({
                action()
                done.countDown()
            }, "lifecycle-test-worker").apply {
                isDaemon = true
                start()
            }
            return done
        }

        fun awaitUninterruptibly(latch: CountDownLatch) {
            var interrupted = false
            while (true) {
                try {
                    latch.await()
                    break
                } catch (_: InterruptedException) {
                    interrupted = true
                }
            }
            if (interrupted) Thread.currentThread().interrupt()
        }
    }
}
