// NEW: the real late native owner must become a selectable, ordered, read-only pool on its head.
package splice.app.auth.claude

import io.ktor.client.HttpClient
import io.ktor.client.engine.java.Java
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.accounts.pool.HeadAccountPinSource
import splice.app.probe.UpstreamPlaygroundProbe
import splice.core.auth.ClientAuthProvider
import splice.core.auth.CredentialKey
import splice.core.usage.PlanLimit
import splice.diagnostics.playground.PlaygroundHead
import splice.head.usage.CredentialQuotaFiles
import splice.upstream.RetryNotice
import splice.usage.quota.QuotaProbes
import java.nio.file.Files
import java.nio.file.Path

internal const val NATIVE_HEAD = "claude-splice"
internal const val NATIVE_SELECTOR = "native:claude"
internal const val SPLICE_SELECTOR = "native:claude-splice"
internal const val NATIVE_ACCESS_EXPIRY = 4_102_444_800_000L

class ClaudeNativePoolWiringTest {
    @TempDir
    lateinit var home: Path

    private val fixture by lazy { ClaudeNativePoolFixture(home) }
    private val paths get() = fixture.paths

    private fun seed(place: String, expiresAt: Long = NATIVE_ACCESS_EXPIRY) = fixture.seed(place, expiresAt)

    private suspend fun rig(
        probes: QuotaProbes? = null,
        upstreamUrl: String = "https://synthetic.example",
    ): ClaudeNativePoolFixture.Rig = fixture.rig(probes, upstreamUrl)

    @Test
    fun `two verified native places become an active pool and default to the soonest reset`() = runBlocking {
        seed("native")
        seed("splice")
        val rig = rig()
        try {
            assertTrue(requireNotNull(rig.head.accountPool).active, "the native owner must publish both places")
            var authorization: String? = null
            HttpClient(
                MockEngine {
                    authorization = it.headers["Authorization"]
                    respond("{}", HttpStatusCode.OK)
                },
            ).use { client ->
                splice.app.probe.UpstreamPlaygroundProbe(rig.plane.playgroundProviders, client)
                    .run(PlaygroundHead(NATIVE_HEAD, rig.head.auth), "synthetic native selection", null)
            }
            assertEquals("Bearer synthetic-native", authorization)
        } finally {
            rig.close()
        }
    }

    @Test
    fun `the real console order route reads and changes the native selector`() = runBlocking {
        seed("native")
        seed("splice")
        val rig = rig()
        try {
            HttpClient(Java).use { client ->
                val url = "http://127.0.0.1:${rig.server.listeningPort}/api/auth/$NATIVE_HEAD/order"
                val initial = client.get(url) { bearerAuth(rig.key.get()) }
                assertEquals(HttpStatusCode.OK, initial.status, "two native accounts must not return no source")
                val changed = client.put(url) {
                    bearerAuth(rig.key.get())
                    contentType(ContentType.Application.Json)
                    setBody("""{"order":["$SPLICE_SELECTOR","$NATIVE_SELECTOR"]}""")
                }
                assertEquals(HttpStatusCode.OK, changed.status)
                val body = Json.parseToJsonElement(changed.bodyAsText()).jsonObject
                assertEquals(
                    listOf(SPLICE_SELECTOR, NATIVE_SELECTOR),
                    body.getValue("effective_order").jsonArray.map { it.jsonPrimitive.content },
                )
                assertEquals(SPLICE_SELECTOR, requireNotNull(rig.head.accountPool).view(null).nextTargetLabel)
            }
        } finally {
            rig.close()
        }
    }

    @Test
    fun `a weekly native refusal hands off to the other live place without changing either credential`() = runBlocking {
        limitControl(expiredStandby = false)
    }

    @Test
    fun `a weekly limit with an expired standby preserves native refusal typing and names its remedy`() = runBlocking {
        limitControl(expiredStandby = true)
    }

