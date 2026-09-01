// NEW: adapters bridging the gateway's file stores to the control plane's read interfaces, so
// the dashboard reads the same on-disk truth the head writes (a DOWN head still shows state).
package splice.app

import splice.control.CompactView
import splice.control.HeadCompactSource
import splice.control.HeadLogSource
import splice.control.HeadPerfSource
import splice.control.HeadUsageSource
import splice.control.RateLimitView
import splice.control.UsageView
import splice.core.util.Cancellables
import splice.core.util.JsonlSink
import splice.core.util.SafeFailureText
import splice.gateway.compact.CompactStats
import splice.gateway.perf.PerfStats
import splice.gateway.usage.UsageStore
import java.nio.file.Files
import java.nio.file.Path

public class UsageStoreSource(private val store: UsageStore) : HeadUsageSource {
    override fun snapshot(): UsageView {
        val state = store.readState()
        val ratelimit = store.readRateLimit()?.let {
            RateLimitView(it.limitTokens, it.remainingTokens, it.resetTokens)
        }
        return UsageView(state.outputTokens5h, state.entries, ratelimit)
    }
}

public class CompactStatsSource(private val stats: CompactStats) : HeadCompactSource {
    override fun summary(tailN: Int): CompactView {
        val s = stats.read(tailN)
        val tail = s.tail.map { row -> row.mapValues { (_, v) -> v.toString() } }
        return CompactView(s.total, s.byOutcome, tail)
    }
}

public class PerfStatsSource(private val stats: PerfStats) : HeadPerfSource {
    override fun tailNumeric(n: Int): List<Map<String, Long>> = stats.tailNumeric(n)
}

