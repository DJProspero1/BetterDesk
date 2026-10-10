package app.betterdesk.rtc

import dev.onvoid.webrtc.*
import dev.onvoid.webrtc.logging.Logging
import dev.onvoid.webrtc.media.audio.AudioDeviceModule
import dev.onvoid.webrtc.media.audio.AudioLayer
import dev.onvoid.webrtc.media.video.*
import java.nio.ByteBuffer

/** Host-only ICE proof. Optional synthetic video; no OS capture or sound devices. */
class DesktopRtcPeer(private val videoProof: Boolean = false) : RtcPeer(videoProof) {
    private val audio = AudioDeviceModule(AudioLayer.kDummyAudio)
    private val factory: PeerConnectionFactory
    private val peer: RTCPeerConnection
    private val channel: RTCDataChannel
    private var videoSource: CustomVideoSource? = null
    private var localVideo: VideoTrack? = null
    private var remoteVideo: VideoTrack? = null
    private val videoSink = VideoTrackSink { frame ->
        if (!isClosed) try {
            val buffer = frame.buffer.toI420()
            try { incomingVideo(RtcVideoSample.read(buffer.width, buffer.height, frame.rotation, buffer.dataY, buffer.strideY)) }
            finally { if (buffer !== frame.buffer) buffer.release() }
        } catch (_: Exception) { close() }
    }

    init {
        Logging.logToDebug(Logging.Severity.NONE)
        factory = try { PeerConnectionFactory(audio) } catch (error: Throwable) { audio.dispose(); throw error }
        peer = try { factory.createPeerConnection(RTCConfiguration(), object : PeerConnectionObserver {
            override fun onIceCandidate(candidate: RTCIceCandidate) = Unit
            override fun onIceGatheringChange(state: RTCIceGatheringState) {
                if (state == RTCIceGatheringState.COMPLETE) dispatch { gathered() }
            }
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
        if (videoProof) try {
            videoSource = CustomVideoSource()
            localVideo = factory.createVideoTrack("betterdesk-synthetic-video", videoSource)
            peer.addTrack(localVideo, listOf("betterdesk-synthetic")).dispose()
        } catch (error: Throwable) { close(); throw error }
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
            override fun onSuccess() {
                if (!local && videoProof) dispatch { attachVideoReceiver(); result(true) } else result(true)
            }
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
    private fun attachVideoReceiver() {
        if (remoteVideo != null) return
        val receivers = peer.receivers
        try {
            for (receiver in receivers) {
                val track = receiver.track
                if (track is VideoTrack) {
                    check(remoteVideo == null)
                    remoteVideo = track
                    track.addSink(videoSink)
                } else track?.dispose()
            }
            check(remoteVideo != null)
        } finally { receivers.forEach { it.dispose() } }
    }
    override fun pushVideoNative(frame: RtcVideoFrame) {
        val buffer = NativeI420Buffer.allocate(frame.width, frame.height)
        val nativeFrame = VideoFrame(buffer, frame.timestampNs)
        try {
            frame.copyPlanes(buffer.dataY, buffer.strideY, buffer.dataU, buffer.strideU, buffer.dataV, buffer.strideV)
            checkNotNull(videoSource).pushFrame(nativeFrame)
        } finally { nativeFrame.release() }
    }
    /** Select aggregate RTP counters only; raw native reports contain addresses. */
    fun videoCounters() = operation<RtcVideoCounters> { result ->
        check(videoProof)
        peer.getStats { report -> dispatch {
            fun count(type: RTCStatsType, key: String): Long = report.stats.values
                .filter { it.type == type && (it.attributes["kind"] == "video" || it.attributes["mediaType"] == "video") }
                .sumOf { (it.attributes[key] as? Number)?.toLong() ?: 0 }
            result.complete(RtcVideoCounters(count(RTCStatsType.OUTBOUND_RTP, "framesEncoded"),
                count(RTCStatsType.INBOUND_RTP, "framesDecoded"), count(RTCStatsType.OUTBOUND_RTP, "bytesSent"),
                count(RTCStatsType.INBOUND_RTP, "bytesReceived")))
        } }
    }
    private fun disposeVideo() {
        try { remoteVideo?.removeSink(videoSink) }
        finally { try { remoteVideo?.dispose() }
            finally { try { localVideo?.dispose() } finally { videoSource?.dispose() } }
        }
        remoteVideo = null; localVideo = null; videoSource = null
    }
    override fun closeNative() {
        try { channel.unregisterObserver(); channel.close() }
        finally {
            try { channel.dispose() }
            finally {
                try { peer.close() }
                finally { try { disposeVideo() } finally { try { factory.dispose() } finally { audio.dispose() } } }
            }
        }
    }
}
