package splice.head.trace

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import splice.core.memory.HeapCapacityException
import splice.core.memory.HeapCategory
import splice.core.memory.HeapJson
import splice.core.memory.HeapLease
import splice.core.memory.HeapReservations
import splice.core.memory.HeapText
import splice.core.storage.DayLine
import splice.core.storage.LineVisit
import splice.core.util.Cancellables
import splice.core.util.JsonScalars
import splice.head.trace.body.TraceBodyReaders
import java.io.IOException
import java.io.Reader
import java.nio.file.Path

// why: match TraceTail's one-minute file-lane slack for a turn record written before its late first attempt.
private const val CAPTURE_LATE_WRITE_SLACK_MS = 60_000L

// why: an 8 Ki-character buffer verifies every referenced byte without retaining the complete captured body.
private const val CAPTURE_VERIFY_CHARS = 8_192

/** Selects the same newest turn and oldest available client attempt as TraceTail, without hydrating its bodies. */
internal class TraceCaptureRead(
    private val ask: TraceAsk,
    private val heap: HeapReservations,
) : LineVisit, AutoCloseable {
    private val json = Json { ignoreUnknownKeys = true }
    private val stamps = TraceStamps(json, heap)
    private val readers = TraceBodyReaders(heap)
    private var selected: TraceCaptureBody? = null
    var turnId: String? = null
        private set
    private var opened = false
    private var ended = false
    private var atOpen: Long? = null

    override fun line(line: DayLine): Boolean {
        val stamp = stamps.of(line)
        if (stamp != null) place(line, stamp)
        return !answered(stamp?.at)
    }

    private fun place(line: DayLine, stamp: TraceStamp) {
        val id = stamp.placedId ?: return
        if (turnId == null && ask.admits(id, JsonScalars.str(stamp.session))) turnId = id
        if (turnId == id) take(line, stamp)
    }

    private fun answered(at: Long?): Boolean {
        val beforeSlack = atOpen?.let { first -> at != null && at < first - CAPTURE_LATE_WRITE_SLACK_MS } == true
        return turnId != null && opened && (ended || atOpen == null || beforeSlack)
    }

    private fun take(line: DayLine, stamp: TraceStamp) {
        if (stamp.isTurnRecord) ended = true else candidate(line)
        if (stamp.opens) {
            opened = true
            atOpen = stamp.at
        }
    }

    private fun candidate(line: DayLine) {
        if (line.byteSize >= Int.MAX_VALUE) throw HeapCapacityException()
        line.bytes().use { input ->
            HeapText.Reader.read(input, line.byteSize, heap).use { staged ->
                val record = json.parseToJsonElement(staged.text) as? JsonObject ?: return
                val value = (record["client"] as? JsonObject)?.get("body") ?: return
                if (!available(value, line.file)) return
                val replacement = TraceCaptureBody(value, line.file, heap)
                selected?.close()
                selected = replacement
            }
        }
    }

    private fun available(value: JsonElement, day: Path): Boolean = try {
        readers.open(value, day)?.use { input ->
            verify(input)
            true
        } ?: false
    } catch (_: IOException) {
        false // A corrupt or evicted attempt is not an available client body, as in the full-record reader.
    }

    private fun verify(input: Reader) {
        val lease = heap.reserve(
            CAPTURE_VERIFY_CHARS * Char.SIZE_BYTES.toLong(),
            HeapCategory.DECODED_BODY,
        ) ?: throw HeapCapacityException()
        lease.use {
            val buffer = CharArray(CAPTURE_VERIFY_CHARS)
            while (input.read(buffer) >= 0) { /* Verify every referenced byte, including unshown content. */ }
        }
    }

    fun open(): Reader? = selected?.let { readers.open(it.value(), it.day) }

    val available: Boolean get() = selected != null

    override fun close() {
        Cancellables.withCleanup({ selected?.close(); selected = null }) { readers.close() }
    }
}

/** Only the selected reference graph survives line parsing, charged until the read-scoped source closes. */
private class TraceCaptureBody(value: JsonElement, val day: Path, heap: HeapReservations) : AutoCloseable {
    private var body: JsonElement? = value
    private val lease: HeapLease = heap.reserve(
        HeapJson.bytes(value),
        HeapCategory.TRACE,
    ) ?: throw HeapCapacityException()

    fun value(): JsonElement = checkNotNull(body)

    override fun close() {
        body = null
        lease.close()
    }
}
