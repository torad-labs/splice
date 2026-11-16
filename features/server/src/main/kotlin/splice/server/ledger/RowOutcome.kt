// NEW: server mode (docs/specs/server-mode.md, F5, ledger-rows). How a request ended, as its ledger row says it, and
// the status the row stands for. The row is honest where the wire is not: a stream that opened as a 200 and then broke
// is an upstream error, a 502, not a 200; a client that left is a 499, apart from every error; a refusal keeps its own
// 4xx; an OpenAI-style stream the server closed without `[DONE]` is cut off; and a failure of splice's own is a 500.
//
// A turn on the Messages route is read from its head's own outcome when the head told one (ServedTurn); an
// OpenAI-style client's turn from what the dialect read of the answer (AnswerReading); and a request either never
// reached from the status it was answered with.
package splice.server.ledger

import splice.core.perf.OutcomeTag
import splice.core.perf.OutcomeTags
import splice.core.server.AnswerReading
import splice.core.wire.HttpStatus

/** A row's outcome, as the ledger writes it. */
internal enum class Outcome(val wire: String) {
    OK("ok"),
    UPSTREAM_ERROR("upstream_error"),
    CLIENT_GONE("client_gone"),
    REFUSED("refused"),
    CUT_OFF("cut_off"),
    SPLICE_ERROR("splice_error"),
}

/** A row's outcome and the status it stands for. */
internal data class RowEnd(val outcome: Outcome, val status: Int)

internal object RowOutcome {
    /** The status of a request whose client left before its answer ended (nginx's word for it). */
    const val CLIENT_GONE_STATUS: Int = 499

    /** A client that left, whatever the wire said. */
    val gone: RowEnd = RowEnd(Outcome.CLIENT_GONE, CLIENT_GONE_STATUS)

    /** A request splice itself failed (a ledger it cannot write, a head of its own not running), as a 500. */
    val own: RowEnd = RowEnd(Outcome.SPLICE_ERROR, HttpStatus.INTERNAL_SERVER_ERROR)

    private val success = HttpStatus.OK..HttpStatus.MAX_SUCCESS
    private val clientError = HttpStatus.BAD_REQUEST..CLIENT_GONE_STATUS
    private val upstreamError = RowEnd(Outcome.UPSTREAM_ERROR, HttpStatus.BAD_GATEWAY)

    // The head's outcomes that are splice's own failure, not the upstream's.
    private val ownFailures = setOf(OutcomeTag.UNEXPECTED.wire, OutcomeTag.RESTARTED.wire)

    /** A request answered [status] with nothing else known of it. A 5xx here is taken for the upstream's: no server
     *  of its provider up, or one that failed before anything of its answer was read. The failures splice knows for
     *  its own (a ledger it cannot write, a head of its own not running) are marked where they happen
     *  (LedgerTurn.failedOwn) and never reach this. */
    fun ofStatus(status: Int): RowEnd = when (status) {
        in success -> RowEnd(Outcome.OK, status)
        in clientError -> RowEnd(Outcome.REFUSED, status)
        else -> upstreamError
    }

    /** A Messages turn its head ended on [tag], answered [status]. */
    fun ofHead(tag: String, status: Int): RowEnd = when {
        OutcomeTags.isStopped(tag) -> gone
        OutcomeTags.isClean(tag) -> RowEnd(Outcome.OK, status)
        tag in ownFailures -> own
        tag == OutcomeTags.UNATTRIBUTED -> ofStatus(status)
        status in clientError -> RowEnd(Outcome.REFUSED, status)
        else -> upstreamError
    }

    /** An OpenAI-style turn answered [status], its answer as [read], a stream when [streamed]. */
    fun ofChat(read: AnswerReading, streamed: Boolean, status: Int): RowEnd = when {
        status !in success -> ofStatus(status)
        !read.finished -> upstreamError
        streamed && !read.ended -> RowEnd(Outcome.CUT_OFF, status)
        else -> RowEnd(Outcome.OK, status)
    }

    /** Whether [outcome] is an error a report counts: anything but a served turn and a client that left. */
    fun isError(outcome: String): Boolean = outcome != Outcome.OK.wire && outcome != Outcome.CLIENT_GONE.wire
}
