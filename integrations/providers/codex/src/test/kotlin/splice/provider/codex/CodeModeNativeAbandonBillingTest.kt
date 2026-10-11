// NEW: native abandonment owns a parked source's unclaimed terminal or live cut, never neither or both.
package splice.provider.codex

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import splice.core.turn.TurnOutcome
import splice.core.turn.Usage
import splice.provider.codex.stream.CodeModeLiveRound
import splice.upstream.PostingTurnRow
import splice.upstream.RedirectableRoundPost
import splice.upstream.RowRelease
import splice.upstream.codemode.CodeModeCell
import splice.upstream.codemode.CodeModeRuntime
import splice.upstream.codemode.CodeModeSource
import java.util.concurrent.atomic.AtomicInteger

private enum class NativeAbandonBranch(val wire: String) { ORDER("nativeOrder"), ABSENT("absent") }
private enum class NativeAbandonEnding { LIVE, TERMINAL, HELD_TERMINAL, RACING_TERMINAL }

internal class CodeModeNativeAbandonBillingTest : CodeModeStatementStreamSupport() {
    @Test
    @Timeout(20)
    fun `native order abandonment claims a terminal after the empty client step`() = runBlocking {
        abandon(NativeAbandonBranch.ORDER, NativeAbandonEnding.TERMINAL)
    }

    @Test
    @Timeout(20)
    fun `absent native abandonment claims a terminal after the empty client step`() = runBlocking {
        abandon(NativeAbandonBranch.ABSENT, NativeAbandonEnding.TERMINAL)
    }

    @Test
    @Timeout(20)
    fun `native order abandonment bills a still live source cut once`() = runBlocking {
        abandon(NativeAbandonBranch.ORDER, NativeAbandonEnding.LIVE)
    }

    @Test
    @Timeout(20)
    fun `absent native abandonment bills a still live source cut once`() = runBlocking {
        abandon(NativeAbandonBranch.ABSENT, NativeAbandonEnding.LIVE)
    }

    @Test
    @Timeout(20)
    fun `native order abandonment leaves a held posting row as the sole terminal owner`() = runBlocking {
        abandon(NativeAbandonBranch.ORDER, NativeAbandonEnding.HELD_TERMINAL)
    }

    @Test
    @Timeout(20)
    fun `absent native abandonment leaves a held posting row as the sole terminal owner`() = runBlocking {
        abandon(NativeAbandonBranch.ABSENT, NativeAbandonEnding.HELD_TERMINAL)
    }

    @Test
    @Timeout(20)
    fun `native order abandonment settles a terminal versus disposal race once`() = runBlocking {
        abandon(NativeAbandonBranch.ORDER, NativeAbandonEnding.RACING_TERMINAL)
    }

    @Test
    @Timeout(20)
    fun `absent native abandonment settles a terminal versus disposal race once`() = runBlocking {
        abandon(NativeAbandonBranch.ABSENT, NativeAbandonEnding.RACING_TERMINAL)
    }

    private val firstUser = item("""{"role":"user","content":"first synthetic request"}""")
    private val middle = item("""{"role":"user","content":"middle synthetic request"}""")
    private val latest = item("""{"role":"user","content":"latest synthetic request"}""")
    private val firstNative =
        item("""{"type":"reasoning","id":"synthetic-first","encrypted_content":"synthetic-first"}""")
    private val secondNative =
        item("""{"type":"reasoning","id":"synthetic-second","encrypted_content":"synthetic-second"}""")

    private suspend fun abandon(branch: NativeAbandonBranch, ending: NativeAbandonEnding) {
        val delegate = IncrementalRuntime()
        val runtime = CompletingRuntime(delegate)
        val manager = bridge(runtime)
        val sink = StepSink()
        val source = GatedPost(sink)
        val held = HeldPosting(source)
        val posting = if (ending == NativeAbandonEnding.HELD_TERMINAL) held else source
        val baseline = listOf(
            firstUser,
            outer("retired").raw,
            opaqueOutput(),
            firstNative,
            middle,
            secondNative,
            latest,
        )
        try {
            val first = manager.interceptor(turn(), disableParallel = false)
                .intercept(body(baseline), sink, posting).turn() as TurnOutcome.Success
            val callback = withTimeout(30_000) { sink.callback.await() }
            assertEmptyStep(first, source)
            val registry = manager.registry
            val driver = manager.driver
            val key = stateFiles.records().single().getValue("key").jsonPrimitive.content
            val record = registry.recordsFor(key).single()
            val round = checkNotNull(driver.streams.find(record))
            prepareEnding(ending, runtime, round, source)
            val replayed = listOf(firstUser) + when (branch) {
                NativeAbandonBranch.ORDER -> listOf(secondNative, firstNative)
                NativeAbandonBranch.ABSENT -> listOf(secondNative)
            } + latest + callbacks(callback)
            val changed = body(replayed)
            val next = manager.interceptor(turn(callback.id, "result-0"), disableParallel = false)
                .intercept(changed, RecordingSink(), source).turn() as TurnOutcome.Success
            withTimeout(30_000) { source.stopped.await() }
            assertTrue(
                logLines.any { "abandoned record" in it && "native_branch=${branch.wire}" in it },
                "the exact placement refusal branch must run before billing is checked",
            )
            // Billing must hold if the session ends here. No future request is part of this check.
            assertBill(ending, held, listOf(first.usage, next.usage), next.usage)
            assertLaterUnbilled(manager, callback, replayed, source)
            assertEquals(1, delegate.starts, "abandonment cannot rerun the source")
        } finally {
            manager.onHeadStop()
        }
    }

