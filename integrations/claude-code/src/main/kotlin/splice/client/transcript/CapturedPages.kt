package splice.client.transcript

import splice.sessions.transcript.MAX_TRANSCRIPT_PAGE
import splice.sessions.transcript.TranscriptLookup
import splice.sessions.transcript.TranscriptMessage
import splice.sessions.transcript.TranscriptPage

private const val CAPTURE_PATH = "captured by splice's trace, turn"
private const val CAPTURE_TOO_LARGE = "captured reply exceeds the transcript page byte limit"

private data class CapturedGroup(val span: CaptureSpan, val messages: List<TranscriptMessage>)

/** Keeps only a bounded page. A cursor pins the trace turn, so eviction or a newer capture is an honest refusal. */
internal class CapturedPages {
    private val reader = CapturedMessageReader()

    fun read(
        sent: CapturedRequest,
        sessionId: String,
        cursor: String?,
        backwards: Boolean,
        limit: Int,
    ): TranscriptLookup {
        val position = cursor?.let(CaptureCursor::parse)
        if (cursor != null && !matches(position, sent.turnId)) return TranscriptLookup.Refused(BAD_CURSOR)
        val wanted = limit.coerceIn(1, MAX_TRANSCRIPT_PAGE)
        val source = CapturedMessageSpans(sent.body)
        val window = CapturedPageWindow(source).read(position?.offset, backwards, wanted)
            ?: return TranscriptLookup.Refused(BAD_CURSOR)
        return if (window.bytes > MAX_CAPTURE_PAGE_BYTES) {
            TranscriptLookup.Refused(CAPTURE_TOO_LARGE)
        } else {
            project(sent, sessionId, window, backwards, wanted)
        }
    }

    private fun matches(position: CapturePosition?, turn: String): Boolean = position?.turn == turn

    private fun project(
        sent: CapturedRequest,
        sessionId: String,
        window: CapturedWindow,
        backwards: Boolean,
        limit: Int,
    ): TranscriptLookup {
        val groups = window.spans.map { CapturedGroup(it, reader.read(sent.body, it)) }
        val selected = if (backwards) newest(groups, limit) else oldest(groups, limit)
        val messages = selected.flatMap { it.messages }
        val next = if (backwards) null else nextOf(groups, selected, window)
        val earlier = if (backwards) earlierOf(groups, selected, window) else null
        if (messages.isEmpty() && noContinuation(next, earlier)) return TranscriptLookup.Missing
        return found(sent, sessionId, messages, next, earlier)
    }

    private fun noContinuation(next: Int?, earlier: Int?): Boolean = next == null && earlier == null

    private fun nextOf(groups: List<CapturedGroup>, selected: List<CapturedGroup>, window: CapturedWindow): Int? =
        if (selected.size < groups.size) groups[selected.size].span.start else window.next

    private fun earlierOf(groups: List<CapturedGroup>, selected: List<CapturedGroup>, window: CapturedWindow): Int? =
        if (selected.size < groups.size) selected.firstOrNull()?.span?.start else window.earlier

    private fun found(
        sent: CapturedRequest,
        sessionId: String,
        messages: List<TranscriptMessage>,
        next: Int?,
        earlier: Int?,
    ): TranscriptLookup = TranscriptLookup.Found(
        TranscriptPage(
            sessionId,
            "$CAPTURE_PATH ${sent.turnId}",
            messages,
            next?.let { CaptureCursor.mint(sent.turnId, it) },
            emptyMap(),
            earlier?.let { CaptureCursor.mint(sent.turnId, it) },
        ),
    )

    private fun oldest(groups: List<CapturedGroup>, limit: Int): List<CapturedGroup> {
        var count = 0
        return groups.takeWhile { group ->
            val keep = count < limit
            count += group.messages.size
            keep
        }
    }

    private fun newest(groups: List<CapturedGroup>, limit: Int): List<CapturedGroup> {
        var count = 0
        var first = groups.size
        while (first > 0 && count < limit) {
            first--
            count += groups[first].messages.size
        }
        return groups.subList(first, groups.size)
    }
}
