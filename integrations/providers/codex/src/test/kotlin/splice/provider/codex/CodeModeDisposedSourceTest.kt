package splice.provider.codex

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import splice.core.model.TurnBill
import splice.core.perf.PerfKeys
import splice.core.turn.TurnOutcome
import splice.core.turn.Usage
import splice.provider.codex.stream.CodeModeLiveRound
import splice.provider.codex.stream.CodeModeRedirectablePost
import splice.provider.codex.stream.CodeModeRuntimeStarter
import splice.provider.codex.stream.CodeModeSourceCapture
import splice.provider.codex.stream.CodeModeSourceInterruptedException
import splice.provider.codex.stream.CodeModeStreamAdmission
import splice.provider.codex.stream.CodeModeStreamingCell
import splice.provider.codex.stream.CodeModeStreams
import splice.upstream.LifecycleScope
import splice.upstream.PostingTurnRow
import splice.upstream.RedirectableRoundPost
import splice.upstream.RoundBody
import splice.upstream.RowRelease
import splice.upstream.codemode.CodeModeCell
import splice.upstream.codemode.CodeModeResult
import splice.upstream.codemode.CodeModeSourcePart
import splice.upstream.codemode.CodeModeStep
import splice.upstream.failure.CodeModeStartException
import splice.upstream.sse.CustomToolSource
import splice.upstream.sse.WireSink
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.hours

