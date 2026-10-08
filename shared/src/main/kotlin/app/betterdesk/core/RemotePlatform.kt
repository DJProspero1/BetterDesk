package app.betterdesk.core

import androidx.compose.ui.graphics.ImageBitmap

interface RemotePlatform {
    val isAndroid: Boolean
    val defaultName: String
    fun controlAvailable(): Boolean
    fun openControlSettings()
    suspend fun startSharing(onFrame: (Packet.Frame) -> Unit, onStopped: () -> Unit): Boolean
    fun stopSharing()
    fun applyInput(input: Message)
    fun decode(frame: Packet.Frame): ImageBitmap?
}
