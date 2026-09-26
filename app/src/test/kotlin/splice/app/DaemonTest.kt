// NEW: daemon assembly smoke (P4-SUP) — a real Daemon built from an in-memory topology pointing
// at a mock upstream, started on real ports, driven end-to-end: control /health-ish + a real
// /v1/messages turn through the assembled head. Plus the daemon lock single-flight and topology
// materialization. No live credentials (fake auth file + fake refresh).
package splice.app

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import splice.app.daemon.DaemonLock
import splice.core.auth.RefreshAttempt
import splice.core.config.Knob
import splice.core.config.MgmtKey
import splice.core.config.StatePaths
import splice.core.testing.TestPorts
import splice.head.MockChatGptUpstream
import splice.head.awaitListening
import splice.topology.TopologyLoader
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CancellationException

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DaemonTest {

    private val boundary = DaemonBoundary()

    private val mock = MockChatGptUpstream()
    private val client = HttpClient(CIO)
    private lateinit var daemon: Daemon
    private lateinit var statePaths: StatePaths
    private lateinit var key: String
    private val controlPort = TestPorts.reserve()
    private val headPort = TestPorts.reserve()

    private fun topologyToml() = """
        [daemon]
        control_port = $controlPort

        [providers.codex]
        dialect = "openai-responses"
        base_url = "${mock.baseUrl}"
        auth = { kind = "chatgpt-oauth", file = "AUTHFILE" }
        quirks = { store = false, account_id_header = true, cache_key = "first-message-hash", effort_ceiling = "max", summary_field = true, zstd_request_body = true }

        [[providers.codex.models]]
        id = "gpt-5.6-sol"
        label = "Sol"
        context_window = 272000

        [heads.claudex]
        provider = "codex"
        port = $headPort
        discovery_prefix = "claude-codex--"
        pinned_model = "gpt-5.6-sol"
    """.trimIndent()

    @BeforeAll
    fun setUp() {
        val tmp = Files.createTempDirectory("daemon-test")
        val authFile = tmp.resolve("auth.json")
        Files.writeString(authFile, """{"tokens":{"access_token":"tok-1","account_id":"acct-1","refresh_token":"r"}}""")
        statePaths = StatePaths(baseOverride = tmp.resolve("state"))
        key = MgmtKey(statePaths).get()
        val topology = TopologyLoader.parse(topologyToml().replace("AUTHFILE", authFile.toString().replace("\\", "/")))
        daemon = Daemon(
            topology = topology,
            statePaths = statePaths,
            dashboardHtml = { "<!doctype html><title>splice</title>" },
            log = {},
            refreshCall = { _, _ -> RefreshAttempt.Denied("test-denied") },
        )
        runBlocking { daemon.start() }
        awaitListening(controlPort, headPort)
    }

    @AfterAll
    fun tearDown() {
        runBlocking { daemon.stop() }
        client.close()
        mock.stop()
    }

    @Test
    fun `control status lists the assembled head`() = runBlocking {
        val body = client.get("http://127.0.0.1:$controlPort/api/status") {
            header("Authorization", "Bearer $key")
        }.bodyAsText()
        assertTrue(body.contains("claudex"))
    }

    @Test
    fun `head health is reachable on its own port`() = runBlocking {
        val body = client.get("http://127.0.0.1:$headPort/health").bodyAsText()
        assertTrue(body.contains("\"ok\":true"))
        assertTrue(body.contains("\"port\":$headPort"))
    }

    @Test
    fun `lowercase bearer is accepted on an inference route`() = runBlocking {
        // review gap K: inference (HeadServer.authorize) shares the control plane's bearerToken parser,
        // so a lowercase scheme must authenticate on /v1/models too — a wrong token still 401.
        val ok = client.get("http://127.0.0.1:$headPort/v1/models") { header("Authorization", "bearer $key") }
        assertEquals(200, ok.status.value)
        val bad = client.get("http://127.0.0.1:$headPort/v1/models") { header("Authorization", "bearer wrong-token") }
        assertEquals(401, bad.status.value)
    }

    @Test
    fun `a real turn flows through the assembled head to the mock upstream`() = runBlocking {
        val sse = client.post("http://127.0.0.1:$headPort/v1/messages") {
            header("Content-Type", "application/json")
            header("Authorization", "Bearer $key")
            setBody(
                """{"model":"claude-codex--gpt-5.6-sol","stream":true,"max_tokens":8000,
                    "system":"You are a test. SCENARIO:basic","messages":[{"role":"user","content":"go"}]}""",
            )
        }.bodyAsText()
        assertTrue(sse.contains("ok after auth"))
        assertTrue(sse.contains("event: message_stop"))
        // the account-id header + bearer reached the upstream
        assertTrue(mock.upstreamAuths.any { it.second == "Bearer tok-1" })
        assertEquals("basic" to "acct-1", mock.upstreamAccountIds.last())
    }

    /** V4-213: /api/heads wrote the gate's counters and live rows as literals (acquired 0, live [],
     *  stream_idle_ms 0), so the console's in-flight list was always empty. A turn held open
     *  mid-stream must read as ONE live row naming its model and phase, whose age grows while it is
     *  held, counted by acquired; released, it leaves the list and is counted by released. */
    @Test
    fun `api heads reports a streaming turn as a live gate row with measured counters`() = runBlocking {
        mock.resetHold()
        val turn = async(Dispatchers.IO) {
            client.post("http://127.0.0.1:$headPort/v1/messages") {
                header("Content-Type", "application/json")
                header("Authorization", "Bearer $key")
                header("x-claude-code-session-id", HELD_SESSION)
                setBody(
                    """{"model":"claude-codex--gpt-5.6-sol","stream":true,"max_tokens":8000,
                        "system":"You are a test. SCENARIO:hold","messages":[{"role":"user","content":"go"}]}""",
                )
            }.bodyAsText()
        }
        val held: JsonObject
        try {
            held = awaitGate("one streaming live row") { it.live().singleOrNull()?.text("phase") == "streaming" }
            val row = held.live().single()
            // the session's short tag leads, so two sessions on one model are two tellable rows
            assertEquals("b2e4d8f1 gpt-5.6-sol", row.text("label"))
            assertFalse(row["compact"]!!.jsonPrimitive.boolean)
            // stream_idle_ms is the head's configured limit
            assertEquals(Knob.STREAM_IDLE_MS.default, held.long("stream_idle_ms"))
            assertTrue(held.long("acquired") >= 1, "the held turn was acquired: $held")
            // acquired minus released is the live count
            assertEquals(1L, held.long("acquired") - held.long("released"), "$held")
            // age grows while held, and a silent stream's idle grows with it
            awaitGate("the held row to age past $row") { gate ->
                val later = gate.live().singleOrNull() ?: return@awaitGate false
                later.long("age_ms") > row.long("age_ms") && later.long("idle_ms") > row.long("idle_ms")
            }
        } finally {
            mock.releaseHold()
            turn.await()
        }
        val after = awaitGate("the released turn gone") { gate -> gate.live().isEmpty() }
        assertEquals(held.long("released") + 1, after.long("released"), "the release is counted: $after")
    }

    private suspend fun gate(): JsonObject {
        val body = client.get("http://127.0.0.1:$controlPort/api/heads") {
            header("Authorization", "Bearer $key")
        }.bodyAsText()
        val heads = Json.parseToJsonElement(body).jsonObject["heads"]!!.jsonArray.map { it.jsonObject }
        return heads.first { it.text("key") == "claudex" }["gate"]!!.jsonObject
    }

    private suspend fun awaitGate(what: String, ready: (JsonObject) -> Boolean): JsonObject {
        var last = gate()
        repeat(GATE_POLLS) {
            if (ready(last)) return last
            delay(GATE_POLL_MS)
            last = gate()
        }
        return if (ready(last)) last else fail("timed out waiting for $what on /api/heads; last gate: $last")
    }

    private fun JsonObject.live() = this["live"]!!.jsonArray.map { it.jsonObject }

    private fun JsonObject.long(field: String) = this[field]!!.jsonPrimitive.long

    private fun JsonObject.text(field: String) = this[field]?.jsonPrimitive?.content

    @Test
    fun `AUTHENTICATION failure surfaces the per-head login hint`() = runBlocking {
        val sse = client.post("http://127.0.0.1:$headPort/v1/messages") {
            header("Content-Type", "application/json")
            header("Authorization", "Bearer $key")
            setBody(
                """{"model":"claude-codex--gpt-5.6-sol","stream":true,"max_tokens":8000,
                    "system":"You are a test. SCENARIO:authfail","messages":[{"role":"user","content":"go"}]}""",
            )
        }.bodyAsText()
        assertTrue(sse.contains("event: error"))
        assertTrue(sse.contains("authentication_error"))
        assertTrue(sse.contains("run: claudex login"))
    }

    // A 2-head topology: one valid (codex → mock) plus a `ghost` head whose provider does not exist.
    // Extracted so the degraded-boot test body stays under the detekt LongMethod ceiling.
    private fun degradedTopologyToml(control: Int, head: Int, ghost: Int, authFile: String) = """
        [daemon]
        control_port = $control

        [providers.codex]
        dialect = "openai-responses"
        base_url = "${mock.baseUrl}"
        auth = { kind = "chatgpt-oauth", file = "$authFile" }
        quirks = { store = false, account_id_header = true, cache_key = "first-message-hash", effort_ceiling = "max", summary_field = true }

        [[providers.codex.models]]
        id = "gpt-5.6-sol"
        label = "Sol"
        context_window = 272000

        [heads.claudex]
        provider = "codex"
        port = $head
        discovery_prefix = "claude-codex--"
        pinned_model = "gpt-5.6-sol"

        [heads.ghost]
        provider = "nonesuch"
        port = $ghost
        discovery_prefix = "claude-ghost--"
        pinned_model = "ghost-1"
    """.trimIndent()

    @Test
    fun `degraded boot - an unknown-provider head is failed while the valid head serves`() = runBlocking {
        // finding 3 (review 2026-07-23): a head whose provider does not exist never enters the `heads`
        // map — assembleDaemonHeads routes it to `failed` — so /health must report the CONFIGURED total
        // as `heads`, else the launch shim's readyHeads + failedHeads == heads never converges. The
        // discriminating case: heads.size (=1, assembled only) gives 1 == 1+1 (false); topology.heads.size
        // (=2) gives 2 == 1+1 (true). One real head + one ghost, on their own ports.
        val tmp = Files.createTempDirectory("daemon-degraded")
        val authFile = tmp.resolve("auth.json")
        Files.writeString(authFile, """{"tokens":{"access_token":"tok-1","account_id":"acct-1","refresh_token":"r"}}""")
        val degradedControl = TestPorts.reserve()
        val degradedHead = TestPorts.reserve()
        val authPath = authFile.toString().replace("\\", "/")
        val toml = degradedTopologyToml(degradedControl, degradedHead, TestPorts.reserve(), authPath)
        val degraded = Daemon(
            topology = TopologyLoader.parse(toml),
            statePaths = StatePaths(baseOverride = tmp.resolve("state")),
            dashboardHtml = { "" },
            log = {},
            refreshCall = { _, _ -> RefreshAttempt.Denied("test-denied") },
        )
        degraded.start()
        try {
            awaitListening(degradedControl, degradedHead)
            val obj = Json.parseToJsonElement(
                client.get("http://127.0.0.1:$degradedControl/health").bodyAsText(),
            ).jsonObject
            val heads = obj["heads"]!!.jsonPrimitive.content.toInt()
            val ready = obj["readyHeads"]!!.jsonPrimitive.content.toInt()
            val failed = obj["failedHeads"]!!.jsonPrimitive.content.toInt()
            assertEquals(2, heads, "configured total counts BOTH heads")
            assertEquals(1, ready, "only the codex head assembled and started")
            assertEquals(1, failed, "the unknown-provider head is failed, not assembled")
            assertEquals(heads, ready + failed, "the invariant the launch shim converges on")
        } finally {
            degraded.stop()
        }
    }

    @Test
    fun `daemon lock is single-flight`() {
        val lock1 = DaemonLock(statePaths.daemonLockFile)
        assertTrue(lock1.tryAcquire())
        val lock2 = DaemonLock(statePaths.daemonLockFile)
        assertFalse(lock2.tryAcquire()) // a second holder loses
        lock1.close()
        val lock3 = DaemonLock(statePaths.daemonLockFile)
        assertTrue(lock3.tryAcquire()) // freed after close
        lock3.close()
    }

    @Test
    fun `topology materializes supported api-key defaults on first run`() {
        val tmp = Files.createTempDirectory("topo")
        val path: Path = tmp.resolve("splice.toml")
        val topo = TopologyLoader.loadOrMaterialize(path)
        assertTrue(Files.exists(path))
        assertEquals(3096, topo.daemon.controlPort)
        assertEquals(setOf("openrouter"), topo.heads.keys)
        assertEquals(setOf("api-key"), topo.providers.values.map { it.auth.kind }.toSet())
        assertTrue(topo.providers.values.none { it.auth.kind.endsWith("oauth") })
        assertEquals("claude-openrouter", topo.heads.getValue("openrouter").claude.command)
    }

    @Test
    fun `dashboard loader prefers a checkout build and falls back to the packaged resource`() {
        val tmp = Files.createTempDirectory("dashboard-loader")
        val dist = tmp.resolve("index.html")
        var packagedReads = 0
        val dashboard = DashboardHtml().source(dist) {
            packagedReads += 1
            "<html>packaged</html>"
        }

        assertEquals("<html>packaged</html>", dashboard())
        assertEquals(1, packagedReads)

        Files.writeString(dist, "<html>checkout</html>")
        assertEquals("<html>checkout</html>", dashboard())
        assertEquals(1, packagedReads)
    }

    @Test
    fun `daemon isolation catches expected startup failures`() {
        assertTrue(boundary.runCatchingDaemonBoundary { throw IllegalStateException("bad head") }.isFailure)
    }

    @Test
    fun `daemon isolation never catches cancellation`() {
        assertThrows(CancellationException::class.java) {
            boundary.runCatchingDaemonBoundary { throw CancellationException("cancelled") }
        }
    }

    @Test
    fun `daemon isolation never catches fatal errors`() {
        assertThrows(OutOfMemoryError::class.java) {
            boundary.runCatchingDaemonBoundary { throw OutOfMemoryError("fatal") }
        }
    }
}

/** /api/heads is polled for up to 10 s: a held turn reaches the gate in well under a second. */
private const val GATE_POLLS = 200
private const val GATE_POLL_MS = 50L
private const val HELD_SESSION = "b2e4d8f1-5c6a-4f32-8d1b-6e3f9a2c4b02"
