package io.github.franticplant.koiterminal.devd

import android.content.Context
import android.os.RemoteException
import androidx.test.core.app.ApplicationProvider
import io.github.franticplant.koiterminal.dev.api.ApiException
import io.github.franticplant.koiterminal.dev.api.IDevHooks
import java.nio.file.Files
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HooksTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val hooks =
        Hooks(
            context,
            DevdLog(Journal(Files.createTempDirectory("koi-hooks").toFile())),
        )

    private fun fake(catalog: String = "[]", result: String = """{"result":{"ok":true}}""") =
        object : IDevHooks.Stub() {
            override fun catalog(): String = catalog

            override fun call(method: String, paramsJson: String): String = result
        }

    @Test
    fun cachesTheCatalogAndForwardsCalls() {
        hooks.attachForTest(
            fake(catalog = """[{"name":"app.info","scope":"read","description":"x"}]""")
        )
        assertTrue(hooks.connected)
        assertEquals("app.info", hooks.catalog().single().name)
        assertEquals(true, JSONObject(hooks.call("app.info", JSONObject()).toString()).getJSONObject("result").getBoolean("ok"))
        hooks.attachForTest(null)
        assertFalse(hooks.connected)
    }

    @Test
    fun deadBinderBecomesUnavailable() {
        hooks.attachForTest(
            object : IDevHooks.Stub() {
                override fun catalog(): String = "[]"

                override fun call(method: String, paramsJson: String): String =
                    throw RemoteException("gone")
            }
        )
        val e = assertThrows(ApiException::class.java) { hooks.call("app.info", JSONObject()) }
        assertEquals("unavailable", e.code)
        assertFalse(hooks.connected)
    }
}
