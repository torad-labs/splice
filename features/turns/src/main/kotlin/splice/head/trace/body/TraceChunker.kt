// NEW: V4-457 streaming Gear chunks and exact JSON body-literal encoding.
// 2026-10-05: chunks target 8 KiB. A client edits each body in several places per request (the cache marker
// moves, new messages land before the system prompt and tools), and the chunk around each edit is stored again
// whole: at a 32 KiB target that was most of what filled the day's pack.
// 2026-10-15: trace writing was 13% of the daemon's CPU (JFR, 90 s). A literal is now encoded in one pass straight to
// bytes, a chunk's opening bytes are copied without Gear, which cannot cut there, and each cut records where its text
// stood, so a remembered literal's marks no longer measure its whole text again. The bytes written are unchanged.
package splice.head.trace.body

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import splice.core.memory.HeapCapacityException
import splice.core.memory.HeapLease
import splice.core.memory.HeapOwners
import splice.core.memory.HeapReservations
import splice.upstream.memory.JvmHeap
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest

// why: Gear boundaries target 8 KiB, bounded below at 4 KiB and above at 64 KiB. Re-chunking the 2026-10-05
// packs at 4/8/64 KiB with zstd stored 113 MiB where 16/32/64 KiB raw stored 1023 MiB (claudex).
internal const val CHUNK_MIN: Int = 4 * 1024

// why: 8 KiB is the expected Gear boundary spacing, so an edit re-stores about 8 KiB rather than about 32 KiB.
internal const val CHUNK_TARGET: Int = 8 * 1024

// why: cap one pack entry and one streaming buffer at 64 KiB regardless of input content.
internal const val CHUNK_MAX: Int = 64 * 1024

// why: Gear shifts its value one bit per byte, so 64 bytes later it holds nothing of a byte. No cut is tested before
// CHUNK_MIN, so a chunk's bytes before this offset never reach a test and are copied without hashing.
private const val TRACE_GEAR_FROM: Int = CHUNK_MIN - Long.SIZE_BITS

// why: one deterministic Gear value for each possible input byte; SHA-256 makes the table platform independent.
private val GEAR = LongArray(1 shl Byte.SIZE_BITS) { byte ->
    ByteBuffer.wrap(MessageDigest.getInstance("SHA-256").digest(byteArrayOf(byte.toByte()))).long
}

/** Cuts one literal's bytes into stored parts. A resumed literal starts with the [reused] parts it shares with a
 *  recent one and drops the first [skip] bytes it is fed, which already lie inside the last of them. */
