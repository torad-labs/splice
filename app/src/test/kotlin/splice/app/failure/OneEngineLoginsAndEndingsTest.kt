// NEW: failure-handling slice 4, the routes the third pass left open (slice-4-report.md, "Open, with reasons", item
// 2) and the endings the slice's goal names beside them. One engine decides every retry a turn makes, and these hold
// the rows about logins and how a turn ends: a refresh token the token endpoint calls dead ends refreshing at once, an
// ending that is not about a login carries no login status, Muse's answer that only reasoned is carried on once and
// then ends, and a warm session waits a short limit out on its own login. Each route is a real head in front of the
// scripted provider, on virtual time, asserting what the provider sees and what the person reads; every expected
// number is section 8's own (final-design.md).
package splice.app.failure

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.app.provider.HeadReplies
import splice.core.auth.RefreshCall
import splice.core.budget.BudgetBlock
import splice.core.budget.HeadBudget
import splice.core.turn.ReasoningDisplay
import splice.core.util.LogSink
import splice.dialect.anthropic.PassthroughProvider
import splice.dialect.anthropic.PassthroughQuirks
import splice.dialect.responses.ReasoningSettings
import splice.dialect.responses.ResponsesLiteQuirks
import splice.dialect.responses.ResponsesProvider
import splice.dialect.responses.ResponsesQuirks
import splice.dialect.responses.ResponsesRoundTripQuirks
import splice.head.scripted.ClientAnswer
import splice.head.scripted.ClientRequest
import splice.head.scripted.ProviderRequest
import splice.head.scripted.ScriptedHead
import splice.head.scripted.ScriptedHeads
import splice.head.scripted.ScriptedLogin
import splice.head.scripted.ScriptedReply
import splice.head.scripted.reply
import splice.head.scripted.withScriptedHeads
import splice.oauth.codex.CodexRefresh
import splice.provider.codex.auth.CodexAuthProvider
import splice.provider.muse.MUSE_CONTINUES_REASONING_ONLY
import splice.upstream.ProviderTuning
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.writeText
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

/** The prefix every line a person reads opens with. */
private const val PREFIX = "[splice] "

/** What a line says where Claude Code sends the turn again itself. */
private const val RESENDS = "Claude Code sends this turn again."

/** Where the scripted virtual wall clock starts (VirtualTime's default). */
private val START: Instant = Instant.parse("2026-10-26T12:00:00Z")

private const val BAD_REQUEST = 400
private const val UNAUTHORIZED = 401
private const val PAYMENT_REQUIRED = 402
private const val FORBIDDEN = 403
private const val TOO_MANY_REQUESTS = 429

/** Where the renewing head's login exchanges its refresh token: the scripted provider answers any path. */
private const val TOKEN_PATH = "/oauth/token"

/** A signed-in codex login whose opaque access token serves as it is until a refusal asks for a renewal. */
private const val CODEX_LOGIN =
    """{"tokens":{"access_token":"scripted-access","refresh_token":"scripted-refresh","account_id":"scripted"}}"""

/** How Muse's head reasons in these routes. */
private val REASONING = ReasoningSettings(ReasoningDisplay.TEXT, replay = false, effort = "high", summary = "detailed")

/** A token endpoint's answer that the refresh token itself is dead (RFC 6749 section 5.2). */
private const val INVALID_GRANT = """{"error":"invalid_grant","error_description":"The refresh token was revoked."}"""

/** What an access token the provider no longer takes is answered with. */
private const val EXPIRED = "The access token expired."

/** OpenAI's code and words for an account out of credit, and its words for a model the account's plan leaves out. */
private const val NO_QUOTA = "insufficient_quota"
private const val NO_CREDIT = "You exceeded your current quota, please check your plan and billing details."
private const val NOT_IN_PLAN = "Your plan does not include access to this model."

/** The statuses and error types Claude Code reads as its login failing, and prints a login label for. */
private val LOGIN_STATUSES = setOf(UNAUTHORIZED, FORBIDDEN)
private val LOGIN_TYPES = setOf("authentication_error", "permission_error")

/** Two logins of one pooled head. */
private const val KESTREL = "kestrel"
private const val OSPREY = "osprey"

