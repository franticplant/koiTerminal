package io.github.franticplant.koiterminal.devd

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SettingsTest {
    @Test
    fun updatesPersistAndListenerDeadlineCanBeCleared() {
        val context: Context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().clear().commit()
        val s = Settings(context)
        s.update(Settings.Values(port = 7391, loopback = false, ifaces = setOf("wlan0"), autoOffMinutes = 15))
        assertEquals(7391, s.values.value.port)
        assertEquals(false, s.values.value.loopback)
        assertEquals(setOf("wlan0"), s.values.value.ifaces)
        assertEquals(15, s.values.value.autoOffMinutes)

        s.rememberListener(1234L)
        assertEquals(1234L, s.listenerOffAt)
        s.rememberListener(null)
        assertNull(s.listenerOffAt)
    }
}
