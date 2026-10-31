// NEW: failure-handling slice 5 (2026-10-27). A turn that meets a limit, an overload with a stated time or a spent
// account holds in the same request until the cause lifts, and continues by itself (final-design.md section 9).
// These routes use a real head
// of each API type in front of the scripted provider, on virtual time, asserting what the provider sees (how many
// requests, and when) and what the person and Claude Code read. Every expected number is the design's own: the
// stagger, the probe schedules, the seven days, the three missed resets and the 30 s margin before the wall.
package splice.app.failure

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.app.cli.status.StatusReadings
import splice.app.cli.status.StatusTable
import splice.app.provider.HeadReplies
import splice.core.terminal.CliPalette
import splice.core.terminal.ColorDepth
import splice.core.topology.AuthConfig
import splice.core.topology.ClaudeWrapperConfig
import splice.core.topology.Dialect
import splice.core.topology.HeadConfig
import splice.core.topology.ProviderConfig
import splice.core.topology.Topology
import splice.core.usage.QuotaSnapshot
import splice.core.usage.QuotaWindow
import splice.core.util.EnvReader
import splice.core.util.LocalTimeText
import splice.daemonclient.DaemonProbe
import splice.head.TurnsHead
import splice.head.TurnsHeadLookup
import splice.head.compact.CompactView
import splice.head.compact.HeadCompactSource
import splice.head.scripted.ClientAnswer
import splice.head.scripted.ClientRequest
import splice.head.scripted.Family
import splice.head.scripted.HeadSays
import splice.head.scripted.HeadShape
import splice.head.scripted.ProviderRequest
import splice.head.scripted.ScriptedHeads
import splice.head.scripted.ScriptedLogin
import splice.head.scripted.ScriptedReply
import splice.head.scripted.VirtualTime
import splice.head.scripted.reply
import splice.head.scripted.withScriptedHeads
import splice.head.turn.LiveTurnsByHead
import splice.head.turn.LiveTurnsRoutes
import splice.head.turn.LiveTurnsSource
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** The prefix every line a person reads opens with. */
private const val PREFIX = "[splice] "

/** The header a launched session names its own client timeout in, which a hold fits inside. */
private const val WALL_HEADER = "x-splice-client-wall-ms"

/** A topology naming the head status prints a held turn for. */
private val STATUS_TOPOLOGY = Topology(
    providers = mapOf(
        "chat" to ProviderConfig(
            dialect = Dialect.OPENAI_CHAT,
            baseUrl = "https://example.invalid",
            auth = AuthConfig(kind = "api-key", env = "TEST_CHAT_KEY"),
        ),
    ),
    heads = mapOf(
        "claude-chat" to HeadConfig(
            provider = "chat",
            port = 3199,
            discoveryPrefix = "chat--",
            pinnedModel = "m",
            claude = ClaudeWrapperConfig(command = "claude-chat"),
        ),
    ),
)

/** Where the scripted virtual wall clock starts (VirtualTime's default). */
private val START: Instant = Instant.parse("2026-10-26T12:00:00Z")

/** A session launched with slice 5's client timeout, longer than any hold: the whole-turn budget, 7 days, 60 s. */
private val LAUNCHED_WALL: Duration = 15.minutes + 7.days + 60.seconds

private const val PAYMENT_REQUIRED = 402
private const val TOO_MANY_REQUESTS = 429
private const val SERVICE_UNAVAILABLE = 503
private const val KEEPALIVES = 100

/** Kimi's 5-hour limit, verbatim from the 429 of Oct 23, 3:41:54 PM CT (audit-maps.md:1075). */
private const val KIMI_FIVE_HOUR =
    "You've reached your 5-hour usage limit. Your quota will reset when the current 5-hour window ends. " +
        "To continue now, purchase extra usage or upgrade your plan: " +
        "https://www.kimi.com/membership/subscription?tab=quota"

/** DeepSeek's answer for an account with no balance. */
private const val DEEPSEEK_NO_BALANCE = "Insufficient Balance"

/** The words Claude Code's compaction request opens its system prompt with (CompactClassifier's marker). */
private const val COMPACTION_SYSTEM = "You are tasked with summarizing conversations for another agent."

