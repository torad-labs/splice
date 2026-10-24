package splice.provider.codex

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.turn.FailureCause
import splice.core.turn.FailurePhase
import splice.core.turn.TurnOutcome
import splice.core.turn.Usage
import splice.core.turn.UsageField
import splice.core.turn.noRequestUsage
import splice.core.util.JsonScalars
import splice.upstream.RoundResult

class CodeModeBatchBillingTest : CodeModeResponseBatchSupport() {
    @Test
    fun `one failed response has one durable usage owner after both sealed items and restart`() = runTest {
        verify(
            TurnOutcome.Failure(
                "synthetic source failure",
                cause = FailureCause.UPSTREAM_CONN_RESET,
                phase = FailurePhase.MID_OUTPUT,
                salvagedUsage = usage(),
            ),
        )
    }

    @Test
    fun `one abandoned response has one durable usage owner after both sealed items and restart`() = runTest {
        verify(TurnOutcome.ClientAbandoned(salvagedUsage = usage()))
    }

    @Test
    fun `a different final member recovers the unclaimed response owner bill after failure and restart`() = runTest {
        val host = Host(callback = false, secondCallback = true)
        val manager = bridge(host)
        val terminal = CompletableDeferred<Unit>()
        val ending = TurnOutcome.Failure("synthetic", cause = FailureCause.UPSTREAM_CONN_RESET, salvagedUsage = usage())
        val post = BatchPost(host, terminalGate = terminal, ending = ending)
        val sink = RecordingSink()
        manager.interceptor(turn(), disableParallel = false).intercept(BASE_REQUEST, sink, post).turn()
        val id = sink.tools.single().id
        val key = checkNotNull(JsonScalars.str(stateFiles.records().first(), "key"))
        val active = manager.registry.recordsFor(key).single { it.phase == CodeModePhase.ACTIVE }
        val staged = CompletableDeferred<Unit>()
        checkNotNull(manager.driver.streams.find(active)).beforeSettle = Runnable { staged.complete(Unit) }
        terminal.complete(Unit)
        staged.await()
        manager.onHeadStop()
        val restored = bridge(Host(callback = false, restored = true))
        var input = JsonArray(emptyList())
        val result = restored.interceptor(turn(id, "edit-result"), disableParallel = false)
            .intercept(followup(id, "Edit", "edit-result"), RecordingSink()) { body ->
                input = Json.parseToJsonElement(body).jsonObject.getValue("input").jsonArray
                RoundResult.Outcome(TurnOutcome.Success(false, false, noRequestUsage))
            }.turn()
        assertTrue(result is TurnOutcome.Success)
        assertEquals(7L, (result as? TurnOutcome.Success)?.usage?.outputTokens)
        val outputs = canonical(input).drop(2)
        assertTrue(outputs.first().toString().contains("first-output"))
        assertTrue(outputs.last().toString().contains("source was not rerun"))
        assertTrue(restored.registry.recordsFor(key).mapNotNull(restored.registry.source::consume).isEmpty())
        restored.onHeadStop()
    }

    private suspend fun verify(ending: TurnOutcome) {
        val host = Host(callback = false)
        val manager = bridge(host)
        val post = BatchPost(host, ending = ending)
        val outcome = manager.interceptor(
            turn(),
            disableParallel = false,
        ).intercept(BASE_REQUEST, RecordingSink(), post).turn()
        val billed = when (outcome) {
            is TurnOutcome.Failure -> outcome.salvagedUsage.outputTokens
            is TurnOutcome.ClientAbandoned -> outcome.salvagedUsage.outputTokens
            is TurnOutcome.Success -> error("source ending was replaced with success")
        }
        assertEquals(7L, billed)
        manager.onHeadStop()
        val saved = stateFiles.records()
        val receipts = saved.mapNotNull { (it["sourceState"] as? JsonObject)?.get("usage") as? JsonObject }
        assertEquals(listOf(7L), receipts.map { JsonScalars.long(it, "outputTokens") })
        val restored = bridge(Host(callback = false, restored = true))
        val key = checkNotNull(JsonScalars.str(saved.first(), "key"))
        val recovered = restored.registry.recordsFor(key).mapNotNull(restored.registry.source::consume)
        assertTrue(recovered.isEmpty(), "one response bill became independently consumable again after restart")
        restored.onHeadStop()
    }

    private fun usage(): Usage = Usage(outputTokens = 7, reported = setOf(UsageField.OUTPUT))
}
