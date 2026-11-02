package io.github.franticplant.koiterminal.devd

import io.github.franticplant.koiterminal.dev.api.ApiException
import io.github.franticplant.koiterminal.dev.api.Scope
import io.github.franticplant.koiterminal.dev.api.method
import io.github.franticplant.koiterminal.dev.api.str
import org.json.JSONObject
import org.junit.Assert.assertThrows
import org.junit.Test

class ServerPolicyTest {
    private val pairing =
        Pairing("client", "laptop", setOf("read"), 0, Long.MAX_VALUE)

    @Test
    fun allowsAMatchingScope() {
        val spec = method("m", Scope.READ, "read")
        Policy.authorize(spec, spec.check(JSONObject()), pairing)
    }

    @Test
    fun refusesAMissingScope() {
        val spec = method("m", Scope.UPDATE, "update")
        assertThrows(ApiException::class.java) {
            Policy.authorize(spec, spec.check(JSONObject()), pairing)
        }
    }

    @Test
    fun secretParametersNeedTheSecretsScope() {
        val spec =
            method(
                "m",
                Scope.READ,
                "read a secret",
                mutates = false,
                str("token", "token", secret = true),
            )
        val params = spec.check(JSONObject().put("token", "value"))
        assertThrows(ApiException::class.java) { Policy.authorize(spec, params, pairing) }
        Policy.authorize(spec, params, pairing.copy(scopes = setOf("read", "secrets")))
    }
}
