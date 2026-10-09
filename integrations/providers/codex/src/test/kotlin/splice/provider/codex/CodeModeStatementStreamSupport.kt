package splice.provider.codex

import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import splice.core.index.WireBlockIndex
import splice.core.model.ModelRates
import splice.core.model.TurnBill
import splice.core.reasoning.ReasoningReplay
import splice.core.turn.AbsorbedRounds
import splice.core.turn.GatewayCustomCall
import splice.core.turn.RoundHandoffs
import splice.core.turn.SpliceNotice
import splice.core.turn.TurnOutcome
import splice.core.turn.Usage
import splice.upstream.RedirectableRoundPost
import splice.upstream.RoundResult
import splice.upstream.codemode.CodeModeCell
import splice.upstream.codemode.CodeModeResult
import splice.upstream.codemode.CodeModeRuntime
import splice.upstream.codemode.CodeModeSource
import splice.upstream.codemode.CodeModeSourcePart
import splice.upstream.codemode.CodeModeStep
import splice.upstream.failure.CodeModeStartException
import splice.upstream.sse.CustomToolSource
import splice.upstream.sse.WireSink
import java.io.IOException

private val BILLING_RATES = ModelRates(input = 2.0, cacheRead = 0.2, output = 10.0, cacheWrite = 2.5)
private const val BILLING_DELTA = 1e-12

/** Gated protocol fixtures shared by the streaming lifetime and durability attacks. */
abstract class CodeModeStatementStreamSupport : CodeModeBridgeTestSupport() {
    protected fun assertFinalIdentity(post: GatedPost, outcome: TurnOutcome.Success) {
        assertTrue(stateFiles.records().single().toString().contains("response-stream"))
        assertTrue(post.continuation.contains("response-stream"))
        assertTrue(post.continuation.contains("custom_tool_call_output"))
        assertTrue(post.continuation.contains("reason-terminal"), "terminal reasoning must survive canonical replay")
        assertEquals(1, outcome.handoffs.reasoningEnvelopes.size)
    }

    protected fun assertSourcePending() {
        val pending = stateFiles.records().single()
        assertFalse(pending.toString().contains("response-stream"), "terminal identity must not finalize early")
        assertFalse(pending.toString().contains("reason-terminal"), "reasoning identity must not finalize early")
        val sourceState = pending.getValue("sourceState").jsonObject
        assertFalse(sourceState["complete"]?.jsonPrimitive?.content?.toBoolean() == true)
    }

    /** The turn that finishes the script bills the source round, which finished after the client turn
     *  that posted it, and the continuation, each once as a request of its own. */
    protected fun assertBilling(usage: Usage) {
        assertEquals(150L, usage.inputTokens)
        assertEquals(12L, usage.outputTokens)
        assertEquals(5L, usage.reasoningTokens)
        assertEquals(4L, usage.cacheWriteTokens)
        assertEquals(AbsorbedRounds(rounds = 1, inputTokens = 100, outputTokens = 7), usage.absorbed)
        val source = requireNotNull(
            TurnBill.usd(TurnBill.counters(Usage(inputTokens = 100, outputTokens = 7)), BILLING_RATES),
        )
        val continuation = requireNotNull(
            TurnBill.usd(
                TurnBill.counters(Usage(inputTokens = 150, outputTokens = 5, cacheWriteTokens = 4)),
                BILLING_RATES,
            ),
        )
        assertEquals(
            source + continuation,
            requireNotNull(TurnBill.usd(TurnBill.counters(usage), BILLING_RATES)),
            BILLING_DELTA,
        )
    }

    protected fun history(calls: List<SeenTool>): String {
        val input = Json.parseToJsonElement(BASE_REQUEST).jsonObject.getValue("input").jsonArray.toMutableList()
        calls.forEachIndexed { index, call ->
            input += JsonObject(
                mapOf(
                    "type" to JsonPrimitive("function_call"), "call_id" to JsonPrimitive(call.id),
                    "name" to JsonPrimitive(call.name), "arguments" to JsonPrimitive("{}"),
                ),
            )
            input += JsonObject(
                mapOf(
                    "type" to JsonPrimitive("function_call_output"), "call_id" to JsonPrimitive(call.id),
                    "output" to JsonPrimitive("result-$index"),
                ),
            )
        }
        return JsonObject(mapOf("input" to JsonArray(input))).toString()
    }