internal class HoldsThroughHeadsTest : HoldRouteFixtures() {
    @Test
    fun `a five-hour limit holds with one line and thirty-second pings, then continues two seconds after reset`(
        @TempDir tmp: Path,
    ) = runTest {
        withScriptedHeads(tmp, VirtualTime()) {
            val reset = START.plusSeconds(10.minutes.inWholeSeconds)
            provider.script(claudeFiveHour(reset), reply { anthropicAnswer("the answer after the reset") })
            val head = anthropic(replies = HeadReplies.claude)

            val stream = head.client.open(launched())
            eventually { stream.soFar().notices.isNotEmpty() }
            assertEquals(1, provider.requests.size, "the limit, and nothing more before the reset")
            runClock(2.seconds, atMost = 15.minutes) { stream.finished }
            val answer = stream.finish()

            assertEquals("the answer after the reset", answer.text, answer.describe())
            assertEquals(2, provider.requests.size, answer.describe())
            assertNear(10.minutes + 2.seconds, provider.requests[1].at, "the request goes 2 s after the reset")
            assertTrue(provider.requests.none { WALL_HEADER in it.headers }, "the session's wall stays with splice")
            val told = lines(answer)
            assertEquals(1, told.size, "one line for the hold: $told")
            assertTrue("5-hour" in told.single(), told.single())
            assertTrue((answer.noticeArrival("5-hour") ?: Duration.INFINITE) <= 1.seconds, "the line comes at once")
            val held = answer.arrivals.filter { it <= provider.requests[1].at }
            val longest = held.zipWithNext { a, b -> b - a }.maxOrNull() ?: Duration.ZERO
            assertTrue(longest <= 32.seconds, "a ping at least every 30 s while held, the longest gap $longest")
        }
    }

    @Test
    fun `Muse's soft limit is probed at 15 s and the answer streams as soon as Muse serves`(@TempDir tmp: Path) =
        runTest {
            withScriptedHeads(tmp) {
                provider.script(museLimited(START.plusSeconds(2.hours.inWholeSeconds)), museAnswer("served at 15 s"))
                val head = responses(replies = HeadReplies.muse)

                val answer = head.client.send(launched())

                assertEquals("served at 15 s", answer.text, answer.describe())
                assertEquals(listOf(Duration.ZERO, 15.seconds), provider.requests.map { it.at })
                assertTrue(lines(answer).single().contains("sooner if the limit lifts"), lines(answer).toString())
            }
        }

    @Test
    fun `two turns held on one soft limit send one probe, and both continue when it is served, a second apart`(
        @TempDir tmp: Path,
    ) = runTest {
        withScriptedHeads(tmp, VirtualTime()) {
            val limited = museLimited(START.plusSeconds(2.hours.inWholeSeconds))
            provider.script(limited, limited, museAnswer("first"), museAnswer("second"))
            val head = responses(replies = HeadReplies.muse)

            val first = head.client.open(launched())
            eventually { first.soFar().notices.isNotEmpty() }
            val second = head.client.open(launched())
            eventually { second.soFar().notices.isNotEmpty() }
            runClock(500.milliseconds, atMost = 1.minutes) { first.finished && second.finished }

            assertEquals(setOf("first", "second"), setOf(first.finish().text, second.finish().text))
            assertEquals(4, provider.requests.size, "two limits, one probe and the second turn's send")
            assertNear(15.seconds, provider.requests[2].at, "one probe, at 15 s")
            assertNear(1.seconds, provider.requests[3].at - provider.requests[2].at, "the second turn a second later")
        }
    }

    @Test
    fun `another turn's answer on the same login wakes a held turn`(@TempDir tmp: Path) = runTest {
        withScriptedHeads(tmp, VirtualTime()) {
            provider.script(
                limitedUntil(START.plusSeconds(3.hours.inWholeSeconds)),
                reply { chatAnswer("the other turn") },
                reply { chatAnswer("the held turn") },
            )
            val head = chat()

            val held = head.client.open(launched())
            eventually { held.soFar().notices.isNotEmpty() }
            val other = head.client.send(launched())
            runClock(500.milliseconds, atMost = 1.minutes) { held.finished }

            assertEquals("the other turn", other.text, other.describe())
            assertEquals("the held turn", held.finish().text, held.finish().describe())
            assertEquals(3, provider.requests.size)
            assertNear(1.seconds, provider.requests[2].at - provider.requests[1].at, "woken, a second after")
        }
    }

