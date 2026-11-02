package io.github.franticplant.koiterminal.devd

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject

/** A pairing request as the screen shows it. */
data class PendingView(
    val clientId: String,
    val name: String,
    val requestedScopes: Set<String>,
    val code: String,
    val local: String,
    val peer: String,
)

/**
 * devd's service as seen from the screen's process (:ui): state polled once a second through
 * [UiService], actions and reads as calls. The screen holds no service objects itself.
 */
class RemoteDevd(private val context: Context) {
    private val bindMutex = Mutex()
    private val stateLock = Any()
    private var service: IDevdUi? = null
    private var connection: ServiceConnection? = null
    private var waiter: CompletableDeferred<IDevdUi>? = null
    private var closed = false

    /** Test seam: lets a host test answer bindService without a real Android service. */
    internal var bindOverride: ((Intent, ServiceConnection, Int) -> Boolean)? = null

    val offAtMs = MutableStateFlow<Long?>(null)
    val addresses = MutableStateFlow<List<String>>(emptyList())
    val binds = MutableStateFlow<List<Server.Bind>>(emptyList())
    val settings = MutableStateFlow(Settings.Values(Settings.DEFAULT_PORT, true, emptySet(), 60))
    val pairings = MutableStateFlow<List<Pairing>>(emptyList())
    val pending = MutableStateFlow<PendingView?>(null)
    val fingerprint = MutableStateFlow("")
    val koi = MutableStateFlow<String?>(null)
    val connections = MutableStateFlow(0)

    /** Why the screen has no fresh state, if the service or a call failed. */
    val stateError = MutableStateFlow<String?>(null)
    private val _metricsVersion = MutableStateFlow(0L)
    val metricsVersion: StateFlow<Long> = _metricsVersion

    private suspend fun bound(): IDevdUi =
        bindMutex.withLock {
            synchronized(stateLock) {
                service?.let {
                    return it
                }
                check(!closed) { "devd's UI connection is closed" }
            }

            val ready = CompletableDeferred<IDevdUi>()
            val existing = synchronized(stateLock) { connection }
            val conn =
                existing
                    ?: object : ServiceConnection {
                        override fun onServiceConnected(name: ComponentName?, b: IBinder?) {
                            val iface = b?.let { IDevdUi.Stub.asInterface(it) }
                            val waiting =
                                synchronized(stateLock) {
                                    if (closed) {
                                        null
                                    } else {
                                        service = iface
                                        waiter.also { waiter = null }
                                    }
                                }
                            if (iface == null) {
                                waiting?.completeExceptionally(
                                    IllegalStateException("devd's UI service returned no binder")
                                )
                            } else {
                                waiting?.complete(iface)
                            }
                        }

                        override fun onServiceDisconnected(name: ComponentName?) {
                            // The process went away. Keep the binding: Android reconnects it and
                            // the next call waits for onServiceConnected.
                            synchronized(stateLock) { service = null }
                        }

                        override fun onBindingDied(name: ComponentName?) {
                            val waiting =
                                synchronized(stateLock) {
                                    service = null
                                    connection = null
                                    waiter.also { waiter = null }
                                }
                            try {
                                context.unbindService(this)
                            } catch (e: IllegalArgumentException) {
                                // Already gone.
                            }
                            waiting?.completeExceptionally(
                                IllegalStateException("devd's UI binding died")
                            )
                        }

                        override fun onNullBinding(name: ComponentName?) {
                            val waiting =
                                synchronized(stateLock) {
                                    connection = null
                                    waiter.also { waiter = null }
                                }
                            try {
                                context.unbindService(this)
                            } catch (e: IllegalArgumentException) {
                                // Already gone.
                            }
                            waiting?.completeExceptionally(
                                IllegalStateException("devd's UI service returned a null binding")
                            )
                        }
                    }

            val newBinding = existing == null
            synchronized(stateLock) {
                if (closed) throw IllegalStateException("devd's UI connection is closed")
                connection = conn
                waiter = ready
            }
            if (newBinding) {
                val ok =
                    try {
                        (bindOverride ?: { intent, c, flags -> context.bindService(intent, c, flags) })(
                            Intent(context, UiService::class.java),
                            conn,
                            Context.BIND_AUTO_CREATE,
                        )
                    } catch (e: Exception) {
                        false
                    }
                if (!ok) {
                    synchronized(stateLock) {
                        if (connection === conn) connection = null
                        if (waiter === ready) waiter = null
                        service = null
                    }
                    throw IllegalStateException("could not bind devd's UI service")
                }
            }

            try {
                withTimeout(10_000) { ready.await() }
            } catch (e: CancellationException) {
                cleanupBinding(conn, ready)
                throw e
            } catch (e: Exception) {
                cleanupBinding(conn, ready)
                throw e
            }
        }

    private fun cleanupBinding(conn: ServiceConnection, ready: CompletableDeferred<IDevdUi>) {
        synchronized(stateLock) {
            if (waiter === ready) waiter = null
            if (connection === conn) {
                try {
                    context.unbindService(conn)
                } catch (e: IllegalArgumentException) {
                    // Already gone.
                }
                connection = null
            }
            service = null
        }
    }