    private suspend fun limitControl(expiredStandby: Boolean) {
        seed("native")
        seed("splice", expiresAt = if (expiredStandby) 1L else NATIVE_ACCESS_EXPIRY)
        val files = listOf(home.resolve(".claude/.credentials.json"), home.resolve(".claude-splice/.credentials.json"))
        val before = files.map(Files::readAllBytes)
        val sent = java.util.concurrent.CopyOnWriteArrayList<String?>()
        val bodies = java.util.concurrent.CopyOnWriteArrayList<String>()
        val upstream = fixture.nativeUpstream(sent, bodies).start()
        val port = upstream.engine.resolvedConnectors().single().port
        val rig = rig(upstreamUrl = "http://127.0.0.1:$port")
        try {
            HttpClient(Java).use { client ->
                val first = nativeTurn(client, rig)
                val second = nativeTurn(client, rig)
                if (expiredStandby) {
                    assertEquals(HttpStatusCode.TooManyRequests, first.status)
                    val error = Json.parseToJsonElement(first.bodyAsText()).jsonObject.getValue("error").jsonObject
                    assertEquals("rate_limit_error", error.getValue("type").jsonPrimitive.content)
                    val message = error.getValue("message").jsonPrimitive.content
                    assertTrue(message.contains("synthetic weekly limit"))
                    assertTrue(message.contains("claude-splice cannot take over") && message.contains("expired"))
                    assertTrue(message.contains("Sign in again on claude-splice in the console."))
                    assertEquals(HttpStatusCode.TooManyRequests, second.status)
                    assertTrue(second.bodyAsText().contains("claude-splice cannot take over"))
                    assertEquals(listOf("Bearer synthetic-native"), sent)
                } else {
                    assertEquals(HttpStatusCode.OK, first.status, first.bodyAsText())
                    assertEquals(HttpStatusCode.OK, second.status, second.bodyAsText())
                    assertEquals(
                        listOf("Bearer synthetic-native", "Bearer synthetic-splice", "Bearer synthetic-splice"),
                        sent,
                    )
                    assertEquals(
                        1,
                        bodies.distinct().size,
                        "account handoff must not alter request bytes or cache keys",
                    )
                }
            }
            files.forEachIndexed { index, file -> assertArrayEquals(before[index], Files.readAllBytes(file)) }
        } finally {
            rig.close()
            upstream.stop()
        }
    }

    private suspend fun nativeTurn(client: HttpClient, rig: ClaudeNativePoolFixture.Rig): HttpResponse =
        client.post("http://127.0.0.1:${rig.head.head.port}/v1/messages") {
            bearerAuth("synthetic-caller")
            contentType(ContentType.Application.Json)
            setBody(
                """{"model":"synthetic-model","stream":false,"max_tokens":32,"messages":[{"role":"user","content":"synthetic native turn"}]}""",
            )
        }

    @Test
    fun `native expiry is read on the next selection without waiting for the cached evidence TTL`() = runBlocking {
        seed("native")
        seed("splice")
        val rig = rig()
        try {
            assertEquals(NATIVE_SELECTOR, rig.plane.playgroundProviders.target(NATIVE_HEAD)?.login?.label)
            seed("native", expiresAt = 1L)
            assertEquals(SPLICE_SELECTOR, rig.plane.playgroundProviders.target(NATIVE_HEAD)?.login?.label)
            val expired = rig.head.accountPool?.view(null)?.accounts?.single { it.label == NATIVE_SELECTOR }
            assertEquals(false, expired?.available)
        } finally {
            rig.close()
        }
    }

    @Test
    fun `removing all native credentials restores forwarding and readding one restores selection`() = runBlocking {
        seed("native")
        seed("splice")
        val rig = rig()
        try {
            Files.delete(home.resolve(".claude/.credentials.json"))
            Files.delete(home.resolve(".claude-splice/.credentials.json"))
            assertEquals(false, rig.head.accountPool?.active)
            assertNull(rig.plane.playgroundProviders.target(NATIVE_HEAD)?.login)
            seed("native")
            assertEquals(true, rig.head.accountPool?.active)
            assertEquals(NATIVE_SELECTOR, rig.plane.playgroundProviders.target(NATIVE_HEAD)?.login?.label)
        } finally {
            rig.close()
        }
    }

    @Test
    fun `a captured native refusal cannot hold a replacement credential at the same physical place`() = runBlocking {
        seed("native")
        seed("splice")
        val rig = rig()
        val entered = CompletableDeferred<Unit>()
        val released = CompletableDeferred<Unit>()
        fixture.delayedClient(entered, released).use { client ->
            try {
                val probe = UpstreamPlaygroundProbe(rig.plane.playgroundProviders, client)
                val first = async { probe.run(PlaygroundHead(NATIVE_HEAD, rig.head.auth), "synthetic", null) }
                withTimeout(10_000L) { entered.await() }
                val previous = requireNotNull(rig.plane.playgroundProviders.target(NATIVE_HEAD)?.login?.selectedAccount)
                previous.cooldown.planHold.hold(
                    PlanLimit("seven_day", System.currentTimeMillis() / 1_000L + 3_600L),
                    RetryNotice {},
                )
                val file = fixture.replaceNative()
                val before = Files.readAllBytes(file)
                val pin = requireNotNull(rig.head.accountPool as? HeadAccountPinSource)
                assertTrue(pin.pin(NATIVE_SELECTOR))
                probe.run(PlaygroundHead(NATIVE_HEAD, rig.head.auth), "synthetic", null)
                released.complete(Unit)
                first.await()
                val replacement = rig.head.accountPool.view(null).accounts.single { it.label == NATIVE_SELECTOR }
                assertTrue(replacement.available)
                assertArrayEquals(before, Files.readAllBytes(file))
            } finally {
                released.complete(Unit)
                rig.close()
            }
        }
    }

