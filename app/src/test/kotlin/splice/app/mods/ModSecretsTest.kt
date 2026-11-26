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
import splice.core.client.LAUNCH_OWNER_HEADER
import splice.core.client.SESSION_HEADER
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

    @Test
    fun `credentials never reach mod events sessions or environment`() = UserHome.within(root) {
        DesktopManagedPolicy.within(root.resolve("etc/managed-settings.json")) {
            runBlocking { assertSecretBoundary() }
        }
    }

    private suspend fun assertSecretBoundary() {
        val auth = "fake-authorization-3984"
        val api = "fake-header-key-3984"
        val cookie = "fake-cookie-3984"
        val env = mapOf(
            "ANTHROPIC_AUTH_TOKEN" to "fake-environment-login-3984",
            "ANTHROPIC_API_KEY" to "fake-environment-key-3984",
            "MOD_TEST_PROVIDER_LOGIN" to "fake-provider-login-3984",
        )
        val rig = ModEventsTestRig(root, System.getenv() + env)
        val state = rig.runner.paths
        val mgmt = MgmtKey(state)
        val turnKey = TurnKey(mgmt).get()
        val mcp = McpAccessKey(state.stateDir, state.mgmtKeyFile, {}, mgmt::get).invoke()
        val values = listOf(auth, api, cookie, mgmt.get(), turnKey, mcp) + env.values
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
                val owner = launch(client, base, mgmt.get(), values, cookie)
                upstream.enqueue(AnthropicFrames.toolUse(api, "mcp__notes__read"))
                val headers = mapOf(
                    "Authorization" to "Bearer $auth",
                    "x-api-key" to api,
                    "Cookie" to "login=$cookie",
                    LAUNCH_PROOF_HEADER to turnKey,
                    LAUNCH_OWNER_HEADER to owner,
                )
                assertTrue("message_stop" in turn(client, head, headers, mcp))
                assertRecorded(rig, values)
            }
        } finally {
            daemon.stop()
            upstream.stop()
        }
    }

    private suspend fun assertRecorded(rig: ModEventsTestRig, secrets: List<String>) {
        val events = rig.through("recorder", "turn.complete")
        assertTrue(events.any { it.name == "session.start" && it.session == "session-secret-test" })
        assertTrue(events.any { it.name == "tool.call" })
        assertTrue(events.any { it.payload["labels"].toString() == """{"safe":"keep"}""" })
        assertEquals("""{"safe":"keep"}""", rig.rows("recorder").single()["labels"].toString())
        val record = rig.runner.log("recorder")
        secrets.forEachIndexed { i, secret ->
            assertFalse(secret in record, "credential #$i escaped the mod boundary")
        }
    }

    private suspend fun turn(client: HttpClient, head: Int, headers: Map<String, String>, echo: String): String {
        val answer = client.post("http://127.0.0.1:$head/v1/messages") {
            headers.forEach { (name, value) -> header(name, value) }
            header(SESSION_HEADER, "session-secret-test")
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
        base: String,
        management: String,
        secrets: List<String>,
        cwdSecret: String,
    ): String {
        val cwd = Files.createDirectory(root.resolve(cwdSecret))
        val result = client.submitForm(
            "$base/launch/claude",
            Parameters.build {
                append("cwd", cwd.toString())
                append("launch", ProcessHandle.current().pid().toString())
                append("label", "SAFE=keep")
                secrets.forEachIndexed { i, secret -> append("label", "COPY_$i=embedded-$secret-value") }
            },
        ) {
            bearerAuth(management)
            accept(ContentType.parse("text/x-splice-launch"))
        }
        assertEquals(HttpStatusCode.OK, result.status)
        return result.bodyAsText().lineSequence().single { it.startsWith("E SPLICE_FOREGROUND_OWNER=") }
            .substringAfter('=')
    }

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

        [heads.claude]
        provider = "client"
        port = $head
        discovery_prefix = "claude-splice--"
        pinned_model = "fixture"
    """.trimIndent()
}
