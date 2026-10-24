package splice.provider.codex

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import splice.core.index.WireBlockIndex
import splice.core.reasoning.ReasoningReplay
import splice.core.turn.RoundHandoffs
import splice.core.turn.RoundText
import splice.core.turn.TurnOutcome
import splice.core.turn.Usage
import splice.core.turn.noRequestUsage
import splice.dialect.responses.request.AssistantPhase
import splice.dialect.responses.request.ResponsesAssistantText
import splice.dialect.responses.responsesTestTranslator
import splice.upstream.RedirectableRoundPost
import splice.upstream.RoundResult
import splice.upstream.sse.CustomToolSource
import splice.upstream.sse.WireSink
import java.io.IOException

/** The item, delivered prose, runtime attachment and response ending have independent gates. */
abstract class CodeModeSealedItemSupport : CodeModeItemCompletionSupport() {
    protected class SourceSink : WireSink by RecordingSink() {
        val text = StringBuilder()
        val callback = CompletableDeferred<SeenTool>()
        var opaqueBlocks = 0
            private set

        override suspend fun addTextBlock(text: String) {
            this.text.append(text)
        }

        override suspend fun textDelta(index: WireBlockIndex, text: String) {
            this.text.append(text)
        }

        override suspend fun openTool(id: String, name: String): WireBlockIndex {
            callback.complete(SeenTool(id, name))
            return WireBlockIndex(0)
        }

        override suspend fun inputJsonDelta(index: WireBlockIndex, partialJson: String) = Unit

        override suspend fun addRedactedThinking(data: String) {
            opaqueBlocks++
        }
    }

