package io.github.franticplant.koiterminal.dev

import io.github.franticplant.koiterminal.dev.api.ApiException
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class StagingTest {
    private fun root(): File = Files.createTempDirectory("koi-staging").toFile()

    private fun sha(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test
    fun beginStatusCommitAndReadBack() {
        val dir = root()
        val staging = Staging(dir, availableBytes = { Long.MAX_VALUE })
        val bytes = ByteArray(100_000) { (it % 251).toByte() }
        val begun = staging.begin("k0", "vmlinuz", bytes.size.toLong(), sha(bytes))
        val id = begun.getString("id")
        assertEquals(0, begun.getInt("received"))

        val partial = File(dir, "k0/.partial/$id")
        FileOutputStream(partial).use { it.write(bytes) }
        assertEquals(bytes.size.toLong(), staging.status(id).getLong("received"))
        val done = staging.commit(id)
        assertEquals(bytes.size.toLong(), done.getLong("size"))
        assertEquals(sha(bytes), done.getString("sha256"))
        assertTrue(File(dir, "k0/vmlinuz").isFile)
        assertTrue(File(dir, "k0/vmlinuz").readBytes().contentEquals(bytes))
        assertFalse(partial.exists())
    }

    @Test
    fun resumesFromThePartialFileAfterARestart() {
        val dir = root()
        val bytes = ByteArray(50_000) { it.toByte() }
        val first = Staging(dir, availableBytes = { Long.MAX_VALUE })
        val id = first.begin("k0", "initrd.img", bytes.size.toLong(), sha(bytes)).getString("id")
        FileOutputStream(File(dir, "k0/.partial/$id")).use { it.write(bytes, 0, 20_000) }

        val again = Staging(dir, availableBytes = { Long.MAX_VALUE })
        assertEquals(20_000L, again.status(id).getLong("received"))
        assertEquals(bytes.size.toLong(), again.status(id).getLong("size"))
        assertEquals("initrd.img", again.status(id).getString("name"))
    }

    @Test
    fun hashMismatchKeepsThePartial() {
        val bytes = "not what was promised".toByteArray()
        val dir = root()
        val staging = Staging(dir, availableBytes = { Long.MAX_VALUE })
        val id = staging.begin("k0", "root_part", bytes.size.toLong(), "0".repeat(64)).getString("id")
        FileOutputStream(File(dir, "k0/.partial/$id")).use { it.write(bytes) }

        val e = assertThrows(ApiException::class.java) { staging.commit(id) }
        assertTrue(e.message!!.contains("sha256 mismatch"))
        assertTrue(File(dir, "k0/.partial/$id").isFile)
        assertEquals(bytes.size.toLong(), staging.status(id).getLong("received"))
    }

    @Test
    fun commitReplyCanBeRetried() {
        val bytes = "retry me".toByteArray()
        val dir = root()
        val staging = Staging(dir, availableBytes = { Long.MAX_VALUE })
        val id = staging.begin("k0", "build_id", bytes.size.toLong(), sha(bytes)).getString("id")
        FileOutputStream(File(dir, "k0/.partial/$id")).use { it.write(bytes) }

        val first = staging.commit(id)
        val second = staging.commit(id)
        assertEquals(first.getString("path"), second.getString("path"))
        assertEquals(first.getString("sha256"), second.getString("sha256"))
    }

    @Test
    fun rejectsBadNamesAndPaths() {
        val staging = Staging(root())
        assertThrows(ApiException::class.java) {
            staging.begin("../escape", "vmlinuz", 1, "0".repeat(64))
        }
        assertThrows(ApiException::class.java) {
            staging.begin("k0", "../vmlinuz", 1, "0".repeat(64))
        }
        assertThrows(ApiException::class.java) {
            staging.begin("k0", "vmlinuz", 1, "not-a-hash")
        }
    }

    @Test
    fun refusesAnUploadThatWouldEatTheHeadroom() {
        val staging = Staging(root(), headroomBytes = 1024, availableBytes = { 0 })
        assertThrows(ApiException::class.java) {
            staging.begin("k0", "vmlinuz", 1024, "0".repeat(64))
        }
    }

    @Test
    fun listShowsFilesAndPartials() {
        val bytes = "kernel".toByteArray()
        val dir = root()
        val staging = Staging(dir, availableBytes = { Long.MAX_VALUE })
        val id = staging.begin("k0", "vmlinuz", bytes.size.toLong(), sha(bytes)).getString("id")
        FileOutputStream(File(dir, "k0/.partial/$id")).use { it.write(bytes) }
        staging.commit(id)

        val listing = staging.list("k0").getJSONArray("profiles").getJSONObject(0)
        assertEquals("k0", listing.getString("name"))
        val file = listing.getJSONArray("files").getJSONObject(0)
        assertEquals("vmlinuz", file.getString("name"))
        assertEquals(sha(bytes), file.getString("sha256"))
        assertEquals(0, listing.getJSONArray("partials").length())
        assertEquals(bytes.size.toLong(), staging.status(id).getLong("received"))
        assertTrue(staging.status(id).getBoolean("committed"))
    }

    @Test
    fun removeIsRefusedWhileTheVmIsAlive() {
        val bytes = "kernel".toByteArray()
        val dir = root()
        var alive = false
        val staging = Staging(dir, vmAlive = { alive }, availableBytes = { Long.MAX_VALUE })
        val id = staging.begin("k0", "vmlinuz", bytes.size.toLong(), sha(bytes)).getString("id")
        FileOutputStream(File(dir, "k0/.partial/$id")).use { it.write(bytes) }
        staging.commit(id)

        alive = true
        assertThrows(ApiException::class.java) { staging.remove("k0", "vmlinuz") }
        alive = false
        staging.remove("k0", "vmlinuz")
        assertFalse(File(dir, "k0/vmlinuz").exists())
    }
}
