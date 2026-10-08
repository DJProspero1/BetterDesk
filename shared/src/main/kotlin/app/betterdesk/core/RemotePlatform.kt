package app.betterdesk.core

import androidx.compose.ui.graphics.ImageBitmap

interface RemotePlatform {
    val isAndroid: Boolean
    val defaultName: String
    fun controlAvailable(): Boolean
    fun openControlSettings()
    fun openAppSettings() = Unit
    val mayRestrictSettings: Boolean get() = false
    fun keepConnectionAlive(active: Boolean) = Unit
    fun captureResponsive(): Boolean = true
    val preferredIncomingCodec: String get() = "jpeg"
    fun selectOutgoingCodec(codec: String) = Unit
    fun requestKeyFrame() = Unit
    suspend fun startSharing(onFrame: (Packet.Frame) -> Unit, onStopped: () -> Unit): Boolean
    fun stopSharing()
    fun applyInput(input: Message)
    fun decode(frame: Packet.Frame): ImageBitmap?
}
