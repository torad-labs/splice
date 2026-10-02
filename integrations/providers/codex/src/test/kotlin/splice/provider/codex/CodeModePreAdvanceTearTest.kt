// NEW: an ACTIVE owner can lose its source before resume or cell lookup without becoming invalid input.
package splice.provider.codex

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import splice.core.turn.FailureCause
import splice.core.turn.TurnOutcome

class CodeModePreAdvanceTearTest : CodeModeStatementStreamSupport() {
    @ParameterizedTest
    @ValueSource(strings = ["before-resume", "before-cell"])
    @Timeout(20)
    fun `a selected ACTIVE record keeps its tear verdict when the cell disappears`(window: String) = runBlocking {
        val runtime = IncrementalRuntime()
        val manager = bridge(runtime)
        val initial = StepSink()
        val post = GatedPost(initial)
        try {
            manager.interceptor(turn(), disableParallel = false).intercept(BASE_REQUEST, initial, post)
            val first = initial.callback.await()
            val registry = managerField(manager, "registry") as CodexCodeModeRegistry
            val resume = managerField(manager, "resume") as CodexCodeModeResume
            val key = stateFiles.records().single().getValue("key").jsonPrimitive.content
            val picked = CompletableDeferred<CodeModeRecord>()
            val release = CompletableDeferred<Unit>()
            val next = StepSink()
            val continuation = turn(first.id, "result-0")
            val tools = object : Set<String> by continuation.tools {
                override fun contains(element: String): Boolean {
                    if (window == "before-cell" && element == "Read") {
                        runBlocking { tear(post, picked.await()) }
                    }
                    return element in continuation.tools
                }
            }
            val request = async {
                val record = checkNotNull(registry.owner(key, "continuation", setOf(first.id), emptySet()))
                assertEquals(CodeModePhase.ACTIVE, record.phase)
                picked.complete(record)
                release.await()
                val context = CodeModeRunContext(
                    continuation.copy(tools = tools),
                    false,
                    key,
                    "continuation",
                    next,
                    post,
                )
                resume.active(record, context, history(listOf(first)))
            }
            val record = picked.await()
            if (window == "before-resume") tear(post, record)
            release.complete(Unit)
            val outcome = withTimeout(5_000) { request.await() } as TurnOutcome.Failure
            assertEquals(FailureCause.UPSTREAM_CONN_RESET, outcome.cause)
            assertFalse(outcome.deterministic)
            assertNull(outcome.partial)
            assertFalse(next.callback.isCompleted)
            assertEquals(1, runtime.starts)
            assertEquals(1, post.posts)
        } finally {
            manager.onHeadStop()
        }
    }

    private suspend fun tear(post: GatedPost, record: CodeModeRecord) {
        post.tearAfterFirst = true
        post.gates[1].complete(Unit)
        withTimeout(5_000) { while (record.phase != CodeModePhase.LOST) yield() }
        assertTrue(post.stopped.isCompleted)
    }

    private fun managerField(manager: CodexCodeModeBridge, name: String): Any =
        CodexCodeModeBridge::class.java.getDeclaredField(name).apply { isAccessible = true }.get(manager)
}
