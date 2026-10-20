// NEW: Oct 19, 2026 (docs/specs/peer-messaging.md 3.3 and 7, package D): the /api/peers routes' PeerMail over package
// C's journal, outbox log and webhook client. The daemon only reads a peer's journal; the helper writes it. The daemon
// writes the outbox, because the send route is where a send happens.
package splice.app

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import splice.app.control.peer.InboxCounts
import splice.app.control.peer.InboxPage
import splice.app.control.peer.PeerMail
import splice.app.control.peer.SentRecord
import splice.app.control.peer.WebhookTestResult
import splice.core.util.LogSink
import splice.core.util.WallClock
import splice.sessions.peer.SendOutcome
import splice.sessions.peer.journal.PeerJournal
import splice.sessions.peer.journal.PeerOutboxLog
import splice.sessions.peer.store.PeerStore
import splice.sessions.peer.webhook.WebhookPusher
import splice.sessions.peer.webhook.WebhookUrl
import splice.upstream.codemode.ProcessWaiter
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

internal class PeerMailbox(
    private val store: PeerStore,
    /** The daemon's state folder; each peer's journal, outbox and cursors are under `peers/<name>/` in it. */
    private val stateDir: Path,
    private val log: LogSink,
    private val clock: WallClock = WallClock { System.currentTimeMillis() },
) : PeerMail {
    private val journals = ConcurrentHashMap<String, PeerJournal>()
    private val waiter = ProcessWaiter()

    override fun inbox(name: String, after: Long, limit: Int, kinds: Set<String>): InboxPage {
        val journal = journal(name)
        val page = journal.read(after, limit, kinds)
        return InboxPage(page.items, page.next, journal.unread())
    }

    override fun counts(name: String): InboxCounts = journal(name).counts().let { InboxCounts(it.total, it.unread) }

    override fun ack(name: String, through: Long): Long = journal(name).ackThrough(through)

    override fun sent(name: String, msgId: String): JsonObject? {
        val record = PeerOutboxLog(dir(name)).find(msgId) ?: return null
        val receipt = journal(name).receiptFor(msgId)
        return buildJsonObject {
            put("msgId", record.id)
            putJsonObject("to") {
                put("name", record.to.name)
                put("sessionId", record.to.sessionId)
            }
            put("at", record.at)
            put("outcome", record.outcome)
            put("detail", record.detail)
            if (receipt == null) {
                put("receipt", JsonNull)
            } else {
                putJsonObject("receipt") {
                    put("status", receipt.status)
                    put("detail", receipt.detail)
                    put("at", receipt.at)
                }
            }
        }
    }

    override fun recordSend(name: String, sent: SentRecord) {
        val outcome = sent.outcome
        val (word, detail) = when (outcome) {
            is SendOutcome.Submitted -> "submitted" to null
            is SendOutcome.Refused -> "refused" to outcome.reason
            is SendOutcome.Failed -> "failed" to outcome.reason
        }
        val target = PeerOutboxLog.Target(
            name = sent.to.name,
            sessionId = sent.to.sessionId,
            address = sent.to.process.messagingSocketPath?.let { "uds:$it" },
        )
        // A refused or failed send carried no msg_id; it still gets an id of its own, so the record has one.
        val id = sent.msgId ?: UUID.randomUUID().toString()
        PeerOutboxLog(dir(name)).append(
            PeerOutboxLog.Record(
                at = clock(),
                id = id,
                to = target,
                bytes = sent.bytes,
                outcome = word,
                detail = detail,
            ),
        )
    }

    override fun lastWebhookOutcome(name: String): String? = journal(name).lastWebhookOutcome()

    override suspend fun testWebhook(name: String): WebhookTestResult {
        val config = store.get(name) ?: return WebhookTestResult(false, null, "no peer has that name")
        val wait = WebhookPusher.Wait { ms -> waiter.wait(ms) }
        return when (val attempt = WebhookPusher(config, journal(name), store.dir(name), wait, log).test()) {
            is WebhookPusher.Attempt.Pushed -> WebhookTestResult(true, attempt.httpStatus, null)
            is WebhookPusher.Attempt.Failed -> WebhookTestResult(false, attempt.httpStatus, attempt.detail)
        }
    }

    override fun webhookUrlRefusal(url: String): String? = WebhookUrl.refusal(url)

    override fun purge(name: String) {
        journals.remove(name)
        val dir = dir(name)
        if (!Files.exists(dir)) return
        val paths = Files.walk(dir).use { stream -> stream.sorted(Comparator.reverseOrder()).toList() }
        paths.forEach { Files.deleteIfExists(it) }
    }

    override fun webhookChanged(name: String) {
        journal(name).resetWebhookCursor()
    }

    private fun dir(name: String): Path = stateDir.resolve("peers").resolve(name)

    private fun journal(name: String): PeerJournal = journals.computeIfAbsent(name) { PeerJournal(dir(name), log) }
}
