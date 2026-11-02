package io.github.franticplant.koiterminal.devd

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import androidx.core.content.edit
import io.github.franticplant.koiterminal.dev.api.ApiException
import io.github.franticplant.koiterminal.dev.api.DevIds
import io.github.franticplant.koiterminal.dev.api.ErrorCode
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/**
 * Receives builds and installs them. Transport authentication (who may upload) is the server's;
 * what may install is decided here and by Android:
 * - the package must be one of koiTerminal's;
 * - every signer must be the pinned koi certificate (this matters for a package's first install,
 *   when Android has no previous signer to compare with);
 * - the versionCode must not go down (Android refuses downgrades anyway; saying so first is
 *   clearer).
 *
 * Uploads land in app-private storage and survive a failed install, so it can be retried.
 */
class Updater(private val context: Context, private val log: DevdLog) {
    private val dir = File(context.filesDir, "updates").apply { mkdirs() }
    private val prefs = context.getSharedPreferences("installs", Context.MODE_PRIVATE)

    private class Upload(val id: String, val file: File, val size: Long, val sha256: String) {
        var received = 0L
        val digest: MessageDigest = MessageDigest.getInstance("SHA-256")
        var verified: JSONObject? = null
        var sha256Actual: String? = null
    }

    private var upload: Upload? = null

    @Synchronized
    fun begin(size: Long, sha256: String): JSONObject {
        if (size <= 0 || size > MAX_APK_BYTES)
            throw ApiException(ErrorCode.INVALID_PARAMS, "size must be 1..$MAX_APK_BYTES")
        if (!sha256.matches(Regex("[0-9a-f]{64}")))
            throw ApiException(ErrorCode.INVALID_PARAMS, "sha256 must be 64 hex digits")
        upload?.file?.delete()
        // Keep APKs that a pending install record still refers to.
        val protected = records()
            .filter { it.getString("state") in setOf("installing", "pendingUserAction") }
            .map { it.optString("apk") }
            .toSet()
        dir.listFiles()?.forEach { if (it.absolutePath !in protected) it.delete() }
        val id = UUID.randomUUID().toString()
        upload = Upload(id, File(dir, "$id.apk"), size, sha256)
        log.info("update", "upload $id begins: $size bytes")
        return JSONObject().put("uploadId", id).put("chunkBytes", CHUNK_BYTES)
    }

    @Synchronized
    fun chunk(id: String, offset: Long, dataB64: String): JSONObject {
        val u = current(id)
        if (offset != u.received) {
            throw ApiException(
                ErrorCode.INVALID_PARAMS,
                "expected offset ${u.received}, got $offset",
            )
        }
        val data =
            try {
                Base64.getDecoder().decode(dataB64)
            } catch (e: IllegalArgumentException) {
                throw ApiException(ErrorCode.INVALID_PARAMS, "data is not base64")
            }
        if (data.size > CHUNK_BYTES || u.received + data.size > u.size) {
            throw ApiException(ErrorCode.INVALID_PARAMS, "chunk too large")
        }
        RandomAccessFile(u.file, "rw").use {
            it.seek(offset)
            it.write(data)
        }
        u.digest.update(data)
        u.received += data.size
        return JSONObject().put("received", u.received)
    }

    /** Checks the finished upload and starts the install. Returns the install record. */
    @Synchronized
    fun commit(id: String): JSONObject {
        val u = current(id)
        if (u.received != u.size)
            throw ApiException(ErrorCode.FAILED, "have ${u.received} of ${u.size} bytes")
        // Keep the digest result: verify() can throw and the client may retry
        // commit with the same upload; a consumed MessageDigest would then
        // produce an empty digest and a false mismatch.
        val got = u.sha256Actual ?: u.digest.digest().joinToString("") { "%02x".format(it) }
            .also { u.sha256Actual = it }
        if (got != u.sha256) {
            u.file.delete()
            upload = null
            throw ApiException(ErrorCode.FAILED, "sha256 mismatch: got $got, expected ${u.sha256}")
        }
        val info = verify(u.file)
        upload = null
        return install(u.file, info, id)
    }

    private fun current(id: String): Upload =
        upload?.takeIf { it.id == id }
            ?: throw ApiException(ErrorCode.NOT_FOUND, "no upload $id in progress")

    /** The checks before any install. Returns what the APK is. */
    fun verify(apk: File): PackageInfo {
        val pm = context.packageManager
        val info =
            pm.getPackageArchiveInfo(apk.path, PackageManager.GET_SIGNING_CERTIFICATES)
                ?: throw ApiException(
                    ErrorCode.FAILED,
                    "not a valid APK (or its signature does not verify)",
                )
        if (info.packageName !in DevIds.KOI_PACKAGES) {
            throw ApiException(ErrorCode.FAILED, "${info.packageName} is not a koiTerminal package")
        }
        val signing =
            info.signingInfo ?: throw ApiException(ErrorCode.FAILED, "the APK is not signed")
        if (signing.hasMultipleSigners())
            throw ApiException(ErrorCode.FAILED, "the APK has several signers")
        val signers = signing.apkContentsSigners.map { Tls.sha256Hex(it.toByteArray()) }
        val pinned = BuildConfig.PINNED_CERT_SHA256
        if (signers.isEmpty() || signers.any { it != pinned }) {
            throw ApiException(
                ErrorCode.FAILED,
                "signed by ${signers.joinToString()}, not the pinned koi certificate $pinned",
            )
        }
        installed(info.packageName)?.let { old ->
            if (info.longVersionCode < old.longVersionCode) {
                throw ApiException(
                    ErrorCode.FAILED,
                    "versionCode ${info.longVersionCode} is older than the installed ${old.longVersionCode}; " +
                        "Android refuses downgrades without an uninstall",
                )
            }
        }
        return info
    }