/** What a request that carries a reasoning-only round on asks the model for. */
private const val ASK_FOR_THE_ANSWER = "You finished reasoning without giving an answer"

/** The reasoning a Muse round bills when it ends after reasoning alone (MuseReplies: enc=~1400). */
private const val REASONING_TOKENS = 1_400L

/** How long a head's spending day lasts in these routes. */
private val BUDGET_DAY = 24.hours

class OneEngineLoginsAndEndingsTest {
    @Test
    fun `an invalid_grant from the token endpoint ends refreshing for that login at once`(@TempDir tmp: Path) =
        runTest {
            withScriptedHeads(tmp) {
                provider.script(
                    reply { status(UNAUTHORIZED); anthropicErrorBody("authentication_error", EXPIRED) },
                    reply { status(BAD_REQUEST); body(INVALID_GRANT) },
                    reply { anthropicAnswer("not reached") },
                )
                val head = renewing(tmp)

                val answer = head.client.send()

                // The refresh token itself is dead: a second exchange, or the turn's request again on the old token,
                // meets the same refusal, so the turn ends on the first one with the line that says to sign in.
                assertEquals(1, provider.requests.count { it.path == TOKEN_PATH }, answer.describe())
                assertEquals(1, provider.requests.count { it.path != TOKEN_PATH }, answer.describe())
                assertLine(answer, "login expired and could not be renewed")
            }
        }

    @Test
    fun `credit, forbidden and budget endings carry no login status`(@TempDir tmp: Path) = runTest {
        withScriptedHeads(tmp) {
            // Slice 5: the spent account holds, probed every 5 minutes, until the session's wall, so each turn gets a
            // script of its own and the budget's turn is counted on its own.
            provider.script(
                reply { status(PAYMENT_REQUIRED); openAiErrorBody("insufficient_quota", NO_CREDIT, NO_QUOTA) },
            )
            val credit = chat().client.send()
            provider.script(
                reply { status(FORBIDDEN); openAiErrorBody("permission_error", NOT_IN_PLAN, "model_not_allowed") },
            )
            val forbidden = chat().client.send()
            val asked = provider.requests.size
            val budget = budgetedChat(SpentBudget).client.send()

            // A login status makes Claude Code print a login label in front of the line, and none of these is about a
            // login: an account out of credit, a model the plan leaves out, and the head's own spending budget.
            for (answer in listOf(credit, forbidden, budget)) {
                assertFalse(answer.status in LOGIN_STATUSES, answer.describe())
                assertFalse(answer.error?.type in LOGIN_TYPES, answer.describe())
                assertTrue(lineOf(answer).startsWith(PREFIX), answer.describe())
            }
            assertEquals(asked, provider.requests.size, "the budget refused its turn before the provider was asked")
        }
    }

    @Test
    fun `Muse's reasoning-only completion is continued once from its reasoning, and a second one ends the turn`(
        @TempDir tmp: Path,
    ) = runTest {
        withScriptedHeads(tmp) {
            provider.script(reasoningOnly(1), reasoningOnly(2), reply { responsesAnswer("not reached") })
            val head = custom("muse") { tuning, log -> MuseHead(tuning, log) }

            val answer = head.client.send()

            assertEquals(2, provider.requests.size, "the round and one continuation: ${answer.describe()}")
            val continuation = provider.requests[1].body
            assertTrue("ENC-1" in continuation, "the continuation carries the reasoning it continues: $continuation")
            assertTrue(ASK_FOR_THE_ANSWER in continuation, "and asks for the answer it never gave: $continuation")
            // Section 8's row: CONTINUE_REASONING once, then End (R), with the empty answer's line.
            assertLine(answer, "without an answer")
            assertLine(answer, RESENDS)
        }
    }

