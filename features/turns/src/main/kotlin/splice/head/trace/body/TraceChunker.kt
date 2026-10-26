// NEW: V4-457 streaming Gear chunks and exact JSON body-literal encoding.
// 2026-10-05: chunks target 8 KiB. A client edits each body in several places per request (the cache marker
// moves, new messages land before the system prompt and tools), and the chunk around each edit is stored again
// whole: at a 32 KiB target that was most of what filled the day's pack.
// 2026-10-15: trace writing was 13% of the daemon's CPU (JFR, 90 s). A literal is now encoded in one pass straight to
// bytes, a chunk's opening bytes are copied without Gear, which cannot cut there, and each cut records where its text
// stood, so a remembered literal's marks no longer measure its whole text again. The bytes written are unchanged.
// 2026-10-25: the Gear scan was 59% of trace encoding (136 of 230 samples, 17:59 to 18:04 CT), and nearly every chunk
// it found was stored already. A cut is now guessed from the chunk that followed the previous part when the pack last
// stored them, and kept when the bytes up to it digest to that chunk and Gear cuts there. A missed guess is cut by the
// plain rule, so the parts stored are unchanged.
package splice.head.trace.body

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import splice.core.memory.HeapCapacityException
import splice.core.memory.HeapLease
import splice.core.memory.HeapOwners
import splice.core.memory.HeapReservations
import splice.core.util.JsonScalars
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

// why: Gear shifts its value one bit per byte, so 64 bytes later it holds nothing of a byte, and its value where a
// chunk may be cut is the 64 bytes before that point alone.
private const val TRACE_GEAR_WINDOW: Int = Long.SIZE_BITS

// why: no cut is tested before CHUNK_MIN, so a chunk's bytes before this offset never reach a test and are copied
// without hashing.
private const val TRACE_GEAR_FROM: Int = CHUNK_MIN - TRACE_GEAR_WINDOW

// why: a cut falls where Gear's low bits below CHUNK_TARGET are clear, once every CHUNK_TARGET bytes on average.
private const val TRACE_GEAR_MASK: Long = CHUNK_TARGET - 1L

// why: one deterministic Gear value for each possible input byte; SHA-256 makes the table platform independent.
private val GEAR = LongArray(1 shl Byte.SIZE_BITS) { byte ->
    ByteBuffer.wrap(MessageDigest.getInstance("SHA-256").digest(byteArrayOf(byte.toByte()))).long
}

