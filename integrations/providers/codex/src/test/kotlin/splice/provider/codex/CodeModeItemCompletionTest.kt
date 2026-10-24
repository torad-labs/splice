package splice.provider.codex

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import splice.core.turn.TurnOutcome
import splice.core.util.JsonScalars
import splice.provider.codex.history.CodeModeScriptFold
import splice.provider.codex.history.CodeModeScriptPlacements
import splice.upstream.codemode.CodeModeResult
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.time.Duration.Companion.seconds

class CodeModeItemCompletionTest : CodeModeItemCompletionSupport() {
    @ParameterizedTest
    @ValueSource(strings = ["", " plus later prose"])
    fun `hidden reasoning precedes joined prefaces and later prose once in completed callback history`(extra: String) {
        val fixture = PrefaceReplayFixture(extra)
        fixture.inputs.forEach { input ->
            val folded = CodeModeScriptFold(
                fixture.codec,
                mapOf("client-read" to fixture.record),
                emptyList(),
                CodeModeScriptPlacements(fixture.codec),
            ).fold(input)
            assertEquals(
                JsonArray(fixture.expected),
                JsonArray(folded),
                "hidden and legacy replay keep the same ordered input",
            )
        }
    }

    @Test
    @Timeout(25)
    fun `restart after sealed source evaluated before its first callback never repeats its effects`() =
        runTest(timeout = 20.seconds) {
            val effects = tempDir.resolve("effects")
            val runtime = EffectRuntime(effects, CompletableDeferred())
            val manager = bridge(runtime)
            val post = ItemPost()
            val sink = StepSink()
            val running = backgroundScope.async {
                manager.interceptor(turn(), disableParallel = false).intercept(BASE_REQUEST, sink, post)
            }
            try {
                post.itemRelease.complete(Unit)
                runtime.evaluated.await()
                assertFalse(sink.callback.isCompleted)
                val crashDir = tempDir.resolve("crash-state")
                Files.walk(stateFiles.dir).use { paths ->
                    paths.forEach { source ->
                        val target = crashDir.resolve(stateFiles.dir.relativize(source))
                        if (Files.isDirectory(source)) Files.createDirectories(target) else Files.copy(source, target)
                    }
                }
                val restored = CodexCodeModeBridge(
                    CodeModeBridgeConfig(
                        { EffectRuntime(effects) },
                        CodeModeStateLocation(crashDir, tempDir.resolve("unused-legacy.json")),
                        clock = Clock.fixed(Instant.ofEpochMilli(1_000), ZoneOffset.UTC),
                    ),
                )
                try {
                    val recoveredSink = StepSink()
                    val recovered = restored.interceptor(turn(), disableParallel = false)
                        .intercept(BASE_REQUEST, recoveredSink, post).turn()
                    assertTrue(recovered is TurnOutcome.Success)
                    assertFalse(recoveredSink.callback.isCompleted, "lost process state never reissues the script")
                    val continuation = post.continuation.await()
                    assertTrue(continuation.contains("source was not rerun"))
                    assertTrue(continuation.contains("reason-before-item"))
                    assertEquals(listOf("executed"), Files.readAllLines(effects))
                } finally {
                    restored.onHeadStop()
                }
            } finally {
                manager.onHeadStop()
                running.cancelAndJoin()
            }
        }

    @Test
    @Timeout(25)
    fun `whole item exposes sequential callbacks before response completion and bills the eventual terminal once`() =
        runTest(timeout = 20.seconds) {
            val effects = tempDir.resolve("effects")
            val manager = bridge(EffectRuntime(effects))
            val post = ItemPost()
            val firstSink = StepSink()
            try {
                val first = async {
                    manager.interceptor(turn(), disableParallel = false).intercept(BASE_REQUEST, firstSink, post).turn()
                }
                post.prefixSent.await()
                assertFalse(firstSink.callback.isCompleted)
                assertFalse(Files.exists(effects), "partial source cannot execute")
                post.itemRelease.complete(Unit)
                assertTrue(first.await() is TurnOutcome.Success)
                val read = firstSink.callback.await()
                assertFalse(post.stopped.isCompleted, "the callback reaches the client while its reader is live")
                val state = stateFiles.records().single().getValue("sourceState").jsonObject
                assertEquals("true", JsonScalars.str(state["itemComplete"]))
                assertFalse(JsonScalars.str(state["complete"]) == "true")
                assertTrue(state["usage"] == null || state["usage"] == JsonNull, "item completion has no terminal bill")

                val retried = StepSink()
                manager.interceptor(turn(), disableParallel = false).intercept(BASE_REQUEST, retried, post)
                assertSameTool(read, retried.callback.await())

                val secondSink = StepSink()
                manager.interceptor(turn(read.id, "result-0"), disableParallel = false)
                    .intercept(history(listOf(read)), secondSink, post)
                val edit = secondSink.callback.await()
                assertEquals("Edit", edit.name)
                assertFalse(post.stopped.isCompleted, "a dependent callback does not wait for response completion")
                val results = listOf(CodeModeResult(read.id, "result-0"), CodeModeResult(edit.id, "result-1"))
                val finishing = async {
                    manager.interceptor(turn(results = results), disableParallel = false)
                        .intercept(history(listOf(read, edit)), StepSink(), post).turn()
                }
                post.terminalRelease.complete(Unit)
                val outcome = finishing.await() as TurnOutcome.Success
                assertEquals(7L, outcome.usage.outputTokens)
                assertEquals(listOf("executed"), Files.readAllLines(effects))
                val continuation = post.continuation.await()
                assertTrue(continuation.contains("saved-result:result-1"))
                assertTrue(
                    continuation.contains("reason-before-item"),
                    "server-owned reasoning survives without a carrier",
                )
                assertTrue(continuation.contains("model preface"))
            } finally {
                manager.onHeadStop()
            }
        }

