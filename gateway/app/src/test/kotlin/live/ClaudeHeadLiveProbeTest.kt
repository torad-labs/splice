// NEW (CH-11, campaign claude-head): the LIVE half of the claude head's proof.
//
// Every other test in this campaign runs against a mock upstream, which can only prove splice does
// what splice intended. This one boots a REAL daemon against the REAL api.anthropic.com and asks
// the questions a mock cannot answer: does the base URL resolve, does TLS work, does Anthropic
// accept the request SHAPE this dialect builds, and does splice add nothing of its own to the auth?
//
// It is OPT-IN and skipped by default (SPLICE_LIVE_PROBE=1), because the gate must never depend on
// a third party being reachable — the repo's own law that externally-dependent checks degrade to a
// non-blocking state rather than turning main red.
//
// IT SPENDS NO QUOTA AND USES NO CREDENTIAL. It sends a deliberately INVALID bearer, so the turn is
// rejected at authentication, before any inference. That is what makes it safe to run unattended —
// and it still discriminates the failures that matter:
//   · 401 from Anthropic  -> the request reached the vendor and was well-formed enough to
//                            authenticate; host, path, version header and body shape are all right,
//                            and splice injected no credential of its own.
//   · 400                 -> the vendor parsed it and REJECTED THE SHAPE — a real defect here.
//   · connect/TLS failure -> wiring or environment, not auth.
//
// The remaining half — a REAL turn on the operator's own Max login, which costs quota and needs a
// credential splice deliberately never holds — is the operator's to run. Recipe and evidence live
// in gateway/spikes/results/claude-head-probe.md.
package live

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import mock.awaitListening
import mock.freshPort
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import splice.app.Daemon
import splice.app.TopologyLoader
import splice.core.auth.RefreshAttempt
import splice.core.config.StatePaths
import java.nio.file.Files

private fun topologyToml(controlPort: Int, headPort: Int): String = """
    [daemon]
    control_port = $controlPort

    [providers.anthropic]
    dialect = "anthropic-passthrough"
    base_url = "https://api.anthropic.com"
    auth = { kind = "client" }
    extra_headers = { anthropic-version = "2023-06-01" }
    [[providers.anthropic.models]]
    id = "claude-fable-5"
    context_window = 200000

    [heads.claude-splice]
    provider = "anthropic"
    port = $headPort
    discovery_prefix = "claude-splice--"
    pinned_model = "claude-fable-5"
""".trimIndent()

@EnabledIfEnvironmentVariable(named = "SPLICE_LIVE_PROBE", matches = "1")
class ClaudeHeadLiveProbeTest {

    @Test
    fun `the claude head reaches the real anthropic endpoint and adds no credential of its own`() =
        runBlocking {
            val controlPort = freshPort()
            val headPort = freshPort()
            val tmp = Files.createTempDirectory("live-probe")
            val daemon = Daemon(
                topology = TopologyLoader.parse(topologyToml(controlPort, headPort)),
                statePaths = StatePaths(baseOverride = tmp.resolve("state")),
                dashboardHtml = { "<!doctype html>" },
                log = {},
                refreshCall = { _, _ -> RefreshAttempt.Denied("live-probe") },
            )
            // start/awaitListening under the daemon's own finally: a bind or readiness failure
            // must still stop the daemon, not leave the test worker holding live resources.
            try {
                daemon.start()
                awaitListening(controlPort, headPort)
                val client = HttpClient(CIO)
                try {
                    val response = probe(client, headPort)
                    // A streaming turn commits 200 + SSE headers BEFORE the upstream resolves
                    // (TurnDriver.stream), so the upstream's 401 arrives as an `event: error`
                    // frame INSIDE the 200. Pinning the status proves the SSE turn path ran —
                    // a non-200 here would be a head-local rejection, not Anthropic's verdict.
                    assertEquals(HttpStatusCode.OK, response.status)
                    assertRejectedAtAuth(response.bodyAsText())
                } finally {
                    client.close()
                }
            } finally {
                daemon.stop()
            }
        }

    private suspend fun probe(client: HttpClient, headPort: Int): HttpResponse =
        client.post("http://127.0.0.1:$headPort/v1/messages") {
            // deliberately invalid: rejected at auth, before any inference, so no quota is spent
            header("Authorization", "Bearer invalid-probe-credential")
            header("Content-Type", "application/json")
            setBody(
                """{"model":"claude-splice--claude-fable-5","max_tokens":16,""" +
                    """"messages":[{"role":"user","content":"probe"}],"stream":true}""",
            )
        }

    private fun assertRejectedAtAuth(body: String) {
        println("LIVE PROBE RESPONSE: ${body.take(400)}")
        // The rejection must be the honest SSE error frame, and it must be ANTHROPIC's rejection —
        // the head's own auth wall answers with this exact body text, and a client-auth head must
        // never take that branch.
        assertTrue(body.contains("event: error"), "expected an SSE error frame, got: $body")
        assertFalse(
            body.contains("invalid local gateway credentials"),
            "rejected by the head's own auth wall, never forwarded to Anthropic: $body",
        )
        // Anthropic authenticated the request and rejected the credential — which means the host,
        // path, version header and body SHAPE all satisfied it.
        assertTrue(
            body.contains("authentication_error") || body.contains("invalid x-api-key"),
            "expected an upstream authentication rejection, got: $body",
        )
        // A shape complaint would mean the dialect builds something Anthropic will not take.
        assertFalse(
            body.contains("invalid_request_error"),
            "Anthropic rejected the request SHAPE, not just the credential: $body",
        )
    }
}
