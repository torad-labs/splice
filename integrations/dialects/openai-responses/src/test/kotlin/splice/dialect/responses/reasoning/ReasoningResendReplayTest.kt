package splice.dialect.responses.reasoning

import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.parse.AnthropicParse
import splice.core.turn.TurnOutcome
import splice.core.util.JsonScalars
import splice.upstream.ReanchorRound
import splice.upstream.TurnSignals
import java.nio.file.Path

/** What a request carries upstream at the end of its history after earlier attempts at the same request.
 *  2026-11-09: one request re-sent across 37 attempts left 6,715 reasoning records at one position, each attempt
 *  streaming ~190 items and ending unfinished, and every later attempt carried all of them upstream. */
class ReasoningResendReplayTest(@param:TempDir private val state: Path) {
    private val signals = TurnSignals(watchdogFired = { null }, clientGone = { false })
    private val opening = AnthropicParse.parseAnthropicBody(
        """{"model":"gpt-5.6-sol","messages":[{"role":"user","content":"Check the answer."}]}""",
    )
    private val heads = DeliveryHeads(state)

    @AfterEach
    fun stopHeads() = heads.stopAll()

    /** 2026-11-09: until then each re-send still opened with the attempt before it (about 2,000 reasoning items after
     *  an hour-long cut attempt on Nov 8), and a restart replayed what the last unfinished attempt had stored. */
    @Test
    fun `a re-sent request carries no reasoning of an attempt that ended without its terminal, nor after a restart`() =
        runTest {
            val provider = heads.start()
            val carried = (1..3).map { attempt ->
                val built = provider.buildTurn(opening, false, "session")
                val reasoning = listOf("rs_${attempt}_a", "rs_${attempt}_b").mapIndexed { index, id ->
                    DeliveryFrames.done(DeliveryFrames.reasoning(id), index)
                }
                val outcome = provider.streamTranslator(built.meta, signals)
                    .driveTurn(reasoning.asFlow(), DeliverySink())
                assertTrue(outcome is TurnOutcome.Failure)
                reasoningIds(built.requestBody.getValue("input").jsonArray.map { it.jsonObject })
            }
            assertEquals(List(3) { emptyList<String>() }, carried)
            for (view in heads.thenRestarted(provider)) {
                val next = view.buildTurn(opening, false, "session").requestBody.getValue("input").jsonArray
                assertEquals(emptyList<String>(), reasoningIds(next.map { it.jsonObject }))
            }
        }

    @Test
    fun `a round cut after prose the client kept keeps the reasoning that led into it`() = runTest {
        val provider = heads.start()
        val built = provider.buildTurn(opening, false, "session")
        val sink = DeliverySink()
        val frames = listOf(DeliveryFrames.done(DeliveryFrames.reasoning("rs_led")), DeliveryFrames.delta("Partial", 1))
        val torn = provider.streamTranslator(built.meta, signals).driveTurn(frames.asFlow(), sink)
        assertTrue(torn is TurnOutcome.Failure)
        assertEquals("Partial", sink.text.toString())
        val followup = AnthropicParse.parseAnthropicBody(
            """{"model":"gpt-5.6-sol","messages":[{"role":"user","content":"Check the answer."},""" +
                """{"role":"assistant","content":"Partial"},{"role":"user","content":"Continue."}]}""",
        )
        for (view in heads.thenRestarted(provider)) {
            val next = view.buildTurn(followup, false, "session").requestBody.getValue("input").jsonArray
            assertEquals(listOf("rs_led"), reasoningIds(next.map { it.jsonObject }))
        }
    }

    @Test
    fun `a round that reaches its terminal after its client left keeps its reasoning`() = runTest {
        val provider = heads.start()
        val built = provider.buildTurn(opening, false, "session")
        val held = DeliveryFrames.reasoning("rs_held")
        val answer = DeliveryFrames.message("msg_held", "Held")
        val gone = TurnSignals(watchdogFired = { null }, clientGone = { true })
        val outcome = provider.streamTranslator(built.meta, gone).driveTurn(
            listOf(
                DeliveryFrames.done(held),
                DeliveryFrames.done(answer, 1),
                DeliveryFrames.terminal(held, answer),
            ).asFlow(),
            DeliverySink(),
        )
        assertTrue(outcome is TurnOutcome.Success, outcome.toString())
        val followup = AnthropicParse.parseAnthropicBody(
            """{"model":"gpt-5.6-sol","messages":[{"role":"user","content":"Check the answer."},""" +
                """{"role":"assistant","content":"Held"},{"role":"user","content":"Continue."}]}""",
        )
        for (view in heads.thenRestarted(provider)) {
            val next = view.buildTurn(followup, false, "session").requestBody.getValue("input").jsonArray
            assertEquals(listOf("rs_held"), reasoningIds(next.map { it.jsonObject }))
        }
    }

