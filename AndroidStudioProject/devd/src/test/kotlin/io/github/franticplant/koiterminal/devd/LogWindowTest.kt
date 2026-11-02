package io.github.franticplant.koiterminal.devd

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LogWindowTest {
    private fun entry(seq: Long, level: String = "info", component: String = "devd") =
        JSONObject()
            .put("seq", seq)
            .put("unixMs", 1_000L + seq)
            .put("level", level)
            .put("component", component)
            .put("message", "message $seq")

    @Test
    fun aReplacementAtTheCapIsVisible() {
        val window = ArrayDeque<JSONObject>()
        repeat(1_500) { window.addLast(entry(it.toLong())) }
        window.removeFirst()
        window.addLast(entry(1_500))
        val shown = visibleEntries(window.toList(), LogSource.DEVD, "debug", null, "")
        assertEquals(1_500, shown.size)
        assertEquals("message 1500", shown.last().getString("message"))
    }

    @Test
    fun levelAndComponentFiltersApply() {
        val entries =
            listOf(
                entry(1, "debug", "devd"),
                entry(2, "warn", "api"),
                entry(3, "error", "api"),
            )
        val shown = visibleEntries(entries, LogSource.DEVD, "warn", "api", "message")
        assertEquals(listOf(2L, 3L), shown.map { it.getLong("seq") })
        assertTrue(visibleEntries(entries, LogSource.DEVD, "error", null, "3").isNotEmpty())
    }
}
