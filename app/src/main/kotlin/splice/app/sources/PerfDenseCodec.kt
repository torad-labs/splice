// NEW: Oct 11, 2026 — one retained perf line as a handful of bytes, and back.
//
// A record is: two flag bytes; the gap since the previous line's end and the line's own length; then, for a line that
// is a row, the ts as a difference from the previous row's, the six repeated descriptions by number, and the numeric
// fields as a presence mask and their packed values; then the cumulative-drops count and the leading ts when they differ
// from what the flags imply. The row's own ids (trace, request, response) and the provider's words are not kept: the
// line is read back from its byte range when a row is shown.
package splice.app.sources

import splice.core.perf.PerfTurnIds
import splice.usage.perf.PerfRow
import splice.usage.perf.PerfTranscriptLink
import splice.usage.perf.PerfTurnFacts

// Flag layout, low bits first.
private const val HAS_ROW = 1
private const val IS_PROBE = 2
private const val MODEL_SHIFT = 2
private const val MODEL_MASK = 0x3
private const val DROPS_CANDIDATE = 1 shl 4
private const val DROPS_COUNT = 1 shl 5
private const val LEADING_SHIFT = 6
private const val LEADING_MASK = 0x3
private const val COLD_SHIFT = 8
private const val COMPACT_SHIFT = 10
private const val CAPTURED_SHIFT = 12
private const val TRI_MASK = 0x3
private const val BYTE_BITS = 8

// What the leading ts says: nothing, the row's own ts, or a number written in place.
private const val LEADING_NONE = 0
private const val LEADING_SAME = 1
private const val LEADING_GIVEN = 2

// Of the numeric schemas by presence mask, no more than this many are kept for reuse.
private const val SCHEMAS_KEPT = 4_096

/** The difference base for the records of one generation: the previous row's ts and the previous line's end. */
internal data class PerfDenseState(var ts: Long = 0L, var end: Long = 0L)

/** Turns lines into records and records into lines. [keys] are the numeric fields a record keeps, in the order that
 *  names their bits; `ts` is not among them, a record's ts is its own. */
internal class PerfDenseCodec(private val names: PerfFieldNames, keys: List<String>) {
    private val kept = keys.filter { it != "ts" }
    private val strings = PerfStringTable()
    private val schemas = HashMap<Int, PerfNumericSchema>()
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
        scratch.put(flags and BYTE_MASK)
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
        var mask = 0
        var count = 0
        kept.forEachIndexed { bit, key ->
            row.fields[key]?.let {
                mask = mask or (1 shl bit)
                values[count++] = it
            }
        }
        val packed = PerfNumericPacking.pack(values, count)
        scratch.putVarint(mask.toLong())
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
        val mask = cursor.varint().toInt()
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

    private fun schemaOf(mask: Int): PerfNumericSchema {
        schemas[mask]?.let { return it }
        val schema = names.schema(kept.filterIndexed { bit, _ -> mask and (1 shl bit) != 0 })
        if (schemas.size < SCHEMAS_KEPT) schemas[mask] = schema
        return schema
    }
}

private const val BYTE_MASK = 0xFF
