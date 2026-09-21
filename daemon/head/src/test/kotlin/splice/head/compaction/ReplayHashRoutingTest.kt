// NEW: V4-166 — a compaction's replay key is the request, not the slot it was routed to. The chat
// provider's body carries llama-server's id_slot (V4-165), which a retry can be handed differently;
// the key kept it, so a byte-identical compaction retry missed its recording and ran again upstream.
package splice.head.compaction

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.auth.AuthDescription
import splice.core.auth.Credentials
import splice.core.auth.RefreshableAuthProvider
import splice.core.model.ModelCatalog
import splice.core.model.ModelEntry
import splice.core.parse.AnthropicTurnBody
import splice.core.perf.TurnPerf
import splice.core.turn.WatchdogBudget
import splice.dialect.passthrough.PassthroughProvider
import splice.dialect.passthrough.PassthroughQuirks
import splice.head.AnthropicBodyParse
import splice.head.headDeps
import splice.head.turn.ProviderTurnBuild
import splice.upstream.BuiltTurn
import splice.upstream.Provider
import splice.upstream.ProviderTuning
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds

private class RoutingTestAuth : RefreshableAuthProvider {
    override suspend fun credentials(): Credentials = Credentials.Bearer("token", "account")
    override suspend fun refresh(): Credentials = credentials()
    override suspend fun describe(): AuthDescription = AuthDescription(true, "test")
}

class ReplayHashRoutingTest {

    /** Each turn is routed to the next slot, as a table whose owner was evicted between two tries. */
    private class Routed(private val base: Provider, private val declared: Boolean) : Provider by base {
        private var next = 0

        override fun buildTurn(body: AnthropicTurnBody, compact: Boolean, sessionId: String?): BuiltTurn {
            val turn = base.buildTurn(body, compact, sessionId)
            return turn.copy(
                requestBody = JsonObject(turn.requestBody + ("id_slot" to JsonPrimitive(next++))),
                routingFields = if (declared) setOf("id_slot") else emptySet(),
            )
        }
    }

    // Mutant: hash base.requestBody whole (V4-165). The retry lands on another slot, its key differs,
    // and the compaction the first client already paid for runs a second time.
    @Test
    fun `a retry routed to another slot keeps its compaction's key`(@TempDir tmp: Path) {
        val hashes = hashesOfTwoTries(tmp, Routed(base(), declared = true))
        assertEquals(hashes[0], hashes[1])
    }

    // The control: the key still covers everything that is not declared routing, so the field this
    // test varies does change a key the moment nobody declares it.
    @Test
    fun `an undeclared field is still part of the key`(@TempDir tmp: Path) {
        val hashes = hashesOfTwoTries(tmp, Routed(base(), declared = false))
        assertNotEquals(hashes[0], hashes[1])
    }

    private fun hashesOfTwoTries(tmp: Path, provider: Provider): List<String?> {
        val build = ProviderTurnBuild(provider, headDeps(tmp), CompactionReplay())
        val parsed = AnthropicBodyParse().parse(REQUEST).getOrThrow()
        return List(2) { build.build(parsed, compact = true, sessionId = "session-v4166", perf = TurnPerf()) }
            .map { it.meta.compactionRequestHash }
    }

    private fun base() = PassthroughProvider(
        ProviderTuning(
            key = "kimi",
            label = "kimix",
            catalog = ModelCatalog(
                discoveryPrefix = "claude-kimi--",
                models = listOf(ModelEntry(MODEL, "Kimi", contextWindow = 200_000)),
                defaultContextWindow = 200_000,
            ),
            pinnedModel = MODEL,
            auth = RoutingTestAuth(),
            baseUrl = "http://127.0.0.1",
            watchdog = WatchdogBudget(5.seconds, 3.seconds, 30.seconds),
        ),
        PassthroughQuirks(providerTag = "test-passthrough"),
    )
}

private const val MODEL = "kimi-k2"

private const val REQUEST =
    """{"model":"claude-kimi--$MODEL","stream":true,"max_tokens":16,""" +
        """"messages":[{"role":"user","content":"summarize the conversation"}]}"""
