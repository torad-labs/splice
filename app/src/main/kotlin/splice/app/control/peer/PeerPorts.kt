// NEW: Oct 19, 2026 (docs/specs/peer-messaging.md 3 and 7): the parts the /api/peers routes take from packages B and
// C, named by the role each plays for a route. PeersWiring adapts B's supervisor and C's journal, outbox log and webhook
// pusher to these, so the routes read the same whichever file a part lives in.
package splice.app.control.peer

import kotlinx.serialization.json.JsonObject
import splice.sessions.peer.SendOutcome
import splice.sessions.registry.SessionRecord

/** A peer's running helper: its pid and the socket it receives on, read from helper.json with its procStart checked. */
internal data class RunningHelper(val pid: Long, val socket: String)

/** B's supervisor, as the routes use it (spec 3.2 and 4.1). */
internal interface PeerHelpers {
    /** Starts [name]'s helper and waits at most 10 s for its registry record: the pid, or null when the wait ran out
     *  and the helper keeps starting. */
    suspend fun start(name: String): Long?

    /** SIGTERM, SIGKILL after 5 s, then removes the record, key file and socket the helper left. Works on a helper
     *  that is already dead. */
    suspend fun stop(name: String)

    /** SIGHUP: the helper re-reads peer.json and its webhook secret. */
    fun reload(name: String)

    /** The helper running for [name] now, or null. */
    fun running(name: String): RunningHelper?
}

/** One page of a peer's journal (spec 3.3): the records as journaled, the seq to read after next, and the unread. */
internal data class InboxPage(val items: List<JsonObject>, val next: Long, val unread: Long)

/** How much a peer's journal holds: every message, and those past its read cursor. */
internal data class InboxCounts(val total: Long, val unread: Long)

/** What `webhook test` came to: pushed or failed, the receiver's status, and the redacted detail (spec 4.4). */
internal data class WebhookTestResult(val pushed: Boolean, val httpStatus: Int?, val detail: String?)

/** One send as the outbox records it: never the text, only its size and SHA-256 (spec 2.3). [msgId] is the id it
 *  carried: the caller's when there was one, else the one it was given, else null for a send that never left. */
internal data class SentRecord(
    val msgId: String?,
    val to: SessionRecord,
    val bytes: Int,
    val outcome: SendOutcome,
    val digest: String? = null,
)

/** C's journal, outbox log and webhook client, per peer, as the routes use them. */
internal interface PeerMail {
    /** The journal records after [after], at most [limit], of [kinds] (message, receipt, webhook), in seq order. */
    fun inbox(name: String, after: Long, limit: Int, kinds: Set<String>): InboxPage

    fun counts(name: String): InboxCounts

    /** Moves the read cursor to [through]; the cursor as it then stands. */
    fun ack(name: String, through: Long): Long

    /** The `sent/{msgId}` answer: `{msgId, to, at, outcome, receipt}`, or null when this peer sent no such id. */
    fun sent(name: String, msgId: String): JsonObject?

    /** Appends one outbox record. */
    fun recordSend(name: String, sent: SentRecord)

    /** The newest send [name] made under [msgId], or null when it made none. */
    fun prior(name: String, msgId: String): PriorSend?

    /** The outcome of the last finished push, `pushed` or `failed`, or null when none finished. */
    fun lastWebhookOutcome(name: String): String?

    /** Pushes the test body to [name]'s webhook now. */
    suspend fun testWebhook(name: String): WebhookTestResult

    /** Why [url] may not be a webhook (https, or http to loopback only), or null when it may. */
    fun webhookUrlRefusal(url: String): String?

    /** Deletes `<state dir>/peers/<name>/`: the journal, the outbox and the cursors. */
    fun purge(name: String)

    /** A webhook was set or cleared: the next pusher starts at the journal's end, not at an older cursor. */
    fun webhookChanged(name: String)
}

/** What packages B and C plug into the routes: the helper supervisor and the journal. Either may be absent, and the
 *  routes that need it answer 503. */
internal data class PeerServices(val helpers: PeerHelpers? = null, val mail: PeerMail? = null)

/** Where the routes read [PeerServices] at each request: the daemon sets them after the server is built. */
internal fun interface PeerServiceSource {
    fun services(): PeerServices
}