    @Test
    fun `a quota reading that turns allowed wakes a held turn`(@TempDir tmp: Path) = runTest {
        withScriptedHeads(tmp, VirtualTime()) {
            val reset = START.plusSeconds(2.hours.inWholeSeconds)
            provider.script(claudeFiveHour(reset), reply { anthropicAnswer("the limit lifted") })
            val head = anthropic(replies = HeadReplies.claude, tracksQuota = true)

            val stream = head.client.open(launched())
            eventually { stream.soFar().notices.isNotEmpty() }
            time.advanceBy(5.minutes)
            head.quotaReading(QuotaSnapshot(QuotaWindow(12.0, reset.epochSecond, 18_000L), updatedAt = now()))
            runClock(500.milliseconds, atMost = 10.minutes) { stream.finished }

            assertEquals("the limit lifted", stream.finish().text, stream.finish().describe())
            assertEquals(2, provider.requests.size)
            assertTrue(provider.requests[1].at < 6.minutes, "sent when the reading came: ${provider.requests[1].at}")
        }
    }

    @Test
    fun `a login added during a hold continues the turn on it at once`(@TempDir tmp: Path) = runTest {
        withScriptedHeads(tmp, VirtualTime()) {
            provider.script(
                claudeFiveHour(START.plusSeconds(2.hours.inWholeSeconds)),
                reply { anthropicAnswer("on the new login") },
            )
            val head = pooledAnthropic(listOf("first"), replies = HeadReplies.claude)

            val stream = head.client.open(launched())
            eventually { stream.soFar().notices.isNotEmpty() }
            time.advanceBy(3.minutes)
            head.addLogin("joined")
            runClock(500.milliseconds, atMost = 10.minutes) { stream.finished }

            assertEquals("on the new login", stream.finish().text, stream.finish().describe())
            assertEquals(2, provider.requests.size)
            assertTrue(
                ScriptedLogin.of("joined") in bearer(provider.requests[1]),
                provider.requests[1].headers.toString(),
            )
            assertTrue(provider.requests[1].at < 4.minutes, "at once: ${provider.requests[1].at}")
        }
    }

    @Test
    fun `a firm hold whose account is topped up before its reset continues at the next hourly probe`(
        @TempDir tmp: Path,
    ) = runTest {
        withScriptedHeads(tmp) {
            provider.script(limitedUntil(START.plusSeconds(3.hours.inWholeSeconds)), reply { chatAnswer("topped up") })
            val head = chat()

            val answer = head.client.send(launched())

            assertEquals("topped up", answer.text, answer.describe())
            assertEquals(listOf(Duration.ZERO, 1.hours), provider.requests.map { it.at })
        }
    }

    @Test
    fun `the console's try-now probes a held login at once, and the turn continues when it serves`(
        @TempDir tmp: Path,
    ) = runTest {
        withScriptedHeads(tmp, VirtualTime()) {
            provider.script(limitedUntil(START.plusSeconds(3.hours.inWholeSeconds)), reply { chatAnswer("tried now") })
            val head = chat()

            // The console's routes over this head's live turns: what /api/heads/chat/live and its try-now answer.
            val console = LiveTurnsRoutes(
                heads = TurnsHeadLookup { name ->
                    if (name == "chat") listOf(TurnsHead("chat", NoCompaction)) else listOf()
                },
                registry = LiveTurnsSource { LiveTurnsByHead().apply { put("chat", head.liveTurns) } },
            )

            val stream = head.client.open(launched())
            eventually { stream.soFar().notices.isNotEmpty() }
            time.advanceBy(7.minutes)
            assertTrue("\"held\":true" in console.live("chat").body, "the console lists the turn as held")
            assertEquals(HttpStatusCode.OK, console.tryNow("chat", null).status, "one login held")
            runClock(500.milliseconds, atMost = 20.minutes) { stream.finished }

            assertEquals("tried now", stream.finish().text, stream.finish().describe())
            assertEquals(2, provider.requests.size)
            assertNear(7.minutes, provider.requests[1].at, "probed when asked")
        }
    }

