package io.github.shaonialife.cardeck.transport

import java.io.Closeable
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * 链路通道：一个读线程 + 一个节奏线程驱动【原生会话】（cardeck-protocol
 * 固件真身：accessoryd iAP2Link/FSM/分包器/队列全链），向会话层暴露
 * sendControl 与 awaitReady。
 *
 * Kotlin 侧只剩传输（RFCOMM 读写）与节拍——帧解析、协商、seq/ack、
 * 重传、会话消息分发全部在 Rust（日志推断的 Iap2LinkEngine 已下线）。
 *
 * **镜像面已退役（A7 下沉 2026-11-04）**：会话消息镜像不再入队过宿主
 * （旧 consumers = Iap2SenderSession 的纯日志解析，零行为消费）——
 * EventSink 只保留 "LINK msg" 诊断日志 + 编排事件分流。
 */
class Iap2LinkChannel internal constructor(
    private val stream: DuplexStream,
    private val name: String,
    private val log: (String) -> Unit,
    private val native: Iap2NativeSession,
) : Closeable {
    constructor(
        stream: DuplexStream,
        name: String = "iap2-link",
        log: (String) -> Unit = {},
    ) : this(stream, name, log, NativeIap2Session)

    val isAlive: Boolean get() = !terminated

    /** 编排事件：WIFI=-1、ENDPOINT=-2、PHASE1_DONE=-3（Rust session_flow 发）。 */
    data class FlowEvent(
        val id: Int,
        val bytes: ByteArray,
    )

    /** 启动原生 Phase-1 编排线程（识别→MFi→问候→WiFi→端点，全固件真身）。
     *  平台值直传（JNI 注入面接线——0x4E 通知波身份域，Rust 侧不落缺省）。
     *  wirelessAllowed/carplayPaired = 平台真值（fw: 0x100152b64/70 注入口，
     *  4E0D 值/4E0E 门/4300 组1——Int 0/1 约定，审查 P1-10/11 收口）。 */
    fun startFlow(
        deviceName: ByteArray,
        bluetoothMac: ByteArray,
        language: ByteArray,
        tzOffsetMinutes: Int,
        dst: Int,
        wirelessAllowed: Int,
        carplayPaired: Int,
    ) {
        val started =
            withNativeHandle { current ->
                native.startFlow(
                    current,
                    deviceName,
                    bluetoothMac,
                    language,
                    tzOffsetMinutes,
                    dst,
                    wirelessAllowed,
                    carplayPaired,
                )
                true
            }
        if (started != true) throw IOException("flow start on closed channel")
    }

    /** 取下一个编排事件；超时返回 null。 */
    fun pollFlowEvent(timeoutMillis: Long): FlowEvent? {
        if (terminated) return null
        val event =
            try {
                flowEvents.poll(timeoutMillis, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                null
            }
        return event?.takeUnless { terminated || it === CLOSED_FLOW_EVENT }
    }

    /**
     * 所有 JNI 调用与销毁串行；原生调用可同步回调 send，所以回调不得再次进入此锁/JNI。
     * writeLock 只串行化写入。close 不拿任一锁，必须先关闭传输以解除阻塞读写。
     */
    private val nativeCallLock = Any()
    private val writeLock = Any()
    private val closeStarted = AtomicBoolean(false)
    private val channelId = CHANNEL_IDS.incrementAndGet()
    private val terminated: Boolean get() = closeStarted.get()
    private var firstWrite = true

    @Volatile
    private var readerThread: Thread? = null

    @Volatile
    private var pacerThread: Thread? = null

    private fun diagnostic(message: String) {
        // 日志失败不能阻止关闭；只新增阶段/计数，不输出报文或身份字段。
        runCatching { log("LINK lifecycle #$channelId: $message") }
    }

    private fun <T> withNativeHandle(block: (Long) -> T): T? {
        if (terminated) return null
        return synchronized(nativeCallLock) {
            if (terminated || handle == 0L) null else block(handle)
        }
    }

    private val flowEvents = LinkedBlockingQueue<FlowEvent>()
    private val readyLatch = CountDownLatch(1)

    @Volatile
    private var readySignalled = false
    private var lastEventPollMs = 0L

    /** 原生会话句柄（0 = 未创建）。 */
    private var handle: Long = 0L

    /** 原始字节转储预算（每方向每连接），真车字节级对照用。 */
    private var rxDumpBudget = RAW_DUMP_LIMIT
    private var txDumpBudget = RAW_DUMP_LIMIT_TX

    init {
        try {
            if (!native.ensureLoaded()) {
                throw IOException("cardeck_protocol 未加载——原生链路不可用")
            }
            synchronized(nativeCallLock) {
                diagnostic("create enter")
                handle = native.create(0x2000L)
                diagnostic("create returned created=${handle != 0L}")
                if (handle == 0L) throw IOException("原生会话创建失败")
                native.setTransport(handle, ::sendBytes)
                if (!terminated) {
                    // 编排事件直通；协议帧镜像仍只记 id+len，不入业务队列。
                    native.setEventSink(handle) { msgId, bytes ->
                        if (!terminated) {
                            log("LINK msg: id=0x${"%04x".format(msgId)} len=${bytes.size}")
                            if (msgId < 0) flowEvents.offer(FlowEvent(msgId, bytes))
                        }
                    }
                }
            }
            val reader = Thread({ runReader() }, "$name-reader").apply { isDaemon = true }
            val pacer = Thread({ runPacer() }, "$name-pacer").apply { isDaemon = true }
            readerThread = reader
            pacerThread = pacer
            if (!terminated) {
                reader.start()
                pacer.start()
            }
        } catch (error: Throwable) {
            finish("create/register failed: ${error.message ?: error.javaClass.simpleName}")
            throw error
        }
    }

    /** 同步 JNI 回调：只写传输；失败后返回原生调用，销毁由独立线程完成。 */
    private fun sendBytes(bytes: ByteArray) {
        if (terminated) return
        try {
            synchronized(writeLock) {
                if (terminated) return
                dumpOutbound(bytes)
                val report = firstWrite
                firstWrite = false
                if (report) diagnostic("write enter bytes=${bytes.size}")
                stream.write(bytes)
                if (report) diagnostic("write returned bytes=${bytes.size}; peer reception unconfirmed")
            }
        } catch (error: IOException) {
            finish("write failed: ${error.message ?: error.javaClass.simpleName}")
        }
    }

    /** 等待链路协商完成（原生 FSM s5 Connected）。 */
    fun awaitReady(timeoutMillis: Long): Boolean {
        if (terminated) return false
        val completed =
            try {
                if (timeoutMillis == 0L) {
                    readyLatch.await()
                    true
                } else {
                    readyLatch.await(timeoutMillis, TimeUnit.MILLISECONDS)
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                false
            }
        // Ready 探测只由节拍线程执行；关闭必须能唤醒等待者，不能让它也卡在 JNI。
        return completed && readySignalled && !terminated
    }

    /** 对端协商的最大控制载荷（已扣除帧头与校验和）。 */
    fun peerMaxControlPayload(): Int? = withNativeHandle(native::peerMaxControl)?.takeIf { it > 0 }

    override fun close() {
        finish(null)
    }

    // ---- 内部 ----

    private fun runReader() {
        try {
            // 传输已连（构造即意味着 RFCOMM 就绪）——固件 attach 流：
            // LinkStart → Attached → FSM ev2；探测/协商报文由原生链决定。
            withNativeHandle { current ->
                diagnostic("attach enter")
                native.attach(current)
                diagnostic("attach returned")
            } ?: return
            val buffer = ByteArray(4096)
            var firstRead = true
            while (!terminated) {
                if (firstRead) diagnostic("read enter")
                val count = stream.read(buffer)
                if (firstRead) {
                    diagnostic("read returned bytes=$count")
                    firstRead = false
                }
                if (count < 0) {
                    finish(null)
                    return
                }
                if (count == 0) continue
                if (terminated) return
                dumpInbound(buffer.copyOf(count))
                withNativeHandle { native.feed(it, buffer.copyOf(count)) } ?: return
            }
        } catch (error: Throwable) {
            finish("reader failed: ${error.message ?: error.javaClass.simpleName}")
        }
    }

    /**
     * 原始字节抓包（真车字节级对照的唯一手段）：每方向仅转储前
     * [RAW_DUMP_LIMIT] 字节，防止长期会话日志爆炸。
     */
    private fun dumpInbound(bytes: ByteArray) {
        if (bytes.isEmpty() || rxDumpBudget <= 0) return
        val shown = minOf(bytes.size, rxDumpBudget)
        log("RAW rx: " + bytes.copyOf(shown).joinToString(" ") { "%02x".format(it) })
        rxDumpBudget -= shown
    }

    private fun runPacer() {
        try {
            while (!terminated) {
                val sleep = withNativeHandle(native::tick)?.coerceIn(1, 20)?.toLong() ?: return
                val now = System.currentTimeMillis()
                if (now - lastEventPollMs >= 1000) {
                    lastEventPollMs = now
                    for (kind in 0..5) {
                        val ev = withNativeHandle { native.takeEvents(it, kind) }
                        if (!ev.isNullOrEmpty() && !ev.contains("\"events\":[]")) {
                            log("IAP2-EVT k=$kind " + ev.take(300))
                        }
                    }
                }
                if (!readySignalled && withNativeHandle(native::linkReady) == 1 && !terminated) {
                    readySignalled = true
                    readyLatch.countDown()
                }
                try {
                    Thread.sleep(sleep)
                } catch (_: InterruptedException) {
                    return
                }
            }
        } catch (error: Throwable) {
            finish("pacer failed: ${error.message ?: error.javaClass.simpleName}")
        }
    }

    private fun dumpOutbound(bytes: ByteArray) {
        if (txDumpBudget <= 0) return
        val shown = minOf(bytes.size, txDumpBudget)
        log("RAW tx: " + bytes.copyOf(shown).joinToString(" ") { "%02x".format(it) })
        txDumpBudget -= shown
    }

    private fun finish(reason: String?) {
        if (!closeStarted.compareAndSet(false, true)) return
        // 先记录第一失败点；不能等一个可能卡住的 nativeDestroy 返回后才记日志。
        diagnostic("close requested${reason?.let { ": $it" } ?: ""}")
        readyLatch.countDown()
        flowEvents.clear()
        flowEvents.offer(CLOSED_FLOW_EVENT)
        // 不等 writeLock/nativeCallLock：关闭底层 socket 才能解除阻塞读/写和同步 JNI 回调。
        diagnostic("transport close enter")
        runCatching { stream.close() }
            .onSuccess { diagnostic("transport close returned") }
            .onFailure { diagnostic("transport close failed: ${it.message ?: it.javaClass.simpleName}") }
        val current = Thread.currentThread()
        readerThread?.takeUnless { it === current }?.interrupt()
        pacerThread?.takeUnless { it === current }?.interrupt()
        Thread({
            synchronized(nativeCallLock) {
                val oldHandle = handle
                handle = 0L
                if (oldHandle != 0L) {
                    diagnostic("destroy enter")
                    runCatching { native.destroy(oldHandle) }
                        .onSuccess { diagnostic("destroy returned") }
                        .onFailure { diagnostic("destroy failed: ${it.message ?: it.javaClass.simpleName}") }
                }
            }
            runCatching { log("LINK closed${reason?.let { ": $it" } ?: ""}") }
        }, "$name-close").apply {
            isDaemon = true
            start()
        }
    }

    private companion object {
        val CHANNEL_IDS = AtomicLong(0)
        val CLOSED_FLOW_EVENT = FlowEvent(Int.MIN_VALUE, byteArrayOf())
        const val PACER_POLL_MILLIS = 20L

        /** 原始字节转储预算：rx 需覆盖车机全部协商帧（Audi 实测约 140B），给 512B 余量。 */
        // 预算按字节计。曾为 512/256：一次完整 bring-up 都盖不住（0x1D01 单帧即 565B），
        // 提取重放 fixture（tools/extract_car_fixtures.py）会缺尾部——提到 4096 覆盖
        // 整个识别/MFi/凭据交换，长会话靠预算封顶防日志爆炸
        const val RAW_DUMP_LIMIT = 4096
        const val RAW_DUMP_LIMIT_TX = 4096

        /** 逐帧摘要日志上限（每方向每连接）。 */
        const val FRAME_LOG_LIMIT = 40
    }
}
