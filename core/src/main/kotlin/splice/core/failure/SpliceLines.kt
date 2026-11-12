// NEW: failure-handling slice 2 (2026-10-26). The one author of every line a person reads in Claude Code: a turn's
// ending, a wait or a recovery, a quiet stretch, a refusal at admission and a session note (final-design.md section
// 10). Every surface that writes a line takes a SpliceLine, which only this file's lines build, so no surface can
// spell its own words, and a line holds no internal code, count of tries, id, key, host or provider advice.
package splice.core.failure

import splice.core.util.LocalTimeText
import java.time.Instant

/** What a line knows of the request it answers. A compaction and a first exchange that are too long say so in their
 *  own words, never with the phrase that makes Claude Code compact (final-design.md section 11). [repeated]: the
 *  request's identical predecessor already ended in a failure Claude Code re-sends (the ResendLedger), so this one
 *  ends for good. [otherLogin]: the head's pool holds another login, so a limit handed to the client (family L)
 *  names a time 30 s away and says the client checks again then (failure-handling slice 5). */
public data class RequestFacts(
    val compaction: Boolean,
    val firstExchange: Boolean,
    val repeated: Boolean = false,
    val otherLogin: Boolean = false,
)

/** What a line names besides its failure: [head], the head's launch command and the subject of most lines;
 *  [loginCommand], the command that signs the head in, null where the client signs itself in; [providerName], the
 *  provider a person acts with (DeepSeek, xAI), null where none is named; [local], whether the head's model server runs
 *  on this machine; the [request]; and [now], from which a wait of an hour or more is said as a clock time. */
internal data class LineFacts(
    val head: String,
    val loginCommand: String?,
    val providerName: String?,
    val local: Boolean,
    val request: RequestFacts,
    val now: Instant,
)

/** What a turn does next about a failure it is recovering from. */
public sealed class Recovery {
    /** It sends the request again after [waitMs]. */
    public data class RetryIn(val waitMs: Long) : Recovery()

    /** It continues the cut answer from where it stopped. */
    public data object Resume : Recovery()

    /** It asks again, the answer having come back empty. */
    public data object AskAgain : Recovery()

    /** It moves to another of the head's logins. */
    public data object OtherLogin : Recovery()

    /** It holds until [until], the reset its cause stated, or until a probe finds it lifted where none was stated:
     *  [soft] says a probe may find it lifted before [until] (failure-handling slice 5). */
    public data class Hold(val until: Instant?, val soft: Boolean) : Recovery()
}

/** A line written while a turn recovers: the failure it met, and what it does next. One opens each recovery episode
 *  (section 8, "Lines from waits"). */
public data class WaitLine(val cause: FailureCause, val next: Recovery)

/** A quiet stretch of a turn: [elapsedMs] since it began, whether model content already reached the client
 *  ([answering]), and whether this line follows another in the same notice block ([again]). */
public data class QuietTurn(val elapsedMs: Long, val answering: Boolean, val again: Boolean)

/** A note splice sends a session that is waiting on it. */
public enum class SessionNote { NEW_LOGIN, RESET }

/** Composes every [SpliceLine]: one line of one or two sentences, at most [MAX_LINE] characters, that says what
 *  happened and what happens next. Times read through [time], in the machine's zone with its abbreviation. */
internal class SpliceLines(time: LocalTimeText = LocalTimeText()) {
    private val spoken = Spoken(time)
    private val providers = ProviderSentences(spoken)
    private val splices = SpliceSentences(spoken)
    private val holds = HoldSentences(spoken)

    /** The line a failed turn ends with. [answer] is what the client is told (ClientWire.answer): a line says Claude
     *  Code sends the turn again only when [WireAnswer.clientResends] says it does, and otherwise says what to do. */
    fun ending(cause: FailureCause, at: Position, answer: WireAnswer, facts: LineFacts): SpliceLine {
        val said = said(cause, at, facts, ongoing = false)
        val resend = said.next as? Next.Resend
        // A request whose identical predecessor already ended this way stops here, and says why (section 10).
        if (resend != null && stopsForGood(facts, answer)) {
            return fit("${said.what} $AFTER_RESEND", null, resend.otherwise)
        }
        val next = when (val then = said.next) {
            is Next.Resend -> if (answer.clientResends) RESENDS else then.otherwise
            is Next.Then -> then.text
            Next.None -> null
        }
        return fit(said.what, said.quote, next)
    }