    @Test
    fun `Kimi's 5-hour 429 of Oct 23 holds at once after one provider request, and continues when a probe is served`(
        @TempDir tmp: Path,
    ) = runTest {
        withScriptedHeads(tmp) {
            provider.script(kimiFiveHour(), reply { anthropicAnswer("the window ended") })
            val kimi = anthropic(replies = HeadReplies.kimi)

            val answer = kimi.client.send(launched())

            assertEquals("the window ended", answer.text, answer.describe())
            // A named window with no time stated holds at once and is probed at 1 minute.
            assertEquals(listOf(Duration.ZERO, 1.minutes), provider.requests.map { it.at })
            assertTrue("5-hour" in lines(answer).single(), lines(answer).toString())
        }
    }

    @Test
    fun `DeepSeek's 402 holds the turn with one line naming DeepSeek and continues when a probe is served`(
        @TempDir tmp: Path,
    ) = runTest {
        withScriptedHeads(tmp) {
            provider.script(deepSeekNoBalance(), reply { anthropicAnswer("credit added") })
            val head = anthropic(says = HeadSays(providerName = "DeepSeek"))

            val answer = head.client.send(launched())

            assertEquals("credit added", answer.text, answer.describe())
            assertEquals(listOf(Duration.ZERO, 5.minutes), provider.requests.map { it.at })
            val told = lines(answer)
            assertEquals(1, told.size, told.toString())
            assertTrue("DeepSeek" in told.single() && "continues by itself" in told.single(), told.single())
        }
    }
}

internal class HoldBoundsThroughHeadsTest : HoldRouteFixtures() {
    @Test
    fun `a credit hold that reaches seven days ends with the credit line and a status Claude Code does not re-send`(
        @TempDir tmp: Path,
    ) = runTest(timeout = 3.minutes) {
        withScriptedHeads(tmp) {
            provider.script(deepSeekNoBalance())
            val head = anthropic(says = HeadSays(providerName = "DeepSeek"))

            val answer = head.client.send(launched())

            assertTrue(provider.requests.last().at <= 7.days, "${provider.requests.last().at}")
            assertTrue(provider.requests.last().at >= 7.days - 5.minutes, "${provider.requests.last().at}")
            assertTrue("still cannot pay after 7 days" in lineOf(answer), answer.describe())
            assertEquals(setOf(Family.P, Family.T, Family.H), answer.families(), answer.describe())
        }
    }

    @Test
    fun `closing the client during a hold ends it, and nothing more reaches the provider`(@TempDir tmp: Path) =
        runTest {
            withScriptedHeads(tmp, VirtualTime()) {
                provider.script(limitedUntil(START.plusSeconds(1.hours.inWholeSeconds)), reply { chatAnswer("x") })
                val head = chat()

                val stream = head.client.open(launched())
                eventually { stream.soFar().notices.isNotEmpty() }
                stream.hangUp()
                eventually { head.liveTurns.list().isEmpty() }
                runClock(1.minutes, atMost = 2.hours) { false }

                assertEquals(1, provider.requests.size, "nothing after the close")
            }
        }

    @Test
    fun `a held turn gives back its slot, so a new turn on a full head is admitted`(@TempDir tmp: Path) = runTest {
        withScriptedHeads(tmp, VirtualTime()) {
            provider.script(
                limitedUntil(START.plusSeconds(3.hours.inWholeSeconds)),
                reply { chatAnswer("admitted while the other held") },
                reply { chatAnswer("the held turn") },
            )
            val head = chat(HeadShape(maxInflight = 1, maxQueued = 1))

            val held = head.client.open(launched())
            eventually { held.soFar().notices.isNotEmpty() }
            val other = head.client.send(launched())
            runClock(500.milliseconds, atMost = 1.minutes) { held.finished }

            assertEquals("admitted while the other held", other.text, other.describe())
            assertEquals("the held turn", held.finish().text, held.finish().describe())
        }
    }

    @Test
    fun `a hold longer than the whole-turn budget does not end as a turn cap`(@TempDir tmp: Path) = runTest {
        withScriptedHeads(tmp) {
            provider.script(limitedUntil(START.plusSeconds(50.minutes.inWholeSeconds)), reply { chatAnswer("after") })
            val head = chat(HeadShape(progressCap = 10.minutes))

            val answer = head.client.send(launched())

            assertEquals("after", answer.text, answer.describe())
            assertEquals(listOf(Duration.ZERO, 50.minutes + 2.seconds), provider.requests.map { it.at })
        }
    }

