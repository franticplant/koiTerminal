package io.github.franticplant.koiterminal.devd

import android.os.ParcelFileDescriptor
import android.system.Os
import io.github.franticplant.koiterminal.dev.api.ApiException
import io.github.franticplant.koiterminal.dev.api.ErrorCode
import io.github.franticplant.koiterminal.dev.api.Params
import java.util.Base64
import org.json.JSONObject

/** What to do with a chunk, from the upload's acknowledged byte count. */
internal enum class ChunkAction {
    WRITE,
    REPEAT,
}

/**
 * Returns what a chunk should do, or null for a gap/overflow. A chunk fully inside the bytes
 * already acknowledged is a retry and is ignored, so a lost reply cannot corrupt the file.
 */
internal fun chunkAction(received: Long, offset: Long, length: Int, size: Long): ChunkAction? =
    when {
        offset == received && received + length <= size -> ChunkAction.WRITE
        offset >= 0 && offset + length <= received -> ChunkAction.REPEAT
        else -> null
    }

/**
 * Uploads files into the app's boot profiles without putting bytes through Binder.
 *
 * The app creates the partial file and hands devd a write fd; devd decodes each chunk and pwrite()s
 * it at its offset. The app re-hashes the finished file and decides whether it matches. Only begin,
 * commit and abort reach the journal individually; chunks are quiet.
 */
class Push(private val app: DevdApp) {
    private class Active(
        val id: String,
        var fd: ParcelFileDescriptor?,
        var received: Long,
        val size: Long,
        val profile: String,
        val name: String,
        val startedMs: Long,
    )

    private val uploads = LinkedHashMap<String, Active>()
    private var lastNotifyMs = 0L

    @Synchronized
    fun begin(p: Params): JSONObject {
        val profile = p.string("profile")
        val name = p.string("name")
        val size = p.long("size")
        val sha = p.string("sha256").lowercase()
        if (!validSegment(profile))
            throw ApiException(
                ErrorCode.INVALID_PARAMS,
                "profile must be letters, digits, dot, underscore or dash",
            )
        if (!validSegment(name))
            throw ApiException(
                ErrorCode.INVALID_PARAMS,
                "name must be letters, digits, dot, underscore or dash",
            )
        if (size <= 0 || size > MAX_BYTES)
            throw ApiException(ErrorCode.INVALID_PARAMS, "size must be 1..$MAX_BYTES")
        if (!sha.matches(Regex("[0-9a-f]{64}")))
            throw ApiException(ErrorCode.INVALID_PARAMS, "sha256 must be 64 hex digits")

        val reply =
            app.hooks.call(
                "staging.begin",
                JSONObject()
                    .put("profile", profile)
                    .put("name", name)
                    .put("size", size)
                    .put("sha256", sha),
            )
        val begun = result(reply)
        val id = begun.getString("id")
        val fd =
            try {
                app.hooks.openUpload(id)
            } catch (e: Exception) {
                runCatching { app.hooks.call("staging.abort", JSONObject().put("id", id)) }
                throw ApiException(ErrorCode.UNAVAILABLE, "cannot open the upload: ${e.message}")
            }
        val active = Active(id, fd, 0, size, profile, name, System.currentTimeMillis())
        uploads[id] = active
        app.log.info("push", "begin $profile/$name: $size bytes")
        notify(active, true)
        return JSONObject()
            .put("id", id)
            .put("received", 0)
            .put("chunkBytes", CHUNK_BYTES)
    }

