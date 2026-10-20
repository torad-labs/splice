// NEW: Oct 19, 2026 (docs/specs/peer-messaging.md 4.4): pushes each journaled message to the peer's webhook, in seq
// order and one at a time. A push that fails for a reason that may pass is tried again after 5 s, 30 s, 2 min, 10 min
// and 30 min, which makes 6 attempts. Its end, pushed or failed, is journaled as a webhook record, and webhook.cursor
// moves on. A failed push is never pushed again by itself.
package splice.sessions.peer.webhook

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import splice.core.util.Cancellables
import splice.core.util.LogSafe
import splice.core.util.LogSink
import splice.core.util.SafeFailureText
import splice.core.util.WallClock
import splice.sessions.peer.PeerConfig
import splice.sessions.peer.journal.JournalLines
import splice.sessions.peer.journal.JournalRecords
import splice.sessions.peer.journal.PEER_KIND_MESSAGE
import splice.sessions.peer.journal.PEER_OUTCOME_FAILED
import splice.sessions.peer.journal.PEER_OUTCOME_PUSHED
import splice.sessions.peer.journal.PeerJournal
import java.nio.file.Path

// why: spec 4.4, one push is tried 6 times at most: the first try and five retries.
private const val PEER_WEBHOOK_MAX_ATTEMPTS = 6

// why: spec 4.4, the first retry comes 5 s after the first failure.
private const val PEER_WEBHOOK_RETRY_1_MS = 5_000L

// why: spec 4.4, the second retry comes 30 s after the second failure.
private const val PEER_WEBHOOK_RETRY_2_MS = 30_000L

// why: spec 4.4, the third retry comes 2 min after the third failure.
private const val PEER_WEBHOOK_RETRY_3_MS = 120_000L

// why: spec 4.4, the fourth retry comes 10 min after the fourth failure.
private const val PEER_WEBHOOK_RETRY_4_MS = 600_000L

// why: spec 4.4, the fifth retry comes 30 min after the fifth failure.
private const val PEER_WEBHOOK_RETRY_5_MS = 1_800_000L

// why: the journal is read 50 records at a time, so one read stays small however far behind the webhook is.
private const val PEER_WEBHOOK_BATCH = 50

/**
 * The webhook of one peer, for the helper that owns its journal. [start] pushes until its job is cancelled; [test]
 * makes one attempt for `splice peer webhook test` and writes nothing. The secret and the bearer are read from files
 * under [peerDir] on every attempt, so a rotated secret takes effect without a restart.
 *
 * With no `webhook.cursor` yet, a pusher starts at the journal's end: messages that arrived before the webhook was set
 * are not pushed. [PeerJournal.resetWebhookCursor] makes the next pusher start there again.
 */
public class WebhookPusher(
    config: PeerConfig,
    private val journal: PeerJournal,
    peerDir: Path,
    private val wait: Wait,
    private val log: LogSink,
    private val wallClock: WallClock = WallClock { System.currentTimeMillis() },
) {
    /** Pauses the pusher for [ms] between two attempts. The helper wires this to its process waiter. */
    public fun interface Wait {
        public suspend fun until(ms: Long)
    }

    /** How one attempt to push ended. */
    public sealed class Attempt {
        public data class Pushed(val httpStatus: Int) : Attempt()

        /** [retryable] is true when the same push may pass later: a connection error, a timeout, 408, 425, 429, 5xx. */
        public data class Failed(val httpStatus: Int?, val detail: String, val retryable: Boolean) : Attempt()
    }

    private data class Delivery(val last: Attempt, val attempts: Int)

    private data class CompletedOutcome(
        val seq: Long,
        val entry: JournalRecords.WebhookEntry,
        val journaled: Boolean = false,
    )

    private var completedOutcome: CompletedOutcome? = null

    private val peer: String = config.name
    private val enabled: Boolean = config.webhook?.enabled == true
    private val sender: WebhookSender? = config.webhook?.let { WebhookSender(it, config.name, peerDir, wallClock) }
    private val retryWaitsMs: List<Long> = listOf(
        PEER_WEBHOOK_RETRY_1_MS,
        PEER_WEBHOOK_RETRY_2_MS,
        PEER_WEBHOOK_RETRY_3_MS,
        PEER_WEBHOOK_RETRY_4_MS,
        PEER_WEBHOOK_RETRY_5_MS,
    )

    /** Pushes from the webhook's place until the returned job is cancelled; ends at once when no webhook is on. */
    public fun start(scope: CoroutineScope): Job = scope.launch {
        val live = sender
        if (live != null && enabled) pushForever(live)
    }

    /** One push of the test body, with no retry and no journal record. */
    public suspend fun test(): Attempt {
        val live = sender ?: return Attempt.Failed(null, "no webhook is set", false)
        val record = WebhookBody.testRecord(peer, wallClock())
        return live.send(0L, WebhookBody.of(peer, record, true))
    }

    private suspend fun pushForever(live: WebhookSender) {
        while (true) {
            Cancellables.runCatchingCancellable { drain(live) }.onFailure { stumbled(it) }
        }
    }

    private suspend fun drain(live: WebhookSender) {
        saveCompletedOutcome()
        val after = journal.cursors.webhook() ?: startCursor()
        val page = journal.read(after, PEER_WEBHOOK_BATCH, setOf(PEER_KIND_MESSAGE))
        if (page.items.isEmpty()) journal.awaitMessage() else page.items.forEach { push(live, it) }
    }

    private fun startCursor(): Long {
        val end = journal.lastSeq()
        journal.cursors.advanceWebhook(end)
        return end
    }

    private suspend fun push(live: WebhookSender, record: JsonObject) {
        val seq = JournalLines.seqOf(record) ?: return
        val delivery = deliver(live, seq, WebhookBody.of(peer, record, false))
        completedOutcome = CompletedOutcome(seq, entryOf(seq, delivery))
        saveCompletedOutcome()
    }

    /** A write failure retries persistence after the outer backoff, never the completed external delivery. */
    private fun saveCompletedOutcome() {
        val pending = completedOutcome ?: return
        if (!pending.journaled) {
            journal.appendWebhook(pending.entry)
            completedOutcome = pending.copy(journaled = true)
        }
        journal.cursors.advanceWebhook(pending.seq)
        completedOutcome = null
    }

    private suspend fun deliver(live: WebhookSender, seq: Long, body: ByteArray): Delivery {
        var attempts = 1
        var last = live.send(seq, body)
        while (again(last) && attempts < PEER_WEBHOOK_MAX_ATTEMPTS) {
            wait.until(retryWaitsMs[attempts - 1])
            attempts += 1
            last = live.send(seq, body)
        }
        return Delivery(last, attempts)
    }

    private fun again(last: Attempt): Boolean = last is Attempt.Failed && last.retryable

    private fun entryOf(seq: Long, delivery: Delivery): JournalRecords.WebhookEntry = when (val last = delivery.last) {
        is Attempt.Pushed ->
            JournalRecords.WebhookEntry(seq, PEER_OUTCOME_PUSHED, last.httpStatus, delivery.attempts, null)
        is Attempt.Failed ->
            JournalRecords.WebhookEntry(seq, PEER_OUTCOME_FAILED, last.httpStatus, delivery.attempts, last.detail)
    }

    private suspend fun stumbled(failure: Throwable) {
        note("the webhook push hit an error (${LogSafe.str(SafeFailureText.render(failure))}); retrying")
        wait.until(PEER_WEBHOOK_RETRY_2_MS)
    }

    private fun note(reason: String) {
        log("[peers] ${LogSafe.str(peer)}: $reason")
    }
}
