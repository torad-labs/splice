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
import splice.core.turn.GatewayCustomCall
import splice.core.turn.RoundHandoffs
import splice.core.turn.TurnOutcome
import splice.core.turn.noRequestUsage
import splice.core.util.JsonScalars
import splice.dialect.responses.responsesTestTranslator
import splice.upstream.RedirectableRoundPost
import splice.upstream.RoundResult
import splice.upstream.codemode.CodeModeCall
import splice.upstream.codemode.CodeModeCell
import splice.upstream.codemode.CodeModeResult
import splice.upstream.codemode.CodeModeStep
import splice.upstream.codemode.NativeCodeModeCell
import splice.upstream.codemode.NativeCodeModeLifecycle
import splice.upstream.codemode.NativeCodeModeOptions
import splice.upstream.sse.CustomToolSource
import splice.upstream.sse.WireSink

abstract class CodeModeResponseBatchSupport : CodeModeBridgeTestSupport() {
    protected fun firstCall(): GatewayCustomCall = outer("batch-first", "first-source")
    protected fun secondCall(): GatewayCustomCall = outer("batch-second", "second-source")
    protected fun batch(calls: List<GatewayCustomCall>): TurnOutcome.Success = TurnOutcome.Success(
        false,
        false,
        noRequestUsage,
        handoffs = RoundHandoffs(customCalls = calls, nativeOutput = calls.map { it.raw }),
    )

    protected inner class BatchPost(
        private val host: Host,
        private val terminalGate: CompletableDeferred<Unit>? = null,
        private val lateItem: CompletableDeferred<Unit>? = null,
        private val buffered: Boolean = false,
        private val ending: TurnOutcome? = null,
    ) : RedirectableRoundPost {
        val secondSealed = CompletableDeferred<Unit>()
        val finalInput = CompletableDeferred<JsonArray>()
        private var initial = true
        override suspend fun invoke(bodyJson: String): RoundResult = error("redirect required")
        override suspend fun into(bodyJson: String, sink: WireSink): RoundResult {
            if (!initial) {
                finalInput.complete(Json.parseToJsonElement(bodyJson).jsonObject.getValue("input").jsonArray)
                return RoundResult.Outcome(completedOutcome())
            }
            initial = false
            val first = firstCall()
            val second = secondCall()
            if (lateItem != null) sink.customToolSource(CustomToolSource.Started(second.copy(input = "")))
            sink.customToolSource(CustomToolSource.Started(first.copy(input = "")))
            sink.customToolSource(CustomToolSource.Completed(first, batch(listOf(first))))
            if (!buffered) host.observed.await()
            lateItem?.await()
            if (lateItem == null) sink.customToolSource(CustomToolSource.Started(second.copy(input = "")))
            sink.customToolSource(CustomToolSource.Completed(second, batch(listOf(first, second))))
            secondSealed.complete(Unit)
            terminalGate?.await()
            sink.customToolSource(CustomToolSource.Terminal)
            return RoundResult.Outcome(ending ?: batch(listOf(first, second)))
        }
    }

    /** Real parser snapshots distinguish delivery order from the opposite native output indexes. */
    protected inner class ReasonedBatchPost : RedirectableRoundPost {
        val secondRelease = CompletableDeferred<Unit>()
        val secondSealed = CompletableDeferred<Unit>()
        val terminalRelease = CompletableDeferred<Unit>()
        val finalInput = CompletableDeferred<JsonArray>()
        val readable = "Inspect the callback, then preserve both accepted results."
        val reasoning = buildJsonObject {
            put("type", "reasoning"); put("id", "batch-reasoning"); put("encrypted_content", "synthetic-batch-cipher")
            put("summary", JsonArray(listOf(buildJsonObject { put("type", "summary_text"); put("text", readable) })))
        }
        private val calls = listOf(firstCall(), secondCall()).mapIndexed { index, call ->
            JsonObject(call.raw + ("id" to JsonPrimitive("batch-item-$index")))
        }
        private var initial = true

        override suspend fun invoke(bodyJson: String): RoundResult = error("redirect required")

        override suspend fun into(bodyJson: String, sink: WireSink): RoundResult {
            if (!initial) {
                finalInput.complete(Json.parseToJsonElement(bodyJson).jsonObject.getValue("input").jsonArray)
                return RoundResult.Outcome(completedOutcome())
            }
            initial = false
            val frames = flow {
                emit(frame("response.output_item.added", 2, reasoning))
                emit(frame("response.output_item.done", 2, reasoning))
                emit(frame("response.output_item.added", 0, JsonObject(calls[1] + ("input" to JsonPrimitive("")))))
                emit(frame("response.output_item.added", 1, JsonObject(calls[0] + ("input" to JsonPrimitive("")))))
                emit(frame("response.output_item.done", 1, calls[0]))
                secondRelease.await()
                emit(frame("response.output_item.done", 0, calls[1]))
                secondSealed.complete(Unit)
                terminalRelease.await()
                emit(
                    buildJsonObject {
                        put("type", "response.completed")
                        put(
                            "response",
                            buildJsonObject {
                                put("status", "completed")
                                put("output", JsonArray(listOf(calls[1], calls[0], reasoning)))
                            },
                        )
                    },
                )
            }
            return RoundResult.Outcome(responsesTestTranslator(codeMode = true).driveTurn(frames, sink))
        }

        fun echoed(body: String): String {
            val root = Json.parseToJsonElement(body).jsonObject
            val input = root.getValue("input").jsonArray
            return JsonObject(root + ("input" to JsonArray(input.take(1) + reasoning + input.drop(1)))).toString()
        }

        private fun frame(type: String, index: Int, item: JsonObject): JsonObject = buildJsonObject {
            put("type", type); put("output_index", index); put("item", item)
        }
    }

