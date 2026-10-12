package app.betterdesk

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import app.betterdesk.core.UpdateAsset
import app.betterdesk.core.UpdatePlatform
import java.io.File
import java.io.IOException

/**
 * Hands a verified APK to Android's own installer. Android always shows its update
 * confirmation and refuses an APK signed with a different key; nothing bypasses that.
 */
class AndroidUpdate(private val context: Context) : UpdatePlatform {
    override val target = "android"
    private val installed = context.packageManager.getPackageInfo(context.packageName, signingFlags())
    override val currentVersion: String = installed.versionName.orEmpty()
    @Suppress("DEPRECATION")
    override val currentVersionCode: Long = if (Build.VERSION.SDK_INT >= 28) installed.longVersionCode else installed.versionCode.toLong()

    override fun stagingFile(asset: UpdateAsset) = File(context.cacheDir, "updates").apply { mkdirs() }.resolve("update.apk")
    override fun canInstall() = context.packageManager.canRequestPackageInstalls()
    override fun openInstallSettings() {
        context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, android.net.Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    override fun install(file: File, onResult: (Boolean) -> Unit) {
        try { checkArchive(file) } catch (e: Exception) { file.delete(); throw e }
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
        }
        val id = installer.createSession(params)
        try {
            installer.openSession(id).use { session ->
                session.openWrite("betterdesk.apk", 0, file.length()).use { output -> file.inputStream().use { it.copyTo(output) }; session.fsync(output) }
                val action = "${context.packageName}.UPDATE_RESULT.$id"
                ContextCompat.registerReceiver(context, object : BroadcastReceiver() {
                    override fun onReceive(receiver: Context, intent: Intent) {
                        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
                            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                                @Suppress("DEPRECATION")
                                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                                if (confirm == null) { finish(false); return }
                                runCatching { context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.onFailure { finish(false) }
                            }
                            // On success Android replaces and stops this process.
                            PackageInstaller.STATUS_SUCCESS -> finish(true)
                            else -> finish(false)
                        }
                    }
                    private fun finish(success: Boolean) { runCatching { context.unregisterReceiver(this) }; file.delete(); onResult(success) }
                }, IntentFilter(action), ContextCompat.RECEIVER_NOT_EXPORTED)
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                session.commit(PendingIntent.getBroadcast(context, id, Intent(action).setPackage(context.packageName), flags).intentSender)
            }
        } catch (e: Exception) { runCatching { installer.abandonSession(id) }; file.delete(); throw e }
    }

    /** Refuse early, with a clear failure, what Android would refuse later anyway. */
    private fun checkArchive(file: File) {
        val archive = context.packageManager.getPackageArchiveInfo(file.path, signingFlags()) ?: throw IOException("Not an APK")
        @Suppress("DEPRECATION")
        val code = if (Build.VERSION.SDK_INT >= 28) archive.longVersionCode else archive.versionCode.toLong()
        if (archive.packageName != context.packageName || code <= currentVersionCode) throw IOException("Not a newer BetterDesk")
        if (Build.VERSION.SDK_INT >= 28) {
            val expected = installed.signingInfo?.apkContentsSigners?.toSet().orEmpty()
            val actual = archive.signingInfo?.apkContentsSigners?.toSet().orEmpty()
            if (expected.isEmpty() || expected != actual) throw IOException("Different signing key")
        }
    }

    @Suppress("DEPRECATION")
    private fun signingFlags() = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
}