/** Cuts one literal's bytes into stored parts. A resumed literal starts with the [reused] parts it shares with a
 *  recent one and drops the first [skip] bytes it is fed, which already lie inside the last of them.
 *
 *  Trace cost (Oct 25): after a part the plain rule cut, the chunker guesses that the chunk which followed that part
 *  when the pack last stored it follows again (TracePackIndex.follow), and buffers that many bytes with no Gear work.
 *  The guess is kept only when those bytes digest to the recorded chunk and the plain rule cuts at their end. Gear
 *  restarts at every cut, so where the rule cuts after a cut depends only on the bytes after it, and equal bytes cut
 *  alike: a kept guess is the plain rule's cut. A missed guess is cut by the plain rule over the buffered bytes. */
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

    // Every part is digested once, here, and the pack stores it under that digest.
    private val sha = MessageDigest.getInstance("SHA-256")

    // The hash of the part before the bytes buffered now, which the chunk they end in is recorded as following.
    private var previous: String? = reused.lastOrNull()?.let { JsonScalars.str(it["hash"]) }

    // The open guess: the length the buffered chunk is cut at, or 0 for none, and the first 48 bits of its digest.
    private var guess = 0
    private var guessed = 0L

    // While the plain rule re-cuts a missed guess, its cuts lie before the bytes fed last.
    private var rescanning = false

    /** Where each part this chunker cuts lies in its literal's text; the encoder names its chars here. */
    val cuts = TraceCuts(bufferLease, CHUNK_MAX.toLong())

    init {
        if (!partsLease.resize(reused.size * TRACE_CHUNK_REFERENCE_BYTES)) throw HeapCapacityException()
        parts += reused
        predict()
    }

    override fun write(value: Int) {
        if (skip > 0) {
            skip--
            return
        }
        buffer[count++] = value.toByte()
        if (guess > 0) {
            if (count == guess) settle()
            return
        }
        gear = (gear shl 1) + GEAR[value.toUByte().toInt()]
        val boundary = count >= CHUNK_MIN && (gear and TRACE_GEAR_MASK) == 0L
        if (boundary || count == CHUNK_MAX) emit(count)
    }

    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        val dropped = minOf(skip, length)
        skip -= dropped
        val end = offset + length
        var index = offset + dropped
        while (index < end) {
            if (guess > 0 || count < TRACE_GEAR_FROM) {
                // A guessed chunk is digested whole once it is buffered, and the Gear value at CHUNK_MIN depends only
                // on the 64 bytes before it, so these bytes skip the hash.
                val run = minOf(end - index, (if (guess > 0) guess else TRACE_GEAR_FROM) - count)
                bytes.copyInto(buffer, count, index, index + run)
                count += run
                index += run
                if (count == guess) settle()
            } else {
                val first = bytes[index++].toUByte().toInt()
                if (index == end) write(first) else pair(first, bytes[index++].toUByte().toInt())
            }
        }
    }

    private fun pair(first: Int, second: Int) {
        val firstGear = GEAR[first]
        val shiftedFirst = (gear shl 2) + (firstGear shl 1)
        buffer[count++] = first.toByte()
        // Shifting h1 drops its low bit, so the original first-byte cut also checks firstGear's parity.
        val firstBoundary = count >= CHUNK_MIN && (firstGear and 1L) == 0L &&
            (shiftedFirst and (TRACE_GEAR_MASK shl 1)) == 0L
        if (firstBoundary || count == CHUNK_MAX) {
            gear = (gear shl 1) + firstGear
            emit(count)
            // The second byte belongs to a fresh chunk after emit resets Gear, and may open its guess.
            write(second)
        } else {
            buffer[count++] = second.toByte()
            gear = shiftedFirst + GEAR[second]
            val secondBoundary = count >= CHUNK_MIN && (gear and TRACE_GEAR_MASK) == 0L
            if (secondBoundary || count == CHUNK_MAX) emit(count)
        }
    }

    /** The literal's parts. Bytes of an open guess are cut by the plain rule first; what is left after the last cut
     *  is the final part, which no rule cut and so is never recorded as a follower. */
    fun finish(): JsonArray {
        if (guess > 0 && count > 0) rescan()
        guess = 0
        if (count > 0) emit(count, rule = false)
        return JsonArray(parts)
    }

    /** The guessed chunk is buffered whole. It is cut there when it digests to the recorded chunk and the plain rule
     *  cuts at its end; otherwise the plain rule cuts the buffered bytes. */
    private fun settle() {
        val digest = digestOf(count)
        val same = ByteBuffer.wrap(digest).long ushr TRACE_FOLLOWER_LENGTH_BITS == guessed
        if (same && cutsAt(count)) emit(count, digest) else rescan()
    }

    /** The plain rule cuts a chunk [length] bytes long at its end: it is CHUNK_MAX long, or Gear over the 64 bytes
     *  before its end, which is all of Gear's value there, has its low bits clear. */
    private fun cutsAt(length: Int): Boolean {
        if (length == CHUNK_MAX) return true
        var value = 0L
        for (at in length - TRACE_GEAR_WINDOW until length) value = (value shl 1) + GEAR[buffer[at].toUByte().toInt()]
        return (value and TRACE_GEAR_MASK) == 0L
    }

    /** Cuts the buffered bytes of a missed guess by the plain rule, as if no guess had been open. Each chunk is stored
     *  before the bytes after it move to the front, and the bytes after the last cut stay buffered with their Gear
     *  value, so the plain path carries on to the next cut, where a guess opens again. */
    private fun rescan() {
        var held = count
        guess = 0
        rescanning = true
        var at = minOf(held, TRACE_GEAR_FROM)
        gear = 0L
        while (at < held) {
            gear = (gear shl 1) + GEAR[buffer[at++].toUByte().toInt()]
            val boundary = at >= CHUNK_MIN && (gear and TRACE_GEAR_MASK) == 0L
            if (boundary || at == CHUNK_MAX) {
                emit(at)
                buffer.copyInto(buffer, 0, at, held)
                held -= at
                at = minOf(held, TRACE_GEAR_FROM)
                gear = 0L
            }
        }
        count = held
        rescanning = false
    }

    /** Opens a guess at the chunk that followed the previous part when the pack last stored them, when the rule could
     *  cut a chunk that long. */
    private fun predict() {
        val follower = previous?.let(pack::follower) ?: 0L
        val length = (follower and TRACE_FOLLOWER_LENGTH_MASK).toInt() + 1
        guess = if (length in CHUNK_MIN..CHUNK_MAX) length else 0
        guessed = follower ushr TRACE_FOLLOWER_LENGTH_BITS
    }

    /** Stores the buffer's first [length] bytes as the next part, under their [known] digest when it is taken. A
     *  [rule] cut is recorded as the previous part's follower; outside a rescan the next chunk starts empty and is
     *  guessed after a rule cut. */
    private fun emit(length: Int, known: ByteArray? = null, rule: Boolean = true) {
        if (!partsLease.resize((parts.size + 1L) * TRACE_CHUNK_REFERENCE_BYTES)) throw HeapCapacityException()
        cuts.cut(length, named = !rescanning)
        val digest = known ?: digestOf(length)
        val part = pack.put(buffer, length, digest)
        parts += part
        val before = previous
        if (rule && before != null) pack.follow(before, digest, length)
        previous = JsonScalars.str(part["hash"])
        if (rescanning) return
        count = 0
        gear = 0L
        if (rule) predict()
    }

    private fun digestOf(length: Int): ByteArray {
        sha.update(buffer, 0, length)
        return sha.digest()
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

    /** A cut [length] bytes after the one before. A [named] cut keeps the char the encoder named last, which lies at
     *  or before it. A rescanned cut can lie before that char, so it keeps none, char 0 and fed 0, and its mark
     *  measures on from the previous part's mark. */
    fun cut(length: Int, named: Boolean) {
        if (size == chars.size) grow()
        chars[size] = if (named) namedChar else 0
        feds[size] = if (named) namedFed else 0L
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
