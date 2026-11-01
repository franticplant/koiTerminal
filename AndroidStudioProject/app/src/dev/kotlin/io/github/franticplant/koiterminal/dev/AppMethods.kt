package io.github.franticplant.koiterminal.dev

import android.net.TrafficStats
import android.os.Process
import android.os.SystemClock
import com.android.virtualization.koiterminal.Application
import com.android.virtualization.koiterminal.BuildConfig
import com.android.virtualization.koiterminal.LauncherActivity
import io.github.franticplant.koiterminal.dev.api.ApiException
import io.github.franticplant.koiterminal.dev.api.ErrorCode
import io.github.franticplant.koiterminal.dev.api.MethodTable
import io.github.franticplant.koiterminal.dev.api.ProcSelf
import io.github.franticplant.koiterminal.dev.api.Scope
import io.github.franticplant.koiterminal.dev.api.int
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
            VmController.shutdownVm()
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
            VmController.resizeTtyWindow(p.int("columns"), p.int("rows"))
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
            Installer.retryCheck()
            installState(Installer.installState.value)
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
