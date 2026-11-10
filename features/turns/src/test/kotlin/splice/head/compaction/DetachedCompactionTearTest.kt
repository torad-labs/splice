// NEW: 2026-11-09 — a detached compaction whose upstream tears after its client left, end to end through a head.
package splice.head.compaction

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.head.scripted.ClientRequest
import splice.head.scripted.ScriptedHead
import splice.head.scripted.reply
import splice.head.scripted.withScriptedHeads
import java.nio.file.Path

/** Claude Code's summarizer instruction, which makes a request a compaction (CompactClassifier). */
private const val COMPACTION_SYSTEM = "You are tasked with summarizing conversations for another agent."

/** How often the test looks again at the head's log, in real time, and how many looks make twenty seconds. */
private const val LOOK_AGAIN_MS = 10L
private const val MAX_LOOKS = 2_000

private const val OK = 200

/**
 * The client of a compaction hangs up, so the drive goes on detached with its recording as the reader; then the
 * upstream tears. The recording still reads every frame for the client's retry, so the tear is the upstream's
 * failure to carry on from (ClientChannel.readerGone), never a client that abandoned the turn: the round is
 * re-anchored, and the retry receives the whole summary from the recording, with no error and no further provider
 * request.
 */
class DetachedCompactionTearTest {

    @Test
    fun `a detached compaction whose upstream tears is re-anchored and its retry gets the whole answer`(
        @TempDir tmp: Path,
    ) = runTest {
        withScriptedHeads(tmp) {
            provider.script(
                reply { responsesStart(); responsesText("summary so far"); hold(); cut() },
                reply { responsesStart(); responsesText("the whole summary"); responsesEnd() },
            )
            val head = responses()
            val compaction = ClientRequest(
                session = "compacting-session",
                extra = buildJsonObject { put("system", COMPACTION_SYSTEM) },
            )
            val first = head.client.open(compaction)
            first.awaitText("summary so far")
            first.hangUp()
            awaitLog(head, "compaction continues detached")
            provider.release()
            awaitLog(head, "detached compaction finished")

            val answer = head.client.send(compaction)

            assertEquals(OK, answer.status, answer.describe())
            assertNull(answer.error, answer.describe())
            assertTrue("the whole summary" in answer.text, answer.describe())
            // The torn round was sent again once, and the retry was served from the recording.
            assertEquals(2, provider.requests.size, provider.requests.joinToString("\n"))
            // DriveSignals.onRoundFailure: the tear counts as the upstream's failure, absorbed by the re-anchor.
            assertTrue(head.log.any { "absorbed by re-anchor" in it }, head.log.joinToString("\n"))
        }
    }

    /** Returns once [fragment] is in [head]'s log: a detached drive signals nothing else. */
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
