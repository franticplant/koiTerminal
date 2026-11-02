package io.github.franticplant.koiterminal.devd

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * The wall-clock backstop for the listener's auto-off. The in-process Handler
 * is prompt while the phone is awake; this alarm still fires (inexactly) when
 * the phone is in Doze, so a forgotten listener does not stay on indefinitely.
 */
class AutoOffReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION) DevdService.stop(context)
    }

    companion object {
        const val ACTION = "io.github.franticplant.koiterminal.devd.AUTO_OFF"

        private fun pending(context: Context): PendingIntent =
            PendingIntent.getBroadcast(
                context,
                0,
                Intent(context, AutoOffReceiver::class.java).setAction(ACTION),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        fun schedule(context: Context, offAtMs: Long) {
            val am = context.getSystemService(AlarmManager::class.java)
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, offAtMs, pending(context))
        }

        fun cancel(context: Context) {
            context.getSystemService(AlarmManager::class.java).cancel(pending(context))
        }
    }
}
