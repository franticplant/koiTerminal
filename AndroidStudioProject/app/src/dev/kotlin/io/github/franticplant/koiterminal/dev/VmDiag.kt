package io.github.franticplant.koiterminal.dev

import com.android.virtualization.koiterminal.new2.core.InstallState
import com.android.virtualization.koiterminal.new2.core.Installer
import com.android.virtualization.koiterminal.new2.core.VmController
import com.android.virtualization.koiterminal.new2.core.VmState
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * A bounded, in-process record of VM and image state changes. It deliberately does not read
 * logcat: only transitions this development build observes in its own controllers. The dev agent
 * can read it through vm.logs and show it in the Logs tab next to devd's journal and the app log.
 */
object VmDiag {
    private const val MAX = 1000
    private val started = AtomicBoolean(false)
    private val entries = ArrayDeque<JSONObject>()
    private val generation = java.util.UUID.randomUUID().toString()
    private var seq = 0L
    private var lastVm: String? = null
    private var lastInstall: String? = null
    private var lastPorts: String? = null

    fun start() {
        if (!started.compareAndSet(false, true)) return
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope.launch {
            VmController.vmState.collect { state ->
                val text = describeVm(state)
                if (text != lastVm) {
                    lastVm = text
                    add(if (state is VmState.Error) "error" else "info", "vm", text)
                }
            }
        }
        scope.launch {
            Installer.installState.collect { state ->
                val text = describeInstall(state)
                if (text != lastInstall) {
                    lastInstall = text
                    add(if (state is InstallState.Error) "error" else "info", "installer", text)
                }
            }
        }
        scope.launch {
            VmController.ports.collect { ports ->
                val text =
                    ports.joinToString(", ") {
                        "${it.name}:${it.port}" + if (it.isForwarded) " (forwarded)" else ""
                    }
                if (text != lastPorts) {
                    lastPorts = text
                    add("info", "ports", if (text.isEmpty()) "none" else text)
                }
            }
        }
    }

    /** The current VM state in the shape vm.status returns. */
    fun vmJson(): JSONObject {
        val state = VmController.vmState.value
        return JSONObject()
            .put("name", nameOf(state))
            .put("alive", state.isAlive)
            .put(
                "terminal",
                (state as? VmState.Running)?.terminalAddress?.let {
                    JSONObject().put("address", it.ipAddress).put("port", it.port)
                } ?: JSONObject.NULL,
            )
    }

    /** The current image state in a compact form for the metrics screen. */
    fun installJson(): JSONObject {
        val state = Installer.installState.value
        return JSONObject()
            .put("name", nameOf(state))
            .put("started", state.isStarted())
            .put("onWifi", (state as? InstallState.Installing)?.onWifi ?: JSONObject.NULL)
    }

    /** Entries with seq > [after], oldest first, at most [max]. */
    @Synchronized
    fun read(after: Long, max: Int): JSONObject {
        val out = JSONArray()
        var next = after
        for (e in entries) {
            val s = e.getLong("seq")
            if (s <= after) continue
            if (out.length() >= max.coerceIn(1, 500)) break
            out.put(e)
            next = s
        }
        return JSONObject()
            .put("generation", generation)
            .put("nextSeq", next)
            .put("entries", out)
    }

    @Synchronized
    private fun add(level: String, component: String, message: String) {
        seq++
        entries.addLast(
            JSONObject()
                .put("seq", seq)
                .put("unixMs", System.currentTimeMillis())
                .put("level", level)
                .put("component", component)
                .put("message", message.take(2000))
        )
        while (entries.size > MAX) entries.removeFirst()
    }

    private fun nameOf(state: VmState): String =
        when (state) {
            VmState.Ready -> "ready"
            VmState.Starting -> "starting"
            is VmState.Running -> "running"
            VmState.Rebooting -> "rebooting"
            VmState.Stopping -> "stopping"
            VmState.Stopped -> "stopped"
            is VmState.Error -> "error"
        }

    private fun describeVm(state: VmState): String =
        when (state) {
            VmState.Ready -> "ready"
            VmState.Starting -> "starting"
            is VmState.Running -> {
                val t = state.terminalAddress
                if (t == null) "running" else "running at ${t.ipAddress}:${t.port}"
            }
            VmState.Rebooting -> "rebooting"
            VmState.Stopping -> "stopping"
            VmState.Stopped -> "stopped"
            is VmState.Error -> "error: ${state.cause.message ?: state.cause.javaClass.simpleName}"
        }

    private fun nameOf(state: InstallState): String =
        when (state) {
            InstallState.Checking -> "checking"
            is InstallState.NotInstalled -> "not_installed"
            is InstallState.NeedsUpgrade -> "needs_upgrade"
            is InstallState.Installing -> "installing"
            is InstallState.InstallSuspended -> "suspended"
            InstallState.Installed -> "installed"
            is InstallState.Error -> "error"
        }

    private fun describeInstall(state: InstallState): String =
        when (state) {
            InstallState.Checking -> "checking the image"
            is InstallState.NotInstalled -> "not installed (${state.totalSizeBytes} bytes)"
            is InstallState.NeedsUpgrade -> "needs upgrade (${state.totalSizeBytes} bytes)"
            is InstallState.Installing ->
                "installing (${state.totalBytes} bytes, wifi=${state.onWifi})"
            is InstallState.InstallSuspended -> "suspended (${state.totalBytes} bytes)"
            InstallState.Installed -> "installed"
            is InstallState.Error ->
                "error (${state.errorCause}): ${state.cause.message ?: state.cause.javaClass.simpleName}"
        }
}
