package splice.provider.codex

import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import splice.core.index.WireBlockIndex
import splice.core.reasoning.ReasoningReplay
import splice.core.turn.RoundHandoffs
import splice.core.turn.RoundText
import splice.core.turn.TurnOutcome
import splice.core.turn.Usage
import splice.core.turn.noRequestUsage
import splice.dialect.responses.request.AssistantPhase
import splice.dialect.responses.request.ResponsesAssistantText
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

        override suspend fun addTextBlock(text: String) {
            this.text.append(text)
        }

        override suspend fun openTool(id: String, name: String): WireBlockIndex {
            callback.complete(SeenTool(id, name))
            return WireBlockIndex(0)
        }

        override suspend fun inputJsonDelta(index: WireBlockIndex, partialJson: String) = Unit
    }

    protected inner class SealedPost(
        status: String? = null,
        val suffix: String = "",
        private val ending: String = "success",
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
