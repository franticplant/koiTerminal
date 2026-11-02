package io.github.franticplant.koiterminal.devd

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.DeadObjectException
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import io.github.franticplant.koiterminal.dev.api.ApiException
import io.github.franticplant.koiterminal.dev.api.DevIds
import io.github.franticplant.koiterminal.dev.api.ErrorCode
import io.github.franticplant.koiterminal.dev.api.IDevHooks
import io.github.franticplant.koiterminal.dev.api.MethodSpec
import org.json.JSONObject

/**
 * devd's connection to koiTerminal's DevHooks service. Binding with BIND_AUTO_CREATE also starts the koiTerminal
 * process when it is not running, which is how "restart the app" works: koiTerminal exits, and the binding
 * brings it back.
 *
 * A prod build of koiTerminal has no DevHooks service; binding then fails and every forwarded method is
 * unavailable, by construction.
 */
class Hooks(private val context: Context, private val log: DevdLog) {
    // A plain monitor: wait/notify on it while the binding comes up.
    @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN") private val lock = Object()
    private var binder: IDevHooks? = null
    private var bound = false
    private var catalog: List<MethodSpec>? = null

    private val connection =
        object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                synchronized(lock) {
                    binder = IDevHooks.Stub.asInterface(service)
                    catalog = null
                    lock.notifyAll()
                }
                log.info("hooks", "connected to koiTerminal")
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                synchronized(lock) { binder = null }
                log.warn("hooks", "koiTerminal disconnected (process ended); Android rebinds when it can")
            }

            override fun onBindingDied(name: ComponentName?) {
                synchronized(lock) {
                    binder = null
                    bound = false
                    catalog = null
                }
                context.unbindService(this)
                log.warn(
                    "hooks",
                    "binding died (koiTerminal updated or uninstalled); rebinding on next use",
                )
            }

            override fun onNullBinding(name: ComponentName?) {
                log.warn("hooks", "koiTerminal returned no binder")
            }
        }

    /** The connected interface, binding and waiting up to [timeoutMs] if needed. */
    fun get(timeoutMs: Long = 10_000): IDevHooks {
        synchronized(lock) {
            binder?.let {
                return it
            }
            if (!bound) {
                val intent = Intent().setClassName(DevIds.KOI_PACKAGE, DevIds.DEV_HOOKS_SERVICE)
                bound =
                    try {
                        context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
                    } catch (e: SecurityException) {
                        throw ApiException(
                            ErrorCode.UNAVAILABLE,
                            "not allowed to bind to koiTerminal: ${e.message}",
                        )
                    }
                if (!bound) {
                    context.unbindService(connection)
                    throw ApiException(
                        ErrorCode.UNAVAILABLE,
                        "koiTerminal's DevHooks is not available: koiTerminal is not installed, or it is a prod build",
                    )
                }
            }
            val deadline = System.currentTimeMillis() + timeoutMs
            while (binder == null) {
                val left = deadline - System.currentTimeMillis()
                if (left <= 0) {
                    if (bound) {
                        try {
                            context.unbindService(connection)
                        } catch (e: IllegalArgumentException) {}
                        bound = false
                    }
                    catalog = null
                    throw ApiException(
                        ErrorCode.UNAVAILABLE,
                        "koiTerminal did not come up within $timeoutMs ms",
                    )
                }
                lock.wait(left)
            }
            return binder!!
        }
    }

    /** koiTerminal's methods, cached until the binding changes. */
    fun catalog(): List<MethodSpec> {
        synchronized(lock) {
            catalog?.let {
                return it
            }
        }
        val specs = MethodSpec.parseCatalog(remote { it.catalog() })
        synchronized(lock) { catalog = specs }
        return specs
    }

    /** Whether koiTerminal is bound right now (without binding). */
    val connected: Boolean
        get() = synchronized(lock) { binder != null }

    /** Test seam: install a fake binder without a real Android service. */
    internal fun attachForTest(fake: IDevHooks?) {
        synchronized(lock) {
            binder = fake
            catalog = null
        }
    }

    fun call(method: String, params: JSONObject): JSONObject =
        JSONObject(remote { it.call(method, params.toString()) })

    /** A write fd for an upload the app started with staging.begin. */
    fun openUpload(id: String): ParcelFileDescriptor = remote { it.openUpload(id) }

    private fun <T> remote(block: (IDevHooks) -> T): T {
        val b = get()
        return try {
            block(b)
        } catch (e: DeadObjectException) {
            unbind()
            throw ApiException(ErrorCode.UNAVAILABLE, "the koiTerminal process ended during the call")
        } catch (e: SecurityException) {
            throw ApiException(ErrorCode.UNAVAILABLE, "koiTerminal refused the call: ${e.message}")
        } catch (e: RemoteException) {
            unbind()
            throw ApiException(ErrorCode.UNAVAILABLE, "Binder call failed: ${e.message}")
        }
    }

    fun unbind() {
        synchronized(lock) {
            if (bound) context.unbindService(connection)
            bound = false
            binder = null
            catalog = null
        }
    }
}
