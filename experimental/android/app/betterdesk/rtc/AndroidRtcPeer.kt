package app.betterdesk.rtc

import android.content.Context
import android.media.AudioFormat
import org.webrtc.*
import org.webrtc.audio.JavaAudioDeviceModule
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.locks.LockSupport

/** Synthetic video is opt-in; never creates camera/mic/MediaProjection capture. */
class AndroidRtcPeer(context: Context, private val videoProof: Boolean = false, private val audioProof: Boolean = false) : RtcPeer(videoProof, audioProof) {
    private val factory: PeerConnectionFactory
    private val peer: PeerConnection
    private val channel: DataChannel
    private var videoSource: VideoSource? = null
    private var localVideo: VideoTrack? = null
    private var remoteVideo: VideoTrack? = null // Owned/disposed by the peer's cached receiver.
    private var audioModule: JavaAudioDeviceModule? = null
    private var audioScheduler: java.util.concurrent.ScheduledExecutorService? = null
    private var audioSource: AudioSource? = null
    private var localAudio: AudioTrack? = null
    private val audioPending = ArrayBlockingQueue<RtcAudioFrame>(4)
    private var audioTickNs = 0L // Owned by the ADM callback thread only.
    @Volatile internal var audioFailureSummary = ""; private set
    @Volatile internal var teardownStage = "open"; private set
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
        if (audioProof) try {
            audioScheduler = Executors.newSingleThreadScheduledExecutor { task -> Thread(task, "BetterDesk RTC audio proof").apply { isDaemon = true } }
            audioModule = JavaAudioDeviceModule.builder(context.applicationContext)
                .setScheduler(checkNotNull(audioScheduler))
                .setSampleRate(RtcAudioFrame.RATE).setUseStereoInput(false).setUseStereoOutput(false)
                .setUseHardwareAcousticEchoCanceler(false).setUseHardwareNoiseSuppressor(false).setEnableVolumeLogger(false)
                .setAudioBufferCallback { buffer, format, channels, rate, _, _ ->
                    // The fork's external mode has no blocking AudioRecord read or clock.
                    // Supply a 10 ms clock; never spin or replay a backlog of missed ticks.
                    val now = System.nanoTime()
                    if (audioTickNs == 0L || now - audioTickNs > 10_000_000L) audioTickNs = now
                    var wait = audioTickNs - now
                    while (wait > 0 && !Thread.currentThread().isInterrupted) {
                        LockSupport.parkNanos(wait)
                        wait = audioTickNs - System.nanoTime()
                    }
                    audioTickNs = maxOf(audioTickNs, System.nanoTime()) + 10_000_000L
                    val target = buffer.duplicate().apply { clear() }
                    try {
                        check(format == AudioFormat.ENCODING_PCM_16BIT && channels == 1 && rate == RtcAudioFrame.RATE && target.remaining() == RtcAudioFrame.BYTES)
                        while (target.hasRemaining()) target.put(0)
                        if (!isClosed) audioPending.poll()?.let { frame ->
                            if (System.nanoTime() - frame.timestampNs in 0..100_000_000L) {
                                target.clear(); frame.copyInto(target)
                                return@setAudioBufferCallback frame.timestampNs
                            }
                        }
                    } catch (error: Exception) {
                        audioFailureSummary = "external PCM: ${error.javaClass.simpleName}; format=$format channels=$channels rate=$rate bytes=${target.capacity()}"
                        failed(error)
                    }
                    System.nanoTime()
                }
                .setPlaybackSamplesReadyCallback { samples ->
                    if (!isClosed) try {
                        check(samples.audioFormat == AudioFormat.ENCODING_PCM_16BIT)
                        incomingAudio(RtcAudioSample.read(samples.data, 16, samples.sampleRate, samples.channelCount, samples.data.size / (samples.channelCount * 2)))
                    } catch (error: Exception) {
                        audioFailureSummary = "decoded PCM: ${error.javaClass.simpleName}; format=${samples.audioFormat} channels=${samples.channelCount} rate=${samples.sampleRate} bytes=${samples.data.size}"
                        failed(error)
                    }
                }.createAudioDeviceModule().also {
                    // Must happen BEFORE native factory/track creation. Never re-enable OS AudioRecord.
                    it.setAudioRecordEnabled(false)
                }
        } catch (error: Throwable) { releaseAudioModule(); throw error }
        factory = try { PeerConnectionFactory.builder().apply {
            audioModule?.let { setAudioDeviceModule(it) }
            if (videoProof) {
                setVideoEncoderFactory(SoftwareVideoEncoderFactory())
                setVideoDecoderFactory(SoftwareVideoDecoderFactory())
            }
        }.createPeerConnectionFactory() } catch (error: Throwable) { releaseAudioModule(); throw error }
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
        }) ?: error("RTC proof unavailable") } catch (error: Throwable) { factory.dispose(); releaseAudioModule(); throw error }
        // This enables external PCM delivery/playout only; the ADM's hardware recorder remains disabled.
        peer.setAudioRecording(audioProof); peer.setAudioPlayout(audioProof)
        channel = try { peer.createDataChannel("betterdesk-proof-v1", DataChannel.Init().apply {
            negotiated = true; id = 0; ordered = true; protocol = "betterdesk-proof-v1"
        }) } catch (error: Throwable) { peer.dispose(); factory.dispose(); releaseAudioModule(); throw error }
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
            if (audioProof) {
                val constraints = MediaConstraints().apply {
                    listOf("googEchoCancellation", "googAutoGainControl", "googNoiseSuppression", "googHighpassFilter").forEach {
                        optional.add(MediaConstraints.KeyValuePair(it, "false"))
                    }
                }
                audioSource = factory.createAudioSource(constraints)
                localAudio = factory.createAudioTrack("betterdesk-synthetic-audio", audioSource)
                peer.addTrack(localAudio, listOf("betterdesk-synthetic"))
            }
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
    override fun pushAudioNative(frame: RtcAudioFrame): Boolean = audioPending.offer(frame)
    fun audioCounters() = operation<RtcAudioCounters> { result ->
        check(audioProof)
        peer.getStats { report -> dispatch {
            fun count(type: String, key: String): Long = report.statsMap.values
                .filter { it.type == type && (it.members["kind"] == "audio" || it.members["mediaType"] == "audio") }
                .sumOf { (it.members[key] as? Number)?.toLong() ?: 0 }
            result.complete(RtcAudioCounters(count("outbound-rtp", "packetsSent"), count("inbound-rtp", "packetsReceived"),
                count("outbound-rtp", "bytesSent"), count("inbound-rtp", "bytesReceived")))
        } }
    }
    private fun disposeAudio() {
        audioPending.clear()
        try { localAudio?.dispose() } finally { audioSource?.dispose() }
        localAudio = null; audioSource = null
    }
    private fun releaseAudioModule() {
        try { audioModule?.release() } finally { audioScheduler?.shutdownNow() }
        audioModule = null; audioScheduler = null
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
        teardownStage = "channel"
        try { channel.unregisterObserver(); channel.close() }
        finally {
            try { channel.dispose() }
            finally {
                try { remoteVideo?.removeSink(videoSink) }
                finally {
                    try { videoSource?.capturerObserver?.onCapturerStopped() }
                    finally {
                        teardownStage = "peer"
                        try { peer.dispose() }
                        finally { try { localVideo?.dispose() }
                            finally { try { videoSource?.dispose() } finally {
                                teardownStage = "audio tracks"
                                try { disposeAudio() } finally {
                                    teardownStage = "factory"
                                    try { factory.dispose() } finally {
                                        teardownStage = "audio module"
                                        releaseAudioModule()
                                        teardownStage = "closed"
                                    }
                                }
                            } }
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