class CodeModeDisposedSourceTest : CodeModeBridgeTestSupport() {
    @Test
    fun `a typed runtime boot failure bills its completed source receipt exactly once`() {
        val state = SourceState()
        assertTrue(
            state.registry.source.finish(
                state.record,
                state.outcome.customCalls.single(),
                state.wire.continuity(state.outcome),
                state.outcome.usage,
            ),
        )
        val starter = CodeModeRuntimeStarter(CodeModeRuntimeRun(state.config.runtimes), state.registry, state.config)
        val failed = starter.failed(state.record, CodeModeStartException(IOException("synthetic boot failed")))
        val streams = CodeModeStreams(state.config, state.registry, state.wire)
        try {
            val billed = streams.billFinished(state.record, failed) as TurnOutcome.Failure
            assertEquals(state.outcome.usage, billed.salvagedUsage)
            assertNull(billed.partial, "a local boot failure cannot open a source re-anchor")
            val counters = TurnBill.counters(billed.salvagedUsage)
            assertEquals(1_400L, counters[PerfKeys.IN_TOKENS])
            assertNull(counters[PerfKeys.ABSORBED_ROUNDS], "the completed source is still the final owned request")
            assertNull(counters[PerfKeys.CUT_SOURCE_ROUNDS], "the runtime failure posted no request")
        } finally {
            streams.stop()
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["lost", "expired"])
    fun `a terminal losing ownership after its early check closes the live cell as disposal`(
        disposition: String,
    ): Unit = runBlocking {
        val state = SourceState()
        val round = CodeModeLiveRound(
            state.config,
            state.registry,
            state.wire,
            CodeModeStreamAdmission { state.record },
            RecordingSink(),
        )
        val terminal = CompletableDeferred<TurnOutcome>()
        val reader = AtomicReference<Thread?>()
        val post = object : RedirectableRoundPost {
            override suspend fun invoke(bodyJson: String): TurnOutcome = error("redirected post required")
            override suspend fun into(bodyJson: String, sink: WireSink): TurnOutcome {
                sink.customToolSource(CustomToolSource.Started(outer(state.record.outerCallId, source = "")))
                return terminal.await().also { reader.set(Thread.currentThread()) }
            }
        }
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { dispatcher ->
            val scope = LifecycleScope(dispatcher)
            try {
                round.start(scope, CodeModeRedirectablePost(post, state.wire), state.wire.body(RoundBody.Text("{}"))) {}
                state.registry.changes.edit(state.record) {
                    terminal.complete(state.outcome)
                    awaitReader(reader)
                    state.dispose(disposition)
                }
                assertEquals(state.outcome, round.outcome())
                assertTrue(round.sourceLost, "rejected terminal staging must publish disposal to the live step")
                assertTrue(round.closedLiveCell, "a closed cell must not become a code-mode protocol failure")
                assertDisposedCell(state.record, round)
                assertTrue(round.source.view().read() is CodeModeSourcePart.Failed)
            } finally {
                scope.cancel()
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["lost", "expired"])
    fun `a terminal waiting on the key cannot certify a disposed source`(disposition: String) {
        val state = SourceState()
        val capture = CodeModeSourceCapture(
            state.config,
            state.registry,
            state.wire,
            CodeModeStreamAdmission { state.record },
        )
        capture.observe(CustomToolSource.Started(outer(state.record.outerCallId, source = "")))
        val failure = AtomicReference<Throwable?>()
        val terminal = Thread {
            try {
                capture.finish(state.outcome)
            } catch (error: Throwable) {
                failure.set(error)
            }
        }.apply { isDaemon = true }
        state.registry.changes.edit(state.record) {
            terminal.start()
            awaitKey(terminal)
            state.dispose(disposition)
        }
        terminal.join(DISPOSAL_WAIT_MILLIS)
        assertFalse(terminal.isAlive, "the terminal must finish without waiting for deferred cancellation")
        assertNull(failure.get(), "revoked ownership is disposal, not an internal response failure")
        assertTrue(runBlocking { capture.source.view().read() } is CodeModeSourcePart.Failed)
        assertEquals("", state.record.source, "the terminal cannot restore the revoked source")
        assertNull(state.record.sourceState?.usage, "rejected staging must not pretend to certify the source")
        if (disposition == "expired") assertTrue(stateFiles.records().isEmpty())
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @ParameterizedTest
    @ValueSource(strings = ["lost", "expired", "completed"])
    fun `a terminal parsed after disposal settles only its original posting row`(disposition: String) = runTest {
        val state = SourceState()
        val round = CodeModeLiveRound(
            state.config,
            state.registry,
            state.wire,
            CodeModeStreamAdmission { state.record },
            RecordingSink(),
        )
        val terminal = CompletableDeferred<TurnOutcome>()
        val released = CompletableDeferred<Usage?>()
        val releases = AtomicInteger()
        val holds = AtomicInteger()
        val post = object : RedirectableRoundPost {
            override val postingRow = PostingTurnRow {
                holds.incrementAndGet()
                RowRelease {
                    releases.incrementAndGet()
                    released.complete(it)
                }
            }
            override suspend fun invoke(bodyJson: String): TurnOutcome = error("redirected post required")
            override suspend fun into(bodyJson: String, sink: WireSink): TurnOutcome {
                sink.customToolSource(CustomToolSource.Started(outer(state.record.outerCallId, source = "")))
                return terminal.await()
            }
        }
        val scope = LifecycleScope(StandardTestDispatcher(testScheduler))
        try {
            round.start(scope, CodeModeRedirectablePost(post, state.wire), state.wire.body(RoundBody.Text("{}"))) {}
            assertNull(round.billing.claim(state.record, TurnOutcome.Success(true, false, Usage(localStep = true))))
            assertEquals(1, holds.get())
            state.dispose(disposition)
            terminal.complete(state.outcome)
            runCurrent()
            assertEquals(state.outcome, round.outcome(), "disposal cannot fail the parsed terminal")
            val usage = checkNotNull(released.await())
            assertEquals(1_400L, usage.inputTokens)
            assertEquals(12L, usage.outputTokens)
            assertEquals(1_100L, usage.cachedTokens)
            assertEquals(0L, usage.cutRounds, "a parsed terminal is not an unreported cut")
            assertNull(round.billing.claim(state.record, TurnOutcome.Success(true, false, Usage(localStep = true))))
            assertEquals(1, holds.get(), "a later step must not acquire another claim")
            assertEquals(1, releases.get(), "the original row settles exactly once")
            assertNull(state.registry.source.consume(state.record), "continuations cannot rebill the parsed terminal")
            assertTrue(round.source.view().read() is CodeModeSourcePart.Failed)
            if (disposition == "expired") {
                assertTrue(stateFiles.records().isEmpty(), "billing must not resurrect an expired record")
            }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `disposal does not swallow identity prefix or source size validation`() {
        val state = SourceState()
        val capture = CodeModeSourceCapture(
            state.config,
            state.registry,
            state.wire,
            CodeModeStreamAdmission { state.record },
        )
        capture.observe(CustomToolSource.Started(outer(state.record.outerCallId, source = "prefix")))
        state.dispose("expired")
        val wrongIdentity = state.outcome.copy(customCalls = listOf(outer("synthetic-other", source = "prefix")))
        assertThrows(IllegalStateException::class.java) { capture.finish(wrongIdentity) }
        assertThrows(IllegalStateException::class.java) { capture.finish(state.outcome) }
        val tooLarge = "prefix" + "x".repeat(state.config.maxSourceChars)
        val oversized = state.outcome.copy(customCalls = listOf(outer(state.record.outerCallId, source = tooLarge)))
        assertThrows(IllegalArgumentException::class.java) { capture.finish(oversized) }
        assertTrue(stateFiles.records().isEmpty())
    }

    @Test
    fun `an owned changed prefix still fails and a detached consumed bill makes no state write`() {
        val writes = AtomicInteger()
        val state = SourceState(
            CodeModeStateWrite { path, text ->
                writes.incrementAndGet()
                splice.provider.codex.state.CodeModeStateJournal.write(path, text)
            },
        )
        assertTrue(state.registry.source.append(state.record, "prefix"))
        assertThrows(IllegalStateException::class.java) { state.registry.source.append(state.record, "changed") }
        val call = outer(state.record.outerCallId, source = "prefix suffix")
        assertTrue(
            state.registry.source.finish(state.record, call, state.wire.continuity(state.outcome), state.outcome.usage),
        )
        state.dispose("expired")
        val before = writes.get()
        assertEquals(12L, checkNotNull(state.registry.source.consume(state.record)).outputTokens)
        assertNull(state.registry.source.consume(state.record), "the detached bill is handed out once")
        assertEquals(before, writes.get(), "detached usage must not write a removed record back")
        assertTrue(stateFiles.records().isEmpty())
    }

    private inner class SourceState(writer: CodeModeStateWrite? = null) {
        val config = CodeModeBridgeConfig({ error("no runtime needed") }, stateLocation())
        val registry = CodexCodeModeRegistry(config, Json, 1.hours, writer)
        val wire = CodexCodeModeWire(Json, {})
        val record = CodeModeRecords.of("synthetic-disposal-key", 0, config.clock.millis()).also {
            it.source = ""
            assertTrue(registry.add(it))
            it.phase = CodeModePhase.ACTIVE
        }
        val outcome = TurnOutcome.Success(
            false,
            false,
            Usage(1_400, 12, 1_100),
            customCalls = listOf(outer(record.outerCallId, source = "return 1;")),
        )

        fun dispose(disposition: String) {
            when (disposition) {
                "lost" -> registry.lose(record, "synthetic disposal")
                "expired" -> {
                    registry.changes.edit(record) { it.updatedAt -= 25.hours.inWholeMilliseconds }
                    registry.recordsFor(record.key)
                    assertTrue(stateFiles.records().isEmpty())
                }
                "completed" -> registry.changes.complete(record, "synthetic completed script")
                else -> error("unknown synthetic disposition")
            }
        }
    }

    private fun assertDisposedCell(record: CodeModeRecord, round: CodeModeLiveRound) {
        val guest = object : CodeModeCell {
            override suspend fun advance(results: List<CodeModeResult>): CodeModeStep =
                CodeModeStep.Completed("synthetic disposed guest")
            override fun close() = Unit
        }
        val cell = CodeModeStreamingCell(guest, record, round)
        assertThrows(CodeModeSourceInterruptedException::class.java) {
            runBlocking { cell.advance(emptyList()) }
        }
    }

    private fun awaitReader(reader: AtomicReference<Thread?>) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(DISPOSAL_WAIT_MILLIS)
        while (reader.get() == null && System.nanoTime() < deadline) Thread.onSpinWait()
        awaitKey(checkNotNull(reader.get()) { "the terminal reader did not resume" })
    }

    private fun awaitKey(thread: Thread) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(DISPOSAL_WAIT_MILLIS)
        while (thread.isAlive && thread.state != Thread.State.WAITING) {
            if (System.nanoTime() >= deadline) break
            Thread.onSpinWait()
        }
        assertEquals(Thread.State.WAITING, thread.state, "terminal capture must wait on the held key")
    }
}

private const val DISPOSAL_WAIT_MILLIS = 5_000L
