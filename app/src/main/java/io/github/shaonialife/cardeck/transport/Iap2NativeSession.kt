package io.github.shaonialife.cardeck.transport

import io.github.shaonialife.cardeck.protocol.NativeBridge

/** JNI 会话入口的薄适配；不承载协议状态，供通道生命周期测试注入同步回调。 */
internal interface Iap2NativeSession {
    fun ensureLoaded(): Boolean

    fun create(deviceEndpoint: Long): Long

    fun setTransport(
        handle: Long,
        sender: (ByteArray) -> Unit,
    )

    fun setEventSink(
        handle: Long,
        sink: (Int, ByteArray) -> Unit,
    )

    fun attach(handle: Long)

    fun feed(
        handle: Long,
        bytes: ByteArray,
    ): Int

    fun tick(handle: Long): Int

    fun takeEvents(
        handle: Long,
        kind: Int,
    ): String?

    fun linkReady(handle: Long): Int

    fun peerMaxControl(handle: Long): Int

    fun startFlow(
        handle: Long,
        deviceName: ByteArray,
        bluetoothMac: ByteArray,
        language: ByteArray,
        tzOffsetMinutes: Int,
        dst: Int,
        wirelessAllowed: Int,
        carplayPaired: Int,
    )

    fun destroy(handle: Long)
}

internal object NativeIap2Session : Iap2NativeSession {
    override fun ensureLoaded(): Boolean = NativeBridge.ensureLoaded()

    override fun create(deviceEndpoint: Long): Long = NativeBridge.nativeSessionCreate(deviceEndpoint)

    override fun setTransport(
        handle: Long,
        sender: (ByteArray) -> Unit,
    ) {
        NativeBridge.nativeSetTransport(
            handle,
            object : NativeBridge.TransportSender {
                override fun send(bytes: ByteArray) = sender(bytes)
            },
        )
    }

    override fun setEventSink(
        handle: Long,
        sink: (Int, ByteArray) -> Unit,
    ) {
        NativeBridge.nativeSessionSetEventSink(
            handle,
            object : NativeBridge.EventSink {
                override fun onEvent(
                    msgId: Int,
                    bytes: ByteArray,
                ) = sink(msgId, bytes)
            },
        )
    }

    override fun attach(handle: Long) = NativeBridge.nativeSessionBtAttached(handle)

    override fun feed(
        handle: Long,
        bytes: ByteArray,
    ): Int = NativeBridge.nativeSessionFeed(handle, bytes)

    override fun tick(handle: Long): Int = NativeBridge.nativeSessionTick(handle)

    override fun takeEvents(
        handle: Long,
        kind: Int,
    ): String? = NativeBridge.nativeSessionTakeEvents(handle, kind)

    override fun linkReady(handle: Long): Int = NativeBridge.nativeSessionLinkReady(handle)

    override fun peerMaxControl(handle: Long): Int = NativeBridge.nativeSessionPeerMaxControl(handle)

    override fun startFlow(
        handle: Long,
        deviceName: ByteArray,
        bluetoothMac: ByteArray,
        language: ByteArray,
        tzOffsetMinutes: Int,
        dst: Int,
        wirelessAllowed: Int,
        carplayPaired: Int,
    ) = NativeBridge.nativeSessionFlowStart(
        handle,
        deviceName,
        bluetoothMac,
        language,
        tzOffsetMinutes,
        dst,
        wirelessAllowed,
        carplayPaired,
    )

    override fun destroy(handle: Long) = NativeBridge.nativeSessionDestroy(handle)
}
