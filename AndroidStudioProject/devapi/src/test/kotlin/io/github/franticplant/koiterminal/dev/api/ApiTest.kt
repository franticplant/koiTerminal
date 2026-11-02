package io.github.franticplant.koiterminal.dev.api

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiTest {
    private val spec =
        method(
            "test.call",
            Scope.OPERATE,
            "A test method.",
            mutates = true,
            int("count", "How many.", required = true, min = 1, max = 10),
            str("mode", "Which mode.", choices = listOf("a", "b")),
            bool("force", "Force it."),
        )

    @Test
    fun convertsAndDefaults() {
        val p = spec.check(JSONObject().put("count", "3").put("mode", "b"))
        assertEquals(3, p.int("count"))
        assertEquals("b", p.string("mode"))
        assertFalse(p.boolOr("force", false))
        assertEquals(setOf("count", "mode"), p.names)
    }

    @Test(expected = ApiException::class)
    fun refusesUnknownParameter() {
        spec.check(JSONObject().put("count", 1).put("typo", true))
    }

    @Test(expected = ApiException::class)
    fun refusesMissingRequired() {
        spec.check(JSONObject())
    }

    @Test(expected = ApiException::class)
    fun refusesOutOfRange() {
        spec.check(JSONObject().put("count", 11))
    }

    @Test(expected = ApiException::class)
    fun refusesBadChoice() {
        spec.check(JSONObject().put("count", 1).put("mode", "c"))
    }

    @Test
    fun tableReturnsOkAndError() {
        val table = MethodTable()
        table.add(spec) { p -> JSONObject().put("count", p.int("count")) }
        val ok = table.call("test.call", JSONObject().put("count", 2))
        assertEquals(2, ok.getJSONObject("result").getInt("count"))
        val err = table.call("test.call", JSONObject())
        assertEquals(ErrorCode.INVALID_PARAMS, err.getJSONObject("error").getString("code"))
        assertTrue(table.specs.any { it.name == "test.call" })
    }

    @Test
    fun catalogRoundTrips() {
        val json = MethodSpec.catalogJson(listOf(spec))
        val back = MethodSpec.parseCatalog(json)
        assertEquals(1, back.size)
        assertEquals("test.call", back[0].name)
        assertEquals("count", back[0].params[0].name)
    }

    @Test
    fun quietIsOptionalAndRoundTrips() {
        val quiet = spec.copy(quiet = true)
        val back = MethodSpec.parseCatalog(MethodSpec.catalogJson(listOf(quiet)))
        assertTrue(back[0].quiet)

        val old = JSONObject(spec.toJson().toString()).apply { remove("quiet") }
        assertFalse(MethodSpec.fromJson(old).quiet)
    }
}
