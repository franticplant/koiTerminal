package io.github.franticplant.koiterminal.dev

import android.app.ActivityManager
import android.net.TrafficStats
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import java.io.ByteArrayOutputStream
import java.io.File
import com.android.virtualization.koiterminal.Application
import com.android.virtualization.koiterminal.BuildConfig
import com.android.virtualization.koiterminal.InstalledImage
import com.android.virtualization.koiterminal.LauncherActivity
import com.android.virtualization.koiterminal.SerialConsole
import io.github.franticplant.koiterminal.dev.api.ApiException
import io.github.franticplant.koiterminal.dev.api.ErrorCode
import io.github.franticplant.koiterminal.dev.api.MethodTable
import io.github.franticplant.koiterminal.dev.api.ProcSelf
import io.github.franticplant.koiterminal.dev.api.Scope
import io.github.franticplant.koiterminal.dev.api.bool
import io.github.franticplant.koiterminal.dev.api.int
import io.github.franticplant.koiterminal.dev.api.long
import io.github.franticplant.koiterminal.dev.api.method
import io.github.franticplant.koiterminal.dev.api.str
import com.android.virtualization.koiterminal.new2.core.InstallState
import com.android.virtualization.koiterminal.new2.core.Installer
import com.android.virtualization.koiterminal.new2.core.VmController
import com.android.virtualization.koiterminal.new2.core.VmState
import com.android.virtualization.koiterminal.new2.ui.main.NetworkConnection
import com.android.virtualization.koiterminal.new2.ui.main.SettingsViewModel
import org.json.JSONArray
import org.json.JSONObject

/** The methods koiTerminal's dev flavor implements. The dev agent forwards these. */
class AppMethods(private val app: Application) {
    val table = MethodTable()

    private val gate =
        AppGate(
            vmAlive = { VmController.vmState.value.isAlive },
            installStarted = { Installer.installState.value.isStarted() },
        )

    private fun checkRunning() = gate.checkRunning()

    private fun <T> withControl(block: () -> T): T = gate.control(block)

    private fun reserveExit() = gate.reserveExit()

    private val staging =
        Staging(File(app.filesDir, "dev/profiles"), vmAlive = { VmController.vmState.value.isAlive })

    private val profiles = Profiles(app, runningProfile = { VmController.runningProfile })

    /** The fd devd writes upload chunks into. */
    fun openUpload(id: String): ParcelFileDescriptor = staging.open(id)

    /** The VM and installer controllers only exist when the new UI is selected. */
    private fun requireNewUi() {
        if (!LauncherActivity.terminalNewuiJetpack(app))
            throw ApiException(
                ErrorCode.UNAVAILABLE,
                "the old UI is selected, so the VM/installer controllers are not initialized",
            )
    }

    init {
        addRead()
        addOperate()
        addProfiles()
        addStaging()
    }

