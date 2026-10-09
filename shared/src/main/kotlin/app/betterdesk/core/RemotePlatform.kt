package app.betterdesk.core

import androidx.compose.ui.graphics.ImageBitmap

interface RemotePlatform {
    val isAndroid: Boolean
    val defaultName: String
    var languageTag: String?
        get() = null
        set(value) = Unit
    fun controlAvailable(): Boolean
    fun openControlSettings()
    fun openAppSettings() = Unit
    val mayRestrictSettings: Boolean get() = false
    fun keepConnectionAlive(active: Boolean) = Unit
    fun captureResponsive(): Boolean = true
    val preferredIncomingCodec: String get() = "jpeg"
    fun selectOutgoingCodec(codec: String) = Unit
    fun requestKeyFrame() = Unit
    val receivesAudio: Boolean get() = false
    val reduceMotion: Boolean get() = false
    /** Capture playback, never a microphone. False means unsupported or denied. */
    fun startAudio(onAudio: (Packet.Audio) -> Unit): Boolean = false
    fun stopAudioCapture() = Unit
    fun playAudio(audio: Packet.Audio) = Unit
    fun startAudioPlayback() = Unit
    fun stopAudioPlayback() = Unit
    suspend fun startSharing(onFrame: (Packet.Frame) -> Unit, onStopped: () -> Unit): Boolean
    fun stopSharing()
    fun applyInput(input: Message)
    fun decode(frame: Packet.Frame): ImageBitmap?
}