    @Test
    fun `saved native order survives reconstruction before the first send`() = runBlocking {
        seed("native")
        seed("splice")
        val first = rig()
        try {
            HttpClient(Java).use { client ->
                val url = "http://127.0.0.1:${first.server.listeningPort}/api/auth/$NATIVE_HEAD/order"
                val response = client.put(url) {
                    bearerAuth(first.key.get())
                    contentType(ContentType.Application.Json)
                    setBody("""{"order":["$SPLICE_SELECTOR","$NATIVE_SELECTOR"]}""")
                }
                assertEquals(HttpStatusCode.OK, response.status)
            }
        } finally {
            first.close()
        }
        val restored = rig()
        try {
            assertEquals(SPLICE_SELECTOR, restored.plane.playgroundProviders.target(NATIVE_HEAD)?.login?.label)
        } finally {
            restored.close()
        }
    }

    @Test
    fun `native pollers use live access tokens and never probe an expired place or change credentials`() = runBlocking {
        seed("native", expiresAt = 1L)
        seed("splice")
        val nativeFile = home.resolve(".claude/.credentials.json")
        val spliceFile = home.resolve(".claude-splice/.credentials.json")
        val nativeBefore = Files.readAllBytes(nativeFile)
        val spliceBefore = Files.readAllBytes(spliceFile)
        val polled = CompletableDeferred<Unit>()
        val requests = java.util.concurrent.CopyOnWriteArrayList<String?>()
        HttpClient(
            MockEngine {
                requests.add(it.headers["Authorization"])
                assertEquals("synthetic-client", it.headers["User-Agent"])
                polled.complete(Unit)
                respond("""{"five_hour":{"utilization":37.0,"resets_at":"2039-12-31T00:00:00Z"}}""", HttpStatusCode.OK)
            },
        ).use { client ->
            val rig = rig(QuotaProbes(client))
            try {
                withTimeout(10_000L) {
                    polled.await()
                    while (rig.head.accountPool?.view(null)?.accounts?.single { it.label == SPLICE_SELECTOR }
                            ?.fiveHourUsedPercent != 37.0
                        ) yield()
                }
                assertEquals(listOf("Bearer synthetic-splice"), requests)
                assertArrayEquals(nativeBefore, Files.readAllBytes(nativeFile))
                assertArrayEquals(spliceBefore, Files.readAllBytes(spliceFile))
                val key = requireNotNull(CredentialKey.fromHeaders(mapOf("Authorization" to "Bearer synthetic-splice")))
                val snapshot = CredentialQuotaFiles(paths.quotaFile(NATIVE_HEAD), {}).read(key)
                assertEquals(37.0, snapshot?.fiveHour?.usedPercent)
                rig.plane.providerAssembly.claudePoolChanges.publish(NATIVE_HEAD)
                val republished = rig.head.accountPool?.view(null)?.accounts?.single { it.label == SPLICE_SELECTOR }
                assertEquals(37.0, republished?.fiveHourUsedPercent)
            } finally {
                rig.close()
            }
        }
    }

    private fun seedUsage(place: String, percent: Double, observed: Long, expiresAt: Long) {
        fixture.seed(
            place,
            expiresAt,
            splice.core.usage.QuotaSnapshot(
                sevenDay = splice.core.usage.QuotaWindow(
                    percent,
                    System.currentTimeMillis() / 1_000L + 86_400L,
                    604_800L,
                ),
                updatedAt = observed,
            ),
        )
    }

    @Test
    fun `usage before any send falls back to the next selectable native login`() = runBlocking {
        val observed = System.currentTimeMillis()
        seedUsage("native", 98.0, observed, NATIVE_ACCESS_EXPIRY)
        seedUsage("splice", 59.0, observed - 75_600_000L, 1L)
        val rig = rig()
        try {
            HttpClient(Java).use { client ->
                val response = client.get("http://127.0.0.1:${rig.server.listeningPort}/api/usage") {
                    bearerAuth(rig.key.get())
                }
                assertEquals(HttpStatusCode.OK, response.status)
                val usage = Json.parseToJsonElement(response.bodyAsText()).jsonObject
                    .getValue("heads").jsonArray.single().jsonObject.getValue("usage").jsonObject
                val weekly = usage.getValue("quota").jsonObject.getValue("seven_day").jsonObject
                assertEquals("98", weekly.getValue("used_pct").jsonPrimitive.content)
                assertEquals("98", usage.getValue("warn").jsonObject.getValue("pct").jsonPrimitive.content)
            }
        } finally {
            rig.close()
        }
    }