    private fun addRead() {
        table.add(
            method(
                "app.info",
                Scope.READ,
                "The koiTerminal build and process (uptime, pid, uid), and whether this is a dev build.",
            )
        ) {
            JSONObject()
                .put("package", app.packageName)
                .put("versionName", BuildConfig.VERSION_NAME)
                .put("versionCode", BuildConfig.VERSION_CODE)
                .put("flavor", BuildConfig.FLAVOR)
                .put("devTools", BuildConfig.DEV_TOOLS)
                .put("pid", Process.myPid())
                .put("uid", Process.myUid())
                .put("uptimeMs", SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime())
        }

        table.add(
            method(
                "vm.status",
                Scope.READ,
                "The VM state and the image install state. The main readout before and after a start.",
            )
        ) {
            requireNewUi()
            JSONObject()
                .put("vm", vmState(VmController.vmState.value))
                .put("install", installState(Installer.installState.value))
                .put("boot", VmController.bootReport ?: JSONObject.NULL)
                .put("lastExit", VmController.lastExit ?: JSONObject.NULL)
        }

        table.add(
            method(
                "metrics.sample",
                Scope.READ,
                "This process as a metrics sample (pid, CPU, RSS/PSS, threads, fds), the VM and " +
                    "image state, and the app UID's traffic counters, in devd's sample shape.",
            )
        ) {
            JSONObject()
                .put("processes", JSONArray().put(ProcSelf.sample("koi-app")))
                .put("sessions", JSONArray())
                .put("vm", VmDiag.vmJson())
                .put("install", VmDiag.installJson())
                .put(
                    "uidTraffic",
                    JSONObject()
                        .put("rxBytes", TrafficStats.getUidRxBytes(Process.myUid()))
                        .put("txBytes", TrafficStats.getUidTxBytes(Process.myUid()))
                )
        }

        table.add(
            method(
                "vm.logs",
                Scope.READ,
                "Recent koiTerminal VM and image state transitions observed in-process, not logcat.",
                false,
                long("after", "Entries with seq greater than this (default 0).", min = 0),
                int("max", "At most this many (default 200).", min = 1, max = 500),
            )
        ) { p ->
            VmDiag.read(p.longOr("after", 0), p.intOr("max", 200))
        }

        table.add(
            method(
                "vm.exits",
                Scope.READ,
                "Recent VM stops and errors: reason, uptime, boot source and the last console output.",
                false,
                int("max", "At most this many, oldest first (default 10).", min = 1, max = 20),
            )
        ) { p -> VmController.exitHistory(p.intOr("max", 10)) }

        table.add(
            method(
                "console.read",
                Scope.READ_CONTENT,
                "Serial console bytes after a cursor, or the newest bytes. Long-polls until there " +
                    "is output or waitMs passes. Quiet: one log entry per attach, not per read.",
                false,
                long("after", "Return bytes after this absolute cursor (default: newest).", min = 0),
                int("max", "At most this many bytes (default 64 KiB).", min = 1, max = 1024 * 1024),
                long("waitMs", "Wait up to this long for new bytes (default 15000).", min = 0, max = 25000),
            ).copy(quiet = true)
        ) { p ->
            val after = if (p.has("after")) p.long("after") else null
            val read =
                SerialConsole.read(
                    after,
                    p.intOr("max", 64 * 1024),
                    p.longOr("waitMs", 15_000),
                )
            if (after == null) DevLog.add("info", "console", "attach at cursor ${read.cursor}")
            JSONObject()
                .put("cursor", read.cursor)
                .put("data", java.util.Base64.getEncoder().encodeToString(read.data))
                .put("gap", read.gap)
                .put("start", read.start)
                .put("end", read.end)
        }

        table.add(
            method(
                "console.write",
                Scope.GUEST_CONSOLE,
                "Writes to the serial console. Text by default, or raw bytes with base64=true.",
                true,
                str("data", "The bytes, text or base64.", required = true),
                bool("base64", "Decode data as base64."),
            )
        ) { p ->
            val data = p.string("data")
            val bytes =
                if (p.boolOr("base64", false)) {
                    try {
                        java.util.Base64.getDecoder().decode(data)
                    } catch (e: IllegalArgumentException) {
                        throw ApiException(ErrorCode.INVALID_PARAMS, "data is not base64")
                    }
                } else {
                    data.toByteArray(Charsets.UTF_8)
                }
            if (bytes.size > 64 * 1024)
                throw ApiException(ErrorCode.INVALID_PARAMS, "at most 64 KiB per write")
            if (!SerialConsole.write(bytes))
                throw ApiException(ErrorCode.UNAVAILABLE, "the VM serial console is not attached")
            JSONObject().put("written", bytes.size)
        }

        table.add(
            method(
                "console.info",
                Scope.READ,
                "Console ring size, cursor range, and whether a VM is attached.",
            )
        ) {
            val info = SerialConsole.info()
            JSONObject()
                .put("capacity", info.capacity)
                .put("start", info.start)
                .put("end", info.end)
                .put("attached", info.attached)
        }

        table.add(
            method(
                "image.info",
                Scope.READ,
                "The installed image: directory, config path, build id, backup and sizes.",
            )
        ) {
            requireNewUi()
            checkRunning()
            val image = InstalledImage.getDefault(app)
            JSONObject()
                .put("installDir", image.installDir.toString())
                .put("configPath", image.configPath.toString())
                .put("hasBackup", image.hasBackup())
                .put("apparentSize", image.getApparentSize())
                .put("physicalSize", image.getPhysicalSize())
                .put(
                    "build",
                    image.buildInfo?.let {
                        JSONObject()
                            .put("raw", it.rawString)
                            .put("target", it.target)
                            .put("buildId", it.buildId)
                            .put("timestamp", it.timestamp.toString())
                    } ?: JSONObject.NULL
                )
        }

        table.add(
            method(
                "config.get",
                Scope.READ,
                "The installed vm_config.json and its path. Read-only; editing is a file operation.",
            )
        ) {
            requireNewUi()
            checkRunning()
            val path = InstalledImage.getDefault(app).configPath
            val file = path.toFile()
            if (!file.isFile)
                throw ApiException(ErrorCode.NOT_FOUND, "no vm_config.json at $path")
            val limit = 256 * 1024
            val bytes = ByteArrayOutputStream()
            file.inputStream().use { input ->
                val buf = ByteArray(8192)
                var total = 0
                while (total < limit) {
                    val n = input.read(buf, 0, minOf(buf.size, limit - total))
                    if (n < 0) break
                    bytes.write(buf, 0, n)
                    total += n
                }
            }
            val truncated = file.length() > bytes.size()
            val text = bytes.toString(Charsets.UTF_8.name())
            val json =
                try {
                    JSONObject(text)
                } catch (e: Exception) {
                    JSONObject().put("raw", text)
                }
            JSONObject()
                .put("path", path.toString())
                .put("truncated", truncated)
                .put("json", json)
        }

        table.add(
            method(
                "ports.list",
                Scope.READ,
                "Ports the guest agent reports, and whether each is forwarded to the Android host.",
            )
        ) {
            requireNewUi()
            JSONArray().apply {
                for (p in VmController.ports.value) {
                    put(
                        JSONObject()
                            .put("port", p.port)
                            .put("name", p.name)
                            .put("forwarded", p.isForwarded)
                    )
                }
            }
        }
    }

