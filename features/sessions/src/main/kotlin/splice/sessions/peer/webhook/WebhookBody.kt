// NEW: Oct 19, 2026 (docs/specs/peer-messaging.md 4.4): the JSON a webhook receives for one journaled message, as the
// bytes the signature covers. It carries the journal's own "auth" (token or none) beside "from", so a receiver sees how
// the frame was authenticated as well as how its sender was checked. A test push carries the same shape with
// "test":true, a seq of 0 and a fixed text, and no "auth".
package splice.sessions.peer.webhook

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.sessions.peer.journal.JournalRecords

// why: the layout number a receiver reads as "v", so it can tell a newer body from this one.
private const val PEER_WEBHOOK_BODY_LAYOUT = 1

private const val PEER_WEBHOOK_TEST_ID = "webhook-test"
private const val PEER_WEBHOOK_TEST_TEXT = "splice webhook test"

internal object WebhookBody {
    private val carried: List<String> = listOf("seq", "id", "at", "from", "auth", "text")

    /** The body for journal [record] from [peer], as compact UTF-8 JSON; [test] marks a test push. */
    fun of(peer: String, record: JsonObject, test: Boolean): ByteArray {
        val body = buildJsonObject {
            put("v", PEER_WEBHOOK_BODY_LAYOUT)
            put("peer", peer)
            for (key in carried) record[key]?.let { put(key, it) }
            if (test) put("test", true)
        }
        return JournalRecords.codec.encodeToString(JsonObject.serializer(), body).toByteArray(Charsets.UTF_8)
    }

    /** The record a webhook test pushes in place of a message: from the peer itself, at [at]. */
    fun testRecord(peer: String, at: Long): JsonObject {
        val self = JournalRecords.self(peer)
        val sender = JournalRecords.codec.encodeToJsonElement(JournalRecords.Sender.serializer(), self)
        return buildJsonObject {
            put("seq", 0)
            put("id", PEER_WEBHOOK_TEST_ID)
            put("at", at)
            put("from", sender)
            put("text", PEER_WEBHOOK_TEST_TEXT)
        }
    }
}
