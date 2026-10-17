// NEW: Oct 11, 2026 — one retained perf line as a handful of bytes, and back.
//
// A record is: two flag bytes; the gap since the previous line's end and the line's own length; then, for a line that
// is a row, the ts as a difference from the previous row's, the six repeated descriptions by number, and the numeric
// fields as a presence mask and their packed values; then the cumulative-drops count and the leading ts when they differ
// from what the flags imply. The row's own ids (trace, request, response) and the provider's words are not kept: the
// line is read back from its byte range when a row is shown.
package splice.app.sources.dense

import splice.app.sources.PerfCachedLine
import splice.app.sources.PerfDropsHint
import splice.app.sources.PerfFieldNames
import splice.app.sources.PerfModelHint
import splice.app.sources.PerfNumericFields
import splice.app.sources.PerfNumericPacking
import splice.app.sources.PerfNumericSchema
import splice.app.sources.PerfRetention
import splice.core.perf.PerfTurnIds
import splice.usage.perf.PerfRow
import splice.usage.perf.PerfTranscriptLink
import splice.usage.perf.PerfTurnFacts

// why: flag bit 0: the record carries a row, not only its line.
private const val HAS_ROW = 1

// why: flag bit 1: the line was a liveness probe.
private const val IS_PROBE = 2

// why: the model hint sits in flag bits 2 and 3.
private const val MODEL_SHIFT = 2

// why: two bits hold a model hint of up to four values.
private const val MODEL_MASK = 0x3

// why: flag bit 4: the line is a drop candidate.
private const val DROPS_CANDIDATE = 1 shl 4

// why: flag bit 5: a dropped-line count follows.
private const val DROPS_COUNT = 1 shl 5

// why: the leading ts kind sits in flag bits 6 and 7.
private const val LEADING_SHIFT = 6

// why: two bits hold the three kinds of leading ts.
private const val LEADING_MASK = 0x3

// why: cache_cold is a tri-state in flag bits 8 and 9.
private const val COLD_SHIFT = 8

// why: compact is a tri-state in flag bits 10 and 11.
private const val COMPACT_SHIFT = 10

// why: captured is a tri-state in flag bits 12 and 13.
private const val CAPTURED_SHIFT = 12

// why: two bits hold absent, false or true.
private const val TRI_MASK = 0x3

// why: eight bits to a byte, for writing the flag word low byte first.
private const val BYTE_BITS = 8

// why: the line has no leading ts.
private const val LEADING_NONE = 0

// why: the leading ts equals the row's ts and is not stored.
private const val LEADING_SAME = 1

// why: the leading ts differs and is stored in place.
private const val LEADING_GIVEN = 2

// why: a few thousand presence masks cover the real data many times over; past this a schema is built per record.
private const val SCHEMAS_KEPT = 4_096

/** The difference base for the records of one generation: the previous row's ts and the previous line's end. */
internal data class PerfDenseState(var ts: Long = 0L, var end: Long = 0L)

/** Turns lines into records and records into lines. [keys] are the numeric fields a record keeps, in the order that
 *  names their bits; `ts` is not among them, a record's ts is its own. */
internal class PerfDenseCodec(private val names: PerfFieldNames, keys: List<String>) {
    private val kept = keys.filter { it != "ts" }
    private val strings = PerfStringTable()
    private val schemas = HashMap<Long, PerfNumericSchema>()
    private val scratch = PerfBytes()
    private val noTurns = PerfTurnIds()
    private val noDrops = PerfDropsHint(candidate = false, count = null)
    private val candidateDrops = PerfDropsHint(candidate = true, count = null)

    val retainedBytes: Long get() = strings.retainedBytes

    fun encode(entry: PerfCachedEntry, base: PerfDenseState): ByteArray {
        scratch.clear()
        val line = entry.line
        val row = line.row
        val flags = flagsOf(line)
        scratch.put(flags and FLAG_BYTE_MASK)
        scratch.put(flags shr BYTE_BITS)
        scratch.putZigzag(entry.start - base.end)
        scratch.putVarint(entry.end - entry.start)
        if (row != null) rowBytes(row, base)
        line.drops.count?.let(scratch::putZigzag)
        if (leadingKind(line) == LEADING_GIVEN) scratch.putZigzag(requireNotNull(line.leadingTs))
        base.end = entry.end
        return scratch.toArray()
    }

    fun decode(cursor: PerfByteCursor, base: PerfDenseState, generation: PerfGenerationRef): PerfCachedEntry {
        val flags = cursor.get() or (cursor.get() shl BYTE_BITS)
        val start = base.end + cursor.zigzag()
        val end = start + cursor.varint()
        val row = if (flags and HAS_ROW != 0) rowOf(cursor, base, flags) else null
        val dropsCount = if (flags and DROPS_COUNT != 0) cursor.zigzag() else null
        val leading = when ((flags shr LEADING_SHIFT) and LEADING_MASK) {
            LEADING_SAME -> row?.ts
            LEADING_GIVEN -> cursor.zigzag()
            else -> null
        }
        base.end = end
        val line = PerfCachedLine(
            row = row,
            numericBytes = 0L,
            leadingTs = leading,
            model = PerfModelHint.entries[(flags shr MODEL_SHIFT) and MODEL_MASK],
            drops = dropsOf(flags, dropsCount),
            probe = flags and IS_PROBE != 0,
            retention = PerfRetention(textBytes = 0L, located = true),
        ).placed(generation, start, end)
        return PerfCachedEntry(start, line, raw = null, end = end)
    }