    @Test
    fun `a limit that misses three stated resets ends with the outlasted-limit line`(
        @TempDir tmp: Path,
    ) = runTest {
        withScriptedHeads(tmp) {
            provider.script(
                limitedUntil(START.plusSeconds(1.hours.inWholeSeconds)),
                limitedUntil(START.plusSeconds(2.hours.inWholeSeconds)),
                limitedUntil(START.plusSeconds(3.hours.inWholeSeconds)),
                limitedUntil(START.plusSeconds(4.hours.inWholeSeconds)),
                reply { chatAnswer("not reached") },
            )
            val head = chat()

            val answer = head.client.send(launched())

            assertEquals(4, provider.requests.size, answer.describe())
            assertTrue("did not lift at the times it stated" in lineOf(answer), answer.describe())
            assertTrue(Family.H in answer.families(), answer.describe())
            assertFalse(Family.R in answer.families(), "never re-sent: ${answer.describe()}")
        }
    }

    @Test
    fun `an overload hold that outlasts its stated times ends with the overloaded line and overloaded_error`(
        @TempDir tmp: Path,
    ) = runTest {
        withScriptedHeads(tmp) {
            provider.script(overloadedFor(20.seconds))
            val head = chat()

            val answer = head.client.send(launched())

            // Every answer says 20 s, which the chat API states firmly, so the hold probes only hourly and never
            // before the time it waits for. It goes again 2 s past each time; the sends at 22, 44 and 66 s each meet
            // the cause again after the time it stated, and the third of them ends the turn.
            val sent = listOf(0, 22, 44, 66).map { it.seconds }
            assertEquals(sent, provider.requests.map { it.at }, answer.describe())
            assertTrue("stayed overloaded past the times it stated" in lineOf(answer), answer.describe())
            assertEquals("overloaded_error", answer.error?.type, answer.describe())
        }
    }

    @Test
    fun `a limit more than seven days away ends at once with its date and a status Claude Code does not re-send`(
        @TempDir tmp: Path,
    ) = runTest {
        withScriptedHeads(tmp) {
            provider.script(limitedUntil(START.plusSeconds(8.days.inWholeSeconds)))
            val head = chat()

            val answer = head.client.send(launched())

            assertEquals(1, provider.requests.size, answer.describe())
            assertEquals("false", answer.shouldRetry, answer.describe())
            assertTrue("longer than a turn can wait" in lineOf(answer), answer.describe())
            assertTrue("Nov 3" in lineOf(answer), "names the date: ${lineOf(answer)}")
        }
    }

    @Test
    fun `a single-login session without a wall header gets a 429 naming its true two-hour reset`(
        @TempDir tmp: Path,
    ) = runTest {
        withScriptedHeads(tmp) {
            val reset = START.plusSeconds(2.hours.inWholeSeconds)
            provider.script(limitedUntil(reset))
            val head = chat()

            val answer = head.client.send()

            assertEquals(1, provider.requests.size, answer.describe())
            assertEquals(TOO_MANY_REQUESTS, answer.status, answer.describe())
            assertNotEquals("false", answer.shouldRetry, answer.describe())
            assertEquals(2.hours.inWholeSeconds.toString(), answer.header("retry-after"), answer.describe())
            assertEquals(reset.epochSecond.toString(), answer.header("anthropic-ratelimit-unified-reset"))
            assertEquals(setOf(Family.L), answer.families(), answer.describe())
            assertTrue("Claude Code retries at that time" in lineOf(answer), answer.describe())
        }
    }