    /** Whether this ending is the end of the road: the identical request before it already ended the same way, and
     *  the answer is not one Claude Code sends again, so nothing more will happen on its own. */
    private fun stopsForGood(facts: LineFacts, answer: WireAnswer): Boolean =
        facts.request.repeated && !answer.clientResends

    /** The line that opens a recovery episode: what the turn met, and what it does about it. It quotes no provider,
     *  but for a spent account's hold, whose line names what the person settles (section 10). */
    fun waiting(wait: WaitLine, facts: LineFacts): SpliceLine {
        val hold = wait.next as? Recovery.Hold
        if (hold != null) {
            val said = holds.holding(wait.cause, hold, facts)
            return fit(said.what, said.quote, (said.next as? Next.Then)?.text)
        }
        val resumes = wait.next == Recovery.Resume
        val at = Position(if (resumes) Moment.MID_CONTENT else Moment.BEFORE_COMMIT, streamBegan = resumes)
        val next = when (val then = wait.next) {
            is Recovery.RetryIn -> spoken.retry(then.waitMs, facts.now)
            Recovery.Resume -> "This turn continues from where it stopped."
            Recovery.AskAgain -> "This turn asks again."
            Recovery.OtherLogin -> "Another ${facts.head} login is free, so this turn continues there."
            is Recovery.Hold -> null
        }
        return fit(said(wait.cause, at, facts, ongoing = true).what, null, next)
    }

    /** The line a quiet stretch of a turn shows on its heartbeat. */
    fun quiet(turn: QuietTurn, facts: LineFacts): SpliceLine {
        val head = facts.head
        val what = when {
            turn.again && turn.answering -> "$head is still paused mid-answer"
            turn.again -> "Still no answer from $head"
            turn.answering -> "$head has paused mid-answer"
            else -> "No answer from $head yet"
        }
        return fit("$what, ${spoken.span(turn.elapsedMs)} into the turn", null, null)
    }

    /** The note a waiting session reads. */
    fun note(note: SessionNote, facts: LineFacts): SpliceLine = when (note) {
        SessionNote.NEW_LOGIN -> fit("${facts.head} has a new login", null, "Continue where you left off.")
        SessionNote.RESET -> fit("${facts.head}'s stated reset has passed", null, "Continue where you left off.")
    }

    private fun said(cause: FailureCause, at: Position, facts: LineFacts, ongoing: Boolean): Said = when (cause) {
        is FailureCause.Provider -> providers.said(cause, at, facts, ongoing)
        is FailureCause.Path -> PathSentences.said(cause, at, facts, ongoing)
        is FailureCause.Splice -> splices.said(cause, facts)
    }

    /** [what] as the line's first sentence, quoting [quote] when the line has room for it, then [next]. A line that
     *  would still pass [MAX_LINE] keeps its first sentence alone, cut at a word if it must be. */
    private fun fit(what: String, quote: ProviderWords?, next: String?): SpliceLine {
        val tail = next?.let { " $it" }.orEmpty()
        val fitted = listOfNotNull(
            quote?.let { "$SPLICE_PREFIX$what: ${it.text}$tail" },
            "$SPLICE_PREFIX$what.$tail",
            "$SPLICE_PREFIX$what.",
        ).firstOrNull { it.length <= MAX_LINE }
        return SpliceLine(fitted ?: spoken.cut("$SPLICE_PREFIX$what", MAX_LINE))
    }
}

/** The longest line a person reads, prefix included (final-design.md section 10). */
internal const val MAX_LINE = 240

/** What a line says when the client sends the turn again by itself. */
internal const val RESENDS = "Claude Code sends this turn again."

/** What a repeated ending adds to what happened: Claude Code already sent the turn again once. */
internal const val AFTER_RESEND = "after Claude Code sent this turn again, so this turn stopped"

/** What a line tells the person to do when the turn is theirs to send again: now, later, or after a time it names. */
internal const val SEND_AGAIN = "Send the message again."
internal const val SEND_LATER = "Send the message again later."
internal const val SEND_AFTER = "Send the message again after that time."

/** One line before it is fitted: what happened, as a sentence without its period; the provider's words it may quote,
 *  set only on refused, rejected, forbidden, payment and unrecognized lines; and what happens next. */
internal data class Said(val what: String, val quote: ProviderWords? = null, val next: Next = Next.None)

/** What a line says after what happened. */
internal sealed class Next {
    /** The client may send the turn again itself: the line says so when its answer is one the client re-sends, and
     *  says [otherwise] when it is not. */
    data class Resend(val otherwise: String) : Next()

    /** A fixed sentence: what the person does, or a fact they need. */
    data class Then(val text: String) : Next()

    data object None : Next()
}