    @ParameterizedTest
    @ValueSource(strings = ["io", "failure", "death", "cancelled", "identity", "incomplete"])
    @Timeout(25)
    fun `late reader failure preserves completed effects and callbacks and retries continue from saved output`(
        ending: String,
    ) = runTest(timeout = 20.seconds) {
        val effects = tempDir.resolve("effects")
        val manager = bridge(EffectRuntime(effects))
        val post = ItemPost(ending)
        val firstSink = StepSink()
        try {
            post.itemRelease.complete(Unit)
            manager.interceptor(turn(), disableParallel = false).intercept(BASE_REQUEST, firstSink, post)
            val read = firstSink.callback.await()
            post.terminalRelease.complete(Unit)
            post.stopped.await()

            val secondSink = StepSink()
            manager.interceptor(turn(read.id, "result-0"), disableParallel = false)
                .intercept(history(listOf(read)), secondSink, post)
            val edit = secondSink.callback.await()
            assertEquals("Edit", edit.name, "the sealed script can still accept results after its reader ended")
            val results = listOf(CodeModeResult(read.id, "result-0"), CodeModeResult(edit.id, "result-1"))
            val request = history(listOf(read, edit))
            val outcome = manager.interceptor(turn(results = results), disableParallel = false)
                .intercept(request, StepSink(), post).turn()
            if (ending == "incomplete") {
                assertTrue((outcome as TurnOutcome.Success).incomplete)
            } else {
                assertTrue(
                    outcome is TurnOutcome.Failure,
                    "a failed response cannot produce a clean terminal: $outcome",
                )
            }
            assertFalse(post.continuation.isCompleted, "the failed turn retains its honest terminal")
            assertEquals(listOf("executed"), Files.readAllLines(effects))

            manager.onHeadStop()
            assertRestoredOutput(effects, results, request, post)
        } finally {
            manager.onHeadStop()
        }
    }

    private suspend fun assertRestoredOutput(
        effects: Path,
        results: List<CodeModeResult>,
        request: String,
        post: ItemPost,
    ) {
        assertTrue(
            stateFiles.records().single().toString().contains("reason-before-item"),
            "the source reasoning remains in the durable record before reconstruction",
        )
        val restored = bridge(EffectRuntime(effects))
        try {
            val recovered = restored.interceptor(turn(results = results), disableParallel = false)
                .intercept(request, StepSink(), post).turn()
            assertTrue(recovered is TurnOutcome.Success)
            val continuation = post.continuation.await()
            assertTrue(continuation.contains("saved-result:result-1"))
            assertTrue(continuation.contains("reason-before-item"))
            assertEquals(
                listOf("executed"),
                Files.readAllLines(effects),
                "recovery cannot repeat the script's effects",
            )
        } finally {
            restored.onHeadStop()
        }
    }

    private fun assertSameTool(expected: SeenTool, actual: SeenTool) {
        assertEquals(expected.id, actual.id, "reconnect replays the durable callback id")
        assertEquals(expected.name, actual.name, "reconnect replays the callback tool name")
        assertEquals(expected.args.toString(), actual.args.toString(), "reconnect replays the callback arguments")
    }

    private inner class PrefaceReplayFixture(extra: String) {
        val codec = CodexCodeModeHistoryCodec(Json)
        private val before = element("""{"role":"user","content":"before"}""")
        private val after = element("""{"role":"user","content":"after"}""")
        private val prefaces = listOf("A", "B").map(::commentary)
        private val reasoning = listOf("source-before", "source-middle").map { id ->
            element("""{"type":"reasoning","id":"$id","encrypted_content":"synthetic-$id"}""")
        }
        private val joined = commentary("AB$extra")
        private val remainder = if (extra.isEmpty()) emptyList() else listOf(commentary(extra))
        private val callback = element(
            """{"type":"function_call","call_id":"client-read","name":"Read","arguments":"{}"}""",
        )
        private val result = element(
            """{"type":"function_call_output","call_id":"client-read","output":"A"}""",
        )
        val record = CodeModeRecords.of("joined-preface", 0).also {
            val call = outer(callId = it.origin.outerCallId)
            it.phase = CodeModePhase.COMPLETED
            it.origin.outer = call.raw
            it.origin.source = call.input
            it.progress.output = "saved output"
            it.carry.continuity = prefaces
            it.carry.replay = reasoning.mapIndexed { index, item -> CodeModeNativeSegment(index, listOf(item)) }
            it.accepted.accept(mapOf("client-read" to CodeModeResult("client-read", "A")), emptyMap())
        }
        val expected = listOf(before, reasoning[0], prefaces[0], reasoning[1], prefaces[1]) + remainder +
            listOf(CodeModeCallReplay.item(record), codec.customOutput(record), after)
        val inputs = buildList {
            add(listOf(before, joined, callback, result, after))
            add(listOf(before, reasoning[0], joined, reasoning[1], callback, result, after))
            add(listOf(before, reasoning[0], prefaces[0], reasoning[1], commentary("B$extra"), callback, result, after))
            if (extra.isEmpty()) {
                add(listOf(before, callback, result, after))
                add(listOf(before, reasoning[0], reasoning[1], callback, result, after))
            }
        }

        private fun element(text: String): JsonElement = Json.parseToJsonElement(text)

        private fun commentary(text: String): JsonElement =
            element("""{"role":"assistant","phase":"commentary","content":"$text"}""")
    }
}