    @Test
    fun `a warm session waits a short limit out on its own login instead of moving`(@TempDir tmp: Path) = runTest {
        withScriptedHeads(tmp) {
            provider.script(
                reply { anthropicAnswer("the first turn") },
                reply {
                    status(TOO_MANY_REQUESTS)
                    header("Retry-After", "20")
                    anthropicErrorBody("rate_limit_error", "Number of requests has exceeded your rate limit.")
                },
                reply { anthropicAnswer("the second turn, on the same login") },
            )
            val head = pooledAnthropic(listOf(KESTREL, OSPREY))
            val turn = ClientRequest(session = "warm-session")

            head.client.send(turn)
            val second = head.client.send(turn.copy(text = "And the next step?"))

            // Moving would rebuild the session's whole cache on the other login, which costs more than the wait.
            assertEquals("the second turn, on the same login", second.text, second.describe())
            assertEquals(listOf(KESTREL, KESTREL, KESTREL), provider.requests.map(::login), second.describe())
            assertEquals(20.seconds, provider.requests[2].at - provider.requests[1].at, "the stated 20 s, waited")
        }
    }

    /** A Responses round that completes with one encrypted reasoning item and nothing else, as Muse's do: its terminal
     *  lists that item as its whole output (ResponsesEventReducer.endedInReasoning). A terminal with an empty output is
     *  a plain empty answer, which is asked again with the same body. */
    private fun reasoningOnly(round: Int): ScriptedReply = reply {
        responsesReasoning("ENC-$round", id = "rs_round_$round")
        event(
            """{"type":"response.completed","response":{"id":"resp_scripted","status":"completed",""" +
                """"output":[{"type":"reasoning","id":"rs_round_$round","encrypted_content":"ENC-$round",""" +
                """"summary":[]}],"usage":{"input_tokens":12,"output_tokens":$REASONING_TOKENS,""" +
                """"output_tokens_details":{"reasoning_tokens":$REASONING_TOKENS}}}}""",
        )
    }

    /** A head whose codex login renews through the scripted provider's token path. */
    private suspend fun ScriptedHeads.renewing(tmp: Path): ScriptedHead {
        val login = tmp.resolve("auth.json").also { it.writeText(CODEX_LOGIN) }
        return custom("renewing") { tuning, log ->
            val tokenUrl = provider.baseUrl + TOKEN_PATH
            val auth = CodexAuthProvider(
                authPath = login,
                authCacheMs = 0,
                refreshCall = RefreshCall { CodexRefresh(log).refresh(tokenUrl, it) },
                log = log,
            )
            PassthroughProvider(tuning.copy(auth = auth), PassthroughQuirks(tuning.key))
        }
    }

    /** Which pooled login [request] was signed with. */
    private fun login(request: ProviderRequest): String? =
        request.headers["authorization"]?.removePrefix("Bearer ${ScriptedLogin.FIRST}-")

    /** The line the client was given: an in-band error event's message, or the error body of a status. */
    private fun lineOf(answer: ClientAnswer): String = answer.error?.message.orEmpty()

    private fun assertLine(answer: ClientAnswer, fragment: String) {
        val line = lineOf(answer)
        assertTrue(line.startsWith(PREFIX), "a person reads one [splice] line: ${answer.describe()}")
        assertTrue(fragment in line, "says \"$fragment\": $line")
        assertFalse("attempt" in line, line)
    }
}

/** Muse's head as its arm (MuseResponsesArm) assembles it for these rounds: the Responses dialect with Muse's quirk
 *  that carries a round that only reasoned on from its reasoning, and Muse's own reading of its failures. */
private class MuseHead(tuning: ProviderTuning, log: LogSink) : ResponsesProvider(
    tuning,
    REASONING,
    ResponsesQuirks(
        providerTag = "muse",
        lite = ResponsesLiteQuirks(),
        roundTrip = ResponsesRoundTripQuirks(continueReasoningOnly = MUSE_CONTINUES_REASONING_ONLY),
    ),
    log = log,
    replies = HeadReplies.muse,
)

/** A spending budget already spent for the day: it refuses every turn, as a `block` budget does once reached. */
private object SpentBudget : HeadBudget {
    override fun admit(): BudgetBlock =
        BudgetBlock("Daily budget reached.", "spent=5.00 limit=5.00", "\$5.00", START.plus(BUDGET_DAY.toJavaDuration()))

    override fun spent(atMs: Long, model: String, counters: Map<String, Long>): Unit = Unit
}