    private fun dropsOf(flags: Int, count: Long?): PerfDropsHint = when {
        count != null -> PerfDropsHint(candidate = flags and DROPS_CANDIDATE != 0, count = count)
        flags and DROPS_CANDIDATE != 0 -> candidateDrops
        else -> noDrops
    }

    private fun flagsOf(line: PerfCachedLine): Int {
        val row = line.row
        var flags = if (row != null) HAS_ROW else 0
        if (line.probe) flags = flags or IS_PROBE
        flags = flags or (line.model.ordinal shl MODEL_SHIFT)
        if (line.drops.candidate) flags = flags or DROPS_CANDIDATE
        if (line.drops.count != null) flags = flags or DROPS_COUNT
        flags = flags or (leadingKind(line) shl LEADING_SHIFT)
        val facts = row?.facts ?: return flags
        return flags or (tri(facts.cacheCold) shl COLD_SHIFT) or (tri(facts.compact) shl COMPACT_SHIFT) or
            (tri(facts.captured) shl CAPTURED_SHIFT)
    }

    private fun leadingKind(line: PerfCachedLine): Int = when {
        line.leadingTs == null -> LEADING_NONE
        line.leadingTs == line.row?.ts -> LEADING_SAME
        else -> LEADING_GIVEN
    }

    private fun tri(value: Boolean?): Int = when (value) {
        null -> 0
        false -> 1
        true -> 2
    }

    private fun triOf(flags: Int, shift: Int): Boolean? = when ((flags shr shift) and TRI_MASK) {
        0 -> null
        1 -> false
        else -> true
    }

    private fun rowBytes(row: PerfRow, base: PerfDenseState) {
        scratch.putZigzag(row.ts - base.ts)
        base.ts = row.ts
        text(row.outcome)
        text(row.cause)
        text(row.facts.model)
        text(row.facts.session)
        text(row.facts.account)
        text(row.transcript.sessionId)
        numbers(row)
    }

    private fun text(value: String?) {
        if (value == null) {
            scratch.putVarint(STRING_NONE.toLong())
            return
        }
        val number = strings.numberOf(value)
        scratch.putVarint(number.toLong())
        if (number == STRING_INLINE) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            scratch.putVarint(bytes.size.toLong())
            scratch.putAll(bytes)
        }
    }

    private fun textAt(cursor: PerfByteCursor): String? {
        val number = cursor.varint().toInt()
        return when (number) {
            STRING_NONE -> null
            STRING_INLINE -> String(cursor.bytes(cursor.varint().toInt()), Charsets.UTF_8)
            else -> strings.textOf(number)
        }
    }

    private fun numbers(row: PerfRow) {
        val values = LongArray(kept.size)
        var mask = 0L
        var count = 0
        kept.forEachIndexed { bit, key ->
            row.fields[key]?.let {
                mask = mask or (1L shl bit)
                values[count++] = it
            }
        }
        val packed = PerfNumericPacking.pack(values, count)
        scratch.putVarint(mask)
        scratch.putVarint(packed.size.toLong())
        scratch.putAll(packed)
    }

    private fun rowOf(cursor: PerfByteCursor, base: PerfDenseState, flags: Int): PerfRow {
        val ts = base.ts + cursor.zigzag()
        base.ts = ts
        val outcome = requireNotNull(textAt(cursor))
        val cause = textAt(cursor)
        val model = textAt(cursor)
        val session = textAt(cursor)
        val account = textAt(cursor)
        val sessionId = textAt(cursor)
        val mask = cursor.varint()
        val packed = cursor.bytes(cursor.varint().toInt())
        return PerfRow(
            ts = ts,
            outcome = outcome,
            fields = PerfNumericFields(schemaOf(mask), packed),
            facts = PerfTurnFacts(
                model = model,
                session = session,
                account = account,
                cacheCold = triOf(flags, COLD_SHIFT),
                compact = triOf(flags, COMPACT_SHIFT),
                captured = triOf(flags, CAPTURED_SHIFT),
            ),
            turns = noTurns,
            transcript = PerfTranscriptLink(sessionId = sessionId),
            cause = cause,
        )
    }

    private fun schemaOf(mask: Long): PerfNumericSchema {
        schemas[mask]?.let { return it }
        val schema = names.schema(kept.filterIndexed { bit, _ -> mask and (1L shl bit) != 0L })
        if (schemas.size < SCHEMAS_KEPT) schemas[mask] = schema
        return schema
    }
}

// why: the low flag byte is written alone, then the high byte, so each is masked to eight bits.
private const val FLAG_BYTE_MASK = 0xFF
