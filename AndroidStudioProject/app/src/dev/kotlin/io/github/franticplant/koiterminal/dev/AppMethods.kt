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
import org.json.JSONArray
import org.json.JSONObject

/** The methods koiTerminal's dev flavor implements. The dev agent forwards these. */
class AppMethods(private val app: Application) {
    val table = MethodTable()

    private val lifecycle = Any()
    @Volatile private var exiting = false

    /** Reject control work once an exit has been admitted; the process is going away. */
    private fun checkRunning() {
        synchronized(lifecycle) {
            if (exiting)
                throw ApiException(ErrorCode.UNAVAILABLE, "the app is exiting")
        }
    }

    /** Admit an exit only when no VM or install is active, and close control work. */
    private fun reserveExit() {
        synchronized(lifecycle) {
            if (exiting)
                throw ApiException(ErrorCode.UNAVAILABLE, "the app is already exiting")
            if (VmController.vmState.value.isAlive)
                throw ApiException(ErrorCode.FAILED, "stop the VM before exiting")
            if (Installer.installState.value.isStarted())
                throw ApiException(ErrorCode.FAILED, "finish the install before exiting")
            exiting = true
        }
    }

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
            requireNewUi()
            checkRunning()
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

        table.add(
            method(
                "vm.stop",
                Scope.OPERATE,
                "Stops the VM (the same path as the app's stop button).",
                mutates = true,
            )
        ) {
            requireNewUi()
            VmController.stop()
            vmState(VmController.vmState.value)
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
            requireNewUi()
            if (VmController.vmState.value.isAlive)
                throw ApiException(ErrorCode.FAILED, "stop the VM before installing")
            if (Installer.installState.value.isStarted())
                throw ApiException(ErrorCode.FAILED, "an install is already in progress")
            Installer.install()
            installState(Installer.installState.value)
        }

        table.add(
            method(
                "installer.upgrade",
                Scope.UPDATE,
                "Upgrades the installed image (the app's upgrade path).",
                mutates = true,
            )
        ) {
            requireNewUi()
            if (VmController.vmState.value.isAlive)
                throw ApiException(ErrorCode.FAILED, "stop the VM before upgrading")
            if (Installer.installState.value.isStarted())
                throw ApiException(ErrorCode.FAILED, "an install is already in progress")
            Installer.upgrade()
            installState(Installer.installState.value)
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
