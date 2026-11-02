package io.github.franticplant.koiterminal.dev

import org.json.JSONArray
import org.json.JSONObject

/**
 * A small bounded ring of app-side development diagnostics. It deliberately
 * does not read logcat: only events this dev flavor emits, so there is no
 * content from other apps and no unbounded buffer.
 */
object DevLog {
    private const val MAX = 512
    private val entries = ArrayDeque<JSONObject>()
    private var seq = 0L

    @Synchronized
    fun add(level: String, component: String, message: String) {
        seq++
        entries.addLast(
            JSONObject()
                .put("seq", seq)
                .put("unixMs", System.currentTimeMillis())
                .put("level", level)
                .put("component", component)
                .put("message", message.take(2000))
        )
        while (entries.size > MAX) entries.removeFirst()
    }

    /** Entries with seq > [after], oldest first, at most [max]. */
    @Synchronized
    fun read(after: Long, max: Int): JSONObject {
        val out = JSONArray()
        var next = after
        for (e in entries) {
            val s = e.getLong("seq")
            if (s <= after) continue
            if (out.length() >= max.coerceIn(1, 500)) break
            out.put(e)
            next = s
        }
        return JSONObject().put("nextSeq", next).put("entries", out)
    }
}