    @Synchronized
    fun chunk(p: Params): JSONObject {
        val id = p.string("id")
        val offset = p.long("offset")
        val active = active(id)
        val data =
            try {
                Base64.getDecoder().decode(p.string("data"))
            } catch (e: IllegalArgumentException) {
                throw ApiException(ErrorCode.INVALID_PARAMS, "data is not base64")
            }
        when (chunkAction(active.received, offset, data.size, active.size)) {
            null ->
                throw ApiException(
                    ErrorCode.INVALID_PARAMS,
                    "expected offset ${active.received}, got $offset " +
                        "(${data.size} bytes, ${active.size} total)",
                )
            ChunkAction.REPEAT -> return JSONObject().put("received", active.received)
            ChunkAction.WRITE -> {}
        }
        val fd =
            active.fd
                ?: throw ApiException(ErrorCode.UNAVAILABLE, "the upload is not open; abort it")
        var done = 0
        while (done < data.size) {
            val n =
                try {
                    Os.pwrite(
                        fd.fileDescriptor,
                        data,
                        done,
                        data.size - done,
                        offset + done,
                    )
                } catch (e: Exception) {
                    throw ApiException(ErrorCode.FAILED, "write failed: ${e.message}")
                }
            if (n <= 0) throw ApiException(ErrorCode.FAILED, "write made no progress")
            done += n
        }
        active.received += data.size
        notify(active, false)
        return JSONObject().put("received", active.received)
    }

    @Synchronized
    fun status(p: Params): JSONObject {
        val id = p.string("id")
        val status = result(app.hooks.call("staging.status", JSONObject().put("id", id)))
        uploads[id]?.let {
            it.received = status.getLong("received")
        }
        return status
    }

    @Synchronized
    fun commit(p: Params): JSONObject {
        val id = p.string("id")
        val active = uploads.remove(id)
        if (active != null) close(active)
        val done = result(app.hooks.call("staging.commit", JSONObject().put("id", id)))
        val size = done.optLong("size")
        val ms = active?.let { System.currentTimeMillis() - it.startedMs } ?: 0
        val rate = if (ms > 0) size * 1000 / ms else 0
        app.log.info(
            "push",
            "commit ${active?.profile ?: "?"}/${active?.name ?: "?"}: $size bytes in ${ms}ms " +
                "($rate B/s)",
        )
        app.notifications.clearPush()
        return done
    }

    @Synchronized
    fun abort(p: Params): JSONObject {
        val id = p.string("id")
        val active = uploads.remove(id)
        if (active != null) close(active)
        app.hooks.call("staging.abort", JSONObject().put("id", id))
        app.log.info("push", "abort ${active?.profile ?: "?"}/${active?.name ?: "?"} at ${active?.received ?: 0} bytes")
        app.notifications.clearPush()
        return JSONObject().put("aborted", id)
    }

    @Synchronized
    fun closeAll() {
        for (active in uploads.values) close(active)
        uploads.clear()
        app.notifications.clearPush()
    }

    /** Finds the active upload, or reconstructs it after a devd restart from the app's partial. */
    private fun active(id: String): Active {
        uploads[id]?.let {
            return it
        }
        val status = result(app.hooks.call("staging.status", JSONObject().put("id", id)))
        val active =
            Active(
                id,
                app.hooks.openUpload(id),
                status.getLong("received"),
                status.getLong("size"),
                status.getString("profile"),
                status.getString("name"),
                status.optLong("createdMs", System.currentTimeMillis()),
            )
        uploads[id] = active
        return active
    }

    private fun close(active: Active) {
        try {
            active.fd?.close()
        } catch (e: Exception) {
            app.log.warn("push", "closing ${active.id} failed: $e")
        }
        active.fd = null
    }

    private fun notify(active: Active, force: Boolean) {
        val now = System.currentTimeMillis()
        if (!force && now - lastNotifyMs < 500) return
        lastNotifyMs = now
        app.notifications.push(active.name, active.received, active.size)
    }

    private fun result(reply: JSONObject): JSONObject {
        reply.optJSONObject("error")?.let {
            throw ApiException(it.getString("code"), it.getString("message"))
        }
        return reply.getJSONObject("result")
    }

    private fun validSegment(s: String) =
        s.matches(Regex("[A-Za-z0-9._-]+")) && s != "." && s != ".."

    companion object {
        const val CHUNK_BYTES = 1024 * 1024
        const val MAX_BYTES = 8L * 1024 * 1024 * 1024
    }
}
