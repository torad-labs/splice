// NEW: failure-handling slice 2 (2026-10-26). The voice of one head, and of one turn on it: who the head is to a
// person and the zone its times read in, held once so no surface has to know any of it.
//
// A surface that tells a person something asks its voice for the line and writes what it gets. It cannot spell a word
// of its own: SpliceLine is built only in this package, and a [TurnVoice] is the only thing a surface outside core
// holds that makes one. That is what makes the one-voice rule structural rather than a convention (final-design.md
// section 10, "Who writes it").
package splice.core.failure

import splice.core.util.LocalTimeText
import java.time.Clock
import java.time.ZoneId

/** What a head needs to speak beyond its own provider: the provider a person acts with ([providerName], DeepSeek,
 *  xAI, Anthropic), whether its model server runs on this machine ([local]), and the [zone] its times read in. The
 *  defaults are a head whose provider a person never has to name, on the machine's own zone; the daemon fills them
 *  per head, and a test names a zone so its lines read the same everywhere. */
public data class VoiceFacts(
    val providerName: String? = null,
    val local: Boolean = false,
    val zone: ZoneId = ZoneId.systemDefault(),
)

/** One head's voice: its launch command, the command that signs it in, and [facts]. [clock] is read when a line about
 *  a wait says a clock time. */
public class HeadVoice(
    private val head: String,
    private val loginCommand: String?,
    private val facts: VoiceFacts = VoiceFacts(),
    private val clock: Clock = Clock.systemUTC(),
) {
    private val lines = SpliceLines(LocalTimeText(facts.zone))

    /** The voice of a turn carrying [request]; [heard] hears each ending it composes (EndingHeard). */
    public fun turn(request: RequestFacts, heard: EndingHeard = EndingHeard {}): TurnVoice =
        TurnVoice(lines, this, request, heard)

    /** The voice of a line about no particular request: an admission refusal, a quiet stretch, a session note. */
    public val anyTurn: TurnVoice get() = turn(RequestFacts(compaction = false, firstExchange = false))

    internal fun lineFacts(request: RequestFacts): LineFacts = LineFacts(
        head = head,
        loginCommand = loginCommand,
        providerName = facts.providerName,
        local = facts.local,
        request = request,
        now = clock.instant(),
    )
}

/** Hears the answer each ending of a turn is told with (failure-handling slice 4): the head remembers a request whose
 *  turn ended with one Claude Code sends again (its ResendLedger), so the identical request after it is told as one. */
public fun interface EndingHeard {
    public fun heard(answer: WireAnswer)
}

/** The voice of one turn: every line about it, composed by [SpliceLines] from the turn's own facts. */
public class TurnVoice internal constructor(
    private val lines: SpliceLines,
    private val voice: HeadVoice,
    private val request: RequestFacts,
    private val heard: EndingHeard,
) {
    /** The line this turn ends with, told as the client is told it ([answer]). */
    public fun ending(cause: FailureCause, at: Position, answer: WireAnswer): SpliceLine {
        heard.heard(answer)
        return lines.ending(cause, at, answer, voice.lineFacts(request))
    }

    /** What the client is told about [cause] at [at] on this turn's request: a request whose identical predecessor
     *  already ended R is told as P (ClientWire). */
    public fun answer(cause: FailureCause, at: Position): WireAnswer =
        ClientWire.answer(cause, at, request.repeated, request.otherLogin)

    /** The line this turn ends with, told as ClientWire answers [cause] at [at]. */
    public fun ending(cause: FailureCause, at: Position): SpliceLine = ending(cause, at, answer(cause, at))

    /** The line that opens a recovery episode: what the turn met, and what it does next. */
    public fun waiting(cause: FailureCause, next: Recovery): SpliceLine =
        lines.waiting(WaitLine(cause, next), voice.lineFacts(request))

    /** The line a quiet stretch of this turn shows on its heartbeat. */
    public fun quiet(turn: QuietTurn): SpliceLine = lines.quiet(turn, voice.lineFacts(request))

    /** The note a session waiting on splice reads. */
    public fun note(note: SessionNote): SpliceLine = lines.note(note, voice.lineFacts(request))
}
