// 2026-11-09 audit: a failure only the request handler's door boundary catches is answered and logged, and since then
// it is a row in the head's perf file too, as unexpected, where before only perf-evidence held it.
package splice.head

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.auth.AuthDescription
import splice.core.auth.Credentials
import splice.core.auth.RefreshableAuthProvider
import splice.core.model.ModelCatalog
import splice.core.model.ModelEntry
import splice.core.perf.OutcomeTag
import splice.core.turn.WatchdogBudget
import splice.core.util.AsyncFileIo
import splice.dialect.anthropic.PassthroughProvider
import splice.dialect.anthropic.PassthroughQuirks
import splice.upstream.ProviderLocations
import splice.upstream.ProviderName
import splice.upstream.ProviderTuning
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private const val BODY =
    """{"model":"synthetic-model","stream":true,"max_tokens":1,"messages":[{"role":"user","content":"hi"}]}"""

class DoorFailureRowTest {
    @Test
    fun `a failure the door catches is answered, logged once and written to the perf file as unexpected`(
        @TempDir tmp: Path,
    ) = runBlocking {
        val lines = CopyOnWriteArrayList<String>()
        val deps = headDeps(tmp, log = { lines += it }).let { deps ->
            // Between the door and preparation's own boundary: the model roster's wait throws.
            deps.copy(traffic = deps.traffic.copy(models = HeadDeps.CurrentModels { error("roster wait broke") }))
        }
        val head = HeadServer(provider(), 0, deps)
        head.start()
        val status = try {
            HttpClient(CIO).use { client ->
                client.post("http://127.0.0.1:${head.port}/v1/messages") {
                    header("Content-Type", "application/json")
                    header("x-api-key", deps.tokens.inferenceToken)
                    setBody(BODY)
                }.also { it.bodyAsText() }.status.value
            }
        } finally {
            head.stop()
        }
        AsyncFileIo.drain()
        withTimeout(5.seconds) {
            while (deps.stores.perfStats.tailNumeric().isEmpty()) delay(10.milliseconds)
        }

        assertTrue(status >= SERVER_ERROR, "a defect is the head's own error, never a client's: $status")
        val rows = Files.readAllLines(tmp.resolve("perf.jsonl")).filter { it.isNotBlank() }
        assertEquals(1, rows.size, "$rows")
        val row = Json.parseToJsonElement(rows.single()).jsonObject
        assertEquals(OutcomeTag.UNEXPECTED.wire, row.getValue("outcome").jsonPrimitive.content)
        val failed = lines.filter { it.contains("request failed: IllegalStateException") }
        assertEquals(1, failed.size, "$lines")
        assertTrue(failed.single().contains("turn ERROR ${OutcomeTag.UNEXPECTED.wire}"), failed.single())
    }

    private fun provider() = PassthroughProvider(
        ProviderTuning(
            name = ProviderName(key = "anthropic", label = "synthetic"),
            catalog = ModelCatalog(
                discoveryPrefix = "synthetic--",
                models = listOf(ModelEntry("synthetic-model", "Synthetic", contextWindow = 200_000)),
                defaultContextWindow = 200_000,
            ),
            pinnedModel = "synthetic-model",
            auth = DoorAuth(),
            locations = ProviderLocations(baseUrl = "http://127.0.0.1:9"),
            watchdog = WatchdogBudget(5.seconds, 3.seconds, 30.seconds),
        ),
        PassthroughQuirks(providerTag = "synthetic"),
    )
}

private class DoorAuth : RefreshableAuthProvider {
    override suspend fun credentials(): Credentials = Credentials.ApiKey("synthetic-held-key", "x-api-key", "")
    override suspend fun refresh(): Credentials = credentials()
    override suspend fun describe(): AuthDescription = AuthDescription(true, "synthetic")
}

// why: the door answers a defect as the head's own retryable error, a 5xx.
private const val SERVER_ERROR = 500
