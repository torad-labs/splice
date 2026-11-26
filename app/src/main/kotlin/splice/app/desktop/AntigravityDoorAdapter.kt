// NEW: desktop-antigravity (door chat). Adapts [AntigravityDoorEngine] streaming events onto
// [DirectAnthropicTurn]'s sole L3-compliant wire sink for Claude Desktop Chat turns.
package splice.app.desktop

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.client.SESSION_HEADER
import splice.core.failure.FailureCause
import splice.core.failure.HeadVoice
import splice.core.failure.ProviderWordsFilter
import splice.core.failure.ServerFault
import splice.core.index.WireBlockIndex
import splice.core.model.ModelCatalog
import splice.core.turn.Usage
import splice.core.turn.UsageField
import splice.core.util.JsonScalars
import splice.core.util.JsonWire
import splice.core.wire.HttpStatus
import splice.head.GatewayTurn
import splice.head.wire.direct.DirectAnthropicTurn
import splice.head.wire.direct.DirectTurnOutcome
import splice.http.JsonReply
import splice.provider.antigravity.ANTIGRAVITY_KEY
import splice.provider.antigravity.door.AntigravityDoorEngine
import splice.provider.antigravity.door.DoorAgySink
import splice.provider.antigravity.door.DoorAgyTurnResult
import splice.share.RefusalVoice
import splice.upstream.sse.WireSink

internal class AntigravityDoorAdapter(
    catalog: ModelCatalog,
    private val engine: AntigravityDoorEngine,
) {
    private val voice = RefusalVoice("splice: ")
    private val directTurn = DirectAnthropicTurn(catalog, HeadVoice(ANTIGRAVITY_KEY, loginCommand = "agy acp --login"))
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun serve(call: ApplicationCall, turn: GatewayTurn) {
        val body = JsonScalars.objectOrNull(json, turn.body.decodeToString())
            ?: return voice.refuse(call, HttpStatus.BAD_REQUEST, "the request body is not a JSON object")
        val upstreamModel = JsonScalars.str(body, "model").orEmpty()
        val stream = JsonScalars.str(body, "stream") == "true"
        val sessionHint = call.request.headers[SESSION_HEADER]
        directTurn.serve(call, upstreamModel, stream, turn) { wire ->
            val sink = DoorWireSink(wire)
            when (val res = engine.serve(body, upstreamModel, sink, sessionHint)) {
                is DoorAgyTurnResult.Completed -> DirectTurnOutcome(
                    hasToolUse = res.hasToolUse,
                    incomplete = res.incomplete,
                    usage = Usage(
                        inputTokens = res.inputTokens,
                        outputTokens = res.outputTokens,
                        reported = UsageField.entries.toSet(),
                    ),
                )
                is DoorAgyTurnResult.Failed -> DirectTurnOutcome(
                    error = FailureCause.Provider.ServerError(
                        ServerFault.BAD_GATEWAY,
                        ProviderWordsFilter.of(res.message),
                    ),
                )
            }
        }
    }

    suspend fun countTokens(call: ApplicationCall, turn: GatewayTurn) {
        val estimate = (turn.body.size / CHARS_PER_TOKEN).coerceAtLeast(1)
        val upstreamModel = JsonScalars.objectOrNull(json, turn.body.decodeToString())
            ?.let { JsonScalars.str(it, "model") }.orEmpty()
        val count = directTurn.countInput(call, upstreamModel, turn, estimate.toLong())
        val payload = buildJsonObject { put("input_tokens", count) }
        JsonReply(HttpStatusCode.OK, JsonWire.string(payload)).send(call)
    }

    private class DoorWireSink(private val wire: WireSink) : DoorAgySink {
        private var thinkingBlock: WireBlockIndex? = null
        private var textBlock: WireBlockIndex? = null

        override suspend fun onThinking(text: String) {
            closeText()
            val idx = thinkingBlock ?: wire.openThinking().also { thinkingBlock = it }
            wire.thinkingDelta(idx, text)
        }

        override suspend fun onText(text: String) {
            closeThinking()
            val idx = textBlock ?: wire.openText().also { textBlock = it }
            wire.textDelta(idx, text)
        }

        override suspend fun onToolUse(id: String, name: String, inputJson: String) {
            closeThinking()
            closeText()
            val idx = wire.openTool(id, name)
            if (inputJson.isNotEmpty()) wire.inputJsonDelta(idx, inputJson)
            wire.closeBlock(idx)
        }

        private suspend fun closeThinking() {
            thinkingBlock?.let { wire.closeBlock(it) }
            thinkingBlock = null
        }

        private suspend fun closeText() {
            textBlock?.let { wire.closeBlock(it) }
            textBlock = null
        }
    }
}

// why: standard rough UTF-8 byte-to-token heuristic for local count_tokens estimates when agy has no tokenizer RPC.
private const val CHARS_PER_TOKEN = 4
