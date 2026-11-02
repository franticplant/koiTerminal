package io.github.franticplant.koiterminal.devd

import android.app.AlarmManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AutoOffReceiverTest {
    @Test
    fun schedulesAndCancelsTheBackstop() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val am = context.getSystemService(AlarmManager::class.java)
        val shadow = shadowOf(am)
        val at = System.currentTimeMillis() + 60_000

        AutoOffReceiver.schedule(context, at)
        val alarm = shadow.nextScheduledAlarm!!
        assertEquals(AlarmManager.RTC_WAKEUP, alarm.type)
        assertEquals(at, alarm.triggerAtTime)

        AutoOffReceiver.cancel(context)
        assertNull(shadow.nextScheduledAlarm)
    }
}
