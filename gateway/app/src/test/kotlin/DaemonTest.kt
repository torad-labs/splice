// NEW: daemon assembly smoke (P4-SUP) — a real Daemon built from an in-memory topology pointing
// at a mock upstream, started on real ports, driven end-to-end: control /health-ish + a real
// /v1/messages turn through the assembled head. Plus the daemon lock single-flight and topology
// materialization. No live credentials (fake auth file + fake refresh).
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.runBlocking
import mock.MockChatGptUpstream
import mock.awaitListening
import mock.freshPort
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import splice.app.Daemon
import splice.app.DaemonLock
import splice.app.TopologyLoader
import splice.core.auth.RefreshAttempt
import splice.core.config.MgmtKey
import splice.core.config.StatePaths
import java.nio.file.Files
import java.nio.file.Path

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DaemonTest {

    private val mock = MockChatGptUpstream()
    private val client = HttpClient(CIO)
    private lateinit var daemon: Daemon
    private lateinit var statePaths: StatePaths
    private lateinit var key: String
    private val controlPort = freshPort()
    private val headPort = freshPort()

    private fun topologyToml() = """
        [daemon]
        control_port = $controlPort

        [providers.codex]
        dialect = "openai-responses"
        base_url = "${mock.baseUrl}"
        auth = { kind = "chatgpt-oauth", file = "AUTHFILE" }
        quirks = { store = false, account_id_header = true, cache_key = "first-message-hash", effort_ceiling = "max", summary_field = true }

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
    fun `a real turn flows through the assembled head to the mock upstream`() = runBlocking {
        val sse = client.post("http://127.0.0.1:$headPort/v1/messages") {
            header("Content-Type", "application/json")
            setBody(
                """{"model":"claude-codex--gpt-5.6-sol","stream":true,"max_tokens":8000,
                    "system":"You are a test. SCENARIO:basic","messages":[{"role":"user","content":"go"}]}""",
            )
        }.bodyAsText()
        assertTrue(sse.contains("ok after auth"))
        assertTrue(sse.contains("event: message_stop"))
        // the account-id header + bearer reached the upstream
        assertTrue(mock.upstreamAuths.any { it.second == "Bearer tok-1" })
    }

    @Test
    fun `AUTHENTICATION failure surfaces the per-head login hint`() = runBlocking {
        val sse = client.post("http://127.0.0.1:$headPort/v1/messages") {
            header("Content-Type", "application/json")
            setBody(
                """{"model":"claude-codex--gpt-5.6-sol","stream":true,"max_tokens":8000,
                    "system":"You are a test. SCENARIO:authfail","messages":[{"role":"user","content":"go"}]}""",
            )
        }.bodyAsText()
        assertTrue(sse.contains("event: error"))
        assertTrue(sse.contains("authentication_error"))
        assertTrue(sse.contains("run: claudex login"))
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
    fun `topology materializes defaults on first run`() {
        val tmp = Files.createTempDirectory("topo")
        val path: Path = tmp.resolve("splice.toml")
        val topo = TopologyLoader.loadOrMaterialize(path)
        assertTrue(Files.exists(path))
        assertEquals(3096, topo.daemon.controlPort)
        assertTrue(topo.heads.containsKey("claudex"))
    }
}
