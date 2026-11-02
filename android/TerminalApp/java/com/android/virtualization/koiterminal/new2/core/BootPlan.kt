package com.android.virtualization.koiterminal.new2.core

/**
 * The start steps a boot source applies and skips. Kept out of [VmController] so a host test can
 * cover every profile shape without loading Android's VM classes.
 */
internal object BootPlan {
    fun steps(
        fromProfile: Boolean,
        agentInstalled: Boolean,
        useInstalledDisks: Boolean,
        profileMemory: Boolean,
    ): Pair<List<String>, List<String>> {
        val applied = ArrayList<String>()
        val skipped = ArrayList<String>()
        applied.add("serial-console")
        if (!fromProfile) {
            applied.add("installed-image")
            return applied to skipped
        }
        applied.add("profile-config")
        applied.add(if (profileMemory) "memory:profile" else "memory:settings")
        if (profileMemory) skipped.add("settings-memory")
        if (!useInstalledDisks) {
            skipped.add("installed-image-disks")
            skipped.add("disk-resize")
            skipped.add("backup-disk")
        }
        if (!agentInstalled) {
            skipped.add("guest-agent-server")
            skipped.add("debian_server_port")
            skipped.add("ttyd-vsock")
            skipped.add("guest-agent-timeout")
        }
        skipped.add("forced-audio")
        return applied to skipped
    }
}
