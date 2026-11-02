package io.github.franticplant.koiterminal.devd

import org.junit.Assert.assertEquals
import org.junit.Test

class TlsTest {
    @Test
    fun pairingCodeMatchesTheCliImplementation() {
        // SHA-256("server" || "client"), first 32 bits mod 1,000,000.
        assertEquals("478424", Tls.pairingCode("server".toByteArray(), "client".toByteArray()))
    }

    @Test
    fun sha256HexIsLowercaseAndStable() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            Tls.sha256Hex(byteArrayOf()),
        )
    }
}
