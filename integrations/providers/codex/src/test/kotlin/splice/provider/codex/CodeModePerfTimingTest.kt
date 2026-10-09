// NEW: canonical duration and cancelled or completed queue waits belong to the actual serving turn.
package splice.provider.codex

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import splice.core.perf.PerfKeys
import splice.core.perf.TurnPerf
import splice.core.util.ElapsedClock
import splice.core.util.WallClock
import splice.provider.codex.stream.CodeModeUpstreamPost
import splice.upstream.InterceptedRoundPost
import splice.upstream.RoundBody
import splice.upstream.RoundResult

@OptIn(ExperimentalCoroutinesApi::class)
internal class CodeModePerfTimingTest : CodeModeBridgeTestSupport() {
    @Test
    fun `canonical elapsed time covers success and refusal without changing transport bytes`() = runTest {
        var ticks = 0L
        val perf = TurnPerf(clock = ElapsedClock { ticks++ * 7 }, wallClock = WallClock { 0 })
        val target = object : InterceptedRoundPost {
            override val perf: TurnPerf = perf
            override suspend fun invoke(bodyJson: String) = error("synthetic timing never posts")
        }
        val post = CodeModeUpstreamPost(target, CodexCodeModeWire(Json, {}))
        val body = CodeModeBody(RoundBody.Text("""{"input":[{"role":"user","content":"synthetic"}]}"""), Json)
        val result = post.canonicalize(body, emptyList(), emptyMap())
        assertNull(result.error)
        assertArrayEquals(body.round.bytes(), checkNotNull(result.body).round.bytes())
        assertEquals(7L, perf.snapshot().counters[PerfKeys.CODE_MODE_CANONICAL_MS])

        val invalid = CodeModeBody(RoundBody.Tree(JsonObject(emptyMap())), Json)
        assertNotNull(post.canonicalize(invalid, emptyList(), emptyMap()).error)
        assertEquals(14L, perf.snapshot().counters[PerfKeys.CODE_MODE_CANONICAL_MS])
    }

    @ParameterizedTest(name = "cancel queued turn: {0}")
    @ValueSource(booleans = [false, true])
    fun `conversation queue wait is recorded on its own turn even when cancelled`(cancelQueued: Boolean) = runTest {
        val manager = bridge(ScriptedRuntime(ArrayDeque()))
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val perf = TurnPerf(clock = ElapsedClock { testScheduler.currentTime }, wallClock = WallClock { 0 })
        val owner = async {
            manager.interceptor(turn(sessionId = "synthetic-perf-session"), disableParallel = false)
                .intercept(BASE_REQUEST, RecordingSink()) {
                    entered.complete(Unit)
                    release.await()
                    RoundResult.Outcome(completedOutcome())
                }
        }
        entered.await()
        val queued = async {
            val post = object : InterceptedRoundPost {
                override val perf: TurnPerf = perf
                override suspend fun invoke(bodyJson: String) = RoundResult.Outcome(completedOutcome())
            }
            manager.interceptor(turn(sessionId = "synthetic-perf-session"), disableParallel = false)
                .intercept(BASE_REQUEST, RecordingSink(), post)
        }
        runCurrent()
        assertFalse(queued.isCompleted)
        advanceTimeBy(17)
        if (cancelQueued) queued.cancelAndJoin()
        release.complete(Unit)
        owner.await()
        if (!cancelQueued) queued.await()
        assertEquals(17L, perf.snapshot().counters[PerfKeys.CODE_MODE_TURN_LOCK_WAIT_MS])
        manager.onHeadStop()
    }
}
