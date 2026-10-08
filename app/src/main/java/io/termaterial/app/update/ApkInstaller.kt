package io.termaterial.app.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import androidx.core.content.IntentCompat
import io.termaterial.app.TermaterialApplication
import java.io.File

/**
 * Hands a downloaded APK to Android's package installer. The system then asks the user to
 * confirm (and, the first time, to allow this app to install apps); the outcome comes back
 * through [UpdateInstallReceiver]. Installing an update of this very app kills its process, so
 * a successful install is usually never reported back at all.
 */
object ApkInstaller {

    private const val ACTION_INSTALL_STATUS = "io.termaterial.app.action.UPDATE_INSTALL_STATUS"

    fun commit(context: Context, apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(apk.length())
        }
        val sessionId = installer.createSession(params)
        try {
            installer.openSession(sessionId).use { session ->
                apk.inputStream().use { input ->
                    session.openWrite("base.apk", 0, apk.length()).use { output ->
                        input.copyTo(output)
                        session.fsync(output)
                    }
                }
                val intent = Intent(context, UpdateInstallReceiver::class.java).setAction(ACTION_INSTALL_STATUS)
                // Mutable: the installer fills in the status extras.
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
                val statusReceiver = PendingIntent.getBroadcast(context, sessionId, intent, flags)
                session.commit(statusReceiver.intentSender)
            }
        } catch (e: Exception) {
            installer.abandonSession(sessionId)
            throw e
        }
    }
}

/** Receives [ApkInstaller]'s session status: shows the system confirmation, or reports the result. */
class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            val confirmation = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
            if (confirmation != null) {
                context.startActivity(confirmation.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            }
        }
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        (context.applicationContext as TermaterialApplication).updateManager.onInstallFinished(status, message)
    }
}