    protected inner class GatedPost(private val initialSink: StepSink) : RedirectableRoundPost {
        val gates = List(3) { CompletableDeferred<Unit>() }
        val sent = List(3) { CompletableDeferred<Unit>() }
        val complete = CompletableDeferred<Unit>()
        val stopped = CompletableDeferred<Unit>()
        val itemDone = CompletableDeferred<Unit>()
        var itemCompletionGate: CompletableDeferred<Unit>? = null
        var terminalProblem: String? = null
        var wholeOnly = false
        var repeatOuter = false
        var tearAfterFirst = false

        /** The reader dies on a throwable none of its catches names, as the record's save once did. */
        var dieAfterFirst = false
        var posts = 0
        var continuation = ""
        private val fragments = listOf("await tools.Read({});\n", "await tools.Edit({});\n", "await tools.Read({});\n")

        override suspend fun invoke(bodyJson: String): RoundResult = into(bodyJson, initialSink)

        override suspend fun into(bodyJson: String, sink: WireSink): RoundResult {
            posts++
            if (posts > 1) {
                continuation = bodyJson
                if (repeatOuter) return RoundResult.Outcome(generate(sink))
                return RoundResult.Outcome(
                    completedOutcome().copy(
                        usage = Usage(inputTokens = 150, outputTokens = 5, reasoningTokens = 2, cacheWriteTokens = 4),
                    ),
                )
            }
            return try {
                RoundResult.Outcome(generate(sink))
            } finally {
                stopped.complete(Unit)
            }
        }

        private suspend fun generate(sink: WireSink): TurnOutcome {
            sink.customToolSource(CustomToolSource.Started(startedCall()))
            val indices = if (wholeOnly) emptyList() else fragments.indices.toList()
            for (index in indices) {
                if (index > 0) breakAfterFirst(index)
                try {
                    source(sink, fragments[index])
                } finally {
                    sent[index].complete(Unit)
                }
            }
            val source = if (terminalProblem == "changed-prefix") {
                "return 'changed';"
            } else {
                fragments.joinToString("") + "return 'done';"
            }
            val raw = JsonObject(
                outer().raw + ("input" to JsonPrimitive(source)) + ("id" to JsonPrimitive("response-stream")),
            )
            val id = if (terminalProblem == "changed-id") "different-call" else "outer-call"
            val name = if (terminalProblem == "changed-name") "different-exec" else CODE_MODE_TOOL_NAME
            val completed = GatewayCustomCall(id, name, source, raw)
            itemCompletionGate?.await()
            sink.customToolSource(CustomToolSource.Completed(completed))
            itemDone.complete(Unit)
            complete.await()
            val reasoning = JsonObject(
                mapOf(
                    "type" to JsonPrimitive("reasoning"),
                    "id" to JsonPrimitive("reason-terminal"),
                    "encrypted_content" to JsonPrimitive("cipher-terminal"),
                ),
            )
            return TurnOutcome.Success(
                false,
                terminalProblem == "incomplete",
                Usage(inputTokens = 100, outputTokens = 7, reasoningTokens = 3),
                handoffs = RoundHandoffs(
                    reasoningEnvelopes = listOf(checkNotNull(ReasoningReplay.encodeReasoningEnvelope(reasoning))),
                    customCalls = listOf(completed),
                ),
            )
        }

        /** A later fragment waits for its gate, then the transport tears or the reader dies when told to. */
        private suspend fun breakAfterFirst(index: Int) {
            gates[index].await()
            if (tearAfterFirst) throw IOException("mock source transport torn")
            if (dieAfterFirst) throw ConcurrentModificationException("mock record snapshot raced")
        }

        private fun startedCall(): GatewayCustomCall {
            val started = outer(source = "")
            return if (terminalProblem == "changed-item") {
                started.copy(raw = JsonObject(started.raw + ("id" to JsonPrimitive("initial-item"))))
            } else {
                started
            }
        }

        private suspend fun source(sink: WireSink, text: String) {
            val index = sink.openThinking()
            sink.thinkingDelta(index, "Waiting for the next statement")
            sink.signatureDelta(index, SpliceNotice.SIGNATURE)
            sink.closeBlock(index)
            sink.customToolSource(CustomToolSource.Delta("outer-call", text))
        }
    }

    protected class StepSink : WireSink by RecordingSink() {
        val callback = CompletableDeferred<SeenTool>()
        val progress = StringBuilder()
        val signatures = mutableListOf<String>()
        var sealed = false
        override suspend fun openTool(id: String, name: String): WireBlockIndex {
            sealed = true
            callback.complete(SeenTool(id, name))
            return WireBlockIndex(1)
        }
        override suspend fun inputJsonDelta(index: WireBlockIndex, partialJson: String) = Unit
        override suspend fun thinkingDelta(index: WireBlockIndex, thinking: String) {
            check(!sealed) { "progress reached a finished client step" }
            progress.append(thinking)
        }
        override suspend fun signatureDelta(index: WireBlockIndex, signature: String) {
            check(!sealed) { "signature reached a finished client step" }
            signatures += signature
        }
    }

    protected inner class IncrementalRuntime(
        private val failFirst: Boolean = false,
        private val failCallSave: Boolean = false,
    ) : CodeModeRuntime {
        val delivered = mutableListOf<List<CodeModeResult>>()
        val firstReads = mutableListOf<CodeModeSourcePart>()
        val firstRead = CompletableDeferred<Unit>()
        var starts = 0
        override suspend fun start(
            source: String,
            tools: Set<String>,
            descriptions: Map<String, String>,
        ): CodeModeCell = error("live source must use startStreaming")

        override suspend fun startStreaming(
            source: CodeModeSource,
            tools: Set<String>,
            descriptions: Map<String, String>,
        ): CodeModeCell {
            starts++
            val first = source.read().also(firstReads::add)
            firstRead.complete(Unit)
            if (failFirst && starts == 1) throw CodeModeStartException(IOException("boot failed before dispatch"))
            return object : CodeModeCell {
                private var initial: CodeModeSourcePart? = first
                private var sequence = 0
                override suspend fun advance(results: List<CodeModeResult>): CodeModeStep {
                    delivered += results
                    return when (val part = initial.also { initial = null } ?: source.read()) {
                        is CodeModeSourcePart.Delta -> {
                            if (failCallSave && sequence == 0) stateFiles.block()
                            val name = if (part.text.contains("Read")) "Read" else "Edit"
                            CodeModeStep.Calls(listOf(call("runtime-${sequence++}", name)))
                        }
                        is CodeModeSourcePart.Complete -> CodeModeStep.Completed("done")
                        is CodeModeSourcePart.Failed -> throw IOException(part.error)
                    }
                }
                override fun close() = Unit
            }
        }
        override fun close() = Unit
    }
}
