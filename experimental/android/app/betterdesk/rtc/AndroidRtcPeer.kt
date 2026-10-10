package app.betterdesk.rtc

import android.content.Context
import org.webrtc.*
import java.nio.ByteBuffer

/** The development-only proof never creates media sources or opens OS capture. */
class AndroidRtcPeer(context: Context) : RtcPeer() {
    private val factory: PeerConnectionFactory
    private val peer: PeerConnection
    private val channel: DataChannel

    init {
        synchronized(initializationLock) {
            if (!initialized) {
                PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context.applicationContext)
                    .setEnableInternalTracer(false)
                    .setInjectableLogger({ _, _, _ -> }, Logging.Severity.LS_NONE)
                    .createInitializationOptions())
                Logging.enableLogToDebugOutput(Logging.Severity.LS_NONE)
                initialized = true
            }
        }
        factory = PeerConnectionFactory.builder().createPeerConnectionFactory()
        peer = try { factory.createPeerConnection(PeerConnection.RTCConfiguration(emptyList()), object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState) = Unit
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                if (state == PeerConnection.IceConnectionState.FAILED || state == PeerConnection.IceConnectionState.CLOSED) channelFailed()
            }
            override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) { dispatch { gathered() } }
            override fun onIceCandidate(candidate: IceCandidate) = Unit
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) = Unit
            override fun onAddStream(stream: MediaStream) = Unit
            override fun onRemoveStream(stream: MediaStream) = Unit
            override fun onDataChannel(channel: DataChannel) { dispatch {
                try { channel.close() } finally { channel.dispose(); channelFailed() }
            } }
            override fun onRenegotiationNeeded() = Unit
        }) ?: error("RTC proof unavailable") } catch (error: Throwable) { factory.dispose(); throw error }
        peer.setAudioRecording(false); peer.setAudioPlayout(false)
        channel = try { peer.createDataChannel("betterdesk-proof-v1", DataChannel.Init().apply {
            negotiated = true; id = 0; ordered = true; protocol = "betterdesk-proof-v1"
        }) } catch (error: Throwable) { peer.dispose(); factory.dispose(); throw error }
        channel.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) = Unit
            override fun onStateChange() { dispatch {
                when (channel.state()) {
                    DataChannel.State.OPEN -> channelOpened()
                    DataChannel.State.CLOSED -> channelFailed()
                    else -> Unit
                }
            } }
            override fun onMessage(buffer: DataChannel.Buffer) { incoming(buffer.data, buffer.binary) }
        })
    }

    override fun createDescription(offer: Boolean, result: (String?) -> Unit) {
        val observer = object : SdpObserver {
            override fun onCreateSuccess(description: SessionDescription) { result(description.description) }
            override fun onCreateFailure(error: String) { result(null) }
            override fun onSetSuccess() = Unit
            override fun onSetFailure(error: String) = Unit
        }
        if (offer) peer.createOffer(observer, MediaConstraints()) else peer.createAnswer(observer, MediaConstraints())
    }
    override fun setDescription(local: Boolean, offer: Boolean, sdp: String, result: (Boolean) -> Unit) {
        val description = SessionDescription(if (offer) SessionDescription.Type.OFFER else SessionDescription.Type.ANSWER, sdp)
        val observer = object : SdpObserver {
            override fun onCreateSuccess(description: SessionDescription) = Unit
            override fun onCreateFailure(error: String) = Unit
            override fun onSetSuccess() { result(true) }
            override fun onSetFailure(error: String) { result(false) }
        }
        if (local) peer.setLocalDescription(observer, description) else peer.setRemoteDescription(observer, description)
    }
    override fun gatheredDescription(): String? = peer.localDescription?.description
    override fun gatheringComplete() = peer.iceGatheringState() == PeerConnection.IceGatheringState.COMPLETE
    override fun sendNative(bytes: ByteArray): Boolean =
        channel.state() == DataChannel.State.OPEN && channel.bufferedAmount() + bytes.size <= MAX_BUFFERED &&
            channel.send(DataChannel.Buffer(ByteBuffer.wrap(bytes), true))
    override fun closeNative() {
        try { channel.unregisterObserver(); channel.close() }
        finally {
            try { channel.dispose() }
            finally { try { peer.dispose() } finally { factory.dispose() } }
        }
    }
    companion object {
        private val initializationLock = Any()
        private var initialized = false
    }
}
