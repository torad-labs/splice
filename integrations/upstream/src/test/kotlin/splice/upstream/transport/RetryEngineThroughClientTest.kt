// NEW: failure-handling slice 4. RetryEngineModelTest's reference model of section 8's plan table (PlanModel), driven
// through UpstreamClient over a MockEngine. Generated sequences of every failure a post can meet (an answer its head's
// reader reads, a path that does not answer, a stream torn before the client saw a frame), with generated token
// exchanges, on one login and in a pool, under walls of every size. What the client did, its sends, the waits it asked
// for, its token exchanges and how the post ended, must be what the model's steps make an executor do. Every expected
// value comes from the model, never from a run of the client.
package splice.upstream.transport

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.auth.AuthDescription
import splice.core.auth.Credentials
import splice.core.auth.RefreshableAuthProvider
import splice.core.failure.FailureCause
import splice.core.failure.Moment
import splice.core.failure.Position
import splice.core.failure.Tear
import splice.core.failure.Unreach
import splice.core.util.ElapsedClock
import splice.core.util.WallClock
import splice.upstream.RoundBody
import splice.upstream.StreamRead
import splice.upstream.Waiter
import splice.upstream.failure.FailedEvent
import splice.upstream.failure.FailedReply
import splice.upstream.failure.Replies
import splice.upstream.failure.ReplyReader
import splice.upstream.retry.Exchange
import splice.upstream.retry.Failures
import splice.upstream.retry.How
import splice.upstream.retry.Met
import splice.upstream.retry.PlanModel
import splice.upstream.retry.RateLimitCooldown
import splice.upstream.retry.RetryLedger
import splice.upstream.retry.RetryStep
import splice.upstream.retry.TurnFacts
import java.net.ConnectException
import java.net.SocketException
import java.time.Instant
import kotlin.random.Random

/** The instant RetryEngineModelTest's failures state their lifts against, which the client reads them by too. */
private val NOW_MS = Instant.parse("2026-10-27T07:00:00Z").toEpochMilli()
private const val SEQUENCES = 600
private const val LONGEST = 30
private const val PLANNED = "planned-"

/** Where the client asks every failure from: no frame reached the client and no stream of the provider began. */
private val BEFORE_CLIENT = Position(Moment.BEFORE_COMMIT, streamBegan = false)

/** How a post ended, as its caller sees it: a transport error is thrown, every other ending is a value. */
private enum class Ending { DELIVERED, ENDED, REFUSED, THROWN, WAIT_SPENT }

/** What a post did: its sends, the waits it asked for, its token exchanges, and how it ended. */
private data class Outcome(val sends: Int, val waits: List<Long>, val exchanges: Int, val ending: Ending)

/** One post: the failure each send meets in order (the send after the last is answered), whether each token exchange
 *  renews the login, whether the login is one of a pool, and the post's wall. */
private data class Scenario(
    val planned: List<FailureCause>,
    val renews: List<Boolean>,
    val pooled: Boolean,
    val wallMs: Long,
) {
    val facts = TurnFacts(
        pooled = pooled,
        freeLogin = pooled,
        warmWaitLeftMs = 0L,
        continuesReasoning = false,
        canContinue = false,
        wallLeftMs = wallMs,
        earliestLift = null,
    )

    override fun toString(): String = "pooled=$pooled wall=$wallMs renews=$renews planned=$planned"
}

class RetryEngineThroughClientTest {
    @Test
    fun `the client takes the steps the plan table names for every failure a post can meet`() = runTest {
        val random = Random(SEED)
        val endings = mutableSetOf<Ending>()
        repeat(SEQUENCES) { n ->
            val scenario = draw(random)
            val expected = ModelRun(scenario).outcome()
            endings += expected.ending

            assertEquals(expected, drive(scenario), "sequence $n: $scenario")
        }
        // The draw reaches every way a post ends, so no ending goes untested.
        assertTrue(endings.containsAll(Ending.entries - Ending.WAIT_SPENT), "endings reached: $endings")
    }

    private fun draw(random: Random): Scenario {
        val failures = Failures(random)
        fun cause(): FailureCause = when (random.nextInt(SHARES)) {
            0 -> FailureCause.Path.Unreachable(Unreach.REFUSED, local = false)
            1 -> FailureCause.Path.Torn(Tear.NO_EVENTS, toolOpen = false)
            else -> generateSequence { failures.cause() }.first { it is FailureCause.Provider }
        }
        val first = cause()
        val planned = List(random.nextInt(1, LONGEST)) { if (random.nextInt(4) == 0) cause() else first }
        val walls = listOf(Long.MAX_VALUE, 3_600_000L, 20_000L, 1_000L)
        return Scenario(
            planned = planned,
            renews = List(planned.size) { random.nextBoolean() },
            pooled = random.nextBoolean(),
            wallMs = walls[random.nextInt(walls.size)],
        )
    }

