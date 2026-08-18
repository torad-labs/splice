// Walls for the round runners (eli design 2026-07-24 + code-review fix round): a spliced turn is
// ONE coherent Anthropic message — single message_start, monotonic block indices, exactly ONE
// terminal; budget exhaustion ends in the honest error AFTER the appended blocks; watchdog fires
// and gone clients never continue; the finish outcome carries the WHOLE turn's facts (cross-round
// merge — a round-2 empty completion must not read as an empty model); fold-eligible turns retry
// failed rounds via trigger-B with never-forwarded prose stripped from the salvage.
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.turn.ErrorType
import splice.core.turn.ToolSearchCall
import splice.core.turn.ToolSearchCallId
import splice.core.turn.TurnOutcome
import splice.core.turn.Usage
import splice.gateway.round.FoldRunner
import splice.gateway.round.ReanchorRunner
import splice.gateway.round.RunnerSignals
import splice.gateway.wire.SseEmitterFactory
import splice.spi.FoldController
import splice.spi.ReanchorController
import splice.spi.ToolSearchController
import splice.spi.WireSink

private fun continuationBody() = buildJsonObject { }

// A search-carrying Success — usage/text default to a no-op round so tests opt into only what
// they need (the ReanchorRunnerTest / FoldRunnerReanchorTest precedent's retryableFailure()).
private fun searchSuccess(
    outputTokens: Long = 0,
    bodyText: String = "",
    thinkingText: String = "",
    emittedText: Boolean = false,
    hasToolUse: Boolean = false,
) = TurnOutcome.Success(
    hasToolUse = hasToolUse,
    incomplete = false,
    usage = Usage(outputTokens = outputTokens),
    thinkingText = thinkingText,
    bodyText = bodyText,
    emittedText = emittedText,
    toolSearches = listOf(ToolSearchCall(ToolSearchCallId("ts_1"), "q", null, buildJsonObject { })),
)

private class Harness {
    val frames = mutableListOf<String>()
    val emitter = SseEmitterFactory().create(
        write = { frames.add(it) },
        model = "m",
        usagePayload = { buildJsonObject { } },
        messageId = "msg_test",
    )
    var finished: TurnOutcome? = null
    val absorbed = mutableListOf<TurnOutcome.Failure>()
    val searchRounds = mutableListOf<Int>()

    fun signals(watchdog: Boolean = false, gone: Boolean = false) = RunnerSignals(
        watchdogFired = { watchdog },
        clientGone = { gone },
        onRoundFailure = { absorbed.add(it) },
        onSearchRound = { searchRounds.add(it) },
    )

    suspend fun finish(outcome: TurnOutcome) {
        finished = outcome
        when (outcome) {
            is TurnOutcome.Success -> emitter.emitTerminal(outcome.hasToolUse, outcome.incomplete, outcome.usage)
            is TurnOutcome.Failure -> emitter.emitError(outcome.type, outcome.message)
            TurnOutcome.ClientAbandoned -> emitter.abandon()
        }
    }

    fun count(event: String) = frames.count { it.startsWith("event: $event\n") }
}

private fun retryableFailure(
    outputTokens: Long = 0,
    bodyText: String = "partial",
    thinkingText: String = "",
    emittedText: Boolean = bodyText.isNotEmpty(),
) = TurnOutcome.Failure(
    ErrorType.OVERLOADED,
    "mid-stream death",
    providerReported = true,
    partial = TurnOutcome.PartialRound(
        thinkingText = thinkingText,
        bodyText = bodyText,
        emittedText = emittedText,
        usage = Usage(outputTokens = outputTokens),
    ),
)

class ReanchorRunnerTest {

    @Test
    fun `continuation splices one coherent message with a single clean terminal`() = runTest {
        val h = Harness()
        val rounds = ArrayDeque<suspend () -> TurnOutcome>()
        rounds.add {
            val i = h.emitter.openText()
            h.emitter.textDelta(i, "part one")
            h.emitter.closeBlock(i)
            retryableFailure(outputTokens = 7)
        }
        rounds.add {
            val i = h.emitter.openText()
            h.emitter.textDelta(i, " part two")
            h.emitter.closeBlock(i)
            TurnOutcome.Success(hasToolUse = false, incomplete = false, usage = Usage(outputTokens = 5))
        }
        ReanchorRunner(
            key = "t",
            log = { },
            postRound = { rounds.removeFirst().invoke() },
            finish = { h.finish(it) },
            signals = h.signals(),
        ).run(continuationBody(), ReanchorController { continuationBody() })

        assertEquals(1, h.count("message_start"), "exactly one message_start across rounds")
        assertEquals(1, h.count("message_stop"), "exactly one clean terminal")
        assertEquals(0, h.count("error"), "no error frame on a recovered turn")
        assertTrue(h.frames.any { it.contains("\"index\":1") }, "continuation appends a NEW block index")
        val success = h.finished as TurnOutcome.Success
        assertEquals(12, success.usage.outputTokens, "salvaged 7 + final 5")
        assertEquals(1, h.absorbed.size, "the absorbed round failure reached the health hook")
    }

