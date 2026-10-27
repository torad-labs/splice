// NEW: failure-handling slice 4, the answer of Oct 27, 2:52 PM CT. Anthropic told claude-splice "We've updated our
// Consumer Terms and Privacy Policy. You'll need to accept them in claude.ai with the email in /status to continue."
// with a 400, splice read it as a request the provider rejected, and 210 turns ended in one minute (daemon.log). The
// answer is about the account, not the request. These routes hold what it must do on a Claude head: stop using the
// login that met it until its owner acts, move the turn to another login, and, with no login left, end with a line that
// names the login and the step its owner takes.
package splice.app.failure

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.app.provider.HeadReplies
import splice.head.scripted.ClientAnswer
import splice.head.scripted.ClientRequest
import splice.head.scripted.HeadSays
import splice.head.scripted.ProviderRequest
import splice.head.scripted.ScriptedLogin
import splice.head.scripted.ScriptedReply
import splice.head.scripted.reply
import splice.head.scripted.withScriptedHeads
import java.nio.file.Path
import kotlin.time.Duration.Companion.hours

/** Anthropic's answer, verbatim. */
private const val UPDATED_TERMS =
    "We've updated our Consumer Terms and Privacy Policy. You'll need to accept them in claude.ai with the email " +
        "in /status to continue."

private const val BAD_REQUEST = 400

/** Two logins of one Claude command, named so a line that names one cannot be mistaken for any other word. */
private const val KESTREL = "kestrel"
private const val OSPREY = "osprey"

/** The prefix every line a person reads opens with. */
private const val PREFIX = "[splice] "

/** Who Claude's heads name as the provider a person acts with, as the daemon's Anthropic heads do. */
private val CLAUDE = HeadSays(providerName = "Anthropic")

class AccountBlockThroughHeadsTest {
    @Test
    fun `a login whose account must accept new terms hands the turn to the next login, and later turns skip it`(
        @TempDir tmp: Path,
    ) = runTest {
        withScriptedHeads(tmp) {
            provider.script(
                termsNotAccepted(),
                reply { anthropicAnswer("answered on the next login") },
                reply { anthropicAnswer("a later turn") },
            )
            val head = pooledAnthropic(listOf(KESTREL, OSPREY), HeadReplies.claude, CLAUDE)

            val first = head.client.send()
            val later = head.client.send()

            assertEquals("answered on the next login", first.text, first.describe())
            assertEquals("a later turn", later.text, later.describe())
            // The blocked login met the answer once, before the client heard anything, and the turn after it, a
            // session of its own, never started on it.
            assertEquals(listOf(KESTREL, OSPREY, OSPREY), provider.requests.map(::login))
        }
    }

    @Test
    fun `with no other login the turn ends naming the login and the terms its owner accepts in claude_ai`(
        @TempDir tmp: Path,
    ) = runTest {
        withScriptedHeads(tmp) {
            provider.script(termsNotAccepted(), reply { anthropicAnswer("not reached") })
            val head = pooledAnthropic(listOf(KESTREL), HeadReplies.claude, CLAUDE)

            val answer = head.client.send()

            assertEquals(1, provider.requests.size, "the same answer awaits every re-send: ${answer.describe()}")
            assertNamesTheStep(answer, KESTREL)
            assertEquals(BAD_REQUEST, answer.status, answer.describe())
            assertEquals("false", answer.shouldRetry, "nothing changes until the owner acts: ${answer.describe()}")
        }
    }

    @Test
    fun `a later turn with no other login asks the blocked one once and is told the step while its owner waits`(
        @TempDir tmp: Path,
    ) = runTest {
        withScriptedHeads(tmp) {
            provider.script(termsNotAccepted())
            val head = pooledAnthropic(listOf(KESTREL), HeadReplies.claude, CLAUDE)
            head.client.send()

            val later = head.client.send()

            // Only the provider can say the owner acted, so a turn no other login can take asks once more, and once.
            assertEquals(listOf(KESTREL, KESTREL), provider.requests.map(::login), later.describe())
            assertNamesTheStep(later, KESTREL)
            assertEquals("false", later.shouldRetry, later.describe())
        }
    }

