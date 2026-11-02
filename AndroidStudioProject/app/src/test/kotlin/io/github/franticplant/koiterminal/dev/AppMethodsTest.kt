package io.github.franticplant.koiterminal.dev

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.android.virtualization.koiterminal.Application
import org.json.JSONObject
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = AppMethodsTest.NoVmApplication::class)
class AppMethodsTest {
    /** Skip the VM controllers (and Android Keystore) for this API-only test. */
    class NoVmApplication : Application() {
        override fun onCreate() {
            // Deliberately do not call super.onCreate(): this test only needs a Context for the
            // method table, not the VM controllers (and their Android Keystore setup).
        }
    }

    @Test
    fun theViewerPageSizePassesMethodTableValidation() {
        val app = ApplicationProvider.getApplicationContext<Context>() as Application
        val reply =
            AppMethods(app).table.call("app.logs", JSONObject().put("max", 500).put("after", 0))
        assertNull(reply.optJSONObject("error"))
    }

    @Test
    fun anOversizedPageIsStillRejected() {
        val app = ApplicationProvider.getApplicationContext<Context>() as Application
        val reply = AppMethods(app).table.call("app.logs", JSONObject().put("max", 501))
        assertTrue(reply.optJSONObject("error") != null)
    }
}
