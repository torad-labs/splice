package splice.app.mods

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.accept
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.app.Daemon
import splice.app.desktop.setup.DesktopManagedPolicy
import splice.app.e2e.AnthropicFrames
import splice.app.e2e.RecordingAnthropic
import splice.client.mcp.McpAccessKey
import splice.core.auth.RefreshAttempt
import splice.core.client.FOREGROUND_OWNER_HEADER
import splice.core.client.LAUNCH_OWNER_HEADER
import splice.core.client.SESSION_HEADER
import splice.core.config.KeyStore
import splice.core.config.KeyStorePath
import splice.core.config.LAUNCH_PROOF_HEADER
import splice.core.config.MgmtKey
import splice.core.config.TurnKey
import splice.core.config.UserHome
import splice.core.testing.TestPorts
import splice.head.awaitListening
import splice.topology.TopologyLoader
import java.nio.file.Files
import java.nio.file.Path

class ModSecretsTest {
    @TempDir lateinit var root: Path

    private val auth = "fake-authorization-3984"
    private val api = "fake-header-key-3984"
    private val cookie = "fake-cookie-3984"
    private val lateKey = "fixture-added-after-boot"
    private val shapedKeys = listOf("xai-${"k".repeat(24)}", "AIza${"e".repeat(35)}")

    @Test
    fun `credentials never reach mod events sessions or environment even when added after boot`() =
        inScratchHome { assertSecretBoundary() }

    @Test
    fun `a launch with mods off stays silent when its HTTP turn omits the owner header`() = inScratchHome {
        withRecorder(emptyMap()) { rig, client, daemon ->
            val owner = launch(client, daemon, emptyList(), "quiet", modsOff = "1")
            bindStartup(client, daemon, owner, "silent")
            val headers = mapOf("Authorization" to "Bearer $auth", LAUNCH_PROOF_HEADER to daemon.turnKey)
            daemon.upstream.enqueue(AnthropicFrames.text("Quiet"))
            assertTrue("message_stop" in turn(client, daemon.head, headers, "quiet", "silent"))
            // An ordinary turn proves the recorder was active and had already drained the first turn's offers.
            daemon.upstream.enqueue(AnthropicFrames.text("Visible"))
            assertTrue("message_stop" in turn(client, daemon.head, headers, "visible", "sentinel"))
            assertEquals(
                setOf("sentinel"),
                rig.through("recorder", "turn.complete", "sentinel").map(RecordedModEvent::session).toSet(),
            )
        }
    }

    private fun inScratchHome(scenario: suspend () -> Unit) = UserHome.within(root) {
        DesktopManagedPolicy.within(root.resolve("etc/managed-settings.json")) {
            runBlocking { scenario() }
        }
    }

    private suspend fun bindStartup(client: HttpClient, daemon: RecorderDaemon, owner: String, session: String) {
        val response = client.post("${daemon.base}/hooks/resume/claude") {
            bearerAuth(daemon.turnKey)
            header(FOREGROUND_OWNER_HEADER, owner)
            setBody("""{"source":"startup","session_id":"$session"}""")
        }
        assertEquals(HttpStatusCode.OK, response.status)
    }

    private suspend fun assertSecretBoundary() {
        val env = mapOf(
            "ANTHROPIC_AUTH_TOKEN" to "fake-environment-login-3984",
            "ANTHROPIC_API_KEY" to "fake-environment-key-3984",
            "MOD_TEST_PROVIDER_LOGIN" to "fake-provider-login-3984",
        )
        withRecorder(env) { rig, client, daemon ->
            val state = rig.runner.paths
            val mcp = McpAccessKey(state.stateDir, state.mgmtKeyFile, {}) { daemon.management }.invoke()
            val values = listOf(auth, api, cookie, daemon.management, daemon.turnKey, mcp, lateKey) +
                env.values + shapedKeys
            KeyStore(KeyStorePath.defaultPath(state.envReader)).write("MOD_TEST_LATE_LOGIN", lateKey)
            val owner = launch(client, daemon, values, "job-$lateKey-tree")
            daemon.upstream.enqueue(AnthropicFrames.toolUse(api, "mcp__notes__read"))
            assertTrue("message_stop" in turn(client, daemon.head, callerHeaders(daemon.turnKey, owner), mcp))
            assertRecorded(rig, values)
        }
    }