    private fun assertEmptyStep(first: TurnOutcome.Success, source: GatedPost) {
        assertFalse(first.text.emittedText)
        assertEquals("", first.text.bodyText)
        assertEquals(0L, first.usage.outputTokens)
        assertEquals(0L, first.usage.cutRounds)
        assertFalse(source.stopped.isCompleted, "the source is still live after the empty-text local step")
    }

    private suspend fun prepareEnding(
        ending: NativeAbandonEnding,
        runtime: CompletingRuntime,
        round: CodeModeLiveRound,
        source: GatedPost,
    ) {
        when (ending) {
            NativeAbandonEnding.LIVE -> Unit
            NativeAbandonEnding.RACING_TERMINAL -> runtime.onClose = Runnable {
                // Release the terminal as disposal closes the worker. Either completion or cancellation may win.
                finishSource(source)
                runBlocking {
                    withTimeout(30_000) {
                        while (!round.upstreamEnded) yield()
                    }
                }
            }
            NativeAbandonEnding.TERMINAL, NativeAbandonEnding.HELD_TERMINAL -> {
                finishSource(source)
                withTimeout(30_000) { round.outcome() }
            }
        }
    }

    private fun finishSource(source: GatedPost) {
        source.gates[1].complete(Unit)
        source.gates[2].complete(Unit)
        source.complete.complete(Unit)
    }

    private suspend fun assertLaterUnbilled(
        manager: CodexCodeModeBridge,
        callback: SeenTool,
        replayed: List<JsonElement>,
        source: GatedPost,
    ) {
        val ordinary = replayed + item("""{"role":"user","content":"a different ordinary continuation"}""")
        val later = manager.interceptor(turn(), disableParallel = false)
            .intercept(body(ordinary), RecordingSink(), source).turn() as TurnOutcome.Success
        val repeated = manager.interceptor(turn(callback.id, "result-0"), disableParallel = false)
            .intercept(body(replayed), RecordingSink(), source).turn() as TurnOutcome.Success
        for (step in listOf(later, repeated)) {
            assertEquals(0L, step.usage.absorbed.rounds)
            assertEquals(0L, step.usage.cutRounds)
        }
    }

    private suspend fun assertBill(
        ending: NativeAbandonEnding,
        held: HeldPosting,
        bills: List<Usage>,
        next: Usage,
    ) {
        val billedRow = if (ending == NativeAbandonEnding.HELD_TERMINAL) {
            withTimeout(30_000) { held.released.await() }.also {
                assertEquals(1, held.releases.get())
                assertEquals(0L, next.cutRounds)
                assertEquals(0L, next.absorbed.rounds, "the next step need not carry the already owned round")
            }
        } else {
            null
        }
        val heldRound = billedRow?.finalRound
        assertEquals(
            1L,
            bills.sumOf { it.absorbed.rounds + it.cutRounds } + (heldRound?.rounds ?: 0L),
            "the source has exactly one billed usage owner or cut even if the next step carries neither",
        )
        if (ending == NativeAbandonEnding.LIVE || next.cutRounds > 0L) {
            assertEquals(1L, next.cutRounds)
            assertEquals(0L, next.absorbed.outputTokens)
        } else {
            assertEquals(7L, bills.sumOf { it.absorbed.outputTokens } + (heldRound?.outputTokens ?: 0L))
            assertEquals(100L, bills.sumOf { it.absorbed.inputTokens } + (heldRound?.inputTokens ?: 0L))
            assertEquals(0L, bills.sumOf { it.cutRounds })
        }
    }

    private class CompletingRuntime(private val runtime: CodeModeRuntime) : CodeModeRuntime by runtime {
        var onClose = Runnable {}
        override suspend fun startStreaming(
            source: CodeModeSource,
            tools: Set<String>,
            descriptions: Map<String, String>,
        ): CodeModeCell {
            val cell = runtime.startStreaming(source, tools, descriptions)
            return object : CodeModeCell by cell {
                override fun close() {
                    onClose.run()
                    cell.close()
                }
            }
        }
    }

    private fun item(json: String): JsonElement = Json.parseToJsonElement(json)

    private fun body(items: List<JsonElement>): String = JsonObject(mapOf("input" to JsonArray(items))).toString()

    private fun opaqueOutput(): JsonElement =
        item("""{"type":"custom_tool_call_output","call_id":"retired","output":"synthetic old result"}""")

    private fun callbacks(call: SeenTool): List<JsonElement> = listOf(
        item("""{"type":"function_call","call_id":"${call.id}","name":"${call.name}","arguments":"{}"}"""),
        item("""{"type":"function_call_output","call_id":"${call.id}","output":"result-0"}"""),
    )

    private class HeldPosting(source: RedirectableRoundPost) : RedirectableRoundPost by source {
        val released = CompletableDeferred<Usage?>()
        val releases = AtomicInteger()
        override val postingRow = PostingTurnRow {
            RowRelease {
                releases.incrementAndGet()
                released.complete(it)
            }
        }
    }
}
