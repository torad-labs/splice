package splice.provider.codex

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import splice.core.reasoning.ReasoningReplay
import splice.core.turn.FailureCause
import splice.core.turn.FailurePhase
import splice.core.turn.RoundHandoffs
import splice.core.turn.RoundText
import splice.core.turn.TurnOutcome
import splice.core.turn.Usage
import splice.core.turn.noRequestUsage
import splice.upstream.RedirectableRoundPost
import splice.upstream.RoundResult
import splice.upstream.codemode.CodeModeCell
import splice.upstream.codemode.CodeModeResult
import splice.upstream.codemode.CodeModeRuntime
import splice.upstream.codemode.CodeModeStep
import splice.upstream.sse.CustomToolSource
import splice.upstream.sse.WireSink
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.APPEND
import java.nio.file.StandardOpenOption.CREATE

/** Protocol gates exercise the provider's public stream and on-disk recovery boundaries. */
abstract class CodeModeItemCompletionSupport : CodeModeStatementStreamSupport() {
    protected val script = "await tools.Read({}); await tools.Edit({}); return 'done';"

    protected inner class EffectRuntime(
        private val effects: Path,
        private val attachGate: CompletableDeferred<Unit>? = null,
    ) : CodeModeRuntime {
        val evaluated = CompletableDeferred<Unit>()

        override suspend fun start(
            source: String,
            tools: Set<String>,
            descriptions: Map<String, String>,
        ): CodeModeCell {
            check(source == script) { "only the completed whole item is executable" }
            Files.writeString(effects, "executed\n", CREATE, APPEND)
            evaluated.complete(Unit)
            attachGate?.await()
            return object : CodeModeCell {
                private var step = 0
                override suspend fun advance(results: List<CodeModeResult>): CodeModeStep = when (step++) {
                    0 -> CodeModeStep.Calls(listOf(call("read", "Read")))
                    1 -> CodeModeStep.Calls(listOf(call("edit", "Edit", "read" to results.single().output)))
                    else -> CodeModeStep.Completed("saved-result:${results.single().output}")
                }

                override fun close() = Unit
            }
        }

        override fun close() = Unit
    }

    protected inner class ItemPost(private val ending: String = "success") : RedirectableRoundPost {
        val prefixSent = CompletableDeferred<Unit>()
        val itemRelease = CompletableDeferred<Unit>()
        val itemSealed = CompletableDeferred<Unit>()
        val terminalRelease = CompletableDeferred<Unit>()
        val stopped = CompletableDeferred<Unit>()
        val continuation = CompletableDeferred<String>()
        private var first = true
        private val completed = outer(source = script)
        private val reasoning = Json.parseToJsonElement(
            """{"type":"reasoning","id":"reason-before-item","encrypted_content":"synthetic-cipher"}""",
        ).jsonObject
        val snapshot = TurnOutcome.Success(
            false,
            false,
            noRequestUsage,
            text = RoundText(bodyText = "model preface", emittedText = true),
            handoffs = RoundHandoffs(
                customCalls = listOf(completed),
                reasoningEnvelopes = listOf(checkNotNull(ReasoningReplay.encodeReasoningEnvelope(reasoning))),
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
                sink.addTextBlock("model preface")
                sink.customToolSource(CustomToolSource.Started(outer(source = "")))
                sink.customToolSource(CustomToolSource.Delta(completed.callId, "await tools.Read({});"))
                prefixSent.complete(Unit)
                itemRelease.await()
                sink.customToolSource(CustomToolSource.Completed(completed, snapshot))
                itemSealed.complete(Unit)
                terminalRelease.await()
                when (ending) {
                    "io" -> throw IOException("synthetic late transport tear")
                    "death" -> throw ConcurrentModificationException("synthetic late reader death")
                    "cancelled" -> throw CancellationException("synthetic reader cancellation")
                }
                sink.customToolSource(CustomToolSource.Terminal)
                return RoundResult.Outcome(
                    when (ending) {
                        "failure" -> TurnOutcome.Failure(
                            "synthetic provider failure",
                            cause = FailureCause.UPSTREAM_CONN_RESET,
                            phase = FailurePhase.MID_OUTPUT,
                            salvagedUsage = Usage(inputTokens = 100, outputTokens = 7),
                        )
                        "identity" -> snapshot.copy(
                            handoffs = snapshot.handoffs.copy(customCalls = listOf(completed.copy(input = "changed"))),
                        )
                        else -> snapshot.copy(
                            incomplete = ending == "incomplete",
                            usage = Usage(inputTokens = 100, outputTokens = 7),
                        )
                    },
                )
            } finally {
                stopped.complete(Unit)
            }
        }
    }
}
