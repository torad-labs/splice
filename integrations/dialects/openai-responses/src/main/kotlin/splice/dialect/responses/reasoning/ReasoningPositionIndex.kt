package splice.dialect.responses.reasoning

import splice.core.reasoning.HISTORY_POSITION_PREFIX
import java.security.MessageDigest
import java.util.HexFormat

internal data class ReasoningItemOrder(
    val round: String,
    val outputIndex: Int,
    val terminal: Boolean,
    val reasoningId: String,
    val terminalBatch: String? = null,
)

/** Ordering metadata is confined to private indexes; the persisted envelope schema remains unchanged. */
internal class ReasoningPositionIndex {
    fun index(position: String, envelope: String, order: ReasoningItemOrder?): String {
        val suffix = order?.let {
            val batch = it.terminalBatch?.takeIf(ORDER_BATCH_DIGEST::matches)
            val observation = when {
                !it.terminal -> "l.${it.outputIndex}"
                batch == null -> "t.${it.outputIndex}"
                else -> "b.-.${it.outputIndex}.$batch"
            }
            "${digest(it.round)}.$observation."
        }.orEmpty()
        return "$position:$suffix${digest(order?.reasoningId ?: envelope)}"
    }

    fun positions(snapshot: Map<String, List<String>>): Map<String, List<String>> {
        val records = snapshot.entries.filter { it.key.startsWith(HISTORY_POSITION_PREFIX) }.map { entry ->
            PositionRecord(entry.key.substringBeforeLast(':'), entry.key.substringAfterLast(':'), entry.value)
        }.groupBy { it.identity }.values.map { copies -> copies.reduce(::preferred) }
        val complete = records.groupBy { it.round }.filterValues(::completeBatch).keys
        return records.groupBy { it.position }.mapValues { (_, runs) ->
            runs.groupBy { it.round }.values.flatMap { round -> ordered(round, round.first().round in complete) }
                .flatMap { it.envelopes }
        }
    }

    fun identity(index: String): String? = record(index)?.identity

    /** Live positions include real streamed prose; terminal observations only add order evidence there. */
    fun preferredIndex(first: String, second: String): String {
        val previous = record(first) ?: return first
        val candidate = record(second) ?: return first
        val chosen = preferred(previous, candidate)
        return "${chosen.position}:${chosen.suffix}"
    }

    /** New hash/live-index metadata spends the existing character budget; legacy keys keep their old charge. */
    fun metadataBytes(index: String): Long {
        val value = record(index) ?: return 0L
        if (value.batch == null) return 0L
        val legacyMode = if (value.liveIndex == null) "t" else "l"
        val old = "${value.position}:${value.round}.$legacyMode.${value.liveIndex ?: value.terminalIndex}.${value.id}"
        return (index.length - old.length).coerceAtLeast(0).toLong()
    }

    /** A different retry cannot erase the last complete array's already proven stable order. */
    fun stableBefore(indexes: List<String>, incoming: String): Map<String, String> {
        val candidate = record(incoming) ?: return emptyMap()
        val cohort = indexes.mapNotNull(::record).filter { it.round == candidate.round }
        val complete = cohort.isNotEmpty() && completeBatch(cohort)
        return if (!complete || cohort.first().batch == candidate.batch) {
            emptyMap()
        } else {
            cohort.associate { previous ->
                val before = "${previous.position}:${previous.suffix}"
                before to "${previous.position}:${previous.round}.l.${previous.terminalIndex}.${previous.id}"
            }
        }
    }

    private fun record(index: String): PositionRecord? = index.takeIf { it.startsWith(HISTORY_POSITION_PREFIX) }?.let {
        PositionRecord(it.substringBeforeLast(':'), it.substringAfterLast(':'), emptyList())
    }

    private fun preferred(first: PositionRecord, second: PositionRecord): PositionRecord {
        val live = first.liveIndex ?: second.liveIndex
        val base = if (first.liveIndex == null && second.liveIndex != null) second else first
        if (first.position != second.position) return base
        val terminal = if (second.batch != null) second else first
        if (terminal.batch == null) return base
        val suffix = "${base.round}.b.${live ?: "-"}.${terminal.terminalIndex}.${terminal.batch}.${base.id}"
        return PositionRecord(base.position, suffix, base.envelopes)
    }

    private fun completeBatch(round: List<PositionRecord>): Boolean {
        val batch = round.first().batch ?: return false
        val indices = round.mapNotNull { it.terminalIndex }
        return round.all { it.batch == batch } && indices.distinct().size == round.size
    }

    private fun ordered(round: List<PositionRecord>, complete: Boolean): List<PositionRecord> = when {
        complete -> round.sortedBy { it.terminalIndex }
        round.any { it.legacyTerminal } && round.any { it.liveIndex != null } -> round
        else -> round.sortedWith(
            compareBy<PositionRecord> { it.liveIndex == null }.thenBy {
                it.liveIndex ?: it.terminalIndex
            },
        )
    }

    private fun digest(text: String): String = HexFormat.of().formatHex(
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)),
    )
}

private val ORDER_BATCH_DIGEST = Regex("[0-9a-f]{64}")

// why: batch indexes encode round, mode, live index, terminal index, array hash, then item identity.
private const val POSITION_TERMINAL_INDEX_PART = 3

// why: the array hash follows the two independent order indexes in the batch suffix grammar.
private const val POSITION_TERMINAL_BATCH_PART = 4

private data class PositionRecord(val position: String, val suffix: String, val envelopes: List<String>) {
    private val parts = suffix.split('.')
    val round = parts.first()
    val id = parts.last()
    val identity = "$round:$id"
    val legacyTerminal = parts.getOrNull(1) == "t"
    val liveIndex = when (parts.getOrNull(1)) {
        "l", "b" -> parts.getOrNull(2)?.toIntOrNull()
        else -> null
    }
    val terminalIndex = when (parts.getOrNull(1)) {
        "t" -> parts.getOrNull(2)?.toIntOrNull()
        "b" -> parts.getOrNull(POSITION_TERMINAL_INDEX_PART)?.toIntOrNull()
        else -> null
    }
    val batch = if (parts.getOrNull(
            1,
        ) == "b"
    ) {
        parts.getOrNull(POSITION_TERMINAL_BATCH_PART)?.takeIf(ORDER_BATCH_DIGEST::matches)
    } else {
        null
    }
}