    private fun install(apk: File, info: PackageInfo, uploadId: String): JSONObject {
        val installer = context.packageManager.packageInstaller
        val params =
            PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(info.packageName)
                setSize(apk.length())
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
                setInstallReason(PackageManager.INSTALL_REASON_USER)
                setPackageSource(PackageInstaller.PACKAGE_SOURCE_LOCAL_FILE)
                // devd becomes the update owner: later updates by others need the user.
                setRequestUpdateOwnership(true)
            }
        val sessionId = installer.createSession(params)
        val record =
            JSONObject()
                .put("installId", sessionId)
                .put("package", info.packageName)
                .put("versionCode", info.longVersionCode)
                .put("versionName", info.versionName)
                .put(
                    "fromVersionCode",
                    installed(info.packageName)?.longVersionCode ?: JSONObject.NULL,
                )
                .put("state", "installing")
                .put("startedMs", System.currentTimeMillis())
                .put("uploadId", uploadId)
                .put("apk", apk.absolutePath)
        save(record)
        log.info(
            "update",
            "installing ${info.packageName} ${info.versionName} (${info.longVersionCode})",
        )
        try {
            installer.openSession(sessionId).use { s ->
                s.openWrite("base.apk", 0, apk.length()).use { out ->
                    apk.inputStream().use { it.copyTo(out) }
                    s.fsync(out)
                }
                val intent =
                    Intent(context, InstallReceiver::class.java)
                        .setAction(InstallReceiver.ACTION)
                        .putExtra(InstallReceiver.EXTRA_INSTALL_ID, sessionId)
                // Mutable: the installer adds the status extras.
                val pi =
                    PendingIntent.getBroadcast(
                        context,
                        sessionId,
                        intent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                    )
                s.commit(pi.intentSender)
            }
        } catch (e: Exception) {
            installer.abandonSession(sessionId)
            finish(sessionId, "failure", "could not start the install: ${e.message}")
            throw ApiException(ErrorCode.FAILED, "could not start the install: ${e.message}")
        }
        return record
    }

    /** Records an install's outcome (from [InstallReceiver]). */
    @Synchronized
    fun finish(installId: Int, state: String, message: String) {
        val all = records()
        val r = all.firstOrNull { it.getInt("installId") == installId } ?: return
        r.put("state", state).put("message", message)
        if (state != "pendingUserAction") r.put("finishedMs", System.currentTimeMillis())
        store(all)
        log.info("update", "install $installId (${r.getString("package")}): $state $message")
        if (state == "success") {
            // Only this install's upload: a newer upload may have begun while this one was
            // waiting for the user or for a process restart.
            r.optString("apk").takeIf { it.isNotEmpty() }?.let { File(it).delete() }
        }
    }

    /** Recent installs, newest first. An install of devd itself is finished on the next start. */
    @Synchronized
    fun status(): JSONArray {
        val all = records()
        var changed = false
        // null means the session query failed: keep records nonterminal rather
        // than guessing from package state.
        val sessions: List<android.content.pm.PackageInstaller.SessionInfo>? =
            try {
                context.packageManager.packageInstaller.mySessions.toList()
            } catch (e: Exception) {
                null
            }
        for (r in all) {
            if (r.getString("state") !in setOf("installing", "pendingUserAction")) continue
            if (sessions == null) continue
            val installId = r.optInt("installId", -1)
            // A live session for this exact install is authoritative.
            if (
                sessions.any {
                    it.sessionId == installId && (it.isActive || it.isCommitted)
                }
            )
                continue
            val info = installed(r.getString("package"))
            val now = info?.longVersionCode
            val replaced =
                info != null &&
                    now == r.getLong("versionCode") &&
                    info.lastUpdateTime >= r.optLong("startedMs", Long.MAX_VALUE)
            if (replaced) {
                r.put("state", "success").put("message", "package replaced")
                r.put("finishedMs", System.currentTimeMillis())
                r.optString("apk").takeIf { it.isNotEmpty() }?.let { File(it).delete() }
                changed = true
            }
        }
        if (changed) store(all)
        return JSONArray(all.reversed())
    }

    fun installed(pkg: String): PackageInfo? =
        try {
            context.packageManager.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES)
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }

    private fun save(record: JSONObject) {
        store((records() + record).takeLast(20))
    }

    private fun records(): List<JSONObject> {
        val a = JSONArray(prefs.getString("list", "[]"))
        return (0 until a.length()).map { a.getJSONObject(it) }
    }

    private fun store(list: List<JSONObject>) {
        // Written synchronously: installing devd itself ends this process right after.
        prefs.edit(commit = true) { putString("list", JSONArray(list).toString()) }
    }

    companion object {
        const val MAX_APK_BYTES = 200L * 1024 * 1024
        const val CHUNK_BYTES = 128 * 1024
    }
}
