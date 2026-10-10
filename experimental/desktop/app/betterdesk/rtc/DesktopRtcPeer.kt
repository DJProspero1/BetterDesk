package app.betterdesk.rtc

import dev.onvoid.webrtc.*
import dev.onvoid.webrtc.logging.Logging
import dev.onvoid.webrtc.media.audio.AudioDeviceModule
import dev.onvoid.webrtc.media.audio.AudioLayer
import java.nio.ByteBuffer

/** Host-only ICE proof. No STUN/TURN, screen, sound devices or media tracks. */
class DesktopRtcPeer : RtcPeer() {
    private val audio = AudioDeviceModule(AudioLayer.kDummyAudio)
    private val factory: PeerConnectionFactory
    private val peer: RTCPeerConnection
    private val channel: RTCDataChannel

    init {
        Logging.logToDebug(Logging.Severity.NONE)
        factory = try { PeerConnectionFactory(audio) } catch (error: Throwable) { audio.dispose(); throw error }
        peer = try { factory.createPeerConnection(RTCConfiguration(), object : PeerConnectionObserver {
            override fun onIceCandidate(candidate: RTCIceCandidate) = Unit
            override fun onIceGatheringChange(state: RTCIceGatheringState) { dispatch { gathered() } }
            override fun onDataChannel(channel: RTCDataChannel) { dispatch {
                try { channel.close() } finally { channel.dispose(); channelFailed() }
            } }
            override fun onConnectionChange(state: RTCPeerConnectionState) {
                if (state == RTCPeerConnectionState.FAILED || state == RTCPeerConnectionState.CLOSED) channelFailed()
            }
        }) ?: error("RTC proof unavailable") } catch (error: Throwable) { factory.dispose(); audio.dispose(); throw error }
        channel = try { peer.createDataChannel("betterdesk-proof-v1", RTCDataChannelInit().apply {
            negotiated = true; id = 0; ordered = true; protocol = "betterdesk-proof-v1"
        }) } catch (error: Throwable) { peer.close(); factory.dispose(); audio.dispose(); throw error }
        channel.registerObserver(object : RTCDataChannelObserver {
            override fun onBufferedAmountChange(sentDataSize: Long) = Unit
            override fun onStateChange() { dispatch {
                when (channel.state) {
                    RTCDataChannelState.OPEN -> channelOpened()
                    RTCDataChannelState.CLOSED -> channelFailed()
                    else -> Unit
                }
            } }
            override fun onMessage(buffer: RTCDataChannelBuffer) { incoming(buffer.data, buffer.binary) }
        })
    }

    override fun createDescription(offer: Boolean, result: (String?) -> Unit) {
        val observer = object : CreateSessionDescriptionObserver {
            override fun onSuccess(description: RTCSessionDescription) { result(description.sdp) }
            override fun onFailure(error: String) { result(null) }
        }
        if (offer) peer.createOffer(RTCOfferOptions(), observer) else peer.createAnswer(RTCAnswerOptions(), observer)
    }
    override fun setDescription(local: Boolean, offer: Boolean, sdp: String, result: (Boolean) -> Unit) {
        val description = RTCSessionDescription(if (offer) RTCSdpType.OFFER else RTCSdpType.ANSWER, sdp)
        val observer = object : SetSessionDescriptionObserver {
            override fun onSuccess() { result(true) }
            override fun onFailure(error: String) { result(false) }
        }
        if (local) peer.setLocalDescription(description, observer) else peer.setRemoteDescription(description, observer)
    }
    override fun gatheredDescription(): String? = peer.localDescription?.sdp
    override fun gatheringComplete() = peer.iceGatheringState == RTCIceGatheringState.COMPLETE
    override fun sendNative(bytes: ByteArray): Boolean {
        if (channel.state != RTCDataChannelState.OPEN || channel.bufferedAmount + bytes.size > MAX_BUFFERED) return false
        channel.send(RTCDataChannelBuffer(ByteBuffer.wrap(bytes), true))
        return true
    }
    override fun closeNative() {
        try { channel.unregisterObserver(); channel.close() }
        finally {
            try { channel.dispose() }
            finally {
                try { peer.close() }
                finally { try { factory.dispose() } finally { audio.dispose() } }
            }
        }
    }
}
