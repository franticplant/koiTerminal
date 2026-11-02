package io.github.franticplant.koiterminal.dev

import android.content.Context
import com.android.virtualization.koiterminal.ConfigJson
import com.android.virtualization.koiterminal.InstalledImage
import com.android.virtualization.koiterminal.new2.core.VmController
import io.github.franticplant.koiterminal.dev.api.ApiException
import io.github.franticplant.koiterminal.dev.api.ErrorCode
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/**
 * Boot profiles: a small JSON document and some files the user pushed, under
 * `files/dev/profiles/<name>/`.
 *
 * A profile can be the whole boot recipe (`base: "none"`) or an overlay on the installed image
 * (`base: "installed"`), and can ask for the guest agent or not (`agent`). The resolved config is
 * parsed by the same [ConfigJson] as a normal image, so a profile cannot invent a boot shape the
 * app does not understand.
 */
class Profiles(
    private val context: Context,
    private val runningProfile: () -> String? = { null },
) {
    private val root = File(context.filesDir, "dev/profiles")
    private val prefs = context.getSharedPreferences("dev_profiles", Context.MODE_PRIVATE)

    init {
        root.mkdirs()
    }

    fun list(): JSONObject =
        JSONObject().put(
            "profiles",
            JSONArray().apply {
                root.listFiles { f -> f.isDirectory && !f.name.startsWith(".") }
                    .orEmpty()
                    .sortedBy { it.name }
                    .forEach { dir ->
                        if (!File(dir, PROFILE).isFile) return@forEach
                        val doc = readDoc(dir.name) ?: return@forEach
                        put(
                            JSONObject()
                                .put("name", dir.name)
                                .put("base", doc.optString("base", "none"))
                                .put("agent", doc.optString("agent", "none"))
                                .put("files", filesJson(dir))
                                .put("size", files(dir).sumOf { it.length() })
                                .put("lastBootedMs", prefs.getLong("last:${dir.name}", 0))
                        )
                    }
            },
        )

    /** The raw document, the resolved config, and every reason it would not boot now. */
    fun get(name: String): JSONObject {
        val dir = dir(name)
        val doc = readDoc(name) ?: throw ApiException(ErrorCode.NOT_FOUND, "no profile $name")
        return inspect(dir, doc)
    }

    fun create(name: String, base: String): JSONObject {
        validateName(name)
        if (!base.matches(Regex("none|installed")))
            throw ApiException(ErrorCode.INVALID_PARAMS, "base must be none or installed")
        val dir = dir(name)
        if (File(dir, PROFILE).exists())
            throw ApiException(ErrorCode.FAILED, "profile $name already exists")
        dir.mkdirs()
        val doc =
            JSONObject()
                .put("base", base)
                .put("agent", if (base == "installed") "installed" else "none")
                .put("config", JSONObject())
        write(name, doc)
        return inspect(dir, doc)
    }

    fun put(name: String, json: String): JSONObject {
        validateName(name)
        val doc =
            try {
                JSONObject(json)
            } catch (e: Exception) {
                throw ApiException(ErrorCode.INVALID_PARAMS, "profile is not a JSON object: ${e.message}")
            }
        validateDocument(name, doc)
        // Validation happens before the write, so a bad document never replaces a good one.
        inspect(dir(name), doc)
        write(name, doc)
        return inspect(dir(name), doc)
    }

    /** Sets one config key to a JSON literal; `null` removes it. */
    fun set(name: String, key: String, value: String): JSONObject {
        validateName(name)
        if (!key.matches(Regex("[A-Za-z0-9_]+")))
            throw ApiException(ErrorCode.INVALID_PARAMS, "key must be letters, digits or underscore")
        val literal =
            try {
                val tokener = JSONTokener(value)
                val parsed = tokener.nextValue()
                // A bare path stops at '/', which JSONTokener treats as a delimiter. If anything
                // is left, the caller meant the whole argument as a string, not a partial literal.
                if (tokener.nextClean() == 0.toChar()) parsed else value
            } catch (e: Exception) {
                throw ApiException(ErrorCode.INVALID_PARAMS, "value is not a JSON literal: ${e.message}")
            }
        val doc = readDoc(name) ?: throw ApiException(ErrorCode.NOT_FOUND, "no profile $name")
        val config = doc.optJSONObject("config") ?: JSONObject().also { doc.put("config", it) }
        if (literal == JSONObject.NULL) config.remove(key) else config.put(key, literal)
        inspect(dir(name), doc)
        write(name, doc)
        return inspect(dir(name), doc)
    }

    fun delete(name: String) {
        validateName(name)
        if (runningProfile() == name)
            throw ApiException(ErrorCode.FAILED, "stop profile $name before deleting it")
        val dir = dir(name)
        if (!dir.isDirectory) throw ApiException(ErrorCode.NOT_FOUND, "no profile $name")
        dir.deleteRecursively()
    }

    /** Builds the one-shot boot override for [name], refusing profiles with problems. */
    internal fun prepare(name: String): VmController.BootOverride {
        val dir = dir(name)
        val doc = readDoc(name) ?: throw ApiException(ErrorCode.NOT_FOUND, "no profile $name")
        val info = inspect(dir, doc)
        val problems = info.getJSONArray("problems")
        if (problems.length() > 0) {
            val text = (0 until problems.length()).joinToString("; ") { problems.getString(it) }
            throw ApiException(ErrorCode.FAILED, "profile $name cannot boot: $text")
        }
        val resolved = info.getJSONObject("config")
        val config =
            try {
                ConfigJson.fromJson(context, resolved.toString())
            } catch (e: Exception) {
                throw ApiException(ErrorCode.FAILED, "profile $name: ${e.message}")
            }
        val agent = doc.optString("agent", "none")
        return VmController.BootOverride(
            source = "profile:$name",
            profile = name,
            config = config,
            agentInstalled = agent != "none",
            useInstalledDisks = doc.optString("base", "none") == "installed",
            memoryMib = if (resolved.has("memory_mib")) resolved.getInt("memory_mib") else null,
        )
    }

    fun markBooted(name: String) {
        prefs.edit().putLong("last:$name", System.currentTimeMillis()).apply()
    }

    fun exists(name: String): Boolean = File(dir(name), PROFILE).isFile

    private fun inspect(dir: File, doc: JSONObject): JSONObject {
        val problems = JSONArray()
        val base = doc.optString("base", "none")
        val agent = doc.optString("agent", "none")
        if (base !in setOf("none", "installed")) problems.put("base must be none or installed")
        if (agent !in setOf("none", "installed")) problems.put("agent must be none or installed")

        val overlay = doc.optJSONObject("config") ?: JSONObject()
        val merged =
            if (base == "installed") {
                val installed = installedConfig()
                if (installed == null) {
                    problems.put("installed image has no vm_config.json")
                    JSONObject()
                } else {
                    merge(installed, overlay)
                }
            } else {
                overlay
            }
        val text =
            ConfigJson.substitute(
                context,
                merged.toString(),
                mapOf("\\\$PROFILE_DIR" to dir.absolutePath),
            )
        val resolved =
            try {
                JSONObject(text)
            } catch (e: Exception) {
                problems.put("config is not a JSON object: ${e.message}")
                JSONObject()
            }
        try {
            ConfigJson.fromJson(context, text)
        } catch (e: Exception) {
            problems.put("config does not parse: ${e.message}")
        }
        if (resolved.length() > 0) {
            for (p in pathProblems(resolved)) problems.put(p)
        }
        return JSONObject()
            .put("name", dir.name)
            .put("base", base)
            .put("agent", agent)
            .put("document", doc)
            .put("config", resolved)
            .put("problems", problems)
            .put("files", filesJson(dir))
    }

    private fun validateDocument(name: String, doc: JSONObject) {
        if (!doc.has("base")) throw ApiException(ErrorCode.INVALID_PARAMS, "profile needs base")
        if (!doc.optString("base").matches(Regex("none|installed")))
            throw ApiException(ErrorCode.INVALID_PARAMS, "base must be none or installed")
        if (doc.has("agent") && !doc.optString("agent").matches(Regex("none|installed")))
            throw ApiException(ErrorCode.INVALID_PARAMS, "agent must be none or installed")
        if (doc.has("config") && doc.opt("config") !is JSONObject)
            throw ApiException(ErrorCode.INVALID_PARAMS, "config must be an object")
        // A path escape is never allowed, even if the file does not exist yet.
        val dir = dir(name)
        val overlay = doc.optJSONObject("config") ?: JSONObject()
        val merged =
            if (doc.optString("base") == "installed") installedConfig()?.let { merge(it, overlay) } ?: overlay
            else overlay
        val text =
            ConfigJson.substitute(
                context,
                merged.toString(),
                mapOf("\\\$PROFILE_DIR" to dir.absolutePath),
            )
        val resolved = JSONObject(text)
        for (p in pathProblems(resolved, missing = false)) {
            throw ApiException(ErrorCode.INVALID_PARAMS, p)
        }
        try {
            ConfigJson.fromJson(context, text)
        } catch (e: Exception) {
            throw ApiException(ErrorCode.INVALID_PARAMS, "config does not parse: ${e.message}")
        }
    }

    private fun pathProblems(config: JSONObject, missing: Boolean = true): List<String> {
        val entries = ArrayList<Pair<String, String>>()
        for (key in listOf("kernel", "initrd", "bootloader")) {
            config.optString(key).takeIf { it.isNotEmpty() }?.let { entries.add(key to it) }
        }
        val disks = config.optJSONArray("disks")
        for (i in 0 until (disks?.length() ?: 0)) {
            val disk = disks!!.optJSONObject(i) ?: continue
            disk.optString("image").takeIf { it.isNotEmpty() }?.let {
                entries.add("disks[$i].image" to it)
            }
            val parts = disk.optJSONArray("partitions")
            for (j in 0 until (parts?.length() ?: 0)) {
                parts!!.optJSONObject(j)?.optString("path")?.takeIf { it.isNotEmpty() }?.let {
                    entries.add("disks[$i].partitions[$j].path" to it)
                }
            }
        }
        val data = context.dataDir.canonicalFile
        val prefix = data.path + File.separator
        val out = ArrayList<String>()
        for ((label, path) in entries) {
            val file = File(path).canonicalFile
            if (!file.path.startsWith(prefix)) {
                out.add("$label escapes the app data directory: $path")
            } else if (missing && !file.exists()) {
                out.add("$label is missing: $path")
            }
        }
        return out
    }

    private fun installedConfig(): JSONObject? {
        val file = InstalledImage.getDefault(context).configPath.toFile()
        if (!file.isFile) return null
        return try {
            JSONObject(file.readText())
        } catch (e: Exception) {
            null
        }
    }

    private fun files(dir: File): List<File> =
        dir.listFiles { f -> f.isFile && !f.name.startsWith(".") && f.name != PROFILE }
            .orEmpty()
            .sortedBy { it.name }

    private fun filesJson(dir: File): JSONArray =
        JSONArray().apply {
            for (file in files(dir)) {
                put(JSONObject().put("name", file.name).put("size", file.length()))
            }
        }

    private fun readDoc(name: String): JSONObject? {
        val file = File(dir(name), PROFILE)
        if (!file.isFile) return null
        return try {
            JSONObject(file.readText())
        } catch (e: Exception) {
            null
        }
    }

    private fun write(name: String, doc: JSONObject) {
        val dir = dir(name).apply { mkdirs() }
        val target = File(dir, PROFILE)
        val temp = File(dir, ".$PROFILE.tmp")
        temp.writeText(doc.toString(2))
        if (!temp.renameTo(target)) {
            temp.delete()
            throw ApiException(ErrorCode.FAILED, "could not write $PROFILE")
        }
    }

    private fun dir(name: String): File {
        validateName(name)
        val file = File(root, name)
        if (!file.canonicalPath.startsWith(root.canonicalPath + File.separator))
            throw ApiException(ErrorCode.INVALID_PARAMS, "profile escapes the profile directory")
        return file
    }

    private fun validateName(name: String) {
        if (!name.matches(Regex("[A-Za-z0-9._-]+")) || name == "." || name == "..")
            throw ApiException(
                ErrorCode.INVALID_PARAMS,
                "name must be letters, digits, dot, underscore or dash",
            )
    }

    companion object {
        const val PROFILE = "profile.json"

        /** Shallow merge: a key replaces the base's, an explicit null removes it. */
        internal fun merge(base: JSONObject, overlay: JSONObject): JSONObject {
            val out = JSONObject(base.toString())
            for (key in overlay.keys()) {
                if (overlay.isNull(key)) out.remove(key) else out.put(key, overlay.get(key))
            }
            return out
        }
    }
}
