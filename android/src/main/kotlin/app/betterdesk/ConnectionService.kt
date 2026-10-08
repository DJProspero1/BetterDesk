package app.betterdesk

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationCompat

/** Keeps an explicit, visible device connection alive while the user switches apps. */
class ConnectionService : Service() {
    companion object { @Volatile var active = false }
    private var wakeLock: PowerManager.WakeLock? = null
    override fun onBind(intent: Intent?) = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") { Runtime.session?.stop(); stopSelf(); return START_NOT_STICKY }
        val pt = resources.configuration.locales[0].language == "pt"
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("connection", "BetterDesk", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 10, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 11, Intent(this, ConnectionService::class.java).setAction("STOP"), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(this, "connection").setSmallIcon(R.drawable.ic_betterdesk)
            .setContentTitle("BetterDesk").setContentText(if (pt) "Disponível para ligação" else "Connection is available")
            .setContentIntent(open).setOngoing(true).setSilent(true)
            .addAction(0, if (pt) "Parar" else "Stop", stop).build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(2, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        else startForeground(2, notification)
        active = true
        if (wakeLock == null) {
            wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BetterDesk:connection").apply { acquire() }
        }
        return START_NOT_STICKY
    }
    override fun onTaskRemoved(rootIntent: Intent?) {
        Runtime.session?.stop()
        stopSelf()
    }
    override fun onDestroy() {
        active = false
        wakeLock?.let { if (it.isHeld) it.release() }; wakeLock = null
        super.onDestroy()
    }
}