    @Test
    fun `the merged outcome carries the whole turn - a round-2 empty completion is not an empty model`() = runTest {
        val h = Harness()
        val rounds = ArrayDeque<suspend () -> TurnOutcome>()
        rounds.add {
            retryableFailure(outputTokens = 9, bodyText = "the full answer", thinkingText = "the reasoning")
        }
        rounds.add {
            // lost-terminal recovery: the model correctly adds nothing on the continuation
            TurnOutcome.Success(hasToolUse = false, incomplete = false, usage = Usage(outputTokens = 1))
        }
        ReanchorRunner(
            key = "t",
            log = { },
            postRound = { rounds.removeFirst().invoke() },
            finish = { h.finish(it) },
            signals = h.signals(),
        ).run(continuationBody(), ReanchorController { continuationBody() })

        val success = h.finished as TurnOutcome.Success
        assertTrue(success.emittedText, "round-1's forwarded text must count for the honesty gate")
        assertEquals("the reasoning", success.thinkingText, "round-1 reasoning must reach the mirror")
        assertEquals("the full answer", success.bodyText)
        assertEquals(10, success.usage.outputTokens)
    }

    @Test
    fun `budget exhaustion surfaces the honest error after the appended blocks`() = runTest {
        val h = Harness()
        var posts = 0
        var asks = 0
        ReanchorRunner(
            key = "t",
            log = { },
            postRound = {
                posts++
                val i = h.emitter.openText()
                h.emitter.textDelta(i, "chunk $posts")
                h.emitter.closeBlock(i)
                retryableFailure()
            },
            finish = { h.finish(it) },
            signals = h.signals(),
        ).run(
            continuationBody(),
            ReanchorController { round ->
                asks++
                if (round.attempt < 2) continuationBody() else null
            },
        )

        assertEquals(3, posts, "two continuations spent, third failure surfaces")
        assertEquals(3, asks)
        assertEquals(1, h.count("error"), "the honest error terminal")
        assertEquals(0, h.count("message_stop"), "never a fabricated clean stop")
        assertTrue(h.finished is TurnOutcome.Failure)
    }

    @Test
    fun `a watchdog fire never continues - its cancellation owns the turn`() = runTest {
        val h = Harness()
        var asks = 0
        ReanchorRunner(
            key = "t",
            log = { },
            postRound = { retryableFailure() },
            finish = { h.finish(it) },
            signals = h.signals(watchdog = true),
        ).run(
            continuationBody(),
            ReanchorController {
                asks++
                continuationBody()
            },
        )
        assertEquals(0, asks, "the controller is never consulted after a watchdog fire")
        assertEquals(1, h.count("error"))
    }

    @Test
    fun `a gone client never continues - no zombie re-POSTs`() = runTest {
        val h = Harness()
        var asks = 0
        ReanchorRunner(
            key = "t",
            log = { },
            postRound = { retryableFailure() },
            finish = { h.finish(it) },
            signals = h.signals(gone = true),
        ).run(
            continuationBody(),
            ReanchorController {
                asks++
                continuationBody()
            },
        )
        assertEquals(0, asks, "the controller is never consulted for a gone client")
        assertEquals(1, h.count("error"), "the turn still finishes with the honest error")
    }

    @Test
    fun `an ineligible failure surfaces immediately - error, not clean stop`() = runTest {
        val h = Harness()
        ReanchorRunner(
            key = "t",
            log = { },
            postRound = { retryableFailure() },
            finish = { h.finish(it) },
            signals = h.signals(),
        ).run(continuationBody(), ReanchorController { null })
        assertEquals(1, h.count("error"))
        assertEquals(0, h.count("message_stop"))
    }
}

class FoldRunnerReanchorTest {

