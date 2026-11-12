// NEW: line/event assembly per WHATWG HTML §9.2 event-stream-interpretation (blank-line dispatch,
// multi-line `data:` joined with \n, CR/CRLF/LF terminators, leading BOM stripped once, pending
// event discarded at EOF) — reference impl: rexxars/eventsource-parser. This file WAS a literal port
// of server/src/codex/stream.mjs's sseEvents, but that JS function is itself pre-WHATWG per-line
// dispatch (each `data:` line parsed+emitted on its own) and is NOT the reference here anymore.
// Still-true invariants carried over: multi-byte-safe UTF-8 across chunk boundaries (streaming
// decoder, never split a codepoint); partial last line carries to the next chunk; only `data:`-
// prefixed lines yield (the space after the colon is OPTIONAL per the SSE spec — kimi emits
// `data:{…}` bare, Anthropic/OpenAI emit `data: {…}`; requiring the space silently dropped every
// kimi frame); empty payloads and [DONE] skipped; malformed JSON frames skipped (never crash the
// stream); onBytes fires ON RAW READ with the chunk size (the watchdog touch + byte telemetry —
// never after downstream write; a slow client must not fake idleness). Hot-path shape: one reused
// decode scratch + one event assembler per stream (no per-chunk buffer allocs) and index-scanned
// lines (no per-line StringBuilder churn). Decode/assemble live in SseDecode.kt; the public seams
// live in SseObservers.kt; the two transport failures live in SseSpuriousWakeupException.kt.
package splice.upstream.sse

import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonObject
import splice.upstream.transport.SseFrameTooLarge

// no space: SSE field syntax is `data:` + optional single space + value (WHATWG spec); the
// leading-ws trim in processLine absorbs the space when present.
private const val DATA_PREFIX = "data:"

/** The SSE line/event reader for ONE stream. The observers and limits are what differs per stream, so they
 *  are the reader's own; every buffer it needs is per-[sseJsonEvents] local state, so a reader holds no
 *  stream state and callers construct one wherever they read a stream. The one fact it keeps is how its read
 *  ENDED early: [exceeded] names the safety limit a frame crossed, and the flow completes there instead of
 *  throwing, so the frames before it have all been delivered and the caller reads the verdict after collecting. */