    fun close() {
        val binding: ServiceConnection?
        synchronized(stateLock) {
            closed = true
            waiter?.completeExceptionally(IllegalStateException("devd's UI connection is closed"))
            waiter = null
            binding = connection
            connection = null
            service = null
        }
        binding?.let {
            try {
                context.unbindService(it)
            } catch (e: IllegalArgumentException) {
                // Already gone.
            }
        }
    }

    /** Calls [method]; returns its result (JSONObject, JSONArray, or null), or throws. */
    suspend fun call(method: String, params: JSONObject = JSONObject()): Any? =
        withContext(Dispatchers.IO) {
            val reply = JSONObject(bound().call(method, params.toString()))
            reply
                .optString("error")
                .takeIf { it.isNotEmpty() }
                ?.let { throw IllegalStateException(it) }
            reply.opt("result").takeIf { it != JSONObject.NULL }
        }

    /** Keeps the state fresh while the screen is open. */
    suspend fun poll() {
        while (true) {
            try {
                apply(call("state") as JSONObject)
                stateError.value = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                stateError.value = e.message ?: e.javaClass.simpleName
            }
            delay(1_000)
        }
    }

    private fun strings(a: JSONArray) = (0 until a.length()).map { a.getString(it) }

    private fun apply(s: JSONObject) {
        offAtMs.value = if (s.isNull("offAtMs")) null else s.getLong("offAtMs")
        addresses.value = strings(s.getJSONArray("addresses"))
        connections.value = s.optInt("connections", 0)
        val b = s.getJSONArray("binds")
        binds.value =
            (0 until b.length()).map {
                val o = b.getJSONObject(it)
                Server.Bind(
                    o.getString("iface"),
                    o.optString("address").ifEmpty { null },
                    o.getString("state"),
                    o.optString("detail"),
                )
            }
        val st = s.getJSONObject("settings")
        settings.value =
            Settings.Values(
                st.getInt("port"),
                st.getBoolean("loopback"),
                strings(st.getJSONArray("ifaces")).toSet(),
                st.getInt("autoOffMinutes"),
            )
        fingerprint.value = s.optString("fingerprint")
        val pr = s.getJSONArray("pairings")
        pairings.value =
            (0 until pr.length()).map {
                val o = pr.getJSONObject(it)
                Pairing(
                    o.getString("id"),
                    o.getString("name"),
                    strings(o.getJSONArray("scopes")).toSet(),
                    o.getLong("created"),
                    o.getLong("expires"),
                )
            }
        pending.value =
            s.optJSONObject("pending")?.let {
                PendingView(
                    it.getString("clientId"),
                    it.getString("name"),
                    strings(it.getJSONArray("scopes")).toSet(),
                    it.getString("code"),
                    it.getString("local"),
                    it.getString("peer"),
                )
            }
        koi.value =
            s.optJSONObject("koi")?.let {
                "koiTerminal ${it.getString("versionName")} (${it.getLong("versionCode")})"
            }
        _metricsVersion.value = s.getLong("metricsVersion")
    }

    suspend fun updateSettings(v: Settings.Values) {
        call(
            "settings.update",
            JSONObject()
                .put("loopback", v.loopback)
                .put("ifaces", JSONArray(v.ifaces.toList()))
                .put("autoOffMinutes", v.autoOffMinutes),
        )
        settings.value = v
    }

    suspend fun approve(p: PendingView, scopes: Set<String>, days: Int) =
        call(
            "pairing.approve",
            JSONObject()
                .put("clientId", p.clientId)
                .put("scopes", JSONArray(scopes.toList()))
                .put("days", days),
        )

    suspend fun deny(p: PendingView) =
        call("pairing.deny", JSONObject().put("clientId", p.clientId))

    suspend fun revoke(p: Pairing) = call("pairing.revoke", JSONObject().put("id", p.id))

    /** A method on koiTerminal's dev API, through the service process. */
    suspend fun appCall(method: String, params: JSONObject = JSONObject()): Any? =
        call("app.call", JSONObject().put("method", method).put("params", params))

    /** A method devd implements itself, through the service process. */
    suspend fun devdCall(method: String, params: JSONObject = JSONObject()): Any? =
        call("devd.call", JSONObject().put("method", method).put("params", params))

    /**
     * Polls vm.status until the VM reaches [target] ("running" or "stopped"), so a control result
     * describes where the machine actually settled instead of just acknowledging the request.
     * An error state or a deadline is thrown, never reported as success.
     */
    suspend fun awaitVm(target: String, timeoutMs: Long = 90_000): JSONObject {
        require(target == "running" || target == "stopped") { "unsupported vm target $target" }
        val deadline = System.currentTimeMillis() + timeoutMs
        var last = JSONObject()
        while (System.currentTimeMillis() < deadline) {
            last = appCall("vm.status") as? JSONObject ?: JSONObject()
            val name = last.optJSONObject("vm")?.optString("name")
            if (name == target) return last
            if (name == "error")
                throw IllegalStateException("the VM entered an error state")
            delay(500)
        }
        val name = last.optJSONObject("vm")?.optString("name") ?: "unknown"
        throw IllegalStateException("the VM did not reach $target (last state: $name)")
    }

    suspend fun points(key: String): List<Pair<Long, Double>> {
        val a =
            call("metrics.points", JSONObject().put("key", key)) as? JSONArray ?: return emptyList()
        return (0 until a.length()).map {
            val x = a.getJSONArray(it)
            x.getLong(0) to x.getDouble(1)
        }
    }
}
