package io.github.franticplant.koiterminal.dev

import android.os.ParcelFileDescriptor
import android.os.StatFs
import io.github.franticplant.koiterminal.dev.api.ApiException
import io.github.franticplant.koiterminal.dev.api.ErrorCode
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/**
 * Boot-profile files in the app's private storage.
 *
 * Uploads land in `<profile>/.partial/<id>` and are only renamed into the profile after the app
 * hashes them itself. The sidecar `<id>.json` makes an interrupted upload resumable across app or
 * devd restarts, because the file length is the source of truth for how far it got.
 *
 * devd writes the bytes through a file descriptor from [open]. It never passes them through a
 * Binder transaction, and it never decides whether the SHA-256 matches.
 */
class Staging(
    private val root: File,
    private val vmAlive: () -> Boolean = { false },
    private val headroomBytes: Long = HEADROOM_BYTES,
    private val availableBytes: (File) -> Long = { StatFs(it.path).availableBytes },
) {
    init {
        root.mkdirs()
    }

    fun begin(profile: String, name: String, size: Long, sha256: String): JSONObject {
        requireProfile(profile)
        requireName(name)
        if (size <= 0 || size > MAX_BYTES)
            throw ApiException(ErrorCode.INVALID_PARAMS, "size must be 1..$MAX_BYTES")
        if (!sha256.matches(Regex("[0-9a-fA-F]{64}")))
            throw ApiException(ErrorCode.INVALID_PARAMS, "sha256 must be 64 hex digits")

        val dir = profileDir(profile)
        val partials = File(dir, PARTIAL).apply { mkdirs() }
        val available = availableBytes(dir)
        if (available < size + headroomBytes) {
            throw ApiException(
                ErrorCode.FAILED,
                "not enough space for $size bytes: $available available, " +
                    "$headroomBytes kept free",
            )
        }

        val id = UUID.randomUUID().toString()
        val file = File(partials, id)
        if (!file.createNewFile())
            throw ApiException(ErrorCode.FAILED, "could not create the upload file")
        sidecar(partials, id)
            .writeText(
                JSONObject()
                    .put("id", id)
                    .put("profile", profile)
                    .put("name", name)
                    .put("size", size)
                    .put("sha256", sha256.lowercase())
                    .put("createdMs", System.currentTimeMillis())
                    .toString()
            )
        return JSONObject()
            .put("id", id)
            .put("received", 0)
            .put("chunkBytes", CHUNK_BYTES)
    }

    fun open(id: String): ParcelFileDescriptor {
        val file = partial(id) ?: throw ApiException(ErrorCode.NOT_FOUND, "no upload $id")
        return try {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_WRITE)
        } catch (e: IOException) {
            throw ApiException(ErrorCode.FAILED, "cannot open the upload: ${e.message}")
        }
    }

    fun status(id: String): JSONObject {
        val meta = meta(id) ?: throw ApiException(ErrorCode.NOT_FOUND, "no upload $id")
        val file = partial(id)
        val received =
            if (file != null) {
                file.length()
            } else if (meta.optBoolean("committed")) {
                val target = File(profileDir(meta.getString("profile")), meta.getString("name"))
                if (!target.isFile) throw ApiException(ErrorCode.NOT_FOUND, "no upload $id")
                target.length()
            } else {
                throw ApiException(ErrorCode.NOT_FOUND, "no upload $id")
            }
        return JSONObject()
            .put("id", id)
            .put("received", received)
            .put("size", meta.getLong("size"))
            .put("profile", meta.getString("profile"))
            .put("name", meta.getString("name"))
            .put("createdMs", meta.optLong("createdMs", 0))
            .put("committed", meta.optBoolean("committed"))
    }

    fun commit(id: String): JSONObject {
        val meta = meta(id) ?: throw ApiException(ErrorCode.NOT_FOUND, "no upload $id")
        val file = partial(id)
        if (file == null) {
            // A previous commit finished but its reply was lost. The sidecar remembers it.
            if (meta.optBoolean("committed")) {
                val target = File(profileDir(meta.getString("profile")), meta.getString("name"))
                if (target.isFile)
                    return JSONObject()
                        .put("path", target.absolutePath)
                        .put("size", target.length())
                        .put("sha256", meta.getString("sha256"))
            }
            throw ApiException(ErrorCode.NOT_FOUND, "no upload $id")
        }
        val profile = meta.getString("profile")
        val name = meta.getString("name")
        val expected = meta.getString("sha256")
        val got = sha256(file)
        if (got != expected) {
            throw ApiException(
                ErrorCode.FAILED,
                "sha256 mismatch: got $got, expected $expected; the partial was kept",
            )
        }
        // Make sure the bytes reached storage before the rename makes them visible.
        try {
            java.io.RandomAccessFile(file, "rw").use { it.fd.sync() }
        } catch (e: IOException) {
            throw ApiException(ErrorCode.FAILED, "cannot flush the upload: ${e.message}")
        }

        val target = File(profileDir(profile), name)
        if (!target.canonicalPath.startsWith(root.canonicalPath + File.separator))
            throw ApiException(ErrorCode.INVALID_PARAMS, "file escapes the profile directory")
        try {
            try {
                Files.move(
                    file.toPath(),
                    target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (e: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(file.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (e: IOException) {
            throw ApiException(ErrorCode.FAILED, "cannot commit the upload: ${e.message}")
        }
        // Keep the sidecar, marked committed: a lost commit reply must be retryable.
        val sidecar = sidecar(file.parentFile!!, id)
        sidecar.writeText(meta.put("committed", true).put("path", target.absolutePath).toString())
        val metaDir = File(profileDir(profile), META).apply { mkdirs() }
        File(metaDir, "$name.json")
            .writeText(
                JSONObject()
                    .put("sha256", got)
                    .put("size", target.length())
                    .put("unixMs", System.currentTimeMillis())
                    .toString()
            )
        return JSONObject()
            .put("path", target.absolutePath)
            .put("size", target.length())
            .put("sha256", got)
    }

    fun abort(id: String) {
        partial(id)?.delete()
        metaFile(id)?.delete()
    }

    fun list(profile: String?): JSONObject {
        val profiles =
            if (profile != null) {
                requireProfile(profile)
                listOf(profileDir(profile))
            } else {
                root.listFiles { f -> f.isDirectory && !f.name.startsWith(".") }
                    .orEmpty()
                    .sortedBy { it.name }
            }
        return JSONObject().put(
            "profiles",
            JSONArray().apply {
                for (dir in profiles) {
                    if (!dir.isDirectory) continue
                    put(
                        JSONObject()
                            .put("name", dir.name)
                            .put(
                                "files",
                                JSONArray().apply {
                                    dir.listFiles { f ->
                                        f.isFile && !f.name.startsWith(".") && f.name != PROFILE
                                    }
                                        .orEmpty()
                                        .sortedBy { it.name }
                                        .forEach {
                                            put(
                                                JSONObject()
                                                    .put("name", it.name)
                                                    .put("size", it.length())
                                                    .put(
                                                        "sha256",
                                                        fileMeta(dir, it.name)?.optString("sha256")
                                                            ?: JSONObject.NULL,
                                                    )
                                            )
                                        }
                                },
                            )
                            .put("partials", partials(dir))
                    )
                }
            },
        )
    }

    fun remove(profile: String, name: String) {
        requireProfile(profile)
        requireName(name)
        if (vmAlive())
            throw ApiException(ErrorCode.FAILED, "stop the VM before removing staged files")
        val target = File(profileDir(profile), name)
        if (!target.canonicalPath.startsWith(root.canonicalPath + File.separator))
            throw ApiException(ErrorCode.INVALID_PARAMS, "file escapes the profile directory")
        if (!target.isFile) throw ApiException(ErrorCode.NOT_FOUND, "no file $profile/$name")
        if (!target.delete()) throw ApiException(ErrorCode.FAILED, "could not delete $profile/$name")
        File(profileDir(profile), META).resolve("$name.json").delete()
    }

    private fun partials(dir: File): JSONArray {
        val partialDir = File(dir, PARTIAL)
        return JSONArray().apply {
            partialDir.listFiles { f -> f.isFile && !f.name.endsWith(".json") }
                .orEmpty()
                .sortedBy { it.name }
                .forEach { file ->
                    val meta = meta(file.name)
                    put(
                        JSONObject()
                            .put("id", file.name)
                            .put("received", file.length())
                            .put("size", meta?.optLong("size") ?: JSONObject.NULL)
                            .put("name", meta?.optString("name") ?: JSONObject.NULL)
                    )
                }
        }
    }

    private fun profileDir(profile: String): File {
        val dir = File(root, profile)
        if (!dir.canonicalPath.startsWith(root.canonicalPath + File.separator))
            throw ApiException(ErrorCode.INVALID_PARAMS, "profile escapes the staging directory")
        dir.mkdirs()
        return dir
    }

    /** Finds a partial file by upload id, across profiles. */
    private fun partial(id: String): File? {
        if (!id.matches(Regex("[0-9a-f-]{36}"))) return null
        for (dir in root.listFiles { f -> f.isDirectory && !f.name.startsWith(".") }.orEmpty()) {
            val file = File(File(dir, PARTIAL), id)
            if (file.isFile) return file
        }
        return null
    }

    private fun meta(id: String): JSONObject? {
        val file = metaFile(id) ?: return null
        return try {
            JSONObject(file.readText())
        } catch (e: Exception) {
            null
        }
    }

    private fun metaFile(id: String): File? {
        if (!id.matches(Regex("[0-9a-f-]{36}"))) return null
        for (dir in root.listFiles { f -> f.isDirectory && !f.name.startsWith(".") }.orEmpty()) {
            val file = File(File(dir, PARTIAL), "$id.json")
            if (file.isFile) return file
        }
        return null
    }

    private fun sidecar(dir: File, id: String) = File(dir, "$id.json")

    private fun fileMeta(dir: File, name: String): JSONObject? {
        val file = File(File(dir, META), "$name.json")
        if (!file.isFile) return null
        return try {
            JSONObject(file.readText())
        } catch (e: Exception) {
            null
        }
    }

    private fun requireProfile(profile: String) {
        if (!profile.matches(Regex("[A-Za-z0-9._-]+")) || profile == "." || profile == "..")
            throw ApiException(
                ErrorCode.INVALID_PARAMS,
                "profile must be letters, digits, dot, underscore or dash",
            )
    }

    private fun requireName(name: String) {
        if (!name.matches(Regex("[A-Za-z0-9._-]+")) || name == "." || name == "..")
            throw ApiException(
                ErrorCode.INVALID_PARAMS,
                "name must be letters, digits, dot, underscore or dash",
            )
    }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buf = ByteArray(128 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val PARTIAL = ".partial"
        const val META = ".meta"
        const val PROFILE = "profile.json"
        const val CHUNK_BYTES = 1024 * 1024
        const val MAX_BYTES = 8L * 1024 * 1024 * 1024
        const val HEADROOM_BYTES = 1024L * 1024 * 1024
    }
}
