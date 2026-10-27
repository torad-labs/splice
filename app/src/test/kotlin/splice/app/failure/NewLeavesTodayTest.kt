// NEW: failure-handling slice 1. Pins the wire at each site slice 1 gives a cause of its own (final-design.md
// section 14, slice 1's new leaves): the status, the error type and x-should-retry, so the refactor that makes each
// site build its cause and answer through one table cannot change what the client is told. A characterization: every
// expected value is read from today's code at the site named beside it, never copied from a run, and like slice 0's
// TodayWireCharacterizationTest it reads no words.
package splice.app.failure

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.topology.SHARE_NODE_TOKEN
import splice.core.turn.ReasoningDisplay
import splice.dialect.responses.ReasoningSettings
import splice.head.scripted.ClientAnswer
import splice.head.scripted.ClientRequest
import splice.head.scripted.ScriptedHead
import splice.head.scripted.ScriptedHeads
import splice.head.scripted.reply
import splice.head.scripted.withScriptedHeads
import splice.provider.codex.CodexProvider
import java.nio.file.Path

private const val OK = 200
private const val BAD_REQUEST = 400
private const val UNAUTHORIZED = 401
private const val OVERLOADED = 529

/** How often a test looks again at a head's log, in real time, and how many looks make twenty seconds. */
private const val LOOK_AGAIN_MS = 10L
private const val MAX_LOOKS = 2_000

/** Claude Code's summarizer instruction, which makes a request a compaction (CompactClassifier). */
private const val COMPACTION_SYSTEM = "You are tasked with summarizing conversations for another agent."

/** A rejection naming a masked key in the shape the Codex backend masks one. It is no token a scripted head sends. */
private val FOREIGN_KEY_WORDS = "Incorrect API key provided: sk-test0" + "*".repeat(40) + "Zq9x."

class NewLeavesTodayTest {
    /** Failure-handling slice 4, replacing `the LAN share's node-word refusal answers 503 with x-should-retry
     *  false`: the refusal is permanent for this request and names no login, so it takes the one non-login
     *  permanent answer, 400 invalid_request_error with x-should-retry false (final-design.md section 11). */
    @Test
    fun `the LAN share's node-word refusal stops the turn with no login status`(@TempDir tmp: Path) = runTest {
        withScriptedHeads(tmp) {
            // PreDriveRefusals.refuseIfNodeWordUnheld: the word would go upstream as the caller's own login, so the
            // head answers it, never retried, since only this machine can bring a login back.
            val head = forwardingAnthropic()

            val answer = head.client.send(ClientRequest(bearer = SHARE_NODE_TOKEN))

            assertEquals(BAD_REQUEST, answer.status, answer.describe())
            assertEquals("invalid_request_error", answer.error?.type, answer.describe())
            assertEquals("false", answer.shouldRetry, answer.describe())
            assertTrue(provider.requests.isEmpty(), "the word never reached the provider: ${provider.requests}")
        }
    }

    /** Slice 4: the console's stop is the operator's verdict on this request, so its re-send is refused with the
     *  permanent answer, now carrying x-should-retry false, which stops the client where no header let it grind. */
    @Test
    fun `a stopped turn's re-send is refused for good`(@TempDir tmp: Path) = runTest {
        withScriptedHeads(tmp) {
            // TurnPreparation refuses the stopped request's re-send inside LiveTurns' window, and HeadAdmission answers
            // the refusal as a 400 invalid_request_error. Claude Code re-sends with stream=false.
            provider.script(reply { responsesStart(); responsesText("running"); hold(); responsesEnd() })
            val head = responses()
            val request = ClientRequest(session = "stopped-session")
            val stream = head.client.open(request)
            stream.awaitText("running")
            assertTrue(head.liveTurns.stop(head.liveTurns.list().single().id), "the console found the running turn")
            stream.finish()

            val resend = head.client.send(request.copy(stream = false))

            assertEquals(BAD_REQUEST, resend.status, resend.describe())
            assertEquals("invalid_request_error", resend.error?.type, resend.describe())
            assertEquals("false", resend.shouldRetry, resend.describe())
            assertEquals(1, provider.requests.size, "the re-send never reached the provider: ${provider.requests}")
        }
    }

    @Test
    fun `a Responses request with an output format the head cannot carry is refused before any provider request`(
        @TempDir tmp: Path,
    ) = runTest {
        withScriptedHeads(tmp) {
            // ResponsesOutputSchema: an output format other than json_schema is refused as the request is prepared.
            val head = responses()
            val format = buildJsonObject { putJsonObject("output_format") { put("type", "json_object") } }

            val answer = head.client.send(ClientRequest(extra = format))

            assertRefusedHere(answer)
            assertTrue(provider.requests.isEmpty(), "nothing reached the provider: ${provider.requests}")
        }
    }

    @Test
    fun `a codex tool choice that names no declared tool is refused before any provider request`(
        @TempDir tmp: Path,
    ) = runTest {
        withScriptedHeads(tmp) {
            // CodexProvider runs the Responses check first (ResponsesOutputSchema), which refuses a named choice of a
            // tool the request did not declare.
            val head = codex()
            val choice = buildJsonObject {
                putJsonObject("tool_choice") {
                    put("type", "tool")
                    put("name", "Missing")
                }
            }

            val answer = head.client.send(ClientRequest(extra = choice))

            assertRefusedHere(answer)
            assertTrue(provider.requests.isEmpty(), "nothing reached the provider: ${provider.requests}")
        }
    }

