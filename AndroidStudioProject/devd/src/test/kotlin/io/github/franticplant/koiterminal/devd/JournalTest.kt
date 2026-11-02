package io.github.franticplant.koiterminal.devd

import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JournalTest {
    private fun tempDir(): File = Files.createTempDirectory("koi-journal").toFile()

    private fun entry(message: String) =
        JSONObject().put("kind", "log").put("level", "info").put("component", "test").put("message", message)

    @Test
    fun appendsReadsAndCursor() {
        val journal = Journal(tempDir())
        repeat(5) { journal.append(entry("m$it")) }

        val read = journal.read(0, 10)
        assertEquals(5, read.getJSONArray("entries").length())
        assertEquals(5L, read.getLong("nextSeq"))
        assertEquals(0L, read.getLong("dropped"))
        assertEquals("m0", read.getJSONArray("entries").getJSONObject(0).getString("message"))
        assertEquals(5L, journal.head().getLong("seq"))
        assertTrue(!read.getJSONArray("entries").getJSONObject(0).has("hash"))
        assertTrue(!read.getJSONArray("entries").getJSONObject(0).has("prev"))

        val page = journal.read(2, 2)
        assertEquals(2, page.getJSONArray("entries").length())
        assertEquals("m2", page.getJSONArray("entries").getJSONObject(0).getString("message"))
        assertEquals(4L, page.getLong("nextSeq"))
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
    fun readsAcrossSegmentBoundaries() {
        val journal = Journal(tempDir(), segmentBytes = 300, keepSegments = 10, ringSize = 3)
        repeat(30) { journal.append(entry("m$it")) }

        val all = journal.read(0, 100)
        assertEquals(30, all.getJSONArray("entries").length())
        assertEquals("m0", all.getJSONArray("entries").getJSONObject(0).getString("message"))
        assertEquals("m29", all.getJSONArray("entries").getJSONObject(29).getString("message"))

        val middle = journal.read(10, 5)
        assertEquals(5, middle.getJSONArray("entries").length())
        assertEquals("m10", middle.getJSONArray("entries").getJSONObject(0).getString("message"))
        assertEquals(15L, middle.getLong("nextSeq"))
    }

    @Test
    fun rotationKeepsTheConfiguredSegmentsAndReportsDropped() {
        val dir = tempDir()
        val journal = Journal(dir, segmentBytes = 200, keepSegments = 3, ringSize = 2)
        repeat(50) { journal.append(entry("message $it")) }

        assertTrue(dir.listFiles { f -> f.name.endsWith(".jsonl") }!!.size <= 3)

        val read = journal.read(0, 100)
        assertTrue(read.getLong("dropped") > 0)
    }

    @Test
    fun recoversFromATruncatedLastLineAndContinuesTheSequence() {
        val dir = tempDir()
        val journal = Journal(dir)
        repeat(4) { journal.append(entry("m$it")) }
        val segment = dir.listFiles { f -> f.name.endsWith(".jsonl") }!!.single()
        RandomAccessFile(segment, "rw").use { it.setLength(it.length() - 20) }

        val recovered = Journal(dir)
        assertEquals(3L, recovered.head().getLong("seq"))
        assertEquals(
            "m2",
            recovered.tail(1).getJSONObject(0).getString("message"),
        )
        recovered.append(entry("next"))
        assertEquals(4L, recovered.head().getLong("seq"))
        assertEquals(
            "next",
            recovered.tail(1).getJSONObject(0).getString("message"),
        )
    }

    @Test
    fun filteredTailFallsBackPastTheRing() {
        val journal = Journal(tempDir(), segmentBytes = 300, keepSegments = 20, ringSize = 2)
        repeat(30) { i ->
            journal.append(entry("m$i").put("kind", if (i % 3 == 0) "access" else "log"))
        }
        val access = journal.tail(5, JournalFilter.of("access"))
        assertEquals(5, access.length())
        assertEquals(28L, access.getJSONObject(4).getLong("seq"))
    }

    @Test
    fun tailAndNearEndReadDoNotGrowWithTheJournal() {
        val dir = tempDir()
        val journal = Journal(dir)
        repeat(100_000) { journal.append(entry("m$it")) }

        val tailStart = System.nanoTime()
        val tail = journal.tailPage(200)
        val tailMs = (System.nanoTime() - tailStart) / 1_000_000

        val readStart = System.nanoTime()
        val read = journal.read(journal.head().getLong("seq") - 300, 200)
        val readMs = (System.nanoTime() - readStart) / 1_000_000

        assertEquals(200, tail.getJSONArray("entries").length())
        assertEquals(200, read.getJSONArray("entries").length())
        assertEquals(0, journal.lastReadSegments)
        assertTrue("tailPage took ${tailMs}ms", tailMs < 2_000)
        assertTrue("near-end read took ${readMs}ms", readMs < 2_000)
        assertNull(read.optJSONObject("error"))
    }
}
