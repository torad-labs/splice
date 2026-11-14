package splice.app.failure

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.app.provider.HeadReplies
import splice.head.scripted.ScriptedLogin
import splice.head.scripted.VirtualTime
import splice.head.scripted.reply
import splice.head.scripted.withScriptedHeads
import java.nio.file.Path
import java.time.Instant
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

/** What a waiting turn does when the command's logins change under it (Nov 12, 2026: eleven sessions waited three
 *  hours on a spent model, and signing Claude Code in to an account with room moved none of them). */
internal class LoginChangesDuringHoldTest : HoldRouteFixtures() {
    @Test
    fun `a login signed in to another account under the same name takes the waiting turn at once`(
        @TempDir tmp: Path,
    ) = runTest {
        withScriptedHeads(tmp, VirtualTime()) {
            provider.script(
                claudeFiveHour(Instant.ofEpochMilli(now()).plusSeconds(2.hours.inWholeSeconds)),
                reply { anthropicAnswer("on the other account") },
            )
            val head = pooledAnthropic(listOf("own"), replies = HeadReplies.claude)

            val stream = head.client.open(launched())
            eventually { stream.soFar().notices.isNotEmpty() }
            time.advanceBy(3.minutes)
            head.signInAs("own", "other")
            runClock(500.milliseconds, atMost = 10.minutes) { stream.finished }

            assertEquals("on the other account", stream.finish().text, stream.finish().describe())
            assertEquals(2, provider.requests.size)
            assertTrue(
                ScriptedLogin.of("other") in bearer(provider.requests[1]),
                provider.requests[1].headers.toString(),
            )
            assertTrue(provider.requests[1].at < 4.minutes, "at once: ${provider.requests[1].at}")
        }
    }

    @Test
    fun `a login asked again that is still at its limit for the model leaves the turn waiting`(
        @TempDir tmp: Path,
    ) = runTest {
        withScriptedHeads(tmp, VirtualTime()) {
            val sooner = Instant.ofEpochMilli(now()).plusSeconds(2.hours.inWholeSeconds)
            val later = Instant.ofEpochMilli(now()).plusSeconds(3.hours.inWholeSeconds)
            provider.script(
                claudeFiveHour(sooner),
                claudeFiveHour(later),
                claudeFiveHour(sooner),
                reply { anthropicAnswer("after the reset") },
            )
            val head = pooledAnthropic(listOf("first", "second"), replies = HeadReplies.claude)

            val stream = head.client.open(launched())
            eventually { stream.soFar().notices.isNotEmpty() }
            time.advanceBy(3.minutes)
            // Both refused, and the turn went back to wait on the one whose reset comes first.
            val asked = provider.requests.size
            head.askAgain("second")
            runClock(500.milliseconds, atMost = 30.minutes) { stream.finished }

            assertFalse(stream.finished, "still waiting half an hour on: ${stream.soFar().describe()}")
            assertEquals(asked, provider.requests.size, "neither login could take it, so neither was asked")
            runClock(1.minutes, atMost = 3.hours) { stream.finished }
            assertEquals("after the reset", stream.finish().text, stream.finish().describe())
        }
    }
}