    @Test
    fun `a pooled session gets a thirty-second recheck and its next request uses the joined login`(
        @TempDir tmp: Path,
    ) = runTest {
        withScriptedHeads(tmp) {
            val reset = START.plusSeconds(2.hours.inWholeSeconds)
            provider.script(claudeFiveHour(reset), claudeFiveHour(reset), reply { anthropicAnswer("on the joined") })
            val head = pooledAnthropic(listOf("first", "second"), replies = HeadReplies.claude)
            val turn = ClientRequest(session = "sess-pooled-wall")

            val refused = head.client.send(turn)
            head.addLogin("joined")
            val again = head.client.send(turn)

            assertEquals(TOO_MANY_REQUESTS, refused.status, refused.describe())
            assertEquals("30", refused.header("retry-after"), refused.describe())
            assertEquals(reset.epochSecond.toString(), refused.header("anthropic-ratelimit-unified-5h-reset"))
            assertTrue("checks again in 30 seconds" in lineOf(refused), refused.describe())
            assertTrue("Oct 26, 9:00 AM" in lineOf(refused), "the true reset: ${lineOf(refused)}")
            assertEquals("on the joined", again.text, again.describe())
            assertTrue(ScriptedLogin.of("joined") in bearer(provider.requests.last()))
        }
    }

    @Test
    fun `an unstated hold ends before its session wall with overloaded_error and its retry holds again`(
        @TempDir tmp: Path,
    ) = runTest {
        withScriptedHeads(tmp) {
            provider.script(kimiFiveHour())
            val kimi = anthropic(replies = HeadReplies.kimi)
            val turn = ClientRequest(session = "sess-unstated-wall", headers = mapOf(WALL_HEADER to "960000"))

            val first = kimi.client.send(turn)
            val sentFirst = provider.requests.size
            val again = kimi.client.send(turn)

            assertEquals("overloaded_error", first.error?.type, first.describe())
            assertTrue(first.arrivals.last() <= 930.seconds, "ends 30 s before the wall: ${first.arrivals.last()}")
            assertTrue(lines(again).any { "without saying when it resets" in it }, "holds again: ${again.describe()}")
            assertEquals("overloaded_error", again.error?.type, "not P: ${again.describe()}")
            assertTrue(provider.requests.size > sentFirst + 1, "the re-send probed again")
        }
    }

    @Test
    fun `a compaction at a stated five-hour limit gets a 429 and sends nothing at the reset`(
        @TempDir tmp: Path,
    ) = runTest {
        withScriptedHeads(tmp, VirtualTime()) {
            val reset = START.plusSeconds(2.hours.inWholeSeconds)
            provider.script(claudeFiveHour(reset), reply { anthropicAnswer("not reached") })
            val head = anthropic(replies = HeadReplies.claude)

            val stream = head.client.open(launched(compaction()))
            eventually { stream.finished }
            runClock(5.minutes, atMost = 3.hours) { false }
            val answer = stream.finish()

            assertEquals(TOO_MANY_REQUESTS, answer.status, answer.describe())
            assertEquals(1, provider.requests.size, "nothing at the reset")
        }
    }

    @Test
    fun `a compaction held within its timer whose client closes ends the hold, and nothing more reaches the provider`(
        @TempDir tmp: Path,
    ) = runTest {
        withScriptedHeads(tmp, VirtualTime()) {
            provider.script(kimiFiveHour(), reply { anthropicAnswer("not reached") })
            val kimi = anthropic(replies = HeadReplies.kimi)

            val stream = kimi.client.open(launched(compaction()))
            eventually { stream.soFar().notices.isNotEmpty() }
            stream.hangUp()
            eventually { kimi.liveTurns.list().isEmpty() }
            runClock(1.minutes, atMost = 2.hours) { false }

            assertEquals(1, provider.requests.size, "nothing after the close")
        }
    }

    @Test
    fun `a wait that reaches 10 s commits and shows its line when it starts`(@TempDir tmp: Path) = runTest {
        withScriptedHeads(tmp, VirtualTime()) {
            val overloaded = reply {
                status(SERVICE_UNAVAILABLE)
                openAiErrorBody("server_error", "The server is overloaded.", "overloaded")
            }
            provider.script(overloaded, overloaded, overloaded, overloaded, reply { chatAnswer("after the waits") })
            val head = chat()

            val stream = head.client.open(launched())
            runClock(1.seconds, atMost = 2.minutes) { stream.finished }
            val answer = stream.finish()

            // The burst waits 2, 4 and 8 s in silence; the fourth overload opens the 15 s wait, whose line commits the
            // stream as it is written, 14 s in, and reaches the client then rather than with the answer.
            assertEquals("after the waits", answer.text, answer.describe())
            val told = answer.noticeArrival("15 seconds") ?: Duration.INFINITE
            assertNear(provider.requests[3].at, told, "the line arrives when its wait starts")
            assertTrue(told < provider.requests[4].at, "and before the wait it names ends: $told")
        }
    }

