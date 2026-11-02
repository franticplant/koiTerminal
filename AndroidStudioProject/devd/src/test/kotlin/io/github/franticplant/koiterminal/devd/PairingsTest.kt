package io.github.franticplant.koiterminal.devd

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PairingsTest {
    private lateinit var context: Context
    private lateinit var journal: Journal
    private lateinit var pairings: Pairings

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("pairings", Context.MODE_PRIVATE).edit().clear().commit()
        journal = Journal(Files.createTempDirectory("koi-pairings").toFile())
        pairings = Pairings(context, DevdLog(journal))
    }

    @After
    fun tearDown() {
        context.getSharedPreferences("pairings", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun request(name: String = "laptop", scopes: Set<String> = setOf("read", "update")) =
        pairings.request("client-$name", name, scopes, "123456", "127.0.0.1:7391", "100.64.0.2:5555")

    @Test
    fun approvesOnlyRequestedScopes() {
        val req = request(scopes = setOf("read", "update"))
        pairings.approve(req, setOf("read", "update", "secrets"), 7)
        val p = pairings.all.value.single()
        assertEquals(setOf("read", "update"), p.scopes)
        assertNull(pairings.pending.value)
    }

    @Test
    fun emptyGrantLeavesRequestPending() {
        val req = request()
        assertThrows(Exception::class.java) { pairings.approve(req, emptySet(), 7) }
        assertSame(req, pairings.pending.value)
    }

    @Test
    fun staleApprovalCannotRecreateAccess() {
        val req = request()
        pairings.deny(req, "denied in test")
        assertThrows(Exception::class.java) { pairings.approve(req, setOf("read"), 7) }
        assertEquals(0, pairings.all.value.size)
    }

    @Test
    fun revokeRemovesThePairing() {
        val req = request()
        pairings.approve(req, setOf("read"), 1)
        val p = pairings.all.value.single()
        pairings.revoke(p)
        assertEquals(0, pairings.all.value.size)
    }
}
