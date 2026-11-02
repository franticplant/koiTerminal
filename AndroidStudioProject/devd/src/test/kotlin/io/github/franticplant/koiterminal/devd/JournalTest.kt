package io.github.franticplant.koiterminal.devd

import java.io.File
import java.nio.file.Files
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JournalTest {
    private fun tempDir(): File = Files.createTempDirectory("koi-journal").toFile()

    private fun entry(message: String) =
        JSONObject().put("kind", "log").put("level", "info").put("component", "test").put("message", message)

    @Test
    fun appendsReadsAndVerifies() {
        val dir = tempDir()
        val journal = Journal(dir)
        repeat(5) { journal.append(entry("m$it")) }

        val verified = journal.verify()
        assertTrue(verified.getBoolean("ok"))
        assertEquals(5L, verified.getLong("entries"))
        assertEquals(5L, verified.getLong("lastSeq"))

        val read = journal.read(0, 10)
        assertEquals(5, read.getJSONArray("entries").length())
        assertEquals(5L, read.getLong("nextSeq"))
        assertEquals("m0", read.getJSONArray("entries").getJSONObject(0).getString("message"))
    }

    @Test
    fun cursorSkipsAndReportsNext() {
        val dir = tempDir()
        val journal = Journal(dir)
        repeat(5) { journal.append(entry("m$it")) }

        val read = journal.read(2, 2)
        assertEquals(2, read.getJSONArray("entries").length())
        assertEquals("m2", read.getJSONArray("entries").getJSONObject(0).getString("message"))
        assertEquals(4L, read.getLong("nextSeq"))
    }

    @Test
    fun tailPageReturnsTheCursorForTheNewestEntry() {
        val journal = Journal(tempDir())
        repeat(5) { journal.append(entry("m$it")) }
        val page = journal.tailPage(2)
        val entries = page.getJSONArray("entries")
        assertEquals(2, entries.length())
        assertEquals("m3", entries.getJSONObject(0).getString("message"))
        assertEquals(5L, page.getLong("nextSeq"))
    }

    @Test
    fun detectsTampering() {
        val dir = tempDir()
        val journal = Journal(dir)
        repeat(3) { journal.append(entry("m$it")) }
        val segment = dir.listFiles { f -> f.name.endsWith(".jsonl") }!!.single()
        val lines = segment.readLines().toMutableList()
        val altered = JSONObject(lines[1]).put("message", "changed")
        lines[1] = altered.toString()
        segment.writeText(lines.joinToString("\n", postfix = "\n"))

        val verified = journal.verify()
        assertFalse(verified.getBoolean("ok"))
        assertTrue(verified.getString("brokenAt").isNotEmpty())
    }

    @Test
    fun rotatesAndKeepsTheChain() {
        val dir = tempDir()
        val journal = Journal(dir, segmentBytes = 200, keepSegments = 2)
        repeat(20) { journal.append(entry("message $it")) }
        val verified = journal.verify()
        assertTrue(verified.toString(), verified.getBoolean("ok"))
        assertTrue(dir.listFiles { f -> f.name.endsWith(".jsonl") }!!.size <= 2)
    }
}
