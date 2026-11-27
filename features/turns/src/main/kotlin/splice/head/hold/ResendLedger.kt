// NEW: failure-handling slice 4 (final-design.md section 10). What a head remembers of the requests whose turn ended
// with an answer Claude Code sends again by itself: a request identical to one of them is that re-send, so its turn is
// told as one whose predecessor already ended this way (RequestFacts.repeated). ClientWire then answers it as an ending
// Claude Code does not send again, and its line says the turn stopped, so a failure that keeps coming back ends after
// one re-send instead of looping. A request is identified as a stop's re-send is (LiveTurns' MessagesHash): its session
// and the hash of its messages, so a request with no session is never told apart, and never repeated.
package splice.head.hold

import splice.core.failure.EndingHeard
import splice.core.failure.HeadVoice
import splice.core.failure.RequestFacts
import splice.core.failure.TurnVoice
import splice.core.turn.TurnScope
import splice.core.util.ElapsedClock
import splice.upstream.retry.RESEND_TTL_MS

/** One head's memory of its requests that ended R, each kept [RESEND_TTL_MS]: past an hour a re-send is the person's
 *  own. At most [capacity] are kept, the oldest forgotten first. Read on [clock], the head's monotonic one. */
internal class ResendLedger(private val clock: ElapsedClock, private val capacity: Int = RESEND_CAPACITY) {
    /** When each request ended, by its key, oldest first. */
    private val ended = LinkedHashMap<String, Long>()

    /** Whether the request of [session] whose messages hash to [messages] is the re-send of one that ended R. */
    @Synchronized
    fun repeated(session: String?, messages: String?): Boolean {
        val key = keyOf(session, messages) ?: return false
        forgetExpired()
        return key in ended
    }

    /** The request of [session] whose messages hash to [messages] ended with an answer Claude Code sends again. */
    @Synchronized
    fun endedForResend(session: String?, messages: String?) {
        val key = keyOf(session, messages) ?: return
        forgetExpired()
        ended.remove(key)
        ended[key] = clock()
        while (ended.size > capacity) ended.remove(ended.keys.first())
    }

    /** [voice] for a turn of [scope], told by what admission read of it: its request is the re-send of one that ended
     *  with an answer Claude Code sends again where it is [repeated], and is marked so itself when it ends that way.
     *  A hold handed to the client (family L) is not an ending to repeat: its re-send holds anew (slice 5). */
    fun voiceOf(
        voice: HeadVoice,
        scope: TurnScope,
        request: RequestFacts,
    ): TurnVoice {
        val repeated = repeated(scope.sessionId, scope.messagesHash)
        val heard = EndingHeard { answer ->
            if (answer.clientResends && !answer.handedOver) endedForResend(scope.sessionId, scope.messagesHash)
        }
        return voice.turn(request.copy(repeated = repeated), heard)
    }

    private fun forgetExpired() {
        val now = clock()
        val expired = ended.entries.takeWhile { now - it.value >= RESEND_TTL_MS }.map { it.key }
        expired.forEach(ended::remove)
    }

    private fun keyOf(session: String?, messages: String?): String? =
        if (session == null || messages == null) null else "$session:$messages"
}

/** The most requests a head remembers: far more than its sessions re-send within an hour, and a bound on its memory. */
private const val RESEND_CAPACITY = 4_096