    @Test
    fun `a provider sending only keepalive comments holds the turn until the session's wall, which it ends 30 s before`(
        @TempDir tmp: Path,
    ) = runTest {
        // On a clock the test steps, so the head reads each keepalive as it comes, 20 s apart, as a slow provider's.
        withScriptedHeads(tmp, VirtualTime()) {
            provider.script(reply { repeat(KEEPALIVES) { keepalive(); delay(20.seconds) } })
            val head = chat(HeadShape(progressCap = 5.minutes))
            val turn = ClientRequest(headers = mapOf(WALL_HEADER to "960000"))

            val stream = head.client.open(turn)
            runClock(5.seconds, atMost = 16.minutes) { stream.finished }
            assertTrue(stream.finished, "the live provider is held until the session wall")
            val answer = stream.finish()

            assertTrue(
                "before this session's time limit" in lineOf(answer),
                "${lineOf(answer)} at ${answer.arrivals.last()}",
            )
            assertEquals("", answer.text, "transport traffic cannot produce a model answer")
            assertFalse(answer.events.any { it.name == "message_stop" }, "a progress timeout cannot finish cleanly")
            val ended = answer.arrivals.last()
            assertTrue(ended >= 930.seconds && ended < 960.seconds, "ended 30 s before the 960 s wall, at $ended")
        }
    }

    @Test
    fun `status shows a held turn with its reset and its share of the heap`(@TempDir tmp: Path) = runTest {
        withScriptedHeads(tmp, VirtualTime()) {
            val reset = START.plusSeconds(3.hours.inWholeSeconds)
            provider.script(limitedUntil(reset), reply { chatAnswer("x") })
            val head = chat()

            val stream = head.client.open(launched())
            eventually { stream.soFar().notices.isNotEmpty() }

            val held = head.heldTurns().single()
            assertEquals(reset.epochSecond, held.untilEpochSeconds)
            assertTrue(held.heapBytes > 0, "its request's share of the heap: ${held.heapBytes}")
            // What `splice status` prints from /health's word for this head.
            val health = DaemonProbe.parseHealth(
                """{"version":"x","heldTurns":{"claude-chat":[{"untilEpochSeconds":${reset.epochSecond},""" +
                    """"login":"${held.login}","cause":"${held.cause}","heapBytes":${held.heapBytes}}]}}""",
            )
            val times = LocalTimeText(ZoneId.of("America/Chicago"))
            val row = StatusTable(CliPalette(ColorDepth.NONE), times = times).lines(
                STATUS_TOPOLOGY,
                EnvReader { null },
                StatusReadings(held = health.held),
            )[1]
            assertTrue("1 turn held until ${times.at(reset.epochSecond)}" in row, row)
            assertTrue(" MB kept" in row, row)
            stream.hangUp()
        }
    }
}

/** Provider replies shared by the hold routes. */
internal abstract class HoldReplyFixtures {

    /** Claude's 429 on a spent 5-hour window: the unified family names the window and its reset. */
    protected fun claudeFiveHour(resetsAt: Instant): ScriptedReply = reply {
        status(TOO_MANY_REQUESTS)
        header("anthropic-ratelimit-unified-status", "rejected")
        header("anthropic-ratelimit-unified-representative-claim", "five_hour")
        header("anthropic-ratelimit-unified-reset", resetsAt.epochSecond.toString())
        anthropicErrorBody("rate_limit_error", "This request would exceed your account's rate limit.")
    }

    protected fun kimiFiveHour(): ScriptedReply = reply {
        status(TOO_MANY_REQUESTS)
        anthropicErrorBody("rate_limit_error", KIMI_FIVE_HOUR)
    }

    protected fun deepSeekNoBalance(): ScriptedReply = reply {
        status(PAYMENT_REQUIRED)
        anthropicErrorBody("invalid_request_error", DEEPSEEK_NO_BALANCE)
    }

