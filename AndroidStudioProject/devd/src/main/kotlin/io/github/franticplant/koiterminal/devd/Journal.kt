package io.github.franticplant.koiterminal.devd

import java.io.File
import java.io.RandomAccessFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * devd's persistent journal: its log lines and every API call (the access log).
 *
 * Entries are JSON lines in segment files named by their first seq. Segments rotate at about
 * [segmentBytes] and only the newest [keepSegments] are kept. The newest [ringSize] entries also
 * stay in memory, so following the log and opening the viewer never touch the disk. Older reads
 * stream one segment at a time and stop at [max]; nobody loads a whole segment file.
 *
 * The journal is deliberately not tamper-evident: seq and the timestamp are enough to follow it,
 * and the old hash chain made every view slower as the log grew.
 */
class Journal(
    private val dir: File,
    private val segmentBytes: Long = SEGMENT_BYTES,
    private val keepSegments: Int = KEEP_SEGMENTS,
    private val ringSize: Int = RING_SIZE,
) {
    private var out: RandomAccessFile? = null
    private var outSize = 0L
    private var lastSeq = 0L
    private val ring = ArrayDeque<JSONObject>(ringSize)
    private val _version = MutableStateFlow(0L)

    /** Changes on every append: observe it to refresh views. */
    val version: StateFlow<Long> = _version

    /** Segments opened by the last [read], for the host-side bound test. */
    internal var lastReadSegments = 0
        private set

    init {
        dir.mkdirs()
        recover()
    }

    /** Appends an entry with [fields] (kind, level, component, message, ...). Returns it. */
    @Synchronized
    fun append(fields: JSONObject): JSONObject {
        val e = JSONObject()
        e.put("seq", lastSeq + 1)
        e.put("unixMs", System.currentTimeMillis())
        for (k in fields.keys()) e.put(k, fields.get(k))
        val line = (e.toString() + "\n").toByteArray(Charsets.UTF_8)
        if (out == null || (outSize > 0 && outSize + line.size > segmentBytes)) {
            rotate(e.getLong("seq"))
        }
        out!!.write(line)
        outSize += line.size
        lastSeq = e.getLong("seq")
        ring.addLast(e)
        while (ring.size > ringSize) ring.removeFirst()
        _version.value = lastSeq
        return e
    }

    /** The newest entry's seq. A client can keep it to notice that the journal was cut. */
    @Synchronized fun head(): JSONObject = JSONObject().put("seq", lastSeq)

    /**
     * Entries with seq > [after], oldest first, at most [max], that pass [filter]. Also returns the
     * seq to continue from (it advances past filtered-out entries) and how many entries before the
     * oldest retained one were dropped.
     */
    @Synchronized
    fun read(after: Long, max: Int, filter: (JSONObject) -> Boolean = { true }): JSONObject {
        val files = segments()
        val oldest = files.firstOrNull()?.let { firstSeqOf(it) } ?: (lastSeq + 1)
        val dropped = (oldest - after - 1).coerceAtLeast(0)
        val out = JSONArray()
        var next = after
        lastReadSegments = 0
        val ringFirst = ring.firstOrNull()?.getLong("seq") ?: (lastSeq + 1)

        if (after + 1 < ringFirst) {
            // Start in the last segment that can hold entries after the cursor.
            val start = files.indexOfLast { firstSeqOf(it) <= after + 1 }.coerceAtLeast(0)
            outer@ for (f in files.drop(start)) {
                if (firstSeqOf(f) >= ringFirst) break
                lastReadSegments++
                val reader = f.bufferedReader()
                try {
                    while (true) {
                        val line = reader.readLine() ?: break
                        val e = parse(line) ?: continue
                        val seq = e.getLong("seq")
                        if (seq <= after) continue
                        if (seq >= ringFirst) break@outer
                        next = seq
                        if (filter(e)) out.put(e)
                        if (out.length() >= max) return page(out, next, dropped)
                    }
                } finally {
                    reader.close()
                }
            }
        }

        // The cursor is inside the ring, or the stream reached its start: finish in memory.
        for (e in ring) {
            val seq = e.getLong("seq")
            if (seq <= after) continue
            next = seq
            if (filter(e)) out.put(e)
            if (out.length() >= max) break
        }
        return page(out, next, dropped)
    }

    /**
     * The newest [max] entries passing [filter], oldest first. Served from the in-memory ring
     * unless a filter is selective enough that older segments must supply more entries.
     */
    @Synchronized
    fun tail(max: Int, filter: (JSONObject) -> Boolean = { true }): JSONArray {
        val picked = ArrayDeque<JSONObject>(max)
        for (e in ring) {
            if (!filter(e)) continue
            if (picked.size == max) picked.removeFirst()
            picked.addLast(e)
        }
        if (picked.size < max) {
            val ringFirst = ring.firstOrNull()?.getLong("seq") ?: (lastSeq + 1)
            val old = segments().filter { firstSeqOf(it) < ringFirst }
            if (old.isNotEmpty()) {
                val older = ArrayDeque<JSONObject>(max)
                for (f in old) {
                    f.bufferedReader().useLines { lines ->
                        for (line in lines) {
                            val e = parse(line) ?: continue
                            if (!filter(e)) continue
                            if (older.size == max) older.removeFirst()
                            older.addLast(e)
                        }
                    }
                }
                for (e in picked) {
                    if (older.size == max) older.removeFirst()
                    older.addLast(e)
                }
                picked.clear()
                picked.addAll(older)
            }
        }
        return JSONArray(picked)
    }

    /**
     * The newest [max] entries plus the sequence to continue from, read as one atomic snapshot so
     * an entry appended between a separate tail and head call cannot be skipped.
     */
    @Synchronized
    fun tailPage(max: Int, filter: (JSONObject) -> Boolean = { true }): JSONObject {
        val entries = tail(max, filter)
        val next =
            if (entries.length() == 0) lastSeq
            else entries.getJSONObject(entries.length() - 1).getLong("seq")
        return JSONObject().put("entries", entries).put("nextSeq", next)
    }

    private fun page(out: JSONArray, next: Long, dropped: Long) =
        JSONObject().put("entries", out).put("nextSeq", next).put("dropped", dropped)

    private fun segments(): List<File> =
        dir.listFiles { f -> f.name.endsWith(".jsonl") }.orEmpty().sortedBy { firstSeqOf(it) }

    private fun firstSeqOf(f: File) = f.name.removeSuffix(".jsonl").toLongOrNull() ?: 0L

    private fun rotate(firstSeq: Long) {
        out?.close()
        val f = File(dir, "%016d.jsonl".format(firstSeq))
        out = RandomAccessFile(f, "rw").apply { seek(length()) }
        outSize = f.length()
        val all = segments()
        if (all.size > keepSegments) {
            all.take(all.size - keepSegments).forEach { it.delete() }
        }
    }

    /** Finds the last seq from the newest segment and rebuilds enough of the ring to serve tail. */
    private fun recover() {
        while (true) {
            val files = segments()
            val last = files.lastOrNull() ?: return
            if (last.length() == 0L) {
                last.delete()
                continue
            }
            // Drop a torn final line from a crash, then find the last complete entry.
            RandomAccessFile(last, "rw").use { raf ->
                val len = raf.length()
                raf.seek(len - 1)
                if (raf.read() != '\n'.code) raf.setLength(lastNewlineEnd(raf, len))
            }
            if (last.length() == 0L) {
                last.delete()
                continue
            }
            val entry = lastEntry(last) ?: return
            lastSeq = entry.getLong("seq")
            loadRing()
            out = RandomAccessFile(last, "rw").apply { seek(length()) }
            outSize = last.length()
            return
        }
    }

    /** Offset just after the last newline before [length], or 0 if there is none. */
    private fun lastNewlineEnd(raf: RandomAccessFile, length: Long): Long {
        val block = ByteArray(8 * 1024)
        var pos = length
        while (pos > 0) {
            val start = maxOf(0L, pos - block.size)
            val n = (pos - start).toInt()
            raf.seek(start)
            raf.readFully(block, 0, n)
            for (i in n - 1 downTo 0) {
                if (block[i] == '\n'.code.toByte()) return start + i + 1
            }
            pos = start
        }
        return 0
    }

    /**
     * The last complete entry in [file]. The caller has already made the file end in a newline, so
     * the last non-blank line is complete even if the read starts in the middle of an older line.
     */
    private fun lastEntry(file: File): JSONObject? {
        RandomAccessFile(file, "r").use { raf ->
            var size = minOf(64L * 1024, raf.length())
            while (true) {
                val start = raf.length() - size
                val buf = ByteArray(size.toInt())
                raf.seek(start)
                raf.readFully(buf)
                val last =
                    String(buf, Charsets.UTF_8)
                        .lineSequence()
                        .lastOrNull { it.isNotBlank() }
                if (last != null) return parse(last)
                if (start == 0L) return null
                size = minOf(size * 2, raf.length())
            }
        }
    }

    private fun loadRing() {
        ring.clear()
        for (f in segments()) {
            f.bufferedReader().useLines { lines ->
                for (line in lines) {
                    val e = parse(line) ?: continue
                    ring.addLast(e)
                    while (ring.size > ringSize) ring.removeFirst()
                }
            }
        }
    }

    private fun parse(line: String): JSONObject? =
        try {
            JSONObject(line)
        } catch (e: org.json.JSONException) {
            null
        }

    companion object {
        const val SEGMENT_BYTES = 1L * 1024 * 1024
        const val KEEP_SEGMENTS = 8
        const val RING_SIZE = 2000
    }
}
