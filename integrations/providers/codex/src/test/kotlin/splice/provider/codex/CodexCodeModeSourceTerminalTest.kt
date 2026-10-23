package splice.provider.codex

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import splice.core.turn.FailureCause
import splice.core.turn.TurnOutcome
import splice.core.util.JsonScalars
import splice.provider.codex.stream.CodeModeRejection
import splice.upstream.RedirectableRoundPost
import splice.upstream.RoundResult
import splice.upstream.sse.CustomToolSource
import splice.upstream.sse.WireSink

class CodexCodeModeSourceTerminalTest : CodeModeStatementStreamSupport() {
    @ParameterizedTest
    @ValueSource(strings = ["protocol", "persistence"])
    fun `local rejection outcomes never carry private throwable text`(kind: String) {
        val error = if (kind == "persistence") {
            CodeModePersistenceException(java.io.IOException("synthetic private checkpoint bytes"), diskFull = false)
        } else {
            IllegalStateException("synthetic private checkpoint bytes")
        }
        val outcome = CodeModeRejection.outcome(error)
        assertFalse(outcome.message.contains("synthetic private checkpoint bytes"))
        assertTrue(outcome.message.contains("source was not rerun"))
        assertEquals(FailureCause.CODE_MODE_PROTOCOL, outcome.cause)
    }

    @Test
    @Timeout(20)
    fun `item completion before response completion cannot finalize source or upstream billing`() = runBlocking {
        val runtime = IncrementalRuntime()
        val manager = bridge(runtime)
        val sink = StepSink()
        val post = GatedPost(sink)
        try {
            val request = async {
                manager.interceptor(turn(), disableParallel = false).intercept(BASE_REQUEST, sink, post)
            }
            post.gates.drop(1).forEach { it.complete(Unit) }
            withTimeout(30_000) { post.itemDone.await() }
            assertSourcePending()
            assertFalse(post.stopped.isCompleted)
            assertFalse(request.isCompleted, "the step waits for the response terminal, not the item")
            post.complete.complete(Unit)
            withTimeout(30_000) { post.stopped.await() }
            withTimeout(30_000) { request.await() }
        } finally {
            manager.onHeadStop()
        }
    }