    protected inner class SealedPost(
        status: String? = null,
        val suffix: String = "",
        private val ending: String = "success",
        val followingReasoning: Boolean = false,
        private val terminalReasoningOnly: Boolean = false,
        private val terminalExecAnchor: Boolean = true,
    ) : RedirectableRoundPost {
        val itemRelease = CompletableDeferred<Unit>()
        val itemHandled = CompletableDeferred<Unit>()
        val tailSent = CompletableDeferred<Unit>()
        val terminalRelease = CompletableDeferred<Unit>()
        val stopped = CompletableDeferred<Unit>()
        val continuation = CompletableDeferred<String>()
        private var first = true
        val completed = outer(source = script).let { call ->
            if (status == null) call else call.copy(raw = JsonObject(call.raw + ("status" to JsonPrimitive(status))))
        }
        val reason = Json.parseToJsonElement(
            """{"type":"reasoning","id":"sealed-reason","encrypted_content":"synthetic-sealed"}""",
        ).jsonObject
        val followingReason = Json.parseToJsonElement(
            """{"type":"reasoning","id":"after-exec-reason","status":"completed",
                "encrypted_content":"synthetic-after","summary":[],"content":[]}""",
        ).jsonObject
        val followingMessage = message("after-exec-message", suffix)
        private val nativePreface = message("native-preface", "A")
        private val preface = JsonObject(
            ResponsesAssistantText.item("A", AssistantPhase.COMMENTARY) +
                mapOf("type" to JsonPrimitive("message"), "id" to JsonPrimitive("preface-item")),
        )
        private val snapshot = TurnOutcome.Success(
            false,
            false,
            noRequestUsage,
            text = RoundText(bodyText = "A", emittedText = true),
            handoffs = RoundHandoffs(
                customCalls = listOf(completed),
                reasoningEnvelopes = listOf(checkNotNull(ReasoningReplay.encodeReasoningEnvelope(reason))),
                nativePrefix = listOf(reason, preface),
            ),
        )

        override suspend fun invoke(bodyJson: String): RoundResult = error("redirect required")

        override suspend fun into(bodyJson: String, sink: WireSink): RoundResult {
            if (!first) {
                continuation.complete(bodyJson)
                sink.addTextBlock("continued from saved output")
                return RoundResult.Outcome(completedOutcome())
            }
            first = false
            try {
                if (followingReasoning) return nativeSource(sink)
                sink.addTextBlock("A")
                sink.customToolSource(CustomToolSource.Started(outer(source = "")))
                sink.customToolSource(CustomToolSource.Delta(completed.callId, "await tools.Read({});"))
                itemRelease.await()
                sink.customToolSource(CustomToolSource.Completed(completed, snapshot))
                itemHandled.complete(Unit)
                if (suffix.isNotEmpty()) sink.addTextBlock(suffix)
                tailSent.complete(Unit)
                terminalRelease.await()
                if (ending == "io") throw IOException("synthetic late source tear")
                sink.customToolSource(CustomToolSource.Terminal)
                return RoundResult.Outcome(terminalOutcome(ending == "incomplete"))
            } finally {
                itemHandled.complete(Unit)
                stopped.complete(Unit)
            }
        }

        fun terminalOutcome(incomplete: Boolean = false): TurnOutcome.Success = snapshot.copy(
            incomplete = incomplete,
            text = snapshot.text.copy(bodyText = "A" + suffix),
            usage = Usage(inputTokens = 19, outputTokens = 3),
        )

        /** This variant drives the real Responses reducer; neither raw post-call item is a client carrier. */
        private suspend fun nativeSource(sink: WireSink): RoundResult {
            val added =
                JsonObject(
                    completed.raw + mapOf("input" to JsonPrimitive(""), "status" to JsonPrimitive("in_progress")),
                )
            val frames = flow {
                emit(event("response.output_item.done", 0, reason))
                prose(1, nativePreface, "A").forEach { emit(it) }
                emit(event("response.output_item.added", 2, added))
                itemRelease.await()
                emit(event("response.output_item.done", 2, completed.raw))
                itemHandled.complete(Unit)
                if (!terminalReasoningOnly) emit(event("response.output_item.done", 3, followingReason))
                if (suffix.isNotEmpty()) prose(4, followingMessage, suffix).forEach { emit(it) }
                tailSent.complete(Unit)
                terminalRelease.await()
                emit(
                    buildJsonObject {
                        put("type", "response.completed")
                        put(
                            "response",
                            buildJsonObject {
                                put("status", "completed")
                                put(
                                    "output",
                                    JsonArray(
                                        terminalItems(),
                                    ),
                                )
                            },
                        )
                    },
                )
            }
            val reader =
                responsesTestTranslator(
                    collectReasoningEnvelopes = true,
                    encode = ReasoningReplay::encodeReasoningEnvelope,
                )
            return RoundResult.Outcome(reader.driveTurn(frames, sink))
        }

        private fun terminalItems(): List<JsonObject> = if (terminalExecAnchor) {
            listOf(reason, nativePreface, completed.raw, followingReason) +
                listOfNotNull(followingMessage.takeIf { suffix.isNotEmpty() })
        } else {
            listOf(followingReason)
        }

        private fun message(id: String, text: String): JsonObject = buildJsonObject {
            put("type", "message")
            put("id", id)
            put("role", "assistant")
            put("phase", "commentary")
            put("content", JsonArray(listOf(buildJsonObject { put("type", "output_text"); put("text", text) })))
        }

        private fun event(type: String, index: Int, item: JsonObject): JsonObject = buildJsonObject {
            put("type", type)
            put("output_index", index)
            put("item", item)
        }

        private fun prose(index: Int, item: JsonObject, text: String): List<JsonObject> = listOf(
            event("response.output_item.added", index, item),
            buildJsonObject {
                put("type", "response.output_text.delta")
                put("output_index", index)
                put("item_id", item.getValue("id"))
                put("delta", text)
            },
            event("response.output_item.done", index, item),
        )
    }

    protected data class SourceRun(val calls: List<SeenTool>, val outcome: TurnOutcome)

    protected suspend fun finishSource(
        manager: CodexCodeModeBridge,
        post: SealedPost,
        read: SeenTool,
    ): SourceRun {
        val sink = SourceSink()
        manager.interceptor(turn(read.id, "result-0"), disableParallel = false)
            .intercept(transcript(listOf(read), "A" + post.suffix), sink, post)
        val edit = sink.callback.await()
        val calls = listOf(read, edit)
        val results = calls.mapIndexed { index, call ->
            splice.upstream.codemode.CodeModeResult(
                call.id,
                "result-$index",
            )
        }
        val outcome = manager.interceptor(turn(results = results), disableParallel = false)
            .intercept(transcript(calls, "A" + post.suffix), SourceSink(), post).turn()
        return SourceRun(calls, outcome)
    }

    protected fun transcript(calls: List<SeenTool>, prose: String): String {
        val root = Json.parseToJsonElement(history(calls)).jsonObject
        val input = root.getValue("input").jsonArray
        val baseline = Json.parseToJsonElement(BASE_REQUEST).jsonObject.getValue("input").jsonArray.size
        val echo = ResponsesAssistantText.item(prose, AssistantPhase.COMMENTARY)
        return JsonObject(root + ("input" to JsonArray(input.take(baseline) + echo + input.drop(baseline)))).toString()
    }
}
