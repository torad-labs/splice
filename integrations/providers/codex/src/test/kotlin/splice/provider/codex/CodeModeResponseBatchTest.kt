package splice.provider.codex

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.turn.TurnOutcome
import splice.core.util.JsonScalars
import splice.upstream.RoundResult

class CodeModeResponseBatchTest : CodeModeResponseBatchSupport() {
    @Test
    fun `two completed exec items preserve both raw calls before either native result`() = runTest {
        val host = Host(callback = false)
        val manager = bridge(host)
        val post = BatchPost(host)
        val outcome = manager.interceptor(
            turn(),
            disableParallel = false,
        ).intercept(BASE_REQUEST, RecordingSink(), post).turn()
        assertTrue(outcome is TurnOutcome.Success)
        assertNativeBatch(post.finalInput.await())
        manager.onHeadStop()
    }

    @Test
    fun `second sealed exec produces its output while the shared response terminal is still held`() = runTest {
        val host = Host(callback = false)
        val manager = bridge(host)
        val terminal = CompletableDeferred<Unit>()
        val post = BatchPost(host, terminal)
        val running = async {
            manager.interceptor(turn(), disableParallel = false).intercept(BASE_REQUEST, RecordingSink(), post).turn()
        }
        // The independent source reader uses a real dispatcher; a virtual deadline can fire before it is scheduled.
        val output = withContext(Dispatchers.Default) { withTimeout(5_000) { host.secondOutput.await() } }
        assertEquals("second-output", output)
        assertFalse(post.finalInput.isCompleted, "model request escaped before the response terminal")
        terminal.complete(Unit)
        assertTrue(running.await() is TurnOutcome.Success)
        assertNativeBatch(post.finalInput.await())
        manager.onHeadStop()
    }

    @Test
    fun `an unfinished earlier item cannot block the first completed exec callback`() = runTest {
        val host = Host(callback = true)
        val manager = bridge(host)
        val late = CompletableDeferred<Unit>()
        val post = BatchPost(host, lateItem = late)
        val sink = RecordingSink()
        val running = async {
            manager.interceptor(turn(), disableParallel = false).intercept(BASE_REQUEST, sink, post).turn()
        }
        val first = withContext(Dispatchers.Default) { withTimeout(5_000) { running.await() } }
        assertTrue(first is TurnOutcome.Success && first.hasToolUse)
        val id = sink.tools.single().id
        late.complete(Unit)
        post.secondSealed.await()
        val resumed = manager.interceptor(turn(id, "read-result"), disableParallel = false)
            .intercept(followup(id), RecordingSink(), post).turn()
        assertTrue(resumed is TurnOutcome.Success)
        assertNativeBatch(post.finalInput.await())
        manager.onHeadStop()
    }

    @Test
    fun `buffered reverse completion order wins even when both sources are whole before startup`() = runTest {
        val host = Host(callback = true)
        val manager = bridge(host)
        val released = CompletableDeferred(Unit)
        val post = BatchPost(host, lateItem = released, buffered = true)
        val sink = RecordingSink()
        val first = manager.interceptor(turn(), disableParallel = false).intercept(BASE_REQUEST, sink, post).turn()
        assertTrue(first is TurnOutcome.Success && first.hasToolUse)
        assertFalse(
            host.secondOutput.isCompleted,
            "later completed source ran ahead of the callback-blocked first item",
        )
        val id = sink.tools.single().id
        val resumed = manager.interceptor(turn(id, "read-result"), disableParallel = false)
            .intercept(followup(id), RecordingSink(), post).turn()
        assertTrue(resumed is TurnOutcome.Success)
        assertNativeBatch(post.finalInput.await())
        manager.onHeadStop()
    }