    @Test
    @Timeout(20)
    fun `whole source with no early statement stays gated until the real response terminal`() = runBlocking {
        val runtime = IncrementalRuntime()
        val manager = bridge(runtime)
        val sink = StepSink()
        val post = GatedPost(sink)
        post.wholeOnly = true
        try {
            val request = async {
                manager.interceptor(turn(), disableParallel = false).intercept(BASE_REQUEST, sink, post)
            }
            withTimeout(30_000) { post.itemDone.await() }
            assertFalse(request.isCompleted)
            assertFalse(sink.callback.isCompleted)
            post.complete.complete(Unit)
            val outcome = withTimeout(30_000) { request.await() }.turn() as TurnOutcome.Success
            assertBilling(outcome.usage)
            assertEquals(1, runtime.starts)
            assertEquals(2, post.posts)
        } finally {
            manager.onHeadStop()
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["reader-wait", "blocked-write"])
    @Timeout(20)
    fun `client cancellation before ready attachment stops the independent reader and releases admission`(
        mode: String,
    ) =
        runBlocking {
            val runtime = ScriptedRuntime(ArrayDeque())
            val manager = bridge(runtime)
            val stopped = CompletableDeferred<Unit>()
            lateinit var client: Job
            val clientSink = object : WireSink by StepSink() {
                override suspend fun textDelta(index: splice.core.index.WireBlockIndex, text: String) {
                    client.cancel()
                    awaitCancellation()
                }
            }
            val post = object : RedirectableRoundPost {
                override suspend fun invoke(bodyJson: String): RoundResult = error("redirect required")
                override suspend fun into(bodyJson: String, sink: WireSink): RoundResult {
                    try {
                        sink.customToolSource(CustomToolSource.Started(outer(source = "")))
                        if (mode == "blocked-write") sink.textDelta(sink.openText(), "synthetic blocked write")
                        client.cancel()
                        awaitCancellation()
                    } finally {
                        stopped.complete(Unit)
                    }
                }
            }
            try {
                client = async(start = kotlinx.coroutines.CoroutineStart.LAZY) {
                    manager.interceptor(turn(), disableParallel = false).intercept(BASE_REQUEST, clientSink, post)
                }
                client.start()
                withTimeout(5_000) { client.join() }
                withTimeout(5_000) { stopped.await() }
                val registry = registryOf(manager)
                withTimeout(5_000) {
                    while (registry.startup.entries.isNotEmpty()) kotlinx.coroutines.yield()
                    val lostPhase = CodeModePhase.LOST.name
                    while (JsonScalars.str(stateFiles.records().singleOrNull()?.get("phase")) != lostPhase) {
                        kotlinx.coroutines.yield()
                    }
                }
                assertEquals(0, runtime.starts, "no source dispatched before client cancellation")
                val saved = stateFiles.records().single()
                assertEquals(CodeModePhase.LOST.name, JsonScalars.str(saved["phase"]))
                assertFalse(JsonScalars.str(saved["error"]).isNullOrBlank())
            } finally {
                manager.onHeadStop()
            }
        }

    private fun registryOf(manager: CodexCodeModeBridge): CodexCodeModeRegistry =
        manager.registry

    @ParameterizedTest
    @ValueSource(strings = ["blank-id", "oversized-start", "changed-id"])
    @Timeout(20)
    fun `local source rejection never escapes through the transport or strands an admission`(problem: String) =
        runBlocking {
            val runtime = ScriptedRuntime(ArrayDeque())
            val manager = bridge(runtime)
            var escaped = 0
            val post = object : RedirectableRoundPost {
                override suspend fun invoke(bodyJson: String): RoundResult = error("redirect required")

                override suspend fun into(bodyJson: String, sink: WireSink): RoundResult {
                    try {
                        val started = when (problem) {
                            "blank-id" -> outer(callId = "", source = "")
                            "oversized-start" -> outer(source = "x".repeat(65_537))
                            else -> outer(source = "")
                        }
                        sink.customToolSource(CustomToolSource.Started(started))
                        sink.customToolSource(CustomToolSource.Completed(outer(callId = "changed-id")))
                    } catch (_: IllegalStateException) {
                        escaped++
                    } catch (_: IllegalArgumentException) {
                        escaped++
                    }
                    return RoundResult.Outcome(outerOutcome())
                }
            }
            try {
                val outcome = manager.interceptor(turn(), disableParallel = false)
                    .intercept(BASE_REQUEST, RecordingSink(), post).turn()
                assertEquals(0, escaped, "splice-local source validation must not become transport failure")
                assertTrue(outcome is TurnOutcome.Failure, outcome.toString())
                assertEquals(FailureCause.CODE_MODE_PROTOCOL, (outcome as TurnOutcome.Failure).cause)
                assertFalse(outcome.message.contains("upstream source failed"))
                assertTrue(
                    registryOf(manager).startup.entries.isEmpty(),
                    "rejection before ready must release admission",
                )
            } finally {
                manager.onHeadStop()
            }
        }

    @Test
    @Timeout(20)
    fun `a streamed continuation cannot reissue an already completed outer call`() = runBlocking {
        val runtime = IncrementalRuntime()
        val manager = bridge(runtime)
        val sink = StepSink()
        val post = GatedPost(sink)
        post.wholeOnly = true
        post.repeatOuter = true
        post.complete.complete(Unit)
        try {
            val outcome = manager.interceptor(turn(), disableParallel = false)
                .intercept(BASE_REQUEST, sink, post).turn()
            assertTrue(outcome is TurnOutcome.Failure, outcome.toString())
            assertEquals(1, runtime.starts, "a duplicate outer cannot dispatch a second runtime")
            assertEquals(2, post.posts)
        } finally {
            manager.onHeadStop()
        }
    }
}
