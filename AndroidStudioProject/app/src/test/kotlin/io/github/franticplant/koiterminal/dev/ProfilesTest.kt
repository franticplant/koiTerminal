package io.github.franticplant.koiterminal.dev

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.franticplant.koiterminal.dev.api.ApiException
import java.io.File
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ProfilesTest {
    private lateinit var context: Context
    private lateinit var profiles: Profiles

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        File(context.filesDir, "dev/profiles").deleteRecursively()
        profiles = Profiles(context)
    }

    @After
    fun tearDown() {
        File(context.filesDir, "dev/profiles").deleteRecursively()
    }

    @Test
    fun mergeReplacesKeysArraysAndRemovesNull() {
        val base =
            JSONObject()
                .put("kernel", "/old")
                .put("params", "console=ttyS0")
                .put("disks", org.json.JSONArray().put("old"))
                .put("memory_mib", 4096)
        val overlay =
            JSONObject()
                .put("kernel", "/new")
                .put("params", JSONObject.NULL)
                .put("disks", org.json.JSONArray().put("new"))
        val merged = Profiles.merge(base, overlay)
        assertEquals("/new", merged.getString("kernel"))
        assertFalse(merged.has("params"))
        assertEquals("new", merged.getJSONArray("disks").getString(0))
        assertEquals(4096, merged.getInt("memory_mib"))
    }

    @Test
    fun createSetAndResolveAProfile() {
        profiles.create("k0", "none")
        val dir = File(context.filesDir, "dev/profiles/k0")
        File(dir, "vmlinuz").writeText("kernel")
        profiles.set("k0", "kernel", "\$PROFILE_DIR/vmlinuz")
        profiles.set("k0", "memory_mib", "1024")
        profiles.set("k0", "console_input_device", "\"ttyS0\"")

        val got = profiles.get("k0")
        assertEquals(got.getJSONArray("problems").toString(), 0, got.getJSONArray("problems").length())
        val config = got.getJSONObject("config")
        assertEquals(File(dir, "vmlinuz").absolutePath, config.getString("kernel"))
        assertEquals(1024, config.getInt("memory_mib"))

        val boot = profiles.prepare("k0")
        assertEquals("profile:k0", boot.source)
        assertFalse(boot.agentInstalled)
        assertFalse(boot.useInstalledDisks)
        assertEquals(1024, boot.memoryMib)
    }

    @Test
    fun missingFilesAreReportedButAllowedOnWrite() {
        profiles.create("k0", "none")
        profiles.set("k0", "kernel", "\$PROFILE_DIR/not-pushed-yet")
        val got = profiles.get("k0")
        assertEquals(got.getJSONArray("problems").toString(), 1, got.getJSONArray("problems").length())
        assertTrue(got.getJSONArray("problems").getString(0).contains("missing"))
        assertThrows(ApiException::class.java) { profiles.prepare("k0") }
    }

    @Test
    fun pathsOutsideTheAppAreRefusedOnWrite() {
        profiles.create("k0", "none")
        assertThrows(ApiException::class.java) { profiles.set("k0", "kernel", "/etc/passwd") }
        assertThrows(ApiException::class.java) {
            profiles.put(
                "k0",
                JSONObject()
                    .put("base", "none")
                    .put("agent", "none")
                    .put(
                        "config",
                        JSONObject()
                            .put("kernel", "\$APP_DATA_DIR/../../etc/passwd")
                            .put("params", "console=ttyS0"),
                    )
                    .toString(),
            )
        }
    }

    @Test
    fun deleteIsRefusedForTheRunningProfile() {
        profiles.create("k0", "none")
        val running = Profiles(context, runningProfile = { "k0" })
        assertThrows(ApiException::class.java) { running.delete("k0") }
        assertTrue(File(context.filesDir, "dev/profiles/k0/profile.json").isFile)
    }

    @Test
    fun listShowsFilesAndLastBootTime() {
        profiles.create("k0", "none")
        File(context.filesDir, "dev/profiles/k0/vmlinuz").writeText("abc")
        profiles.markBooted("k0")
        val listed = profiles.list().getJSONArray("profiles").getJSONObject(0)
        assertEquals("k0", listed.getString("name"))
        assertEquals(3, listed.getJSONArray("files").getJSONObject(0).getInt("size"))
        assertTrue(listed.getLong("lastBootedMs") > 0)
    }

    @Test
    fun namesAreValidated() {
        assertThrows(ApiException::class.java) { profiles.create("../escape", "none") }
        assertThrows(ApiException::class.java) { profiles.create("k0", "bogus") }
    }
}