    protected class Host(
        private val callback: Boolean,
        private val restored: Boolean = false,
        private val secondCallback: Boolean = false,
    ) : NativeCodeModeLifecycle {
        val observed = CompletableDeferred<Unit>()
        val secondOutput = CompletableDeferred<String>()
        override suspend fun start(
            source: String,
            tools: Set<String>,
            descriptions: Map<String, String>,
        ): CodeModeCell =
            error("native lifecycle required")
        override suspend fun startNativeSession(
            sessionKey: String,
            source: String,
            tools: Set<String>,
            descriptions: Map<String, String>,
            options: NativeCodeModeOptions,
        ): NativeCodeModeCell = when (source) {
            "first-source" -> {
                check(!restored) { "accepted first source was rerun" }
                Cell(
                    "first-cell",
                    if (callback) callbackSteps() else ArrayDeque(listOf(CodeModeStep.Completed("first-output"))),
                    observed,
                )
            }
            "second-source" -> Cell(
                "second-cell",
                if (secondCallback) secondSteps() else ArrayDeque(listOf(CodeModeStep.Completed("second-output"))),
                observed,
                secondOutput,
            )
            else -> error("unknown source")
        }
        private fun callbackSteps(): ArrayDeque<CodeModeStep> = ArrayDeque(
            listOf(
                CodeModeStep.Calls(listOf(CodeModeCall("read", "Read", JsonObject(emptyMap())))),
                CodeModeStep.Completed("first-output"),
            ),
        )
        private fun secondSteps(): ArrayDeque<CodeModeStep> = ArrayDeque(
            listOf(
                CodeModeStep.Calls(listOf(CodeModeCall("edit", "Edit", JsonObject(emptyMap())))),
                CodeModeStep.Completed("second-output"),
            ),
        )
        override fun close() = Unit
    }

    private class Cell(
        override val cellId: String,
        private val steps: ArrayDeque<CodeModeStep>,
        private val observed: CompletableDeferred<Unit>,
        private val produced: CompletableDeferred<String>? = null,
    ) : NativeCodeModeCell {
        private var accepted: String? = null
        override suspend fun submitResults(results: List<CodeModeResult>) {
            results.lastOrNull()?.let { accepted = it.output }
        }
        override suspend fun advance(results: List<CodeModeResult>): CodeModeStep = observe(0)
        override suspend fun observe(yieldTimeMs: Long): CodeModeStep {
            observed.complete(Unit)
            val step = steps.removeFirst()
            val result = if (step is CodeModeStep.Completed && accepted != null) {
                step.copy(output = step.output + ":" + accepted)
            } else {
                step
            }
            if (result is CodeModeStep.Completed) produced?.complete(result.output)
            return result
        }
        override suspend fun terminate(): CodeModeStep = CodeModeStep.Completed("", terminated = true)
        override fun close() = Unit
    }

    protected fun followup(
        id: String,
        name: String = "Read",
        output: String = "read-result",
    ): String = buildJsonObject {
        put(
            "input",
            JsonArray(
                listOf(
                    buildJsonObject { put("role", "developer"); put("content", "s") },
                    buildJsonObject {
                        put(
                            "type",
                            "function_call",
                        ); put("call_id", id); put("name", name); put("arguments", "{}")
                    },
                    buildJsonObject {
                        put(
                            "type",
                            "function_call_output",
                        ); put("call_id", id); put("output", output)
                    },
                ),
            ),
        )
    }.toString()

    protected fun bothResults(first: String, second: String): String {
        val baseline = Json.parseToJsonElement(followup(first)).jsonObject.getValue("input").jsonArray
        val extra = listOf(
            buildJsonObject {
                put(
                    "type",
                    "function_call",
                ); put("call_id", second); put("name", "Edit"); put("arguments", "{}")
            },
            buildJsonObject {
                put(
                    "type",
                    "function_call_output",
                ); put("call_id", second); put("output", "edit-result")
            },
        )
        return buildJsonObject { put("input", JsonArray(baseline + extra)) }.toString()
    }

    protected fun canonical(input: JsonArray): List<JsonObject> = input.filterIsInstance<JsonObject>().filter {
        JsonScalars.str(it, "type") in setOf("custom_tool_call", "custom_tool_call_output")
    }
}
