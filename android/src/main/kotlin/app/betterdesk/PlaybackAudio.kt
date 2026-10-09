package app.betterdesk

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.*
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Process
import androidx.core.content.ContextCompat
import app.betterdesk.core.Packet
import app.betterdesk.core.Wire
import java.util.concurrent.atomic.AtomicBoolean

/** Native app-playback capture only. No AudioSource.MIC or microphone foreground service. */
internal class PlaybackAudioCapture(private val context: Context, private val projection: MediaProjection) {
    private val active = AtomicBoolean(false)
    @Volatile private var record: AudioRecord? = null
    @Synchronized fun start(emit: (Packet.Audio) -> Unit): Boolean {
        if (Build.VERSION.SDK_INT < 29 || ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return false
        if (!active.compareAndSet(false, true)) return false
        return try {
            val config = AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA).addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN).excludeUid(Process.myUid()).build()
            val format = AudioFormat.Builder().setSampleRate(Wire.AUDIO_RATE).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_IN_STEREO).build()
            val minimum = AudioRecord.getMinBufferSize(Wire.AUDIO_RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
            check(minimum > 0)
            val input = AudioRecord.Builder().setAudioFormat(format).setAudioPlaybackCaptureConfig(config)
                .setBufferSizeInBytes(maxOf(minimum, Wire.AUDIO_BYTES * 4)).build()
            record = input
            check(input.state == AudioRecord.STATE_INITIALIZED)
            input.startRecording(); check(input.recordingState == AudioRecord.RECORDSTATE_RECORDING)
            Thread({
                var sequence = 0L
                try {
                    while (active.get()) {
                        val data = ByteArray(Wire.AUDIO_BYTES)
                        var used = 0
                        while (active.get() && used < data.size) {
                            val count = input.read(data, used, data.size - used, AudioRecord.READ_BLOCKING)
                            check(count > 0); used += count
                        }
                        if (active.get() && used == data.size) emit(Packet.Audio(++sequence, data))
                    }
                } catch (_: Exception) { /* Video continues if audio becomes unavailable. */ }
                finally { active.set(false); runCatching { input.stop() }; input.release(); if (record === input) record = null }
            }, "BetterDesk playback capture").apply { isDaemon = true; start() }
            true
        } catch (_: Exception) {
            active.set(false); runCatching { record?.release() }; record = null; false
        }
    }
    @Synchronized fun stop() { active.set(false); runCatching { record?.stop() }; Unit }
}

internal class AndroidAudioPlayer {
    private val lock = Any()
    private var enabled = false
    private var track: AudioTrack? = null
    fun start() = synchronized(lock) { enabled = true }
    internal fun framesPlayed(): Int = synchronized(lock) { track?.playbackHeadPosition ?: 0 }
    fun play(audio: Packet.Audio) {
        val output = synchronized(lock) {
            if (!enabled) return
            track ?: run {
                val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                if (Build.VERSION.SDK_INT >= 29) attributes.setAllowedCapturePolicy(AudioAttributes.ALLOW_CAPTURE_BY_NONE)
                val minimum = AudioTrack.getMinBufferSize(Wire.AUDIO_RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
                check(minimum > 0)
                AudioTrack.Builder().setAudioAttributes(attributes.build())
                    .setAudioFormat(AudioFormat.Builder().setSampleRate(Wire.AUDIO_RATE).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
                    .setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(maxOf(minimum, Wire.AUDIO_BYTES * 4))
                    .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY).build().apply {
                        check(state == AudioTrack.STATE_INITIALIZED); play(); track = this
                    }
            }
        }
        check(output.write(audio.pcm, 0, audio.pcm.size, AudioTrack.WRITE_BLOCKING) >= 0)
    }
    fun stop() = synchronized(lock) {
        enabled = false
        val old = track; track = null
        runCatching { old?.pause(); old?.flush(); old?.release() }; Unit
    }
}