    @Test
    fun `both outer scripts own successive callback echoes and accepted results`() = runTest {
        val host = Host(callback = true, secondCallback = true)
        val manager = bridge(host)
        val post = BatchPost(host)
        val read = RecordingSink()
        manager.interceptor(turn(), disableParallel = false).intercept(BASE_REQUEST, read, post).turn()
        post.secondSealed.await()
        val readId = read.tools.single().id
        val edit = RecordingSink()
        val second = manager.interceptor(turn(readId, "read-result"), disableParallel = false)
            .intercept(followup(readId), edit, post).turn()
        assertTrue(second is TurnOutcome.Success && second.hasToolUse)
        assertFalse(post.finalInput.isCompleted)
        val editId = edit.tools.single().id
        val result = manager.interceptor(turn(editId, "edit-result"), disableParallel = false)
            .intercept(bothResults(readId, editId), RecordingSink(), post).turn()
        assertTrue(result is TurnOutcome.Success)
        val input = post.finalInput.await()
        assertNativeBatch(input)
        assertTrue(canonical(input)[2].toString().contains("read-result"))
        assertTrue(canonical(input)[3].toString().contains("edit-result"))
        assertFalse(logLines.any { "interrupted extra=STEERING" in it })
        manager.onHeadStop()
    }

    @Test
    fun `terminal only transport drains two exec items before posting another model request`() = runTest {
        val manager = bridge(Host(callback = false))
        val replies = ArrayDeque(listOf(batch(listOf(firstCall(), secondCall())), completedOutcome()))
        var input = JsonArray(emptyList())
        val outcome = manager.interceptor(
            turn(),
            disableParallel = false,
        ).intercept(BASE_REQUEST, RecordingSink()) { body ->
            input = Json.parseToJsonElement(body).jsonObject.getValue("input").jsonArray
            RoundResult.Outcome(replies.removeFirst())
        }.turn()
        assertTrue(outcome is TurnOutcome.Success)
        assertNativeBatch(input)
        manager.onHeadStop()
    }

    @Test
    fun `callback blocked first exec retains and later dispatches the second sealed source`() = runTest {
        val host = Host(callback = true)
        val manager = bridge(host)
        val post = BatchPost(host)
        val sink = RecordingSink()
        val first = manager.interceptor(turn(), disableParallel = false).intercept(BASE_REQUEST, sink, post).turn()
        assertTrue(first is TurnOutcome.Success && first.hasToolUse)
        post.secondSealed.await()
        val id = sink.tools.single().id
        val resumed = manager.interceptor(turn(id, "read-result"), disableParallel = false)
            .intercept(followup(id), RecordingSink(), post).turn()
        assertTrue(resumed is TurnOutcome.Success)
        assertNativeBatch(post.finalInput.await())
        assertFalse(logLines.any { "interrupted extra=STEERING" in it })
        manager.onHeadStop()
    }

    @Test
    fun `restart executes the durable unstarted sibling while the accepted first source stays lost`() = runTest {
        val host = Host(callback = true)
        val original = bridge(host)
        val post = BatchPost(host)
        val sink = RecordingSink()
        original.interceptor(turn(), disableParallel = false).intercept(BASE_REQUEST, sink, post).turn()
        post.secondSealed.await()
        val id = sink.tools.single().id
        original.onHeadStop()
        val restored = bridge(Host(callback = false, restored = true))
        var input = JsonArray(emptyList())
        val outcome = restored.interceptor(turn(id, "read-result"), disableParallel = false)
            .intercept(followup(id), RecordingSink()) { body ->
                input = Json.parseToJsonElement(body).jsonObject.getValue("input").jsonArray
                RoundResult.Outcome(completedOutcome())
            }.turn()
        assertTrue(outcome is TurnOutcome.Success)
        val items = canonical(input)
        assertEquals(listOf(firstCall().raw, secondCall().raw), items.take(2))
        assertTrue(items[2].toString().contains("source was not rerun"))
        assertTrue(items[3].toString().contains("second-output"))
        restored.onHeadStop()
    }

    private fun assertNativeBatch(input: JsonArray) {
        val items = canonical(input)
        assertEquals(listOf(firstCall().raw, secondCall().raw), items.take(2))
        assertEquals(listOf("batch-first", "batch-second"), items.drop(2).map { JsonScalars.str(it, "call_id") })
        assertTrue(items[2].toString().contains("first-output"))
        assertTrue(items[3].toString().contains("second-output"))
    }
}
