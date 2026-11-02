package io.github.franticplant.koiterminal.devd

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class ControlSummaryTest {
    @Test
    fun vmStatusShowsTheSettledStateAndImage() {
        val r =
            JSONObject()
                .put("vm", JSONObject().put("name", "running"))
                .put("install", JSONObject().put("name", "installed"))
        assertEquals("vm running · install installed", summarizeControl("vm.status", r))
    }

    @Test
    fun startAndStopUseTheSettledStatusShape() {
        val r = JSONObject().put("vm", JSONObject().put("name", "stopped"))
        assertEquals("vm stopped · install null", summarizeControl("vm.stop", r))
    }

    @Test
    fun restartShowsBothPids() {
        val r = JSONObject().put("oldPid", 10).put("newPid", 11)
        assertEquals("pid 10 → 11", summarizeControl("app.restart", r))
    }
}
