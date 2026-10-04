package splice.provider.codex

import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import splice.core.turn.GatewayCustomCall
import splice.core.turn.TurnOutcome
import splice.core.turn.Usage
import splice.core.util.JsonScalars
import splice.provider.codex.stream.CodeModeSourceBuffer
import splice.upstream.RedirectableRoundPost
import splice.upstream.codemode.CodeModeCall
import splice.upstream.codemode.CodeModeCell
import splice.upstream.codemode.CodeModeResult
import splice.upstream.codemode.CodeModeRuntime
import splice.upstream.codemode.CodeModeSource
import splice.upstream.codemode.CodeModeSourcePart
import splice.upstream.codemode.CodeModeStep
import splice.upstream.sse.CustomToolSource
import splice.upstream.sse.WireSink

class CodeModeSourceBufferedCertificationTest : CodeModeStatementStreamSupport() {
    @ParameterizedTest
    @ValueSource(strings = ["incomplete", "stop-on-malformed"])
    @Timeout(20)
    fun `already buffered suffix waits and stopped terminal failure releases the waiter`(ending: String) = runBlocking {
        val runtime = BufferedRuntime()
        val manager = bridge(runtime)
        val sink = StepSink()
        val source = "await tools.Read({}); await tools.Edit({});"
        val call = outer(source = source)
        val post = BufferedPost(sink, sink.callback, call)
        if (ending == "stop-on-malformed") post.stopAtMalformedTerminal = manager
        try {
            manager.interceptor(turn(), disableParallel = false).intercept(BASE_REQUEST, sink, post)
            val first = sink.callback.await()
            withTimeout(5_000) { post.itemDone.await() }
            val buffer = buffer(manager)
            val next = StepSink()
            val callback = async {
                manager.interceptor(turn(first.id, "result-0"), disableParallel = false)
                    .intercept(history(listOf(first)), next, post)
            }
            try {
                withTimeout(5_000) {
                    runtime.secondAdvance.await()
                    while (!next.callback.isCompleted && !hasWaiter(buffer)) kotlinx.coroutines.yield()
                }
                assertEquals(1, runtime.reads, "the worker already holds Edit and must not request more source")
                assertFalse(next.callback.isCompleted, "certification, not a cursor read, must block buffered Edit")
                post.terminal.complete(Unit)
                val outcome = withTimeout(5_000) { callback.await() }
                assertTrue(outcome is TurnOutcome.Failure, outcome.toString())
                assertFalse(next.callback.isCompleted, "a rejected or stopped terminal never publishes buffered Edit")
                assertEquals(1, runtime.starts)
            } finally {
                post.terminal.complete(Unit)
                callback.cancelAndJoin()
            }
        } finally {
            post.terminal.complete(Unit)
            manager.onHeadStop()
        }
    }

    private fun buffer(manager: CodexCodeModeBridge): CodeModeSourceBuffer {
        val registry = manager.javaClass.getDeclaredField("registry").apply { isAccessible = true }
            .get(manager) as CodexCodeModeRegistry
        val driver = manager.javaClass.getDeclaredField("driver").apply { isAccessible = true }
            .get(manager) as CodexCodeModeDriver
        val key = checkNotNull(JsonScalars.str(stateFiles.records().single()["key"]))
        return checkNotNull(driver.streams.find(registry.recordsFor(key).single())).source
    }

    private fun hasWaiter(buffer: CodeModeSourceBuffer): Boolean {
        val state = buffer.javaClass.getDeclaredField("state").apply { isAccessible = true }
            .get(buffer) as MutableStateFlow<*>
        return state.subscriptionCount.value > 0
    }
}

private class BufferedPost(
    private val initial: WireSink,
    private val firstIssued: Deferred<*>,
    private val call: GatewayCustomCall,
) : RedirectableRoundPost {
    val itemDone = kotlinx.coroutines.CompletableDeferred<Unit>()
    val terminal = kotlinx.coroutines.CompletableDeferred<Unit>()
    var stopAtMalformedTerminal: CodexCodeModeBridge? = null

    override suspend fun invoke(bodyJson: String): TurnOutcome = into(bodyJson, initial)

    override suspend fun into(bodyJson: String, sink: WireSink): TurnOutcome {
        sink.customToolSource(CustomToolSource.Started(call.copy(input = "")))
        sink.customToolSource(CustomToolSource.Delta(call.callId, call.input))
        firstIssued.await()
        sink.customToolSource(CustomToolSource.Completed(call))
        itemDone.complete(Unit)
        terminal.await()
        val stopper = stopAtMalformedTerminal
        val calls = if (stopper == null) {
            listOf(call)
        } else {
            object : AbstractList<GatewayCustomCall>() {
                override val size = 1
                override fun get(index: Int): GatewayCustomCall {
                    stopper.onHeadStop()
                    return call.copy(name = "changed-exec")
                }
            }
        }
        return TurnOutcome.Success(false, stopper == null, Usage(), customCalls = calls)
    }
}

private class BufferedRuntime : CodeModeRuntime {
    var starts = 0
    var reads = 0
    val secondAdvance = kotlinx.coroutines.CompletableDeferred<Unit>()

    override suspend fun start(
        source: String,
        tools: Set<String>,
        descriptions: Map<String, String>,
    ): CodeModeCell = error("streaming source required")

    override suspend fun startStreaming(
        source: CodeModeSource,
        tools: Set<String>,
        descriptions: Map<String, String>,
    ): CodeModeCell {
        starts++
        reads++
        val first = source.read()
        check(first is CodeModeSourcePart.Delta && "Read" in first.text && "Edit" in first.text)
        return object : CodeModeCell {
            private var advances = 0
            override suspend fun advance(results: List<CodeModeResult>): CodeModeStep {
                val name = if (advances++ == 0) {
                    "Read"
                } else {
                    secondAdvance.complete(Unit)
                    "Edit"
                }
                return CodeModeStep.Calls(
                    listOf(CodeModeCall(name, name, JsonObject(emptyMap()))),
                )
            }
            override fun close() = Unit
        }
    }

    override fun close() = Unit
}