    /** The scenario through a real client: each send meets its planned failure, then the provider answers. */
    private suspend fun drive(scenario: Scenario): Outcome {
        var sends = 0
        var meets: FailureCause? = null
        val engine = MockEngine {
            val reply = scenario.planned.getOrNull(sends)
            meets = reply
            sends += 1
            answer(reply, sends - 1)
        }
        val waits = mutableListOf<Long>()
        var exchanges = 0
        val auth = object : RefreshableAuthProvider {
            override suspend fun credentials(): Credentials = Credentials.Bearer("token")
            override suspend fun refresh(): Credentials? =
                scenario.renews[exchanges++].takeIf { it }?.let { Credentials.Bearer("token-$exchanges") }
            override suspend fun describe(): AuthDescription = AuthDescription(true, "planned", emptyMap())
        }
        val client = UpstreamClient(
            totalTimeoutMs = scenario.wallMs,
            client = HttpClient(engine),
            pacing = RetryPacing(waiter = Waiter { waits += it }, wall = WallClock { NOW_MS }),
            clock = ElapsedClock { 0L },
        )
        val ctx = PostContext(
            url = "https://api.example.test/v1",
            auth = auth,
            extraHeaders = { emptyMap() },
            replies = Replies(PlannedReader(scenario.planned)),
            recovery = PostRecovery(clientFrameEmitted = { false }),
            limits = PostLimits(rateLimitCooldown = RateLimitCooldown(ElapsedClock { 0L }).takeIf { scenario.pooled }),
        )
        val ending = try {
            endingOf(
                client.post(ctx, RoundBody.Text("{}")) {
                    if (meets is FailureCause.Path.Torn) {
                        StreamRead.Torn(
                            SocketException("torn"),
                        )
                    } else {
                        StreamRead.Read(1)
                    }
                },
            )
        } catch (_: ConnectException) {
            Ending.THROWN
        }
        return Outcome(sends, waits, exchanges, ending)
    }

    /** What the provider answers the send that meets [reply], the [nth] of the scenario: a path that does not answer
     *  throws where the client sends, a tear answers 200 and tears in the reader, and any other failure is a 500 the
     *  reader reads as the planned cause. */
    private fun MockRequestHandleScope.answer(reply: FailureCause?, nth: Int): HttpResponseData = when {
        reply == null -> respond("answer", HttpStatusCode.OK)
        reply is FailureCause.Path.Unreachable -> throw ConnectException("Connection refused")
        reply is FailureCause.Path.Torn -> respond("", HttpStatusCode.OK)
        else -> respond("$PLANNED$nth", HttpStatusCode.InternalServerError)
    }

    /** How the post ended, as its caller reads it. */
    private fun endingOf(posted: UpstreamPost<Int>): Ending = when (posted) {
        is UpstreamPost.Delivered -> Ending.DELIVERED
        is UpstreamPost.Ended -> Ending.ENDED
        is UpstreamPost.Refused -> Ending.REFUSED
        UpstreamPost.TurnWaitExhausted -> Ending.WAIT_SPENT
    }

    /** The head's reader of the planned answers: the cause each was planned as. */
    private class PlannedReader(private val planned: List<FailureCause>) : ReplyReader {
        override fun http(reply: FailedReply): FailureCause.Provider =
            planned[reply.body.removePrefix(PLANNED).toInt()] as FailureCause.Provider

        override fun event(event: FailedEvent): FailureCause.Provider = error("a post reads no in-band event")
    }

    /** The scenario as the model's steps make an executor run it: an Again sends again after its wait, a renewal makes
     *  one token exchange first and sends again only once the login is renewed, and any other step ends the post. */
    private class ModelRun(private val scenario: Scenario) {
        private val ledger = RetryLedger()
        private val waits = mutableListOf<Long>()
        private var exchanges = 0
        private var sends = 0

        fun outcome(): Outcome {
            send()
            for (cause in scenario.planned) {
                val ending = meet(cause)
                if (ending != null) return Outcome(sends, waits, exchanges, ending)
                send()
            }
            return Outcome(sends, waits, exchanges, Ending.DELIVERED)
        }

        private fun send() {
            ledger.sent()
            sends += 1
        }

        /** The step the table names for [cause], taken: null when the request goes out again. */
        private fun meet(cause: FailureCause): Ending? {
            val step = renewed(cause) ?: return null
            if (step is RetryStep.Again && step.how == How.SAME) {
                take(step, cause)
                return null
            }
            return when {
                cause is FailureCause.Path.Unreachable -> Ending.THROWN
                step == RetryStep.SwitchLogin -> Ending.REFUSED
                else -> Ending.ENDED
            }
        }

        /** The step the table names once every renewal it asks for is made, each one token exchange; null once the
         *  login renewed, which sends the request again on no attempt of its own. */
        private fun renewed(cause: FailureCause): RetryStep? {
            var step = PlanModel.next(Met(cause, BEFORE_CLIENT, scenario.facts), ledger)
            while (step is RetryStep.Again && step.how == How.REFRESH_LOGIN) {
                take(step, cause)
                val renews = scenario.renews[exchanges++]
                ledger.exchanged(if (renews) Exchange.RENEWED else Exchange.REFUSED)
                if (renews) return null
                step = PlanModel.next(Met(cause, BEFORE_CLIENT, scenario.facts), ledger)
            }
            return step
        }

        private fun take(step: RetryStep.Again, cause: FailureCause) {
            ledger.took(step, cause)
            if (step.afterMs > 0L) waits += step.afterMs
        }
    }

    private companion object {
        const val SEED = 27

        /** One draw in [SHARES] is a path that does not answer, one a tear, and the rest an answer read. */
        const val SHARES = 6
    }
}