    @Test
    fun `fold trigger-B retries a failed round with never-forwarded prose stripped from the salvage`() = runTest {
        val h = Harness()
        val seenPartialBodies = mutableListOf<String?>()
        val rounds = ArrayDeque<suspend () -> TurnOutcome>()
        rounds.add { retryableFailure(outputTokens = 3, bodyText = "buffered never-sent prose") }
        rounds.add {
            TurnOutcome.Success(
                hasToolUse = false,
                incomplete = false,
                usage = Usage(outputTokens = 5),
                bodyText = "clean full answer",
                emittedText = true,
            )
        }
        FoldRunner(
            emitter = h.emitter,
            key = "t",
            log = { },
            postRound = { _, _ -> rounds.removeFirst().invoke() },
            finish = { h.finish(it) },
            reanchor = ReanchorController { round ->
                seenPartialBodies.add(round.failure.partial?.bodyText)
                continuationBody()
            },
            signals = h.signals(),
        ).run(continuationBody()) { null }

        assertEquals(listOf(""), seenPartialBodies, "buffered prose must be stripped before the policy sees it")
        val success = h.finished as TurnOutcome.Success
        assertEquals("clean full answer", success.bodyText, "the merge-side strip must hold too")
        assertEquals(8, success.usage.outputTokens, "failed round's salvaged usage accrues")
        assertEquals(1, h.count("message_stop"))
        assertEquals(1, h.absorbed.size)
    }

    @Test
    fun `a discarded round cannot vouch for text the client never saw`() = runTest {
        // The blocker class (review-pr 2026-07-24): the failed round streamed prose into the
        // BUFFER (emittedText=true) which was discarded; the retry completes with no text. The
        // merged outcome must report emittedText=false so the empty-model honesty gate sees the
        // turn's real emptiness instead of a clean empty end-of-turn.
        val h = Harness()
        val rounds = ArrayDeque<suspend () -> TurnOutcome>()
        rounds.add { retryableFailure(bodyText = "buffered prose", emittedText = true) }
        rounds.add {
            TurnOutcome.Success(
                hasToolUse = false,
                incomplete = false,
                usage = Usage(),
                emittedText = false,
            )
        }
        FoldRunner(
            emitter = h.emitter,
            key = "t",
            log = { },
            postRound = { _, _ -> rounds.removeFirst().invoke() },
            finish = { h.finish(it) },
            reanchor = ReanchorController { continuationBody() },
            signals = h.signals(),
        ).run(continuationBody()) { null }
        val success = h.finished as TurnOutcome.Success
        assertEquals(false, success.emittedText, "a discarded buffer must not vouch for emitted text")
        assertEquals("", success.bodyText)
    }

    @Test
    fun `salvaged usage rides the failure when the turn ultimately fails`() = runTest {
        val h = Harness()
        var posts = 0
        ReanchorRunner(
            key = "t",
            log = { },
            postRound = {
                posts++
                retryableFailure(outputTokens = 4)
            },
            finish = { h.finish(it) },
            signals = h.signals(),
        ).run(
            continuationBody(),
            ReanchorController { round -> if (round.attempt < 2) continuationBody() else null },
        )
        assertEquals(3, posts)
        val failure = h.finished as TurnOutcome.Failure
        assertEquals(
            8,
            failure.salvagedUsage.outputTokens,
            "two absorbed rounds' tokens carried, final round's not salvaged",
        )
    }

    @Test
    fun `fold trigger-B respects the gone-client gate`() = runTest {
        val h = Harness()
        var asks = 0
        FoldRunner(
            emitter = h.emitter,
            key = "t",
            log = { },
            postRound = { _, _ -> retryableFailure() },
            finish = { h.finish(it) },
            reanchor = ReanchorController {
                asks++
                continuationBody()
            },
            signals = h.signals(gone = true),
        ).run(continuationBody()) { null }
        assertEquals(0, asks)
        assertNull(h.finished as? TurnOutcome.Success)
    }
}

// Search-continuation walls on ReanchorRunner (the search-only path — no ReanchorController at
// all, or a ReanchorController that stays silent because nothing failed).
class ReanchorRunnerSearchTest {

    @Test
    fun `a search round continues once, appends, and the turn emits exactly one terminal`() = runTest {
        val h = Harness()
        val rounds = ArrayDeque<suspend () -> TurnOutcome>()
        rounds.add { searchSuccess() }
        rounds.add {
            TurnOutcome.Success(
                hasToolUse = false,
                incomplete = false,
                usage = Usage(outputTokens = 5),
                bodyText = "answer",
                emittedText = true,
            )
        }
        var searchAsks = 0
        val search = ToolSearchController { round ->
            // The runner consults the controller on EVERY Success round (searchContinuation only
            // type/liveness-guards); the "nothing to answer" decision lives inside the real
            // controller (ResponsesToolSearch.kt). Count only genuine answers, matching this
            // test's intent ("a search round continues ONCE") — review 2026-07-24 round 1.
            if (round.outcome.toolSearches.isEmpty()) {
                null
            } else {
                searchAsks++
                continuationBody()
            }
        }
        ReanchorRunner(
            key = "t",
            log = { },
            postRound = { rounds.removeFirst().invoke() },
            finish = { h.finish(it) },
            signals = h.signals(),
            toolSearch = search,
        ).run(continuationBody(), reanchor = null)

        assertEquals(1, searchAsks)
        assertEquals(1, h.count("message_stop"), "exactly one clean terminal")
        assertEquals(0, h.count("error"))
        val success = h.finished as TurnOutcome.Success
        assertEquals("answer", success.bodyText)
    }

