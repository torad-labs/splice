package splice.upstream.transport

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import splice.core.failure.FailureCause
import splice.core.failure.FailureEvidence
import splice.core.failure.Lift
import splice.core.failure.Moment
import splice.core.failure.Position
import splice.core.perf.TurnPerf
import splice.core.perf.observation.PerfEventKind
import splice.core.perf.observation.PerfIdentity
import splice.core.perf.observation.PerfObservation
import splice.core.perf.observation.PerfObserver
import splice.core.perf.observation.PerfRecorder
import splice.core.perf.observation.PerfScope
import splice.core.turn.TurnOutcome
import splice.core.util.ElapsedClock
import splice.core.util.WallClock
import splice.upstream.Waiter
import splice.upstream.failure.Replies
import splice.upstream.testing.OpenAiTestReplies

class UpstreamAttemptOutcomeTest {
    @ParameterizedTest
    @CsvSource("failure,upstream_failed", "client_abandoned,client_abort", "success,ok")
    fun `HTTP 200 attempt metrics reflect the translated result`(ending: String, expected: String) = runTest {
        val events = mutableListOf<PerfObservation>()
        val clock = ElapsedClock { 0L }
        val recorder = PerfRecorder(
            PerfIdentity("attempt-test", "test", "1", "config"),
            PerfObserver { events.add(it) },
            clock,
            WallClock { 60_000L },
        )
        val request = recorder.request(PerfScope(head = "test"), 0L, 60_000L)
        val perf = TurnPerf(observations = request, clock = clock)
        val translated: Any = when (ending) {
            "failure" -> TurnOutcome.Failure(
                FailureCause.Provider.Limited(Lift.Unstated, window = null),
                Position(Moment.MID_CONTENT, streamBegan = true),
                FailureEvidence("translated provider event", status = 200),
            )
            "client_abandoned" -> TurnOutcome.ClientAbandoned()
            else -> "successful payload"
        }
        HttpClient(MockEngine { respond("{}", HttpStatusCode.OK) }).use { http ->
            val client = UpstreamClient(5_000, client = http, pacing = RetryPacing(waiter = Waiter {}))
            val result = client.posted(
                PostContext(
                    url = "https://api.example.test/v1",
                    auth = fakeAuth,
                    extraHeaders = { emptyMap() },
                    replies = Replies(OpenAiTestReplies),
                    observers = PostObservers(perf = perf),
                ),
                "{}",
            ) { translated }
            assertSame(translated, result)
        }
        assertEquals(expected, events.single { it.kind == PerfEventKind.ATTEMPT_FINISHED }.outcome)
    }
}
