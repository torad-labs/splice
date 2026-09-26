// NEW (2026-09-23): a head built on port 0 binds an OS-assigned port and REPORTS it — HeadServer.port,
// GET /health and the control plane's health snapshot all name the port the connector bound. It is
// how every HeadServer test gets a port with no window between choosing it and binding it: the old
// idiom leased one with ServerSocket(0), released it and handed the number to the head to bind later,
// and anything that took it in between failed the bind (CI run 35881955038, HeadServerLoadTest's
// @BeforeAll). A split-out file because HeadServerIntegrationTest sits at detekt's LargeClass ceiling.
package splice.head

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.auth.AuthDescription
import splice.core.auth.Credentials
import splice.core.auth.RefreshableAuthProvider
import splice.core.model.ModelCatalog
import splice.core.model.ModelEntry
import splice.core.turn.ReasoningDisplay
import splice.core.turn.WatchdogBudget
import splice.upstream.ProviderTuning
import java.net.Socket
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds

private class BoundPortAuth : RefreshableAuthProvider {
    override suspend fun credentials(): Credentials = Credentials.Bearer("tok-bound", "acct-bound")
    override suspend fun refresh(): Credentials = credentials()
    override suspend fun describe(): AuthDescription = AuthDescription(true, "fake")
}

class HeadServerBoundPortTest {

    /** No turn runs here, so the upstream is never dialled: [UNUSED_UPSTREAM] only fills the field. */
    private fun head(tmp: Path) = HeadServer(
        provider = TestResponsesProvider(
            tuning = ProviderTuning(
                key = "codex",
                label = "claudex",
                catalog = ModelCatalog(
                    discoveryPrefix = "claude-codex--",
                    models = listOf(ModelEntry("gpt-5.6-sol", "Sol", contextWindow = 272_000)),
                    defaultContextWindow = 272_000,
                ),
                pinnedModel = "gpt-5.6-sol",
                auth = BoundPortAuth(),
                baseUrl = UNUSED_UPSTREAM,
                watchdog = WatchdogBudget(10.seconds, 10.seconds, 30.seconds),
            ),
            showReasoning = ReasoningDisplay.TEXT,
            replayReasoning = false,
            configEffort = "high",
            configSummary = "detailed",
        ),
        listenPort = 0,
        deps = headDeps(tmp = tmp),
    )

    /** Red if the head reports the configured 0 instead of what its connector bound, if /health or the
     *  snapshot does, or if a stopped head keeps advertising a port it no longer holds. */
    @Test
    fun `a head started on port 0 reports the port it bound, and health and the snapshot agree`(
        @TempDir tmp: Path,
    ) = runBlocking {
        val head = head(tmp)
        val client = HttpClient(CIO)
        head.start()
        try {
            val bound = head.port
            assertTrue(bound > 0, "a head built on port 0 must report the OS-assigned port, got $bound")
            Socket("127.0.0.1", bound).use { assertTrue(it.isConnected, "nothing accepts on :$bound") }
            val body = client.get("http://127.0.0.1:$bound/health").bodyAsText()
            assertTrue(body.contains("\"port\":$bound"), "/health must report the bound port: $body")
            assertEquals(bound, head.healthSnapshot().port, "the control plane's snapshot must report it too")
        } finally {
            head.stop()
            client.close()
        }
        assertEquals(0, head.port, "a stopped head reports the port it was configured with")
    }
}

private const val UNUSED_UPSTREAM = "http://127.0.0.1:9"