    private fun addOperate() {
        table.add(
            method(
                "vm.start",
                Scope.OPERATE,
                "Starts the installed VM image, or a boot profile. Returns immediately; poll " +
                    "vm.status for the result.",
                mutates = true,
                str("profile", "Boot profile to use instead of the installed image."),
            )
        ) { p ->
            withControl {
                requireNewUi()
                val boot =
                    if (p.has("profile")) {
                        val name = p.string("profile")
                        profiles.prepare(name).also { profiles.markBooted(name) }
                    } else {
                        when (Installer.installState.value) {
                            is InstallState.Installed -> {}
                            is InstallState.NeedsUpgrade ->
                                throw ApiException(ErrorCode.FAILED, "the image needs an upgrade first")
                            else -> throw ApiException(ErrorCode.FAILED, "the image is not installed")
                        }
                        null
                    }
                VmController.start(boot)
                vmState(VmController.vmState.value)
            }
        }

        table.add(
            method(
                "vm.restart",
                Scope.OPERATE,
                "Stops the VM, waits for Stopped, then starts the same source as last time, or " +
                    "the named profile. Returns immediately after starting.",
                mutates = true,
                str("profile", "Boot profile for the new start (default: the last one)."),
            )
        ) { p ->
            withControl {
                requireNewUi()
                val boot =
                    if (p.has("profile")) {
                        val name = p.string("profile")
                        profiles.prepare(name).also { profiles.markBooted(name) }
                    } else {
                        (VmController.runningProfile ?: VmController.lastProfile)?.let { name ->
                            if (!profiles.exists(name))
                                throw ApiException(ErrorCode.NOT_FOUND, "no profile $name")
                            profiles.prepare(name).also { profiles.markBooted(name) }
                        }
                    }
                if (VmController.vmState.value.isAlive) {
                    VmController.stop()
                    val deadline = System.currentTimeMillis() + 30_000
                    while (VmController.vmState.value.isAlive &&
                        System.currentTimeMillis() < deadline) {
                        Thread.sleep(100)
                    }
                    if (VmController.vmState.value.isAlive)
                        throw ApiException(ErrorCode.TIMEOUT, "the VM did not stop within 30s")
                }
                VmController.reset()
                VmController.start(boot)
                vmState(VmController.vmState.value)
            }
        }

        table.add(
            method(
                "vm.stop",
                Scope.OPERATE,
                "Stops the VM (the same path as the app's stop button).",
                mutates = true,
            )
        ) {
            withControl {
                requireNewUi()
                VmController.stop()
                vmState(VmController.vmState.value)
            }
        }

        table.add(
            method(
                "vm.shutdown",
                Scope.OPERATE,
                "Asks the guest agent to shut the guest down cleanly.",
                mutates = true,
            )
        ) {
            requireNewUi()
            checkRunning()
            if (!VmController.guestAgentReady)
                throw ApiException(ErrorCode.UNAVAILABLE, "the guest agent is not ready")
            if (!VmController.shutdownVm())
                throw ApiException(ErrorCode.FAILED, "the guest agent refused the shutdown")
            vmState(VmController.vmState.value)
        }

        table.add(
            method(
                "vm.resizeTty",
                Scope.OPERATE,
                "Tells the guest the serial console size.",
                mutates = true,
                int("columns", "Terminal columns.", required = true, min = 1, max = 1000),
                int("rows", "Terminal rows.", required = true, min = 1, max = 1000),
            )
        ) { p ->
            requireNewUi()
            checkRunning()
            if (!VmController.koiServiceReady)
                throw ApiException(ErrorCode.UNAVAILABLE, "the koi guest service is not ready")
            if (!VmController.resizeTtyWindow(p.int("columns"), p.int("rows")))
                throw ApiException(ErrorCode.FAILED, "the guest refused the resize")
            JSONObject().put("columns", p.int("columns")).put("rows", p.int("rows"))
        }

        table.add(
            method(
                "installer.install",
                Scope.UPDATE,
                "Installs the image from the configured source (the app's install path).",
                mutates = true,
            )
        ) {
            withControl {
                requireNewUi()
                if (VmController.vmState.value.isAlive)
                    throw ApiException(ErrorCode.FAILED, "stop the VM before installing")
                if (Installer.installState.value.isStarted())
                    throw ApiException(ErrorCode.FAILED, "an install is already in progress")
                Installer.install()
                installState(Installer.installState.value)
            }
        }

        table.add(
            method(
                "installer.upgrade",
                Scope.UPDATE,
                "Upgrades the installed image (the app's upgrade path).",
                mutates = true,
            )
        ) {
            withControl {
                requireNewUi()
                if (VmController.vmState.value.isAlive)
                    throw ApiException(ErrorCode.FAILED, "stop the VM before upgrading")
                if (Installer.installState.value.isStarted())
                    throw ApiException(ErrorCode.FAILED, "an install is already in progress")
                Installer.upgrade()
                installState(Installer.installState.value)
            }
        }

        table.add(
            method(
                "installer.cancel",
                Scope.UPDATE,
                "Cancels an installation in progress.",
                mutates = true,
            )
        ) {
            requireNewUi()
            checkRunning()
            Installer.cancelInstall()
            installState(Installer.installState.value)
        }

        table.add(
            method(
                "installer.retry",
                Scope.UPDATE,
                "Re-checks the image state after an error.",
                mutates = true,
            )
        ) {
            requireNewUi()
            checkRunning()
            Installer.retryCheck()
            installState(Installer.installState.value)
        }

        table.add(
            method(
                "installer.status",
                Scope.READ,
                "The image install state on its own: progress, Wi-Fi policy, backup presence.",
            )
        ) {
            requireNewUi()
            installState(Installer.installState.value)
                .put("wifiOnly", Installer.wifiOnly.value)
                .put("hasBackup", Installer.hasBackup())
        }

        table.add(
            method(
                "installer.setWifiOnly",
                Scope.UPDATE,
                "Only download image updates on Wi-Fi. Can wake a suspended install.",
                mutates = true,
                io.github.franticplant.koiterminal.dev.api.bool(
                    "enabled",
                    "Whether updates wait for Wi-Fi.",
                ),
            )
        ) { p ->
            requireNewUi()
            checkRunning()
            Installer.setWifiOnly(p.boolOr("enabled", true))
            installState(Installer.installState.value)
                .put("wifiOnly", Installer.wifiOnly.value)
        }

        table.add(
            method(
                "ports.setForwarding",
                Scope.OPERATE,
                "Enables or disables forwarding for a port the guest currently reports.",
                mutates = true,
                int("port", "The guest port.", required = true, min = 1, max = 65535),
                io.github.franticplant.koiterminal.dev.api.bool(
                    "enabled",
                    "Forward it (true) or stop forwarding it (false).",
                ),
            )
        ) { p ->
            requireNewUi()
            checkRunning()
            if (!VmController.guestAgentReady)
                throw ApiException(ErrorCode.UNAVAILABLE, "the guest agent is not ready")
            val port = p.int("port")
            if (VmController.ports.value.none { it.port == port })
                throw ApiException(ErrorCode.NOT_FOUND, "the guest does not report port $port")
            val enabled = p.boolOr("enabled", true)
            if (!VmController.enablePortForwarding(port, enabled))
                throw ApiException(ErrorCode.UNAVAILABLE, "the guest agent is not ready")
            JSONObject().put("port", port).put("requested", enabled)
        }

        table.add(
            method(
                "vm.reset",
                Scope.OPERATE,
                "Clears a stopped/error state back to Ready, as the app's reset control does.",
                mutates = true,
            )
        ) {
            requireNewUi()
            checkRunning()
            VmController.reset()
            vmState(VmController.vmState.value)
        }

        table.add(
            method(
                "settings.get",
                Scope.READ,
                "The app's VM settings as currently stored: memory, network mode, proxy, " +
                    "keep-awake, display and time sharing.",
            )
        ) {
            val prefs =
                app.getSharedPreferences(SettingsViewModel.PREFS_NAME, android.content.Context.MODE_PRIVATE)
            settingsJson(prefs)
        }

        table.add(
            method(
                "settings.set",
                Scope.OPERATE,
                "Changes allowlisted VM settings (memory, network mode, SOCKS5 delegate, " +
                    "keep-awake, time sharing). Refused while the VM is alive; changes take " +
                    "effect on the next start.",
                mutates = true,
                int("memoryMiB", "Memory in MiB.", min = 200, max = 16384),
                io.github.franticplant.koiterminal.dev.api.str(
                    "network",
                    "none, full, managed_socks5 or delegate_socks5.",
                    choices = listOf("none", "full", "managed_socks5", "delegate_socks5"),
                ),
                int("socks5Delegate", "SOCKS5 port.", min = 1, max = 65535),
                io.github.franticplant.koiterminal.dev.api.bool(
                    "socks5LoopbackOk",
                    "Allow the SOCKS5 delegate on loopback.",
                ),
                io.github.franticplant.koiterminal.dev.api.bool(
                    "keepAwake",
                    "Keep the screen awake while the VM runs.",
                ),
                io.github.franticplant.koiterminal.dev.api.bool(
                    "shareTime",
                    "Share the host time with the guest.",
                ),
            )
        ) { p ->
            requireNewUi()
            checkRunning()
            if (VmController.vmState.value.isAlive)
                throw ApiException(ErrorCode.FAILED, "stop the VM before changing settings")
            val prefs =
                app.getSharedPreferences(SettingsViewModel.PREFS_NAME, android.content.Context.MODE_PRIVATE)
            val edit = prefs.edit()
            p.intOr("memoryMiB", 0).takeIf { it > 0 }?.let {
                if (it < SettingsViewModel.MIN_MEMORY_MIB)
                    throw ApiException(ErrorCode.INVALID_PARAMS, "memoryMiB is too small")
                edit.putInt(SettingsViewModel.KEY_MEMORY_MIB, it)
            }
            if (p.has("network")) {
                val net = NetworkConnection.valueOf(p.string("network").uppercase())
                edit.putString(SettingsViewModel.KEY_NETWORK_CONNECTION, net.name)
            }
            p.intOr("socks5Delegate", 0).takeIf { it > 0 }?.let {
                edit.putInt(SettingsViewModel.KEY_SOCKS5_DELEGATE, it)
            }
            if (p.has("socks5LoopbackOk"))
                edit.putBoolean(
                    SettingsViewModel.KEY_SOCKS5_LOOPBACK_OK,
                    p.boolOr("socks5LoopbackOk", false),
                )
            if (p.has("keepAwake"))
                edit.putBoolean(SettingsViewModel.KEY_KEEP_AWAKE, p.boolOr("keepAwake", false))
            if (p.has("shareTime"))
                edit.putBoolean(SettingsViewModel.KEY_SHARE_TIME, p.boolOr("shareTime", true))
            edit.apply()
            settingsJson(prefs).put("restartRequired", true)
        }

        table.add(
            method(
                "app.logs",
                Scope.READ,
                "Bounded app-side dev diagnostics (API calls and control actions), not logcat.",
                false,
                long("after", "Entries with seq greater than this (default 0).", min = 0),
                int("max", "At most this many (default 200).", min = 1, max = 500),
            )
        ) { p ->
            DevLog.read(p.longOr("after", 0), p.intOr("max", 200))
        }

        table.add(
            method(
                "app.exit",
                Scope.LIFECYCLE,
                "Ends this app process. devd's app.start (or a Binder binding) brings it back.",
                mutates = true,
            )
        ) {
            reserveExit()
            val pid = Process.myPid()
            Thread {
                    Thread.sleep(500)
                    Process.killProcess(pid)
                }
                .start()
            JSONObject().put("pid", pid).put("exiting", true)
        }

        table.add(
            method(
                "app.exits",
                Scope.READ,
                "Recent process exits (reason, status, timestamp, RSS/PSS). No stack traces.",
                false,
                int("max", "At most this many exits (default 10).", min = 1, max = 50),
            )
        ) { p ->
            val am = app.getSystemService(ActivityManager::class.java)
            JSONArray().apply {
                for (x in am.getHistoricalProcessExitReasons(null, 0, p.intOr("max", 10))) {
                    put(
                        JSONObject()
                            .put("pid", x.pid)
                            .put("unixMs", x.timestamp)
                            .put("reason", x.reason)
                            .put("status", x.status)
                            .put("description", x.description ?: "")
                            .put("importance", x.importance)
                            .put("pssKb", x.pss)
                            .put("rssKb", x.rss)
                    )
                }
            }
        }
    }