    @Test
    fun `search salvage carries the round's real emitted text truthfully`() = runTest {
        val h = Harness()
        val rounds = ArrayDeque<suspend () -> TurnOutcome>()
        rounds.add {
            searchSuccess(
                bodyText = "partial answer",
                thinkingText = "reasoning so far",
                emittedText = true,
                outputTokens = 3,
            )
        }
        rounds.add { TurnOutcome.Success(hasToolUse = false, incomplete = false, usage = Usage(outputTokens = 2)) }
        val search = ToolSearchController { round ->
            if (round.outcome.toolSearches.isEmpty()) null else continuationBody()
        }
        ReanchorRunner(
            key = "t",
            log = { },
            postRound = { rounds.removeFirst().invoke() },
            finish = { h.finish(it) },
            signals = h.signals(),
            toolSearch = search,
        ).run(continuationBody(), reanchor = null)

        val success = h.finished as TurnOutcome.Success
        assertTrue(success.emittedText, "round-1's real emitted text must count for the honesty gate")
        assertEquals("reasoning so far", success.thinkingText)
        assertEquals("partial answer", success.bodyText)
        assertEquals(5, success.usage.outputTokens)
    }

    @Test
    fun `hasToolUse in the outcome reaches the controller, which then blocks the continuation`() = runTest {
        val h = Harness()
        var sawHasToolUse = false
        val search = ToolSearchController { round ->
            sawHasToolUse = round.outcome.hasToolUse
            if (round.outcome.hasToolUse) null else continuationBody()
        }
        ReanchorRunner(
            key = "t",
            log = { },
            postRound = { searchSuccess(hasToolUse = true) },
            finish = { h.finish(it) },
            signals = h.signals(),
            toolSearch = search,
        ).run(continuationBody(), reanchor = null)
        assertTrue(sawHasToolUse, "the runner must pass the round's real hasToolUse to the controller")
        assertEquals(1, h.count("message_stop"))
    }

    @Test
    fun `a watchdog fire never continues a search - its cancellation owns the turn`() = runTest {
        val h = Harness()
        var asks = 0
        val search = ToolSearchController {
            asks++
            continuationBody()
        }
        ReanchorRunner(
            key = "t",
            log = { },
            postRound = { searchSuccess() },
            finish = { h.finish(it) },
            signals = h.signals(watchdog = true),
            toolSearch = search,
        ).run(continuationBody(), reanchor = null)
        assertEquals(0, asks, "the controller is never consulted after a watchdog fire")
        assertEquals(1, h.count("message_stop"))
    }

    @Test
    fun `a gone client never continues a search`() = runTest {
        val h = Harness()
        var asks = 0
        val search = ToolSearchController {
            asks++
            continuationBody()
        }
        ReanchorRunner(
            key = "t",
            log = { },
            postRound = { searchSuccess() },
            finish = { h.finish(it) },
            signals = h.signals(gone = true),
            toolSearch = search,
        ).run(continuationBody(), reanchor = null)
        assertEquals(0, asks)
        assertEquals(1, h.count("message_stop"))
    }

    @Test
    fun `the search counter is independent of the re-anchor attempt counter`() = runTest {
        val h = Harness()
        val rounds = ArrayDeque<suspend () -> TurnOutcome>()
        rounds.add { retryableFailure(outputTokens = 1) }
        rounds.add { searchSuccess(outputTokens = 1) }
        rounds.add { TurnOutcome.Success(hasToolUse = false, incomplete = false, usage = Usage(outputTokens = 1)) }
        val search = ToolSearchController { round ->
            if (round.outcome.toolSearches.isEmpty()) null else continuationBody()
        }
        var reanchorAsks = 0
        ReanchorRunner(
            key = "t",
            log = { },
            postRound = { rounds.removeFirst().invoke() },
            finish = { h.finish(it) },
            signals = h.signals(),
            toolSearch = search,
        ).run(
            continuationBody(),
            ReanchorController { round ->
                reanchorAsks++
                if (round.attempt < 1) continuationBody() else null
            },
        )
        assertEquals(1, reanchorAsks, "the re-anchor budget was consulted for its own retryable round only")
        assertEquals(listOf(1), h.searchRounds, "one search round, an independent counter from re-anchor's attempt")
        assertEquals(1, h.count("message_stop"))
    }