    @Test
    fun `usage reads the carrying login instead of the expired primary folder reading`() = runBlocking {
        val observed = System.currentTimeMillis()
        seedUsage("native", 98.0, observed, NATIVE_ACCESS_EXPIRY)
        seedUsage("splice", 59.0, observed - 75_600_000L, 1L)
        val sent = java.util.concurrent.CopyOnWriteArrayList<String?>()
        val bodies = java.util.concurrent.CopyOnWriteArrayList<String>()
        val upstream = fixture.nativeUpstream(sent, bodies, refusedCredential = null).start()
        val port = upstream.engine.resolvedConnectors().single().port
        val rig = rig(upstreamUrl = "http://127.0.0.1:$port")
        try {
            HttpClient(Java).use { client ->
                assertEquals(HttpStatusCode.OK, nativeTurn(client, rig).status)
                assertEquals(listOf("Bearer synthetic-native"), sent)
                val accounts = client.get("http://127.0.0.1:${rig.server.listeningPort}/api/accounts") {
                    bearerAuth(rig.key.get())
                }
                val native = Json.parseToJsonElement(accounts.bodyAsText()).jsonObject
                    .getValue("accounts").jsonArray.single {
                        it.jsonObject["selector_key"]?.jsonPrimitive?.content == NATIVE_SELECTOR
                    }
                assertEquals("true", native.jsonObject.getValue("carrying_request").jsonPrimitive.content)
                val response = client.get("http://127.0.0.1:${rig.server.listeningPort}/api/usage") {
                    bearerAuth(rig.key.get())
                }
                assertEquals(HttpStatusCode.OK, response.status)
                val head = Json.parseToJsonElement(response.bodyAsText()).jsonObject
                    .getValue("heads").jsonArray.single().jsonObject
                assertEquals("null", head.getValue("account_pool").jsonObject.getValue("selected_label").toString())
                val usage = head.getValue("usage").jsonObject
                val weekly = usage.getValue("quota").jsonObject.getValue("seven_day").jsonObject
                assertEquals("98", weekly.getValue("used_pct").jsonPrimitive.content)
                assertEquals((observed / 1_000L).toString(), weekly.getValue("observed_at").jsonPrimitive.content)
                val warn = usage.getValue("warn").jsonObject
                assertEquals("98", warn.getValue("pct").jsonPrimitive.content)
                assertTrue(warn.getValue("level").jsonPrimitive.content != "ok")
            }
        } finally {
            rig.close()
            upstream.stop()
        }
    }

    @Test
    fun `an expired native place is skipped and its credential is never refreshed or written`() = runBlocking {
        seed("native", expiresAt = 1L)
        seed("splice")
        val file = home.resolve(".claude/.credentials.json")
        val before = Files.readAllBytes(file)
        val rig = rig()
        try {
            val native = rig.plane.providerAssembly.claudeAccounts(NATIVE_HEAD, ClientAuthProvider(NATIVE_HEAD))
                .single { it.label == NATIVE_SELECTOR }
            assertNull(native.auth.refresh(), "only the Claude Code owning a native place may refresh it")
            assertArrayEquals(before, Files.readAllBytes(file), "splice must never write native credentials")
            val pool = requireNotNull(rig.head.accountPool).view(null)
            assertEquals(false, pool.accounts.single { it.label == NATIVE_SELECTOR }.available)
            assertEquals(SPLICE_SELECTOR, pool.nextTargetLabel)
            assertTrue(native.auth.describe().fields["refusal"].orEmpty().contains("expired"))
            HttpClient(Java).use { client ->
                val response = client.get("http://127.0.0.1:${rig.server.listeningPort}/api/accounts") {
                    bearerAuth(rig.key.get())
                }
                val rows = Json.parseToJsonElement(response.bodyAsText()).jsonObject.getValue("accounts").jsonArray
                assertEquals(2, rows.size, "native places must not also appear as managed pool rows")
                val expired = rows.single { it.jsonObject["label"]?.jsonPrimitive?.content == "claude" }.jsonObject
                val usable = rows.single { it.jsonObject["label"]?.jsonPrimitive?.content == "claude-splice" }.jsonObject
                assertEquals("false", expired.getValue("available").jsonPrimitive.content)
                assertEquals(
                    "Access token expired. Sign in again on claude in the console.",
                    expired.getValue("refusal").jsonPrimitive.content,
                )
                assertEquals(NATIVE_SELECTOR, expired.getValue("selector_key").jsonPrimitive.content)
                assertEquals("claude", expired.getValue("edit_target").jsonObject.getValue("id").jsonPrimitive.content)
                assertEquals("true", usable.getValue("available").jsonPrimitive.content)
            }
        } finally {
            rig.close()
        }
    }
}