    private fun addProfiles() {
        table.add(
            method(
                "profile.list",
                Scope.READ,
                "Boot profiles: names, base, agent, files, size and when each was last booted.",
            )
        ) { profiles.list() }

        table.add(
            method(
                "profile.get",
                Scope.READ,
                "One profile: the raw document, the resolved config, files, and every problem " +
                    "that would stop it booting.",
                false,
                str("name", "Profile name.", required = true),
            )
        ) { p -> profiles.get(p.string("name")) }

        table.add(
            method(
                "profile.create",
                Scope.BOOT_CONFIG,
                "Creates an empty profile. base=none is a whole recipe; base=installed overlays " +
                    "the installed image's vm_config.json.",
                true,
                str("name", "Profile name.", required = true),
                str("base", "none or installed.", required = true, choices = listOf("none", "installed")),
            )
        ) { p -> profiles.create(p.string("name"), p.string("base")) }

        table.add(
            method(
                "profile.put",
                Scope.BOOT_CONFIG,
                "Replaces the whole profile document with a JSON object.",
                true,
                str("name", "Profile name.", required = true),
                str("json", "The profile document.", required = true),
            )
        ) { p -> profiles.put(p.string("name"), p.string("json")) }

        table.add(
            method(
                "profile.set",
                Scope.BOOT_CONFIG,
                "Sets one config key to a JSON literal; null removes it.",
                true,
                str("name", "Profile name.", required = true),
                str("key", "A vm_config.json key, e.g. memory_mib.", required = true),
                str("value", "The JSON literal, e.g. 1024, \"ttyS0\", true.", required = true),
            )
        ) { p -> profiles.set(p.string("name"), p.string("key"), p.string("value")) }

        table.add(
            method(
                "profile.delete",
                Scope.BOOT_CONFIG,
                "Deletes a profile and its files. Refused while that profile is running.",
                true,
                str("name", "Profile name.", required = true),
            )
        ) { p ->
            profiles.delete(p.string("name"))
            null
        }
    }