    @Test
    fun `a codex tool choice the backend may not honour is refused before any provider request`(
        @TempDir tmp: Path,
    ) = runTest {
        withScriptedHeads(tmp) {
            // CodexRequestConstraints: a choice other than auto that names a declared tool which is not the structured
            // output tool, so the Responses check passes it and the codex one refuses it.
            val head = codex()
            val choice = buildJsonObject {
                put("tools", readTool())
                putJsonObject("tool_choice") {
                    put("type", "tool")
                    put("name", "Read")
                }
            }

            val answer = head.client.send(ClientRequest(extra = choice))

            assertRefusedHere(answer)
            assertTrue(provider.requests.isEmpty(), "nothing reached the provider: ${provider.requests}")
        }
    }

    @Test
    fun `a retry after an unfinished compaction is answered as today`(@TempDir tmp: Path) = runTest {
        withScriptedHeads(tmp) {
            // A compaction outlives its client (TurnStreamer's detachable drive), and a byte-identical retry follows
            // the drive still running. When the drive ends with no clean terminal, LocalResponses answers the retry
            // with what was recorded and an overloaded_error event, and no retry header.
            provider.script(reply { responsesStart(); responsesText("summary so far"); hold(); cut() })
            val head = responses()
            val compaction = ClientRequest(
                session = "compacting-session",
                extra = buildJsonObject { put("system", COMPACTION_SYSTEM) },
            )
            val first = head.client.open(compaction)
            first.awaitText("summary so far")
            first.hangUp()
            awaitLog(head, "compaction continues detached")
            val retry = head.client.open(compaction)
            awaitLog(head, "still running")
            provider.release()

            val answer = retry.finish()

            assertEquals(OK, answer.status, answer.describe())
            assertEquals("overloaded_error", answer.error?.type, answer.describe())
            assertNull(answer.shouldRetry, answer.describe())
        }
    }

    /** Slice 4, replacing `a 401 naming a masked key the login did not send is answered as today's transient API
     *  error`: the provider's own failure is in the R family, so the buffered answer is the 529 2.1.310 sends again
     *  (http-529-non-stream) rather than the 502 it carried, which no row of the record covers. */
    @Test
    fun `a 401 naming a masked key the login did not send is the provider's own failure`(
        @TempDir tmp: Path,
    ) = runTest {
        withScriptedHeads(tmp) {
            // ForeignCredential.upstreamsOwn reads the 401 as the upstream's own failure, which a re-send can heal:
            // in a stream it is an overloaded_error event, and a request that does not stream answers 529.
            provider.script(
                reply {
                    status(UNAUTHORIZED)
                    openAiErrorBody("invalid_request_error", FOREIGN_KEY_WORDS, "invalid_api_key")
                },
            )
            val head = chat()

            val streamed = head.client.send()
            val collected = head.client.send(ClientRequest(stream = false))

            assertEquals(OK, streamed.status, streamed.describe())
            assertEquals("overloaded_error", streamed.error?.type, streamed.describe())
            assertNull(streamed.shouldRetry, streamed.describe())
            assertEquals(OVERLOADED, collected.status, collected.describe())
            assertEquals("overloaded_error", collected.error?.type, collected.describe())
            assertNull(collected.shouldRetry, collected.describe())
        }
    }

    /** A codex head in front of the scripted provider, on the codex provider's own quirks. */
    private suspend fun ScriptedHeads.codex(): ScriptedHead = custom("codex") { tuning, log ->
        CodexProvider(
            tuning = tuning,
            reasoning = ReasoningSettings(ReasoningDisplay.TEXT, replay = false, effort = "high", summary = "detailed"),
            log = log,
        )
    }

    /** One declared tool, in the shape Claude Code declares its own. */
    private fun readTool(): JsonArray = buildJsonArray {
        add(
            buildJsonObject {
                put("name", "Read")
                put("description", "Reads a file.")
                putJsonObject("input_schema") {
                    put("type", "object")
                    putJsonObject("properties") {}
                }
            },
        )
    }

    /** A refusal the head made as it prepared the request: 400 invalid_request_error. Slice 4 adds x-should-retry
     *  false, since the same request earns the same refusal (final-design.md section 11). */
    private fun assertRefusedHere(answer: ClientAnswer) {
        assertEquals(BAD_REQUEST, answer.status, answer.describe())
        assertEquals("invalid_request_error", answer.error?.type, answer.describe())
        assertEquals("false", answer.shouldRetry, answer.describe())
    }

    /** Returns once [fragment] is in [head]'s log: a detached drive and a follower signal nothing else. */
    private suspend fun awaitLog(head: ScriptedHead, fragment: String) {
        withContext(Dispatchers.Default) {
            repeat(MAX_LOOKS) {
                if (head.log.any { fragment in it }) return@withContext
                delay(LOOK_AGAIN_MS)
            }
            fail<Unit>("the head never logged \"$fragment\":\n${head.log.joinToString("\n")}")
        }
    }
}