public class LogFileSource(
    private val logFile: Path,
    private val headTag: String? = null,
) : HeadLogSource {
    // DR-68 (class law, display flavor): genuine absence stays the quiet empty tail; an
    // UNREADABLE log degrades to one explicit in-band line instead of a silently blank
    // dashboard/`splice logs` — the daemon.log itself may be the unreadable file, so the
    // surface IS the returned text.
    override fun tail(lines: Int): String {
        if (lines <= 0) return ""
        return Cancellables.runCatchingCancellable {
            renderLines(JsonlSink.readTail(logFile, LOG_TAIL_BYTES), lines)
        }.getOrElse { failure -> unreadable(failure) }
    }

    /** DR-135: the --follow discontinuity re-baseline — [tail]'s text AND the byte offset the
     *  caller must adopt, from ONE read. followPoll used to print [tail] and then adopt a size
     *  STAT taken BEFORE it, so a torn final line (which [tail] withholds) was skipped unprinted
     *  and a line appended between the stat and the read was printed twice. Offset 0 on failure
     *  re-baselines again next poll rather than advancing over unread bytes. */
    public fun tailAt(lines: Int): LogRebase =
        Cancellables.runCatchingCancellable {
            val at = JsonlSink.readTailAt(logFile, LOG_TAIL_BYTES)
            LogRebase(renderLines(at.lines, lines), at.completeEnd)
        }.getOrElse { failure -> LogRebase(unreadable(failure), 0L) }

    /** DR-68 (class law, display flavor): genuine absence stays the quiet empty tail; an
     *  UNREADABLE log degrades to one explicit in-band line instead of a silently blank
     *  dashboard/`splice logs` — the daemon.log itself may be the unreadable file, so the
     *  surface IS the returned text. */
    private fun unreadable(failure: Throwable): String {
        val genuinelyAbsent = failure is java.nio.file.NoSuchFileException &&
            !Files.exists(logFile, java.nio.file.LinkOption.NOFOLLOW_LINKS)
        return if (genuinelyAbsent) {
            ""
        } else {
            "[logs unavailable: $logFile unreadable (${SafeFailureText.render(failure)})]"
        }
    }

    /** One definition of the head filter and the line cap, so [tail] and [tailAt] cannot drift. */
    private fun renderLines(lines: List<String>, keep: Int): String =
        lines.asSequence()
            .filter { headTag == null || headTag in it }
            .toList()
            .takeLast(keep.coerceAtMost(MAX_LOG_LINES))
            .joinToString("\n")

    override fun path(): String = logFile.toString()

    /** DR-100: the --follow delta read — complete lines from [fromOffset] to EOF (bounded at
     *  LOG_TAIL_BYTES per call), head-filtered like [tail]. [LogDelta.consumed] is the bytes up
     *  to and including the last newline — a torn final line stays unconsumed so the caller's
     *  baseline never advances past unprinted bytes. Read failures return the quiet zero delta:
     *  the follow loop's own polledSize warning owns the unreadable-episode surface (DR-68). */
    public fun readFrom(fromOffset: Long): LogDelta =
        Cancellables.runCatchingCancellable { readDelta(fromOffset) }.getOrElse { LogDelta("", 0L) }

    private fun readDelta(fromOffset: Long): LogDelta =
        java.nio.channels.FileChannel.open(logFile, java.nio.file.StandardOpenOption.READ).use { ch ->
            val end = minOf(ch.size(), fromOffset + LOG_TAIL_BYTES)
            val want = (end - fromOffset).toInt()
            // `end < ch.size()` means the window was cut by the byte cap, not by EOF — there IS
            // more file past it. DR-138 needs that to tell "a line still being written" (wait)
            // from "a line longer than the whole window" (never completes inside it).
            if (want <= 0) LogDelta("", 0L) else completeLines(readBytes(ch, fromOffset, want), end < ch.size())
        }

    private fun readBytes(ch: java.nio.channels.FileChannel, from: Long, want: Int): ByteArray {
        val buf = java.nio.ByteBuffer.allocate(want)
        ch.position(from)
        var more = true
        while (buf.hasRemaining() && more) {
            more = ch.read(buf) >= 0
        }
        buf.flip()
        return ByteArray(buf.remaining()).also { buf.get(it) }
    }

    private fun completeLines(bytes: ByteArray, moreBeyondWindow: Boolean): LogDelta {
        // '\n' is unambiguous in UTF-8, so the byte split is safe before decoding.
        val lastNewline = bytes.indexOfLast { it == NEWLINE_BYTE }
        // DR-138: no newline anywhere in the window. If the window ended at EOF this is the
        // ordinary torn tail — consume nothing and wait for the writer to finish the line. But if
        // there is MORE FILE past the window, the line is longer than LOG_TAIL_BYTES and can never
        // complete inside one: consumed stayed 0, followPoll returned its baseline unchanged, and
        // every later poll re-read the identical window forever. That is a PERMANENT freeze —
        // every subsequent line lost for the life of the process, re-reading 1 MiB twice a second,
        // silently. Skipping the over-long line costs one line and keeps the follow alive, which
        // is the never-below-status-quo side of the trade; the skip is announced in-band, the
        // DR-68 idiom, because a silent gap in a log follower is its own defect.
        if (lastNewline < 0) {
            return if (moreBeyondWindow) {
                LogDelta("[log line exceeds ${bytes.size} bytes — skipped to keep --follow live]", bytes.size.toLong())
            } else {
                LogDelta("", 0L)
            }
        }
        val text = String(bytes, 0, lastNewline, Charsets.UTF_8)
            .lineSequence()
            .filter { headTag == null || headTag in it }
            .joinToString("\n")
        return LogDelta(text, (lastNewline + 1).toLong())
    }
}

/** One --follow delta: the filtered complete lines to print (may be empty when every new line was
 *  another head's) and the raw bytes consumed — the caller advances its baseline by [consumed]. */
public data class LogDelta(val text: String, val consumed: Long)

/** A --follow re-baseline: the bounded tail to print and the ABSOLUTE offset to adopt (not a
 *  delta — the discontinuity means the old baseline is meaningless). Both from one read. */
public data class LogRebase(val text: String, val offset: Long)

// LogFileSource's tail bounds. File-scope consts (Kotlin style law, 2026-08-15): a top-level
// `private const val` is the sanctioned home for constants, never a static namespace on the type.
private const val LOG_TAIL_BYTES = 1024 * 1024
private const val MAX_LOG_LINES = 2_000
private const val NEWLINE_BYTE = '\n'.code.toByte()