    private fun addStaging() {
        table.add(
            method(
                "staging.begin",
                Scope.BOOT_PUSH,
                "Starts an upload into a boot profile. devd then writes chunks through openUpload " +
                    "and the app hashes the finished file itself.",
                true,
                str("profile", "Profile name, e.g. k0.", required = true),
                str("name", "File name inside the profile.", required = true),
                long("size", "Exact size in bytes.", required = true, min = 1),
                str("sha256", "The expected SHA-256, hex.", required = true),
            )
        ) { p ->
            checkRunning()
            staging.begin(
                p.string("profile"),
                p.string("name"),
                p.long("size"),
                p.string("sha256"),
            )
        }

        table.add(
            method(
                "staging.status",
                Scope.BOOT_PUSH,
                "How far an upload got, from the partial file's length. Used to resume after a " +
                    "dropped connection or a restart.",
                false,
                str("id", "The upload id from staging.begin.", required = true),
            )
        ) { p -> staging.status(p.string("id")) }

        table.add(
            method(
                "staging.commit",
                Scope.BOOT_PUSH,
                "Hashes the finished upload in the app and, on a match, renames it into the profile.",
                true,
                str("id", "The upload id from staging.begin.", required = true),
            )
        ) { p ->
            checkRunning()
            staging.commit(p.string("id"))
        }

        table.add(
            method(
                "staging.abort",
                Scope.BOOT_PUSH,
                "Deletes a partial upload.",
                true,
                str("id", "The upload id from staging.begin.", required = true),
            )
        ) { p ->
            checkRunning()
            staging.abort(p.string("id"))
            null
        }

        table.add(
            method(
                "staging.list",
                Scope.READ,
                "Committed files and in-progress uploads in the boot profiles.",
                false,
                str("profile", "Only this profile (default: all)."),
            )
        ) { p ->
            staging.list(if (p.has("profile")) p.string("profile") else null)
        }

        table.add(
            method(
                "staging.remove",
                Scope.BOOT_PUSH,
                "Deletes one committed file. Refused while the VM is running.",
                true,
                str("profile", "Profile name.", required = true),
                str("name", "File name inside the profile.", required = true),
            )
        ) { p ->
            checkRunning()
            staging.remove(p.string("profile"), p.string("name"))
            null
        }
    }

