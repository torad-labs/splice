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

    @Test
    fun `a re-sent request carries only the reasoning of the attempt before it`() = runTest {
        val provider = heads.start()
        val carried = (1..3).map { attempt ->
            val built = provider.buildTurn(opening, false, "session")
            val reasoning = listOf("rs_${attempt}_a", "rs_${attempt}_b").mapIndexed { index, id ->
                DeliveryFrames.done(DeliveryFrames.reasoning(id), index)
            }
            val outcome = provider.streamTranslator(built.meta, signals).driveTurn(reasoning.asFlow(), DeliverySink())
            assertTrue(outcome is TurnOutcome.Failure)
            reasoningIds(built.requestBody.getValue("input").jsonArray.map { it.jsonObject })
        }
        assertEquals(listOf(emptyList(), listOf("rs_1_a", "rs_1_b"), listOf("rs_2_a", "rs_2_b")), carried)
        for (view in heads.thenRestarted(provider)) {
            val next = view.buildTurn(opening, false, "session").requestBody.getValue("input").jsonArray
            assertEquals(listOf("rs_3_a", "rs_3_b"), reasoningIds(next.map { it.jsonObject }))
        }
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
        provider.streamTranslator(sideBuilt.meta, signals)
            .driveTurn(listOf(DeliveryFrames.done(DeliveryFrames.reasoning("rs_side"))).asFlow(), DeliverySink())
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
