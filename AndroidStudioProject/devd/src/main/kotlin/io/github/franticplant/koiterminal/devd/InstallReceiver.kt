package io.github.franticplant.koiterminal.devd

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller

/** Receives install results from PackageInstaller and records them. */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val app = context.applicationContext as DevdApp
        val id = intent.getIntExtra(EXTRA_INSTALL_ID, -1)
        val status =
            intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                if (confirm != null) app.pendingInstall = id to confirm
                app.notifications.confirmInstall(id, confirm)
                app.updater.finish(id, "pendingUserAction", "waiting for a tap on the phone")
            }
            PackageInstaller.STATUS_SUCCESS -> {
                if (app.pendingInstall?.first == id) app.pendingInstall = null
                app.updater.finish(id, "success", message)
            }
            else -> {
                if (app.pendingInstall?.first == id) app.pendingInstall = null
                app.updater.finish(id, "failure", "status $status: $message")
            }
        }
    }

    companion object {
        const val ACTION = "io.github.franticplant.koiterminal.devd.INSTALL_RESULT"
        const val EXTRA_INSTALL_ID = "installId"
    }
}
