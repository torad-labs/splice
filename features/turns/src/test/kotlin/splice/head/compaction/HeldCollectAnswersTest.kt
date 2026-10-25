// NEW: the answer ledger behind non-stream compaction retries: what a hold keeps, when it is
// dropped, how a newer hold supersedes an older one, and how a retry waits on a still-driving one.
package splice.head.compaction

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
internal class HeldCollectAnswersTest {
    private val body = Json.parseToJsonElement("""{"id":"msg_held","type":"message"}""") as JsonObject

    @Test
    fun `a collected answer completed after its client left is held for the identical retry`() =
        runBlocking {
            val answers = HeldCollectAnswers()
            val key = "session-a:hash"
            val hold = answers.begin(key)
            answers.finish(key, hold, body, keep = true)
            assertEquals(body, answers.find(key)?.getCompleted())
        }

    @Test
    fun `an answer that ended without keeping leaves nothing for the retry`() = runBlocking {
        val answers = HeldCollectAnswers()
        val key = "session-b:hash"
        val hold = answers.begin(key)
        answers.finish(key, hold, null, keep = false)
        // Dropped like any recording the client was present for: a further attempt runs upstream.
        assertNull(answers.find(key))
    }

    @Test
    fun `with no compaction under the key a retry finds nothing to follow`() = runBlocking {
        assertNull(HeldCollectAnswers().find("session-c:hash"))
    }

    @Test
    fun `a newer hold under the same key supersedes an older one like a recording does`() = runBlocking {
        val answers = HeldCollectAnswers()
        val key = "session-d:hash"
        val older = answers.begin(key)
        val newer = answers.begin(key)
        assertSame(newer, answers.find(key))
        assertFalse(older === newer)
        // The superseded launcher's settle must not clobber the newer entry:
        answers.finish(key, older, body, keep = true)
        assertTrue(answers.find(key)!!.isActive)
        // The current launcher settles its own slot:
        answers.finish(key, newer, body, keep = true)
        assertEquals(body, answers.find(key)?.getCompleted())
    }

    @Test
    fun `a retry while the compaction still drives follows it to its answer`() = runBlocking {
        val answers = HeldCollectAnswers()
        val key = "session-e:hash"
        val hold = answers.begin(key)
        val held = checkNotNull(answers.find(key)) { "a begun hold must be findable" }
        assertSame(hold, held)
        assertFalse(held.isCompleted)
        answers.finish(key, hold, body, keep = true)
        assertEquals(body, held.getCompleted())
    }

    @Test
    fun `an answer delivered to its retry leaves nothing for a further attempt`() = runBlocking {
        val answers = HeldCollectAnswers()
        val key = "session-f:hash"
        val hold = answers.begin(key)
        answers.finish(key, hold, body, keep = true)
        answers.consumed(key, hold)
        assertNull(answers.find(key))
    }
}
