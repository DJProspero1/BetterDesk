package app.betterdesk.rtc

import android.content.Context
import org.webrtc.*
import java.nio.ByteBuffer

/** Synthetic video is opt-in; never creates camera/mic/MediaProjection capture. */
class AndroidRtcPeer(context: Context, private val videoProof: Boolean = false) : RtcPeer(videoProof) {
    private val factory: PeerConnectionFactory
    private val peer: PeerConnection
    private val channel: DataChannel
    private var videoSource: VideoSource? = null
    private var localVideo: VideoTrack? = null
    private var remoteVideo: VideoTrack? = null // Owned/disposed by the peer's cached receiver.
    private val videoSink = VideoSink { frame ->
        if (!isClosed) try {
            val buffer = checkNotNull(frame.buffer.toI420())
            try { incomingVideo(RtcVideoSample.read(buffer.width, buffer.height, frame.rotation, buffer.dataY, buffer.strideY)) }
            finally { buffer.release() }
        } catch (_: Exception) { close() }
    }

    init {
        synchronized(initializationLock) {
            if (!initialized) {
                PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context.applicationContext)
                    .setEnableInternalTracer(false)
                    .setInjectableLogger({ _, _, _ -> }, Logging.Severity.LS_NONE)
                    .createInitializationOptions())
                initialized = true
            }
        }
        factory = PeerConnectionFactory.builder().apply {
            if (videoProof) {
                setVideoEncoderFactory(SoftwareVideoEncoderFactory())
                setVideoDecoderFactory(SoftwareVideoDecoderFactory())
            }
        }.createPeerConnectionFactory()
        peer = try { factory.createPeerConnection(PeerConnection.RTCConfiguration(emptyList()), object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState) = Unit
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                if (state == PeerConnection.IceConnectionState.FAILED || state == PeerConnection.IceConnectionState.CLOSED) channelFailed()
            }
            override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {
                if (state == PeerConnection.IceGatheringState.COMPLETE) dispatch { gathered() }
            }
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
        if (videoProof) try {
            videoSource = factory.createVideoSource(true)
            videoSource?.capturerObserver?.onCapturerStarted(true)
            localVideo = factory.createVideoTrack("betterdesk-synthetic-video", videoSource)
            peer.addTrack(localVideo, listOf("betterdesk-synthetic"))
        } catch (error: Throwable) { close(); throw error }
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
            override fun onSetSuccess() {
                if (!local && videoProof) dispatch { attachVideoReceiver(); result(true) } else result(true)
            }
            override fun onSetFailure(error: String) { result(false) }
        }
        if (local) peer.setLocalDescription(observer, description) else peer.setRemoteDescription(observer, description)
    }
    override fun gatheredDescription(): String? = peer.localDescription?.description
    override fun gatheringComplete() = peer.iceGatheringState() == PeerConnection.IceGatheringState.COMPLETE
    override fun sendNative(bytes: ByteArray): Boolean =
        channel.state() == DataChannel.State.OPEN && channel.bufferedAmount() + bytes.size <= MAX_BUFFERED &&
            channel.send(DataChannel.Buffer(ByteBuffer.wrap(bytes), true))
    private fun attachVideoReceiver() {
        if (remoteVideo != null) return
        // Android's PeerConnection owns these receiver wrappers; do not double-dispose them.
        for (receiver in peer.receivers) {
            val track = receiver.track()
            if (track is VideoTrack) {
                check(remoteVideo == null)
                remoteVideo = track
                track.addSink(videoSink)
            }
        }
        check(remoteVideo != null)
    }
    override fun pushVideoNative(frame: RtcVideoFrame) {
        val buffer = JavaI420Buffer.allocate(frame.width, frame.height)
        val nativeFrame = VideoFrame(buffer, 0, frame.timestampNs)
        try {
            frame.copyPlanes(buffer.dataY, buffer.strideY, buffer.dataU, buffer.strideU, buffer.dataV, buffer.strideV)
            checkNotNull(videoSource).capturerObserver.onFrameCaptured(nativeFrame)
        } finally { nativeFrame.release() }
    }
    fun videoCounters() = operation<RtcVideoCounters> { result ->
        check(videoProof)
        peer.getStats { report -> dispatch {
            fun count(type: String, key: String): Long = report.statsMap.values
                .filter { it.type == type && (it.members["kind"] == "video" || it.members["mediaType"] == "video") }
                .sumOf { (it.members[key] as? Number)?.toLong() ?: 0 }
            result.complete(RtcVideoCounters(count("outbound-rtp", "framesEncoded"), count("inbound-rtp", "framesDecoded"),
                count("outbound-rtp", "bytesSent"), count("inbound-rtp", "bytesReceived")))
        } }
    }
    override fun closeNative() {
        try { channel.unregisterObserver(); channel.close() }
        finally {
            try { channel.dispose() }
            finally {
                try { remoteVideo?.removeSink(videoSink) }
                finally {
                    try { videoSource?.capturerObserver?.onCapturerStopped() }
                    finally {
                        try { peer.dispose() }
                        finally { try { localVideo?.dispose() }
                            finally { try { videoSource?.dispose() } finally { factory.dispose() } }
                        }
                    }
                }
            }
        }
    }
    companion object {
        private val initializationLock = Any()
        private var initialized = false
    }
}
