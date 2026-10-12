// NEW: the Version card's "Check" through its real routes. The one fake is the network edge: the fetch answers the
// release assets a tap asks for, and records what was asked.
package splice.lifecycle.upgrade

import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.terminal.TerminalOutput
import splice.core.util.EnvReader
import splice.core.util.WallClock
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

private const val LATEST_BASE = "https://github.com/torad-labs/splice/releases/latest/download"

class UpgradeCheckRoutesTest {
    private val asked = CopyOnWriteArrayList<String>()
    private var now = 1_000_000L
    private var shim: String? = launchShim("0.4.1")
    private var sums: String? = null
    private var hold: CountDownLatch? = null
    private var entered = CountDownLatch(1)

    private fun launchShim(version: String) = "#!/bin/sh\nSPLICE_GATEWAY_VERSION=\"$version\"\n"

    private fun sha(text: String) =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    private val fetch = UpgradeFetch { url ->
        asked += url
        entered.countDown()
        hold?.await(WAIT_S, TimeUnit.SECONDS)
        when (url.substringAfterLast('/')) {
            SUMS_ASSET -> (sums ?: shim?.let { "${sha(it)}  $SHIM_ASSET\n" })?.toByteArray()
            SHIM_ASSET -> shim?.toByteArray()
            else -> null
        }
    }

    private val checks = UpgradeChecks(EnvReader { null }, WallClock { now }).also {
        val noGh = UpgradeProcess { _, _ -> UpgradeExit(NO_SUCH_COMMAND, "") }
        it.release = UpgradeRelease(TerminalOutput { }, fetch, noGh, "java")
    }

    private fun ApplicationTestBuilder.mount() {
        val routes = UpgradeCheckRoutes({ checks }, Dispatchers.IO)
        application {
            routing {
                get("/api/upgrade/check") { routes.snapshot(call) }
                post("/api/upgrade/check") { routes.check(call) }
            }
        }
    }

    private fun HttpResponse.state() =
        Json.parseToJsonElement(kotlinx.coroutines.runBlocking { bodyAsText() }).jsonObject

    @Test
    fun `before any tap the snapshot says never checked and nothing was fetched`() = testApplication {
        mount()
        val snapshot = client.get("/api/upgrade/check").state()
        assertEquals("never_checked", snapshot.getValue("state").jsonPrimitive.content)
        assertEquals(JsonNull, snapshot.getValue("latest"))
        assertEquals(emptyList<String>(), asked)
    }

    @Test
    fun `a tap reads the newest version from the verified release and the snapshot keeps it without fetching again`() =
        testApplication {
            mount()
            val tap = client.post("/api/upgrade/check")
            assertEquals(HttpStatusCode.OK, tap.status)
            assertEquals("read", tap.state().getValue("state").jsonPrimitive.content)
            assertEquals("0.4.1", tap.state().getValue("latest").jsonPrimitive.content)
            val fetches = asked.size
            val snapshot = client.get("/api/upgrade/check").state()
            assertEquals("0.4.1", snapshot.getValue("latest").jsonPrimitive.content)
            assertEquals(now, snapshot.getValue("read_at_epoch_millis").jsonPrimitive.content.toLong())
            assertEquals(fetches, asked.size, "the GET never fetches")
            assertTrue(asked.all { it.startsWith("$LATEST_BASE/") }, "only the release assets are asked for: $asked")
        }

    @Test
    fun `a failed read after a good one answers not read and drops the older version`() = testApplication {
        mount()
        client.post("/api/upgrade/check")
        now += 60_000
        shim = null
        val failed = client.post("/api/upgrade/check").state()
        assertEquals("not_read", failed.getValue("state").jsonPrimitive.content)
        assertEquals(JsonNull, failed.getValue("latest"))
        val snapshot = client.get("/api/upgrade/check").state()
        assertEquals("not_read", snapshot.getValue("state").jsonPrimitive.content)
        assertEquals(JsonNull, snapshot.getValue("latest"))
    }

    @Test
    fun `a shim that does not match the published sums is not read`() = testApplication {
        mount()
        sums = "${sha("something else")}  $SHIM_ASSET\n"
        val tap = client.post("/api/upgrade/check").state()
        assertEquals("not_read", tap.getValue("state").jsonPrimitive.content)
        assertEquals(JsonNull, tap.getValue("latest"))
        assertTrue(tap.getValue("reason").jsonPrimitive.content.contains("sha256 verification FAILED"))
    }

    @Test
    fun `a second tap within ten seconds is refused with when to retry and does not fetch`() = testApplication {
        mount()
        client.post("/api/upgrade/check")
        val fetches = asked.size
        now += 4_000
        val again = client.post("/api/upgrade/check")
        assertEquals(HttpStatusCode.TooManyRequests, again.status)
        assertEquals("6", again.headers[HttpHeaders.RetryAfter])
        assertEquals(fetches, asked.size)
        now += 6_000
        assertEquals(HttpStatusCode.OK, client.post("/api/upgrade/check").status)
    }

    @Test
    fun `a tap while a read is running is refused and the running read still lands`() = testApplication {
        mount()
        hold = CountDownLatch(1)
        coroutineScope {
            val first = async(Dispatchers.IO) { client.post("/api/upgrade/check") }
            assertTrue(entered.await(WAIT_S, TimeUnit.SECONDS), "the first read started")
            val second = client.post("/api/upgrade/check")
            assertEquals(HttpStatusCode.Conflict, second.status)
            hold?.countDown()
            val done = first.await()
            assertEquals(HttpStatusCode.OK, done.status)
            assertEquals("0.4.1", done.state().getValue("latest").jsonPrimitive.content)
        }
    }

    @Test
    fun `the upgrade status reports the version a tap read as measured, and the older guess never`() {
        val status = ConsoleUpgradeStatus(EnvReader { null }, checks)
        val before = Json.parseToJsonElement(status.json()).jsonObject
        assertEquals("unavailable", before.getValue("latest_basis").jsonPrimitive.content)
        checks.check()
        val after = Json.parseToJsonElement(status.json()).jsonObject
        assertEquals("0.4.1", after.getValue("latest").jsonPrimitive.content)
        assertEquals("measured", after.getValue("latest_basis").jsonPrimitive.content)
        assertEquals(now, after.getValue("checked_at_epoch_millis").jsonPrimitive.content.toLong())
    }

    private companion object {
        const val WAIT_S = 10L
    }
}
