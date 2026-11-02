package io.github.franticplant.koiterminal.devd

import android.content.Context
import android.content.ServiceConnection
import androidx.test.core.app.ApplicationProvider
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RemoteDevdTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun stub(action: (String) -> String) =
        object : IDevdUi.Stub() {
            override fun call(method: String, paramsJson: String): String = action(method)
        }

    @Test
    fun unwrapsResultsAndErrors() = runBlocking {
        val remote = RemoteDevd(context)
        remote.bindOverride = { _, conn, _ ->
            conn.onServiceConnected(
                null,
                stub { method ->
                    if (method == "ok") """{"result":{"value":7}}"""
                    else """{"error":"boom"}"""
                }.asBinder(),
            )
            true
        }
        val value = remote.call("ok") as JSONObject
        assertEquals(7, value.getInt("value"))
        val error =
            runCatching { runBlocking { remote.call("bad") } }.exceptionOrNull()
        assertTrue(error?.message?.contains("boom") == true)
        remote.close()
    }

    @Test
    fun concurrentCallsShareOneBinding() = runBlocking {
        val binds = AtomicInteger()
        val remote = RemoteDevd(context)
        remote.bindOverride = { _, conn, _ ->
            binds.incrementAndGet()
            Thread.sleep(100)
            conn.onServiceConnected(null, stub { """{"result":{"ok":true}}""" }.asBinder())
            true
        }
        coroutineScope {
            (1..4)
                .map { async(Dispatchers.Default) { remote.call("ok") } }
                .awaitAll()
        }
        assertEquals(1, binds.get())
        remote.close()
    }

    @Test
    fun aDisconnectedCallerRebindsInsteadOfDuplicating() = runBlocking {
        val binds = AtomicInteger()
        val connection = AtomicReference<ServiceConnection>()
        val remote = RemoteDevd(context)
        remote.bindOverride = { _, conn, _ ->
            binds.incrementAndGet()
            connection.set(conn)
            conn.onServiceConnected(null, stub { """{"result":{"ok":true}}""" }.asBinder())
            true
        }
        remote.call("ok")
        connection.get().onServiceDisconnected(null)
        val reconnect =
            Thread {
                    Thread.sleep(100)
                    connection
                        .get()
                        .onServiceConnected(
                            null,
                            stub { """{"result":{"ok":true}}""" }.asBinder(),
                        )
                }
                .apply { start() }
        remote.call("ok")
        reconnect.join(2_000)
        assertEquals(1, binds.get())
        remote.close()
    }

    @Test
    fun waitsForTheRequestedVmState() = runBlocking {
        val states = ArrayDeque(listOf("starting", "running"))
        val remote = RemoteDevd(context)
        remote.bindOverride = { _, conn, _ ->
            conn.onServiceConnected(
                null,
                stub {
                    val name = states.removeFirstOrNull() ?: "running"
                    """{"result":{"vm":{"name":"$name"}}}"""
                }.asBinder(),
            )
            true
        }
        val result = remote.awaitVm("running", 2_000)
        assertEquals("running", result.getJSONObject("vm").getString("name"))
        remote.close()
    }

    @Test
    fun vmErrorsAndTimeoutsAreThrown() = runBlocking {
        val errorRemote = RemoteDevd(context)
        errorRemote.bindOverride = { _, conn, _ ->
            conn.onServiceConnected(
                null,
                stub { """{"result":{"vm":{"name":"error"}}}""" }.asBinder(),
            )
            true
        }
        assertTrue(
            runCatching { runBlocking { errorRemote.awaitVm("running", 1_000) } }
                .exceptionOrNull()
                ?.message
                ?.contains("error state") == true
        )
        errorRemote.close()

        val timeoutRemote = RemoteDevd(context)
        timeoutRemote.bindOverride = { _, conn, _ ->
            conn.onServiceConnected(
                null,
                stub { """{"result":{"vm":{"name":"starting"}}}""" }.asBinder(),
            )
            true
        }
        assertTrue(
            runCatching { runBlocking { timeoutRemote.awaitVm("running", 50) } }
                .exceptionOrNull()
                ?.message
                ?.contains("did not reach") == true
        )
        timeoutRemote.close()
    }

    @Test
    fun aFailedBindIsReported() = runBlocking {
        val remote = RemoteDevd(context)
        remote.bindOverride = { _, _, _ -> false }
        val error = runCatching { runBlocking { remote.call("ok") } }.exceptionOrNull()
        assertTrue(error?.message?.contains("could not bind") == true)
        remote.close()
    }
}
