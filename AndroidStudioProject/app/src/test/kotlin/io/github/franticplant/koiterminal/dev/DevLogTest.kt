package io.github.franticplant.koiterminal.dev

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class DevLogTest {
    @Test
    fun aFreshProcessGetsANewGenerationAndDoesNotHideBehindAnOldCursor() {
        DevLog.resetForTest()
        DevLog.add("info", "test", "first")
        val first = DevLog.read(0, 10)
        val generation = first.getString("generation")
        assertEquals(1, first.getJSONArray("entries").length())

        val behindOldCursor = DevLog.read(9_000, 10)
        assertEquals(generation, behindOldCursor.getString("generation"))
        assertEquals(0, behindOldCursor.getJSONArray("entries").length())

        DevLog.resetForTest()
        DevLog.add("info", "test", "after restart")
        val fresh = DevLog.read(9_000, 10)
        assertNotEquals(generation, fresh.getString("generation"))
        assertEquals(0, fresh.getJSONArray("entries").length())
    }
}
