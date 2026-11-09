package splice.app.failure

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.app.provider.HeadReplies
import splice.head.scripted.ClientRequest
import splice.head.scripted.Family
import splice.head.scripted.ScriptedLogin
import splice.head.scripted.reply
import splice.head.scripted.withScriptedHeads
import java.nio.file.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class PooledLimitsThroughHeadsTest {
    @Test
    fun `a temporary headerless throttle finishes the same turn on a healthy sibling`(@TempDir tmp: Path) = runTest {
        withScriptedHeads(tmp) {
            provider.script(
                reply { status(429); anthropicErrorBody("rate_limit_error", "temporary rate limit") },
                reply { anthropicAnswer("served on backup") },
            )
            val head = pooledAnthropic(listOf("primary", "backup"))

            val answer = head.client.send(request("recover this turn"))

            assertEquals("served on backup", answer.text, answer.describe())
            assertTrue(answer.completed, answer.describe())
            assertEquals(null, answer.error, answer.describe())
            assertEquals(
                listOf("primary", "backup").map { "Bearer ${ScriptedLogin.of(it)}" },
                provider.requests.map { it.headers["authorization"] },
            )
            assertEquals(Duration.ZERO, time.passed(), "a free sibling takes the turn without a cooldown wait")
        }
    }

    @Test
    fun `a spent billing cycle on one login does not stop a head with available quota`(@TempDir tmp: Path) = runTest {
        withScriptedHeads(tmp) {
            provider.script(
                reply {
                    status(429)
                    anthropicErrorBody("rate_limit_error", "Monthly usage limit reached for this billing cycle")
                },
                reply { anthropicAnswer("quota on the other login") },
            )
            val head = pooledAnthropic(listOf("spent", "available"), replies = HeadReplies.kimi)

            val answer = head.client.send(request("use the available quota"))

            assertEquals("quota on the other login", answer.text, answer.describe())
            assertTrue(answer.completed, answer.describe())
            assertEquals(Duration.ZERO, time.passed())
            assertEquals(
                listOf("spent", "available").map { "Bearer ${ScriptedLogin.of(it)}" },
                provider.requests.map { it.headers["authorization"] },
            )
        }
    }

    @Test
    fun `a head with one temporarily throttled login can recover after a short pause`(@TempDir tmp: Path) = runTest {
        withScriptedHeads(tmp) {
            provider.script(
                reply { status(429); anthropicErrorBody("rate_limit_error", "temporary rate limit") },
                reply { anthropicAnswer("recovered") },
            )
            val head = pooledAnthropic(listOf("only"))

            val answer = head.client.send(request("finish after the throttle"))

            assertEquals("recovered", answer.text, answer.describe())
            assertTrue(answer.completed, answer.describe())
            assertTrue(time.passed() in 1.seconds..30.seconds)
        }
    }

    @Test
    fun `when every login is temporarily throttled the same turn survives a shared minute-long pause`(
        @TempDir tmp: Path,
    ) = runTest {
        withScriptedHeads(tmp) {
            val throttled = List(8) {
                reply { status(429); anthropicErrorBody("rate_limit_error", "temporary rate limit") }
            }
            provider.script(*(throttled + reply { anthropicAnswer("shared throttle lifted") }).toTypedArray())
            val head = pooledAnthropic(listOf("primary", "backup"))

            val answer = head.client.send(request("survive the shared pause"))

            assertEquals("shared throttle lifted", answer.text, answer.describe())
            assertTrue(answer.completed, answer.describe())
            assertEquals(null, answer.error, answer.describe())
            assertTrue(time.passed() in 60.seconds..120.seconds, "quota remains usable after the shared short throttle")
            assertTrue(head.heldTurns().isEmpty())
        }
    }

    @Test
    fun `a persistent bare throttle ends retryably within two minutes`(@TempDir tmp: Path) = runTest {
        withScriptedHeads(tmp) {
            provider.script(reply { status(429); anthropicErrorBody("rate_limit_error", "temporary rate limit") })
            val head = pooledAnthropic(listOf("only"))

            val answer = head.client.send(request("bounded throttle"))

            assertTrue(Family.R in answer.families(), answer.describe())
            assertFalse(answer.completed, answer.describe())
            assertTrue(
                time.passed() in 60.seconds..120.seconds,
                "a bare throttle gets bounded time for shared recovery",
            )
            assertFalse(answer.error?.message.orEmpty().contains("usage limit"), answer.describe())
            assertTrue(head.heldTurns().isEmpty())
        }
    }

    private fun request(text: String) = ClientRequest(
        text = text,
        headers = mapOf("x-splice-client-wall-ms" to "1200000"),
    )
}
