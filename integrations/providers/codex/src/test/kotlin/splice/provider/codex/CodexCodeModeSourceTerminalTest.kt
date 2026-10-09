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
import splice.upstream.codemode.CodeModeCell
import splice.upstream.codemode.CodeModeRuntime
import splice.upstream.codemode.CodeModeSource
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

    @ParameterizedTest
    @ValueSource(strings = ["changed-prefix", "changed-id", "changed-name", "changed-item", "incomplete"])
    @Timeout(20)
    fun `terminal source corruption never releases a completed executable suffix`(problem: String) = runBlocking {
        val runtime = IncrementalRuntime()
        val manager = bridge(runtime)
        val sink = StepSink()
        val post = GatedPost(sink)
        post.terminalProblem = problem
        val terminalReached = CompletableDeferred<Unit>()
        val releaseTerminal = CompletableDeferred<Unit>()
        val sourcePost = object : RedirectableRoundPost by post {
            override suspend fun into(bodyJson: String, sink: WireSink): RoundResult {
                val outcome = post.into(bodyJson, sink)
                val ran = (outcome as? RoundResult.Outcome)?.outcome
                if (ran is TurnOutcome.Success && ran.incomplete) {
                    terminalReached.complete(Unit)
                    releaseTerminal.await()
                }
                return outcome
            }
        }
        try {
            manager.interceptor(turn(), disableParallel = false).intercept(BASE_REQUEST, sink, sourcePost)
            val first = sink.callback.await()
            post.gates.drop(1).forEach { it.complete(Unit) }
            withTimeout(1_500) { post.sent.last().await() }
            post.complete.complete(Unit)
            withTimeout(1_500) { post.stopped.await() }
            if (problem == "incomplete") withTimeout(1_500) { terminalReached.await() }
            releaseTerminal.complete(Unit)
            // The post has stopped before LiveRound receives its outcome. Only published rejection
            // proves the terminal invalid; admission is already LOST and is not that proof.
            withTimeout(5_000) {
                while (JsonScalars.str(stateFiles.records().single()["error"]) == null) kotlinx.coroutines.yield()
            }
            val next = StepSink()
            val outcome = manager.interceptor(turn(first.id, "result-0"), disableParallel = false)
                .intercept(history(listOf(first)), next, sourcePost).turn()
            assertFalse(next.callback.isCompleted, "invalid terminal source must not execute a later Edit")
            assertEquals(1, runtime.delivered.size, "terminal rejection must prevent another runtime advance")
            assertTrue(outcome is TurnOutcome.Success || outcome is TurnOutcome.Failure)
            assertEquals(1, runtime.starts)
            assertFalse(stateFiles.records().single()["sourceState"].toString().contains("\"complete\":true"))
        } finally {
            releaseTerminal.complete(Unit)
            manager.onHeadStop()
        }
    }

    @Test
    @Timeout(20)
    fun `item completion before response completion cannot finalize source or upstream billing`() = runBlocking {
        val runtime = IncrementalRuntime()
        val manager = bridge(runtime)
        val sink = StepSink()
        val post = GatedPost(sink)
        try {
            manager.interceptor(turn(), disableParallel = false).intercept(BASE_REQUEST, sink, post)
            post.gates.drop(1).forEach { it.complete(Unit) }
            withTimeout(1_500) { post.itemDone.await() }
            assertSourcePending()
            assertFalse(post.stopped.isCompleted)
            post.complete.complete(Unit)
            withTimeout(1_500) { post.stopped.await() }
        } finally {
            manager.onHeadStop()
        }
    }

    @Test
    @Timeout(20)
    fun `a genuinely cancelled client-result request cancels its step and never advances later source`() = runBlocking {
        val runtime = IncrementalRuntime()
        val manager = bridge(runtime)
        val sink = StepSink()
        val post = GatedPost(sink)
        try {
            manager.interceptor(turn(), disableParallel = false).intercept(BASE_REQUEST, sink, post)
            val first = sink.callback.await()
            val resumed = async {
                manager.interceptor(turn(first.id, "result-0"), disableParallel = false)
                    .intercept(history(listOf(first)), StepSink(), post)
            }
            withTimeout(1_500) { while (runtime.delivered.size < 2) kotlinx.coroutines.yield() }
            resumed.cancel()
            resumed.join()
            assertTrue(resumed.isCancelled)
            withTimeout(1_500) { post.stopped.await() }
            assertFalse(post.sent[1].isCompleted, "cancelled execution generates no unread source")
            assertEquals(1, runtime.starts)
            assertEquals(1, post.posts)
        } finally {
            manager.onHeadStop()
        }
    }

    @Test
    @Timeout(20)
    fun `a failed parked reader stops claiming source resumption before any client result`() = runBlocking {
        val runtime = IncrementalRuntime()
        val manager = bridge(runtime)
        val sink = StepSink()
        val post = GatedPost(sink)
        try {
            manager.interceptor(turn(), disableParallel = false).intercept(BASE_REQUEST, sink, post)
            val first = sink.callback.await()
            val resumed = manager.interceptor(turn(first.id, "result-0"), disableParallel = false)
            assertTrue(resumed.resumesSource(), "the parked reader is genuinely live before failure")
            post.dieAfterFirst = true
            post.gates[1].complete(Unit)
            withTimeout(5_000) {
                while (JsonScalars.str(stateFiles.records().single()["phase"]) != CodeModePhase.LOST.name) {
                    kotlinx.coroutines.yield()
                }
            }
            assertFalse(
                resumed.resumesSource(),
                "failed source ownership cannot retain admission on a later client step",
            )
            assertEquals(1, runtime.starts)
            assertEquals(1, post.posts, "the failed source is never re-dispatched")
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
            withTimeout(1_500) { post.itemDone.await() }
            assertFalse(request.isCompleted)
            assertFalse(sink.callback.isCompleted)
            post.complete.complete(Unit)
            val outcome = withTimeout(1_500) { request.await() }.turn() as TurnOutcome.Success
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
        CodexCodeModeBridge::class.java.getDeclaredField("registry").apply {
            isAccessible = true
        }.get(manager) as CodexCodeModeRegistry

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
    fun `local rejection loses its attached record even when cell cleanup throws`() = runBlocking {
        val delegate = IncrementalRuntime()
        var closes = 0
        val runtime = object : CodeModeRuntime by delegate {
            override suspend fun startStreamingSession(
                sessionKey: String,
                source: CodeModeSource,
                tools: Set<String>,
                descriptions: Map<String, String>,
            ): CodeModeCell {
                val cell = delegate.startStreaming(source, tools, descriptions)
                return object : CodeModeCell by cell {
                    override fun close() {
                        closes++
                        error("synthetic close failure")
                    }
                }
            }
        }
        val manager = bridge(runtime)
        val sink = StepSink()
        val post = GatedPost(sink).also { it.terminalProblem = "changed-id" }
        var escaped = 0
        val sourcePost = object : RedirectableRoundPost by post {
            override suspend fun into(bodyJson: String, sink: WireSink): RoundResult = try {
                post.into(bodyJson, sink)
            } catch (_: IllegalStateException) {
                escaped++
                RoundResult.Outcome(completedOutcome())
            }
        }
        try {
            manager.interceptor(turn(), disableParallel = false).intercept(BASE_REQUEST, sink, sourcePost)
            sink.callback.await()
            post.gates.drop(1).forEach { it.complete(Unit) }
            post.complete.complete(Unit)
            withTimeout(5_000) {
                while (JsonScalars.str(stateFiles.records().single()["phase"]) != CodeModePhase.LOST.name) {
                    kotlinx.coroutines.yield()
                }
            }
            assertEquals(0, escaped)
            assertEquals(1, closes)
            assertEquals(1, delegate.starts)
            assertTrue(registryOf(manager).startup.entries.isEmpty())
            assertTrue(logLines.any { it.contains("rejected cell close failed") })
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

    @Test
    @Timeout(20)
    fun `expiring a parked source disposes its reader and cannot recreate the expired record`() = runBlocking {
        val runtime = IncrementalRuntime()
        val clock = MutableClock(1_000)
        val manager = bridge(runtime, ttl = kotlin.time.Duration.parse("1s"), clock = clock)
        val sink = StepSink()
        val post = GatedPost(sink)
        try {
            manager.interceptor(turn(), disableParallel = false).intercept(BASE_REQUEST, sink, post)
            clock.now += 2_000
            sweepOwnHistory(manager)
            withTimeout(1_500) { post.stopped.await() }
            assertFalse(post.sent[1].isCompleted, "expiry leaves no source reader behind")
            assertTrue(stateFiles.records().isEmpty())
            assertEquals(1, runtime.starts)
            assertEquals(1, post.posts)
        } finally {
            manager.onHeadStop()
        }
    }

    @Test
    @Timeout(20)
    fun `poisoning a live source at its explicit round bound cancels the reader and cannot dispatch twice`() = runBlocking {
        val runtime = IncrementalRuntime()
        val manager = bridge(runtime, maxRounds = 1)
        val sink = StepSink()
        val post = GatedPost(sink)
        try {
            manager.interceptor(turn(), disableParallel = false).intercept(BASE_REQUEST, sink, post)
            val first = sink.callback.await()
            val outcome = manager.interceptor(turn(first.id, "result-0"), disableParallel = false)
                .intercept(history(listOf(first)), StepSink(), post).turn()
            assertTrue(outcome is TurnOutcome.Failure)
            withTimeout(1_500) { post.stopped.await() }
            assertFalse(post.sent[1].isCompleted, "poisoning generates no unread source")
            assertFalse(manager.interceptor(turn(first.id, "result-0"), disableParallel = false).resumesSource())
            assertEquals(1, runtime.starts)
            assertEquals(1, post.posts)
        } finally {
            manager.onHeadStop()
        }
    }
}