internal class TraceChunker(
    private val pack: TraceBodyPack,
    private val heap: HeapReservations = JvmHeap.budget,
    reused: List<JsonObject> = emptyList(),
    private var skip: Int = 0,
) : OutputStream() {
    // The buffer and the cut record live and die with this chunker, so one lease holds both.
    private val bufferLease = heap.reserve(CHUNK_MAX + TRACE_CUTS_FIRST * TRACE_CUT_BYTES)
        ?: throw HeapCapacityException()
    private val buffer = ByteArray(CHUNK_MAX).also { HeapOwners.keep(it, bufferLease) }
    private val parts = ArrayList<JsonObject>()
    private val partsLease = HeapOwners.charge(parts, heap, 0L)
    private var count = 0
    private var gear = 0L

    /** Where each part this chunker cuts lies in its literal's text; the encoder names its chars here. */
    val cuts = TraceCuts(bufferLease, CHUNK_MAX.toLong())

    init {
        if (!partsLease.resize(reused.size * TRACE_CHUNK_REFERENCE_BYTES)) throw HeapCapacityException()
        parts += reused
    }

    override fun write(value: Int) {
        if (skip > 0) {
            skip--
            return
        }
        buffer[count++] = value.toByte()
        gear = (gear shl 1) + GEAR[value.toUByte().toInt()]
        val boundary = count >= CHUNK_MIN && (gear and (CHUNK_TARGET - 1).toLong()) == 0L
        if (boundary || count == CHUNK_MAX) emit()
    }

    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        val dropped = minOf(skip, length)
        skip -= dropped
        val end = offset + length
        var index = offset + dropped
        while (index < end) {
            if (count < TRACE_GEAR_FROM) {
                // The Gear value at CHUNK_MIN depends only on the 64 bytes before it, so these skip the hash.
                val run = minOf(end - index, TRACE_GEAR_FROM - count)
                bytes.copyInto(buffer, count, index, index + run)
                count += run
                index += run
            } else {
                val first = bytes[index++].toUByte().toInt()
                if (index == end) write(first) else pair(first, bytes[index++].toUByte().toInt())
            }
        }
    }

    private fun pair(first: Int, second: Int) {
        val mask = (CHUNK_TARGET - 1).toLong()
        val firstGear = GEAR[first]
        val shiftedFirst = (gear shl 2) + (firstGear shl 1)
        buffer[count++] = first.toByte()
        // Shifting h1 drops its low bit, so the original first-byte cut also checks firstGear's parity.
        val firstBoundary = count >= CHUNK_MIN && (firstGear and 1L) == 0L &&
            (shiftedFirst and (mask shl 1)) == 0L
        if (firstBoundary || count == CHUNK_MAX) {
            gear = (gear shl 1) + firstGear
            emit()
            // The second byte belongs to a fresh chunk after emit resets Gear.
            write(second)
        } else {
            buffer[count++] = second.toByte()
            gear = shiftedFirst + GEAR[second]
            val secondBoundary = count >= CHUNK_MIN && (gear and mask) == 0L
            if (secondBoundary || count == CHUNK_MAX) emit()
        }
    }

    fun finish(): JsonArray {
        if (count > 0) emit()
        return JsonArray(parts)
    }

    private fun emit() {
        if (!partsLease.resize((parts.size + 1L) * TRACE_CHUNK_REFERENCE_BYTES)) throw HeapCapacityException()
        cuts.cut(count)
        val copy = heap.reserve(count.toLong()) ?: throw HeapCapacityException()
        copy.use { parts += pack.put(buffer.copyOf(count)) }
        count = 0
        gear = 0L
    }
}

// why: array-list references and growth slack for one body chunk, whose payload index is charged separately.
private const val TRACE_CHUNK_REFERENCE_BYTES = 64L

/** For each part one chunker cut, reused parts not counted: its length, and the last text char the encoder named
 *  before the cut with the bytes it had fed before that char. Every char before that one lies inside the parts up
 *  to this cut, so a mark measures from it (TraceRecentLiterals). Growth is charged to [lease] above [base]. */
internal class TraceCuts(private val lease: HeapLease, private val base: Long) {
    private var chars = IntArray(TRACE_CUTS_FIRST)
    private var feds = LongArray(TRACE_CUTS_FIRST)
    private var lengths = IntArray(TRACE_CUTS_FIRST)
    private var size = 0
    private var namedChar = 0
    private var namedFed = 0L

    /** The encoder's bytes for text char [char] start [fed] bytes into what it feeds the chunker. */
    fun origin(char: Int, fed: Long) {
        namedChar = char
        namedFed = fed
    }

    fun cut(length: Int) {
        if (size == chars.size) grow()
        chars[size] = namedChar
        feds[size] = namedFed
        lengths[size] = length
        size++
    }

    fun originChar(cut: Int): Int = chars[cut]

    fun originFed(cut: Int): Long = feds[cut]

    fun length(cut: Int): Int = lengths[cut]

    private fun grow() {
        val slots = chars.size * 2
        if (!lease.resize(base + slots * TRACE_CUT_BYTES)) throw HeapCapacityException()
        chars = chars.copyOf(slots)
        feds = feds.copyOf(slots)
        lengths = lengths.copyOf(slots)
    }
}

// why: eight cuts hold a literal of about 96 KiB at the usual cut spacing, so most literals never grow the record.
private const val TRACE_CUTS_FIRST = 8

// why: a cut is two ints and a long, 16 bytes, and half again while a doubling copy holds the old arrays too.
private const val TRACE_CUT_BYTES = 24L
