package io.github.franticplant.koiterminal.dev

import io.github.franticplant.koiterminal.dev.api.ApiException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertNull
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
    fun controlHoldsExitUntilTheOperationFinishes() {
        val gate = AppGate(vmAlive = { false }, installStarted = { false })
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>(null)
        val operation =
            Thread {
                gate.control {
                    entered.countDown()
                    release.await()
                }
            }.apply { start() }
        assertTrue(entered.await(2, java.util.concurrent.TimeUnit.SECONDS))
        val exit = Thread {
                try {
                    gate.reserveExit()
                } catch (e: Throwable) {
                    failure.set(e)
                }
            }.apply { start() }
        Thread.sleep(100)
        assertTrue("exit must wait for the operation", exit.isAlive)
        assertNull(failure.get())
        release.countDown()
        operation.join(2000)
        exit.join(2000)
        assertTrue("exit is admitted after the operation", gate.isExiting)
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
