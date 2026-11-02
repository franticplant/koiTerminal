package io.github.franticplant.koiterminal.devd

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class UpdaterTest {
    private lateinit var context: Context
    private lateinit var updater: Updater

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("installs", Context.MODE_PRIVATE).edit().clear().commit()
        updater = Updater(context, DevdLog(Journal(Files.createTempDirectory("koi-updater").toFile())))
    }

    private fun sha(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test
    fun beginRejectsBadSizeAndDigest() {
        assertThrows(Exception::class.java) { updater.begin(0, "0".repeat(64)) }
        assertThrows(Exception::class.java) { updater.begin(1, "not-hex") }
        assertThrows(Exception::class.java) { updater.begin(200L * 1024 * 1024 + 1, "0".repeat(64)) }
    }

    @Test
    fun chunkRejectsBadOffsetAndOversizeData() {
        val bytes = "hello".toByteArray()
        val upload = updater.begin(bytes.size.toLong(), sha(bytes)).getString("uploadId")
        assertThrows(Exception::class.java) {
            updater.chunk(upload, 1, Base64.getEncoder().encodeToString(bytes))
        }
        assertThrows(Exception::class.java) {
            updater.chunk(upload, 0, "A".repeat(300 * 1024))
        }
    }

    @Test
    fun beginKeepsAnApkReferencedByAPendingInstall() {
        val dir = File(context.filesDir, "updates").apply { mkdirs() }
        val pending = File(dir, "pending.apk").apply { writeText("pending") }
        val orphan = File(dir, "orphan.apk").apply { writeText("orphan") }
        val record =
            JSONObject()
                .put("state", "pendingUserAction")
                .put("apk", pending.absolutePath)
        context.getSharedPreferences("installs", Context.MODE_PRIVATE)
            .edit()
            .putString("list", JSONArray().put(record).toString())
            .commit()

        updater.begin(1, "0".repeat(64))

        assertTrue("a pending APK must survive a new upload", pending.exists())
        assertTrue("an unreferenced APK should be cleaned up", !orphan.exists())
    }

    @Test
    fun commitRejectsADigestMismatchAndDropsTheUpload() {
        val bytes = "hello".toByteArray()
        val upload = updater.begin(bytes.size.toLong(), "0".repeat(64)).getString("uploadId")
        updater.chunk(upload, 0, Base64.getEncoder().encodeToString(bytes))
        val e = assertThrows(Exception::class.java) { updater.commit(upload) }
        assertTrue(e.message?.contains("sha256 mismatch") == true)
        assertThrows(Exception::class.java) {
            updater.chunk(upload, bytes.size.toLong(), Base64.getEncoder().encodeToString(bytes))
        }
    }

    @Test
    fun commitRetryKeepsTheDigestAfterVerificationFailure() {
        val bytes = "not an apk".toByteArray()
        val upload = updater.begin(bytes.size.toLong(), sha(bytes)).getString("uploadId")
        assertEquals(bytes.size.toLong(), updater.chunk(upload, 0, Base64.getEncoder().encodeToString(bytes)).getLong("received"))

        val first = assertThrows(Exception::class.java) { updater.commit(upload) }
        val second = assertThrows(Exception::class.java) { updater.commit(upload) }
        assertNotEquals("a retry must not become a digest mismatch", true, second.message?.contains("sha256 mismatch"))
        assertTrue(first.message?.isNotEmpty() == true)
    }
}