    @Test
    fun `the turn after its owner accepted the terms is served on the only login`(@TempDir tmp: Path) = runTest {
        withScriptedHeads(tmp) {
            provider.script(termsNotAccepted(), reply { anthropicAnswer("served once the terms were accepted") })
            val head = pooledAnthropic(listOf(KESTREL), HeadReplies.claude, CLAUDE)
            head.client.send()

            val later = head.client.send(ClientRequest(text = "After the owner accepted."))

            assertEquals("served once the terms were accepted", later.text, later.describe())
            assertEquals(listOf(KESTREL, KESTREL), provider.requests.map(::login))
        }
    }

    @Test
    fun `a turn that arrives while the blocked login is being asked is told the step without reaching the provider`(
        @TempDir tmp: Path,
    ) = runTest {
        withScriptedHeads(tmp) {
            provider.script(
                termsNotAccepted(),
                reply {
                    hold()
                    status(BAD_REQUEST)
                    anthropicErrorBody("invalid_request_error", UPDATED_TERMS)
                },
                // What a turn that reached the provider while the login was being asked would be answered.
                reply { anthropicAnswer("reached the provider") },
            )
            val head = pooledAnthropic(listOf(KESTREL), HeadReplies.claude, CLAUDE)
            head.client.send()
            val asking = head.client.open()
            provider.awaitRequests(2)

            val meanwhile = head.client.send()
            provider.release()
            val asked = asking.finish()

            assertEquals(2, provider.requests.size, "one turn asks at a time: ${meanwhile.describe()}")
            assertNamesTheStep(meanwhile, KESTREL)
            assertEquals("false", meanwhile.shouldRetry, meanwhile.describe())
            assertNamesTheStep(asked, KESTREL)
        }
    }

    @Test
    fun `once its owner accepted the terms the login serves turns again`(@TempDir tmp: Path) = runTest {
        withScriptedHeads(tmp) {
            provider.script(
                termsNotAccepted(),
                reply { anthropicAnswer("answered on the next login") },
                reply { anthropicAnswer("back on the first login") },
            )
            val head = pooledAnthropic(listOf(KESTREL, OSPREY), HeadReplies.claude, CLAUDE)
            head.client.send()

            // The owner accepts the terms in claude.ai; splice learns it the only way it can, by asking again later.
            time.advanceBy(1.hours)
            val later = head.client.send(ClientRequest(text = "After the owner accepted."))

            assertEquals("back on the first login", later.text, later.describe())
            assertEquals(listOf(KESTREL, OSPREY, KESTREL), provider.requests.map(::login))
        }
    }

    @Test
    fun `a Claude head on its one login ends naming its account and the terms its owner accepts`(@TempDir tmp: Path) =
        runTest {
            withScriptedHeads(tmp) {
                provider.script(termsNotAccepted(), reply { anthropicAnswer("not reached") })
                val head = anthropic(replies = HeadReplies.claude, says = CLAUDE)

                val answer = head.client.send()

                assertEquals(1, provider.requests.size, answer.describe())
                assertNamesTheStep(answer, "account")
                assertEquals("false", answer.shouldRetry, answer.describe())
            }
        }

    /** Anthropic's 400 telling a Claude account to accept its updated terms. */
    private fun termsNotAccepted(): ScriptedReply = reply {
        status(BAD_REQUEST)
        anthropicErrorBody("invalid_request_error", UPDATED_TERMS)
    }

    /** Which login [request] was signed with. */
    private fun login(request: ProviderRequest): String? =
        request.headers["authorization"]?.removePrefix("Bearer ${ScriptedLogin.FIRST}-")

    /** The line names [whose] (the login, or the head's account) and the step its owner takes, and where. */
    private fun assertNamesTheStep(answer: ClientAnswer, whose: String) {
        val line = answer.error?.message.orEmpty()
        assertTrue(line.startsWith(PREFIX), "a person reads one [splice] line: ${answer.describe()}")
        assertTrue(whose in line, "names $whose: $line")
        assertTrue("accept" in line && "terms" in line, "names the step: $line")
        assertTrue("claude.ai" in line, "names where the owner takes it: $line")
        assertFalse("rejected" in line, "the request was not what the provider refused: $line")
    }
}