    private fun settingsJson(prefs: android.content.SharedPreferences): JSONObject =
        JSONObject()
            .put(
                "memoryMiB",
                prefs.getInt(SettingsViewModel.KEY_MEMORY_MIB, SettingsViewModel.DEFAULT_MEMORY_MIB)
            )
            .put("network", SettingsViewModel.networkConnectionPref(prefs).name.lowercase())
            .put("socks5Delegate", SettingsViewModel.socks5DelegatePref(prefs))
            .put("socks5LoopbackOk", SettingsViewModel.socks5LoopbackOkPref(prefs))
            .put("shareTime", SettingsViewModel.shareTimePref(prefs))
            .put("keepAwake", prefs.getBoolean(SettingsViewModel.KEY_KEEP_AWAKE, false))
            .put(
                "displayResolution",
                prefs.getString(SettingsViewModel.KEY_DISPLAY_RESOLUTION, "")
            )

    private fun vmState(s: VmState): JSONObject =
        JSONObject().apply {
            put(
                "name",
                when (s) {
                    VmState.Ready -> "ready"
                    VmState.Starting -> "starting"
                    VmState.Stopping -> "stopping"
                    VmState.Stopped -> "stopped"
                    VmState.Rebooting -> "rebooting"
                    is VmState.Running -> "running"
                    is VmState.Error -> "error"
                }
            )
            if (s is VmState.Running) {
                put(
                    "terminal",
                    s.terminalAddress?.let {
                        JSONObject().put("address", it.ipAddress).put("port", it.port)
                    } ?: JSONObject.NULL
                )
            }
            if (s is VmState.Error) {
                put("error", s.cause.message ?: s.cause.javaClass.simpleName)
            }
        }

    private fun installState(s: InstallState): JSONObject =
        JSONObject().apply {
            put(
                "name",
                when (s) {
                    InstallState.Checking -> "checking"
                    is InstallState.NotInstalled -> "notInstalled"
                    is InstallState.NeedsUpgrade -> "needsUpgrade"
                    is InstallState.Installing -> "installing"
                    is InstallState.InstallSuspended -> "suspended"
                    InstallState.Installed -> "installed"
                    is InstallState.Error -> "error"
                }
            )
            when (s) {
                is InstallState.NotInstalled -> put("totalBytes", s.totalSizeBytes)
                is InstallState.NeedsUpgrade -> put("totalBytes", s.totalSizeBytes)
                is InstallState.Installing -> {
                    put("totalBytes", s.totalBytes)
                    put("progressBytes", s.progress.value)
                    put("onWifi", s.onWifi)
                }
                is InstallState.InstallSuspended -> {
                    put("totalBytes", s.totalBytes)
                    put("progressBytes", s.progress.value)
                }
                is InstallState.Error -> {
                    put("errorCause", s.errorCause.name)
                    put("error", s.cause.message ?: s.cause.javaClass.simpleName)
                }
                else -> {}
            }
        }
}
