package io.github.franticplant.koiterminal.dev

import io.github.franticplant.koiterminal.dev.api.ApiException
import io.github.franticplant.koiterminal.dev.api.ErrorCode

/**
 * Admission for control operations and process exit. It is deliberately a
 * small pure object so the refusal rules can be tested without Android:
 * - an exit is admitted only when no VM is alive and no install is started;
 * - once an exit is admitted, every later control call is refused;
 * - two exits cannot both be admitted.
 */
class AppGate(
    private val vmAlive: () -> Boolean,
    private val installStarted: () -> Boolean,
) {
    private val lock = Any()
    private var exiting = false

    fun checkRunning() {
        synchronized(lock) {
            if (exiting) throw ApiException(ErrorCode.UNAVAILABLE, "the app is exiting")
        }
    }

    fun reserveExit() {
        synchronized(lock) {
            if (exiting) throw ApiException(ErrorCode.UNAVAILABLE, "the app is already exiting")
            if (vmAlive()) throw ApiException(ErrorCode.FAILED, "stop the VM before exiting")
            if (installStarted())
                throw ApiException(ErrorCode.FAILED, "finish the install before exiting")
            exiting = true
        }
    }

    val isExiting: Boolean
        get() = synchronized(lock) { exiting }
}