    /** Nov 8: a turn cut by the 60-minute socket limit with nothing but reasoning. Its re-anchor goes upstream carrying
     *  every cut round's reasoning and the marker after each, so the model carries on instead of starting over. */
    @Test
    fun `a reasoning-only round cut twice is carried on from both rounds' reasoning, not sent again as it was`() =
        runTest {
            val provider = heads.start()
            val built = provider.buildTurn(opening, false, "session")
            val policy = checkNotNull(provider.reanchorPolicy(built.meta))
            var body = built.requestBody
            listOf("rs_hour_1", "rs_hour_2").forEachIndexed { attempt, id ->
                val torn = provider.streamTranslator(built.meta, signals)
                    .driveTurn(listOf(DeliveryFrames.done(DeliveryFrames.reasoning(id))).asFlow(), DeliverySink())
                val failure = checkNotNull(torn as? TurnOutcome.Failure)
                body = checkNotNull(policy.continuationForFailure(ReanchorRound(body, failure, attempt)))
            }
            val added = body.getValue("input").jsonArray.drop(built.requestBody.getValue("input").jsonArray.size)
                .map { it.jsonObject }
            assertEquals(
                listOf("rs_hour_1", "commentary", "rs_hour_2", "commentary"),
                added.map { JsonScalars.str(it, "id") ?: JsonScalars.str(it, "phase").orEmpty() },
            )
        }

    @Test
    fun `a turn carried on after an unfinished round keeps every round's reasoning`() = runTest {
        val provider = heads.start()
        val built = provider.buildTurn(opening, false, "session")
        val sink = DeliverySink()
        val first = DeliveryFrames.reasoning("rs_cut")
        val torn = provider.streamTranslator(built.meta, signals)
            .driveTurn(listOf(DeliveryFrames.done(first)).asFlow(), sink)
        assertTrue(torn is TurnOutcome.Failure)
        val second = DeliveryFrames.reasoning("rs_after")
        val answer = DeliveryFrames.message("msg_answer", "Done")
        provider.streamTranslator(built.meta, signals).driveTurn(
            listOf(
                DeliveryFrames.done(second),
                DeliveryFrames.delta("Done", 1),
                DeliveryFrames.done(answer, 1),
                DeliveryFrames.terminal(second, answer),
            ).asFlow(),
            sink,
        )
        assertEquals("Done", sink.text.toString())
        val followup = AnthropicParse.parseAnthropicBody(
            """{"model":"gpt-5.6-sol","messages":[{"role":"user","content":"Check the answer."},""" +
                """{"role":"assistant","content":"Done"},{"role":"user","content":"Continue."}]}""",
        )
        for (view in heads.thenRestarted(provider)) {
            val next = view.buildTurn(followup, false, "session").requestBody.getValue("input").jsonArray
            assertEquals(listOf("rs_cut", "rs_after"), reasoningIds(next.map { it.jsonObject }))
        }
    }

    @Test
    fun `a request ending on kept output leaves the reasoning that followed that output`() = runTest {
        val provider = heads.start()
        val built = provider.buildTurn(opening, false, "session")
        val sink = DeliverySink()
        val hello = DeliveryFrames.message("msg_hello", "Hello world")
        val kept = DeliveryFrames.reasoning("rs_kept")
        val done = DeliveryFrames.message("msg_done", " Done.")
        provider.streamTranslator(built.meta, signals).driveTurn(
            listOf(
                DeliveryFrames.delta("Hello world"),
                DeliveryFrames.done(hello),
                DeliveryFrames.done(kept, 1),
                DeliveryFrames.delta(" Done.", 2),
                DeliveryFrames.done(done, 2),
                DeliveryFrames.terminal(hello, kept, done),
            ).asFlow(),
            sink,
        )
        assertEquals("Hello world Done.", sink.text.toString())
        // A request that ends on the first message of that output stands where rs_kept was stored.
        val side = AnthropicParse.parseAnthropicBody(
            """{"model":"gpt-5.6-sol","messages":[{"role":"user","content":"Check the answer."},""" +
                """{"role":"assistant","content":"Hello world"}]}""",
        )
        val sideBuilt = provider.buildTurn(side, false, "session")
        // The side request finishes: an attempt cut before its terminal would leave nothing at that position at all.
        val sideReasoning = DeliveryFrames.reasoning("rs_side")
        provider.streamTranslator(sideBuilt.meta, signals).driveTurn(
            listOf(DeliveryFrames.done(sideReasoning), DeliveryFrames.terminal(sideReasoning)).asFlow(),
            DeliverySink(),
        )
        val followup = AnthropicParse.parseAnthropicBody(
            """{"model":"gpt-5.6-sol","messages":[{"role":"user","content":"Check the answer."},""" +
                """{"role":"assistant","content":"Hello world Done."},{"role":"user","content":"Continue."}]}""",
        )
        for (view in heads.thenRestarted(provider)) {
            val next = view.buildTurn(followup, false, "session").requestBody.getValue("input").jsonArray
            // rs_side rides too: it was stored at the same position, which is how this request shows it got there.
            assertEquals(setOf("rs_kept", "rs_side"), reasoningIds(next.map { it.jsonObject }).toSet())
        }
    }

    private fun reasoningIds(items: List<JsonObject>): List<String> =
        items.filter { JsonScalars.str(it, "type") == "reasoning" }.mapNotNull { JsonScalars.str(it, "id") }
}