    @Test
    fun `reanchor null and a non-null search still runs the loop`() = runTest {
        val h = Harness()
        val rounds = ArrayDeque<suspend () -> TurnOutcome>()
        rounds.add { searchSuccess() }
        rounds.add { TurnOutcome.Success(hasToolUse = false, incomplete = false, usage = Usage(outputTokens = 1)) }
        val search = ToolSearchController { round ->
            if (round.outcome.toolSearches.isEmpty()) null else continuationBody()
        }
        ReanchorRunner(
            key = "t",
            log = { },
            postRound = { rounds.removeFirst().invoke() },
            finish = { h.finish(it) },
            signals = h.signals(),
            toolSearch = search,
        ).run(continuationBody(), reanchor = null)
        assertEquals(1, h.count("message_stop"))
    }
}

// Search-continuation walls on FoldRunner: buffered rounds strip bodyText/emittedText but keep
// live thinking (the same rule fold's own re-anchor trigger-B applies), and fold-continuation
// (a truncated round) takes precedence over a search on the same round.
class FoldRunnerSearchTest {

    @Test
    fun `a fold-round search continuation discards the buffer and strips only the text signals`() = runTest {
        val h = Harness()
        val rounds = ArrayDeque<suspend (WireSink) -> TurnOutcome>()
        rounds.add { sink ->
            val i = sink.openText()
            sink.textDelta(i, "buffered never-sent prose")
            sink.closeBlock(i)
            searchSuccess(
                bodyText = "buffered never-sent prose",
                thinkingText = "live reasoning",
                emittedText = true,
                outputTokens = 2,
            )
        }
        rounds.add { _ ->
            TurnOutcome.Success(
                hasToolUse = false,
                incomplete = false,
                usage = Usage(outputTokens = 1),
                bodyText = "final",
                emittedText = true,
            )
        }
        val search = ToolSearchController { round ->
            if (round.outcome.toolSearches.isEmpty()) null else continuationBody()
        }
        FoldRunner(
            emitter = h.emitter,
            key = "t",
            log = { },
            postRound = { _, sink -> rounds.removeFirst().invoke(sink) },
            finish = { h.finish(it) },
            signals = h.signals(),
            toolSearch = search,
        ).run(continuationBody()) { null }

        val success = h.finished as TurnOutcome.Success
        assertEquals("final", success.bodyText, "the buffered never-sent prose must not leak into the merge")
        assertEquals("live reasoning", success.thinkingText, "live-streamed thinking belongs in the mirror merge")
        assertEquals(1, h.count("message_stop"))
    }

    @Test
    fun `fold-continuation takes precedence over search on a round that is both truncated and searching`() = runTest {
        val h = Harness()
        // A dumb controller that always declines — the point is proving it is never even ASKED
        // about the round that carries a search (round 1); round 2 legitimately has none.
        var consultedForSearchingRound = false
        val search = ToolSearchController { round ->
            if (round.outcome.toolSearches.isNotEmpty()) consultedForSearchingRound = true
            null
        }
        val fold = FoldController { round -> if (round.roundIndex == 0) continuationBody() else null }
        val rounds = ArrayDeque<suspend (WireSink) -> TurnOutcome>()
        rounds.add { _ ->
            TurnOutcome.Success(
                hasToolUse = false,
                incomplete = false,
                usage = Usage(reasoningTokens = 516),
                toolSearches = listOf(ToolSearchCall(ToolSearchCallId("ts_1"), "q", null, buildJsonObject { })),
            )
        }
        rounds.add { _ -> TurnOutcome.Success(hasToolUse = false, incomplete = false, usage = Usage(outputTokens = 1)) }
        FoldRunner(
            emitter = h.emitter,
            key = "t",
            log = { },
            postRound = { _, sink -> rounds.removeFirst().invoke(sink) },
            finish = { h.finish(it) },
            signals = h.signals(),
            toolSearch = search,
        ).run(continuationBody(), fold)

        assertEquals(
            false,
            consultedForSearchingRound,
            "fold continuation wins on round 1; the search gate never sees that round's outcome",
        )
        assertEquals(1, h.count("message_stop"))
    }
}
