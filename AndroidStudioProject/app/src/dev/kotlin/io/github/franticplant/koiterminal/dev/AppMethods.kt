package io.github.franticplant.koiterminal.dev

import android.app.ActivityManager
import android.net.TrafficStats
import android.os.Process
import android.os.SystemClock
import java.io.ByteArrayOutputStream
import com.android.virtualization.koiterminal.Application
import com.android.virtualization.koiterminal.BuildConfig
import com.android.virtualization.koiterminal.InstalledImage
import com.android.virtualization.koiterminal.LauncherActivity
import io.github.franticplant.koiterminal.dev.api.ApiException
import io.github.franticplant.koiterminal.dev.api.ErrorCode
import io.github.franticplant.koiterminal.dev.api.MethodTable
import io.github.franticplant.koiterminal.dev.api.ProcSelf
import io.github.franticplant.koiterminal.dev.api.Scope
import io.github.franticplant.koiterminal.dev.api.int
import io.github.franticplant.koiterminal.dev.api.long
import io.github.franticplant.koiterminal.dev.api.method
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
        }

        table.add(
            method(
                "metrics.sample",
                Scope.READ,
                "This process as a metrics sample (pid, CPU, RSS/PSS, threads, fds) and the app " +
                    "UID's traffic counters, in devd's sample shape.",
            )
        ) {
            JSONObject()
                .put("processes", JSONArray().put(ProcSelf.sample("koi-app")))
                .put("sessions", JSONArray())
                .put(
                    "uidTraffic",
                    JSONObject()
                        .put("rxBytes", TrafficStats.getUidRxBytes(Process.myUid()))
                        .put("txBytes", TrafficStats.getUidTxBytes(Process.myUid()))
                )
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
                "Starts the installed VM image. Returns immediately; poll vm.status for the result.",
                mutates = true,
            )
        ) {
            withControl {
                requireNewUi()
                when (Installer.installState.value) {
                    is InstallState.Installed -> {}
                    is InstallState.NeedsUpgrade ->
                        throw ApiException(ErrorCode.FAILED, "the image needs an upgrade first")
                    else ->
                        throw ApiException(ErrorCode.FAILED, "the image is not installed")
                }
                VmController.start()
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