    /** Muse's 429, whose body states a soft reset in epoch seconds. */
    protected fun museLimited(resetsAt: Instant): ScriptedReply = reply {
        status(TOO_MANY_REQUESTS)
        body(
            """{"error":{"message":"Usage window spent.","type":"rate_limit_error",""" +
                """"resets_at":${resetsAt.epochSecond}}}""",
        )
    }

    protected fun museAnswer(text: String): ScriptedReply = reply { responsesAnswer(text) }

    /** A 429 whose Retry-After names [resetsAt] in its date form (RFC 7231), which every reader reads. */
    protected fun limitedUntil(resetsAt: Instant): ScriptedReply = reply {
        status(TOO_MANY_REQUESTS)
        header("Retry-After", DateTimeFormatter.RFC_1123_DATE_TIME.format(resetsAt.atOffset(ZoneOffset.UTC)))
        openAiErrorBody("rate_limit_error", "Rate limit reached for this plan.", "rate_limit_exceeded")
    }

    protected fun overloadedFor(wait: Duration): ScriptedReply = reply {
        status(SERVICE_UNAVAILABLE)
        header("Retry-After", wait.inWholeSeconds.toString())
        openAiErrorBody("server_error", "The server is overloaded.", "overloaded")
    }
}

/** The launched client and virtual-clock controls shared by the hold routes. */
internal abstract class HoldRouteFixtures : HoldReplyFixtures() {

    /** A request from a session launched with slice 5's client timeout. */
    protected fun launched(request: ClientRequest = ClientRequest()): ClientRequest =
        request.copy(headers = request.headers + (WALL_HEADER to LAUNCHED_WALL.inWholeMilliseconds.toString()))

    /** Claude Code's compaction request. */
    protected fun compaction(): ClientRequest =
        ClientRequest(extra = buildJsonObject { put("system", COMPACTION_SYSTEM) })

    protected fun ScriptedHeads.now(): Long = START.toEpochMilli() + time.passed().inWholeMilliseconds

    /** Moves the virtual clock to the next time something waits for, at most [step] at a time, once the head has
     *  settled after the last move, until [done] or [atMost]. A send a wake starts reaches the provider at the time
     *  it was due, not a step later. */
    protected suspend fun ScriptedHeads.runClock(step: Duration, atMost: Duration, done: () -> Boolean) =
        withContext(Dispatchers.Default) {
            while (!done() && time.passed() < atMost) {
                settle()
                val now = time.passed()
                val next = minOf(time.nextDue() ?: (now + step), now + step, atMost)
                time.advanceBy((next - now).coerceAtLeast(Duration.ZERO))
            }
        }

    /** Waits in real time until the head has gone quiet: no wait parked or woken and no request sent for a few polls. */
    private suspend fun ScriptedHeads.settle() {
        var seen = time.parkedNow() to provider.requests.size
        var quiet = 0
        while (quiet < SETTLE_POLLS) {
            delay(POLL_MS)
            val now = time.parkedNow() to provider.requests.size
            if (now == seen) quiet++ else quiet = 0
            seen = now
        }
    }

    /** Waits in real time, a few seconds at most, for [check]. */
    protected suspend fun eventually(check: () -> Boolean) = withContext(Dispatchers.Default) {
        withTimeout(EVENTUALLY_MS) {
            while (!check()) delay(POLL_MS)
        }
    }

    protected fun assertNear(expected: Duration, actual: Duration, what: String) {
        assertTrue(actual >= expected && actual <= expected + 1.seconds, "$what: expected $expected, got $actual")
    }

    protected fun bearer(request: ProviderRequest): String =
        request.headers["authorization"] ?: request.headers["x-api-key"].orEmpty()

    /** The lines splice wrote into the answer's notice blocks. */
    protected fun lines(answer: ClientAnswer): List<String> = answer.notices.filter { it.startsWith(PREFIX) }

    /** The line the client was given: an in-band error event's message, or the error body of a status. */
    protected fun lineOf(answer: ClientAnswer): String = answer.error?.message.orEmpty()

    /** A head whose compactions the console reads nothing of. */
    protected object NoCompaction : HeadCompactSource {
        override fun summary(tailN: Int): CompactView = CompactView(0, emptyMap(), emptyList())
    }

    private companion object {
        const val SETTLE_POLLS = 5
        const val POLL_MS = 2L
        const val EVENTUALLY_MS = 10_000L
    }
}