public class SseReader(
    private val onBytes: BytesRead = BytesRead {},
    private val onMalformed: MalformedLine = MalformedLine {},
    private val onRawText: RawTextObserver? = null,
    private val maxLineChars: Int = MAX_SSE_LINE_CHARS,
    private val maxEventChars: Int = MAX_SSE_EVENT_CHARS,
) {

    /** The limit the read stopped at, or null while it has not. Read it after the flow completes. */
    @Volatile
    public var exceeded: SseFrameTooLarge? = null
        private set

    // the chunk/line/skip walk is the literal port; malformed frames must never crash the stream.
    // onRawText (opt-in, null for every hot-path caller) exposes the FULL decoded body text as it
    // arrives — not just `data:`-prefixed lines — so a zero-event terminal can classify a non-SSE
    // dead-head body (HTML/JSON login page). Null-callback matches the `perf: TurnPerf? = null` idiom:
    // when null the added cost is one null check per chunk, preserving the no-per-chunk-alloc invariant.
    public fun sseJsonEvents(channel: ByteReadChannel): Flow<JsonObject> = flow {
        val scratch = DecodeScratch()
        val lineBuffer = StringBuilder(SSE_READ_BUFFER_BYTES)
        val assembler = SseEventAssembler(onMalformed, maxEventChars)
        var bomChecked = false
        var rawObserver = onRawText
        var n = scratch.readChunk(channel)
        while (n >= 0) {
            onBytes(n)
            val before = lineBuffer.length
            scratch.decodeInto(n, lineBuffer)
            // Strip a leading UTF-8 BOM exactly once, only after real characters exist (a first chunk
            // that decodes to zero chars — all bytes still UTF-8 carry — must not falsely mark the
            // check done); never re-checked mid-stream (a later U+FEFF is an ordinary character).
            if (!bomChecked) bomChecked = stripLeadingBomWhenReady(lineBuffer)
            if (lineBuffer.length > maxLineChars) {
                exceeded = SseFrameTooLarge("SSE line", maxLineChars, lineBuffer.length)
                return@flow
            }
            rawObserver = notifyRawObserver(rawObserver, lineBuffer, before)
            emitCompleteLines(this, lineBuffer, assembler)
            exceeded = assembler.tooLarge
            if (exceeded != null) return@flow
            n = scratch.readChunk(channel)
        }
    }

    private fun stripLeadingBomWhenReady(lineBuffer: StringBuilder): Boolean {
        if (lineBuffer.isEmpty()) return false
        if (lineBuffer[0] == '\uFEFF') lineBuffer.deleteCharAt(0)
        return true
    }

    private fun notifyRawObserver(
        observer: RawTextObserver?,
        lineBuffer: StringBuilder,
        before: Int,
    ): RawTextObserver? {
        if (observer == null || lineBuffer.length <= before) return observer
        return observer.takeIf { it(lineBuffer.subSequence(before, lineBuffer.length)) }
    }

    /**
     * Scan [lineBuffer] for complete lines (terminated by `\n`, `\r`, or `\r\n`) and feed each to
     * [processLine], compacting the trailing partial in place. No per-line StringBuilder realloc — the
     * same builder is reused for the whole stream. Line/event state ([SseEventAssembler.pendingCR],
     * [SseEventAssembler.dataBuffer]) persists across calls so terminators split across chunk boundaries
     * resolve correctly. An unterminated trailing partial is LEFT untouched — that IS the discard-at-EOF
     * behavior (no flush anywhere on channel close).
     */
    private suspend fun emitCompleteLines(
        collector: FlowCollector<JsonObject>,
        lineBuffer: StringBuilder,
        assembler: SseEventAssembler,
    ) {
        var start = 0
        var i = 0
        val end = lineBuffer.length
        while (i < end) {
            val c = lineBuffer[i]
            if (assembler.pendingCR) {
                assembler.pendingCR = false
                if (c == '\n') {
                    // CRLF: the CR already terminated the line (this call or the previous chunk); eat the LF.
                    i++
                    start = i
                    continue
                }
            }
            if (c == '\n' || c == '\r') {
                processLine(collector, lineBuffer, start, i, assembler)
                if (assembler.tooLarge != null) return
                assembler.pendingCR = c == '\r' // a lone CR may still be the CR of a CRLF split next
                i++
                start = i
            } else {
                i++
            }
        }
        dropConsumed(lineBuffer, start)
    }

    /** Drop the [consumed] leading characters of complete lines, keeping the unterminated trailing partial. */
    private fun dropConsumed(lineBuffer: StringBuilder, consumed: Int) {
        if (consumed == 0) return
        if (consumed >= lineBuffer.length) lineBuffer.setLength(0) else lineBuffer.delete(0, consumed)
    }

    /**
     * Interpret one complete line [start, end) (WHATWG field parsing, `data:` only): a blank line
     * dispatches the pending event; a `data:`-prefixed line trims its value (ASCII-ws, kimi-space-safe)
     * and appends it to the assembler's dataBuffer joined by `\n`; every other line shape (comments,
     * `event:`/`id:`/`retry:`, anything not matching [DATA_PREFIX]) is silently ignored.
     */
    private suspend fun processLine(
        collector: FlowCollector<JsonObject>,
        buf: StringBuilder,
        start: Int,
        end: Int,
        assembler: SseEventAssembler,
    ) {
        if (end == start) {
            assembler.dispatch(collector)
            return
        }
        if (!matchesAt(buf, start, end, DATA_PREFIX)) return
        // trim ASCII whitespace at both ends (SSE payloads are JSON — no full Unicode trim needed)
        var pStart = start + DATA_PREFIX.length
        var pEnd = end
        while (pStart < pEnd && isAsciiWs(buf[pStart])) pStart++
        while (pEnd > pStart && isAsciiWs(buf[pEnd - 1])) pEnd--
        assembler.append(buf, pStart, pEnd)
    }

    /** [literal] present at [start] within [start, end) of [buf]? Char-wise — no substring allocation. */
    private fun matchesAt(buf: StringBuilder, start: Int, end: Int, literal: String): Boolean {
        if (end - start < literal.length) return false
        for (j in literal.indices) {
            if (buf[start + j] != literal[j]) return false
        }
        return true
    }

    private fun isAsciiWs(c: Char): Boolean =
        c == ' ' || c == '\t' || c == '\r' || c == '\n'
}

private const val MAX_SSE_LINE_CHARS = 1024 * 1024
private const val MAX_SSE_EVENT_CHARS = 4 * 1024 * 1024
