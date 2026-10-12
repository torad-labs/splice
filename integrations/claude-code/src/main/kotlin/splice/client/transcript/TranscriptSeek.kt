// NEW: Oct 12, 2026 (Marlin's walk of desk c) — where in a transcript a moment falls, without reading the file.
//
// A door from a request opens its agent's conversation at that request's place. A week-long agent's transcript runs
// past a gigabyte (1.4 GB on desk c), so paging back from the end to last Friday is hundreds of reads. Records are
// written in time order, so the place is found by halving the file: each probe reads one record's top-level
// `timestamp` at a line start, and a final window is walked line by line. A record with no timestamp (mode,
// last-prompt and the other bookkeeping kinds) is passed over; one too long to read is treated as no timestamp.
package splice.client.transcript

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.format.DateTimeParseException

// why: below this many bytes left to halve, walking the lines is cheaper than another probe.
private const val SEEK_WINDOW_BYTES = 256L shl 10

// why: the longest record a probe reads whole; a longer one (a huge tool result) is passed over, not parsed.
private const val SEEK_LINE_BYTES = 8 shl 20

// why: how many records a probe passes over looking for a timestamp before it calls the stretch undated.
private const val SEEK_UNDATED_LINES = 64

// why: the read-ahead each probe's stream keeps, so one line of a few kilobytes is one read.
private const val SEEK_BUFFER_BYTES = 1 shl 16

/** When a record was written: its top-level timestamp, or null for a record with none or one too long to read. */
private fun interface RecordStamp {
    fun at(bytes: ByteArray?): Long?
}

internal class TranscriptSeek(private val opener: TranscriptOpener) {
    private val json = Json { ignoreUnknownKeys = true }

    /** The byte offset of the first record in [file] written after [atMs], or the file's end when none was. It is
     *  always a line start, so it is a cursor the page before it reads exactly up to. */
    fun after(file: Path, atMs: Long): Long {
        var lo = 0L
        var hi = Files.size(file)
        while (hi - lo > SEEK_WINDOW_BYTES) {
            val mid = lo + (hi - lo) / 2
            val probe = dated(file, mid, hi)
            when {
                probe == null -> hi = mid
                probe.atMs > atMs -> hi = probe.start
                else -> lo = probe.end
            }
        }
        return walk(file, lo, hi, atMs)
    }

    /** The first dated record whose line starts at or after [from] and before [limit]. */
    private fun dated(file: Path, from: Long, limit: Long): Dated? {
        lines(file, from).use { lines ->
            repeat(SEEK_UNDATED_LINES) {
                val line = lines.next()?.takeIf { it.start < limit }
                line?.atMs?.let { return Dated(line.start, line.end, it) }
                if (line == null) return null
            }
        }
        return null
    }

    /** The start of the first record after [atMs] among the lines starting in [from, limit), else [limit]. */
    private fun walk(file: Path, from: Long, limit: Long, atMs: Long): Long {
        lines(file, from).use { lines ->
            var line = lines.next()
            while (line != null && line.start < limit) {
                val at = line.atMs
                if (at != null && at > atMs) return line.start
                line = lines.next()
            }
        }
        return limit
    }

    /** The lines whose start is at or after [from]: read from the byte before it through the end of that byte's line,
     *  so a [from] that is itself a line start keeps its line. */
    private fun lines(file: Path, from: Long): Lines {
        if (from == 0L) return Lines(opener.open(file, 0L), 0L, RecordStamp(::stamp))
        return Lines(opener.open(file, from - 1), from - 1, RecordStamp(::stamp)).also { it.skipPartial() }
    }

    private fun stamp(bytes: ByteArray?): Long? {
        val text = bytes?.toString(Charsets.UTF_8) ?: return null
        return try {
            val value = json.parseToJsonElement(text).jsonObject["timestamp"]?.jsonPrimitive?.content
            value?.let { Instant.parse(it).toEpochMilli() }
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: DateTimeParseException) {
            null
        }
    }

    private data class Dated(val start: Long, val end: Long, val atMs: Long)

    /** A whole line: where it starts, where the next begins, and its record's timestamp when it has one. */
    private data class Line(val start: Long, val end: Long, val atMs: Long?)

    /** The lines of a file from a byte offset, each with where it starts and where the next begins. */
    private class Lines(
        stream: InputStream,
        private var at: Long,
        private val stampOf: RecordStamp,
    ) : AutoCloseable {
        private val input = BufferedInputStream(stream, SEEK_BUFFER_BYTES)

        /** Passes the rest of the line [at] falls inside, so the next line read starts a record. */
        fun skipPartial() {
            var b = input.read()
            while (b >= 0) {
                at += 1
                if (b == '\n'.code) return
                b = input.read()
            }
        }

        /** The next whole line, undated when it is longer than a probe reads; null at the file's end. */
        fun next(): Line? {
            val start = at
            val buffer = ByteArrayOutputStream()
            var kept = true
            var b = input.read()
            while (b >= 0 && b != '\n'.code) {
                at += 1
                if (kept && buffer.size() < SEEK_LINE_BYTES) buffer.write(b) else kept = false
                b = input.read()
            }
            if (b < 0 && at == start) return null
            if (b >= 0) at += 1
            return Line(start, at, stampOf.at(buffer.toByteArray().takeIf { kept }))
        }

        override fun close() = input.close()
    }
}
