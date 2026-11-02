package io.github.franticplant.koiterminal.devd

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = DevdApp::class)
class DevdMethodsTest {
    @Test
    fun catalogCarriesTheCoreSurface() {
        val app = ApplicationProvider.getApplicationContext<DevdApp>()
        val methods = DevdMethods(app)
        for (name in listOf("devd.info", "methods", "devd.logs", "journal.verify", "packages.list", "update.begin", "update.commit", "app.start", "app.restart")) {
            val spec = methods.table.spec(name)
            assertTrue("missing $name", spec != null)
        }
        assertEquals("read", methods.table.spec("devd.info")!!.scope)
        assertEquals("update", methods.table.spec("update.commit")!!.scope)
        assertEquals("lifecycle", methods.table.spec("app.restart")!!.scope)
    }
}