    private suspend fun withRecorder(
        env: Map<String, String>,
        scenario: suspend (ModEventsTestRig, HttpClient, RecorderDaemon) -> Unit,
    ) {
        val rig = ModEventsTestRig(root, System.getenv() + env)
        val state = rig.runner.paths
        val mgmt = MgmtKey(state)
        val control = TestPorts.reserve()
        val head = TestPorts.reserve()
        val upstream = RecordingAnthropic()
        val daemon = Daemon(
            topology = TopologyLoader.parse(topology(control, head, upstream.baseUrl)),
            statePaths = state,
            log = {},
            refreshCall = { _, _ -> RefreshAttempt.Denied("test stand-in") },
        )
        val base = "http://127.0.0.1:$control"
        try {
            daemon.start()
            awaitListening(control, head)
            HttpClient(CIO).use { client ->
                rig.install(client, "recorder", base = base)
                scenario(rig, client, RecorderDaemon(base, head, mgmt.get(), TurnKey(mgmt).get(), upstream))
            }
        } finally {
            daemon.stop()
            upstream.stop()
        }
    }

    private fun callerHeaders(turnKey: String, owner: String): Map<String, String> = mapOf(
        "Authorization" to "Bearer $auth",
        "x-api-key" to api,
        "Cookie" to "login=$cookie",
        LAUNCH_PROOF_HEADER to turnKey,
        LAUNCH_OWNER_HEADER to owner,
    )

    private suspend fun assertRecorded(rig: ModEventsTestRig, secrets: List<String>) {
        val events = rig.through("recorder", "turn.complete")
        val start = events.single { it.name == "session.start" && it.session == "session-secret-test" }
        assertEquals("[redacted]", start.payload.getValue("cwd").jsonPrimitive.content)
        assertTrue(events.any { it.name == "tool.call" })
        val labels = start.payload.getValue("labels").jsonObject
        assertEquals("keep", labels.getValue("safe").jsonPrimitive.content)
        secrets.indices.forEach { assertEquals("[redacted]", labels.getValue("copy_$it").jsonPrimitive.content) }
        assertEquals(labels, rig.rows("recorder").single()["labels"])
        val record = rig.runner.log("recorder")
        secrets.forEachIndexed { i, secret ->
            assertFalse(secret in record, "credential #$i escaped the mod boundary")
        }
    }

    private suspend fun turn(
        client: HttpClient,
        head: Int,
        headers: Map<String, String>,
        echo: String,
        session: String = "session-secret-test",
    ): String {
        val answer = client.post("http://127.0.0.1:$head/v1/messages") {
            headers.forEach { (name, value) -> header(name, value) }
            header(SESSION_HEADER, session)
            header("Content-Type", "application/json")
            setBody(
                """{"model":"fixture","max_tokens":16,"stream":true,""" +
                    """"messages":[{"role":"user","content":"$echo"}]}""",
            )
        }
        assertEquals(HttpStatusCode.OK, answer.status)
        return answer.bodyAsText()
    }

    private suspend fun launch(
        client: HttpClient,
        daemon: RecorderDaemon,
        secrets: List<String>,
        workingDir: String,
        modsOff: String? = null,
    ): String {
        val cwd = Files.createDirectory(root.resolve(workingDir))
        val result = client.submitForm(
            "${daemon.base}/launch/claude",
            Parameters.build {
                append("cwd", cwd.toString())
                append("launch", ProcessHandle.current().pid().toString())
                append("label", "SAFE=keep")
                if (modsOff != null) append("modsOff", modsOff)
                secrets.forEachIndexed { i, secret -> append("label", "COPY_$i=embedded-$secret-value") }
            },
        ) {
            bearerAuth(daemon.management)
            accept(ContentType.parse("text/x-splice-launch"))
        }
        assertEquals(HttpStatusCode.OK, result.status)
        return result.bodyAsText().lineSequence().single { it.startsWith("E SPLICE_FOREGROUND_OWNER=") }
            .substringAfter('=')
    }

    private data class RecorderDaemon(
        val base: String,
        val head: Int,
        val management: String,
        val turnKey: String,
        val upstream: RecordingAnthropic,
    )

    private fun topology(control: Int, head: Int, upstream: String): String = """
        [daemon]
        control_port = $control

        [providers.client]
        dialect = "anthropic-passthrough"
        base_url = "$upstream"
        auth = { kind = "client" }
        extra_headers = { anthropic-version = "2023-06-01" }
        [[providers.client.models]]
        id = "fixture"
        context_window = 200000

        [providers.keyed]
        dialect = "anthropic-passthrough"
        base_url = "$upstream"
        auth = { kind = "api-key", env = "MOD_TEST_PROVIDER_LOGIN" }

        [providers.late]
        dialect = "anthropic-passthrough"
        base_url = "$upstream"
        auth = { kind = "api-key", env = "MOD_TEST_LATE_LOGIN" }

        [heads.claude]
        provider = "client"
        port = $head
        discovery_prefix = "claude-splice--"
        pinned_model = "fixture"
    """.trimIndent()
}
