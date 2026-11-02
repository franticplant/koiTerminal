package io.github.franticplant.koiterminal.dev

import com.android.virtualization.koiterminal.new2.core.BootPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BootStepsTest {
    @Test
    fun installedImageAppliesTheNormalPath() {
        val (applied, skipped) =
            BootPlan.steps(
                fromProfile = false,
                agentInstalled = true,
                useInstalledDisks = true,
                profileMemory = false,
            )
        assertTrue(applied.contains("installed-image"))
        assertTrue(skipped.isEmpty())
    }

    @Test
    fun serialOnlyProfileSkipsAgentDisksAndForcedAudio() {
        val (applied, skipped) =
            BootPlan.steps(
                fromProfile = true,
                agentInstalled = false,
                useInstalledDisks = false,
                profileMemory = true,
            )
        assertTrue(applied.contains("profile-config"))
        assertTrue(applied.contains("memory:profile"))
        assertTrue(skipped.contains("installed-image-disks"))
        assertTrue(skipped.contains("backup-disk"))
        assertTrue(skipped.contains("guest-agent-server"))
        assertTrue(skipped.contains("debian_server_port"))
        assertTrue(skipped.contains("ttyd-vsock"))
        assertTrue(skipped.contains("guest-agent-timeout"))
        assertTrue(skipped.contains("forced-audio"))
        assertTrue(skipped.contains("settings-memory"))
    }

    @Test
    fun profileWithoutMemoryUsesSettings() {
        val (applied, skipped) =
            BootPlan.steps(
                fromProfile = true,
                agentInstalled = true,
                useInstalledDisks = true,
                profileMemory = false,
            )
        assertTrue(applied.contains("memory:settings"))
        assertFalse(skipped.contains("settings-memory"))
        assertFalse(skipped.contains("installed-image-disks"))
        assertFalse(skipped.contains("guest-agent-server"))
    }

    @Test
    fun agentInstalledKeepsTheGuestPath() {
        val (_, skipped) =
            BootPlan.steps(
                fromProfile = true,
                agentInstalled = true,
                useInstalledDisks = true,
                profileMemory = true,
            )
        assertFalse(skipped.contains("guest-agent-server"))
        assertFalse(skipped.contains("ttyd-vsock"))
    }

    @Test
    fun everyStepNameIsDistinct() {
        val (applied, skipped) =
            BootPlan.steps(
                fromProfile = true,
                agentInstalled = false,
                useInstalledDisks = false,
                profileMemory = true,
            )
        assertEquals(applied.size, applied.toSet().size)
        assertEquals(skipped.size, skipped.toSet().size)
    }
}
