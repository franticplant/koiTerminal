package io.github.franticplant.koiterminal.dev

import io.github.franticplant.koiterminal.dev.api.ApiException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AppGateTest {
    @Test
    fun refusesExitWhileVmAlive() {
        val gate = AppGate(vmAlive = { true }, installStarted = { false })
        assertThrows(ApiException::class.java) { gate.reserveExit() }
        assertFalse(gate.isExiting)
    }

    @Test
    fun refusesExitWhileInstallStarted() {
        val gate = AppGate(vmAlive = { false }, installStarted = { true })
        assertThrows(ApiException::class.java) { gate.reserveExit() }
        assertFalse(gate.isExiting)
    }

    @Test
    fun admitsOneExitAndClosesControl() {
        var vmAlive = false
        val gate = AppGate(vmAlive = { vmAlive }, installStarted = { false })
        gate.checkRunning()
        gate.reserveExit()
        assertTrue(gate.isExiting)
        assertThrows(ApiException::class.java) { gate.checkRunning() }
        assertThrows(ApiException::class.java) { gate.reserveExit() }
        vmAlive = true
    }
}
