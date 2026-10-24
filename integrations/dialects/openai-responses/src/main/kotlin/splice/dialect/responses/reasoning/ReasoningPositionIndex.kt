package splice.dialect.responses.reasoning

import splice.core.reasoning.HISTORY_POSITION_PREFIX
import java.security.MessageDigest
import java.util.HexFormat

internal data class ReasoningItemOrder(
    val round: String,
    val outputIndex: Int,
    val terminal: Boolean,
    val reasoningId: String,
)

/** Ordering metadata is confined to private indexes; the persisted envelope schema remains unchanged. */
internal class ReasoningPositionIndex {
    fun index(position: String, envelope: String, order: ReasoningItemOrder?): String {
        val suffix = order?.let {
            "${digest(it.round)}.${if (it.terminal) "t" else "l"}.${it.outputIndex}."
        }.orEmpty()
        return "$position:$suffix${digest(order?.reasoningId ?: envelope)}"
    }

    fun positions(snapshot: Map<String, List<String>>): Map<String, List<String>> {
        val records = snapshot.entries.filter { it.key.startsWith(HISTORY_POSITION_PREFIX) }.map { entry ->
            PositionRecord(entry.key.substringBeforeLast(':'), entry.key.substringAfterLast(':'), entry.value)
        }
        val terminalItems = records.filter { it.terminal }.map { it.identity }.toSet()
        return records.filter { it.terminal || it.identity !in terminalItems }
            .groupBy { it.position }.mapValues { (_, runs) ->
                runs.groupBy { it.round }.values.flatMap { round ->
                    round.sortedWith(
                        compareBy<PositionRecord> { !it.terminal }.thenBy { it.outputIndex },
                    ).flatMap { it.envelopes }
                }
            }
    }

    private fun digest(text: String): String = HexFormat.of().formatHex(
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)),
    )
}

private class PositionRecord(val position: String, suffix: String, val envelopes: List<String>) {
    private val parts = suffix.split('.')
    val round = parts.first()
    val identity = "$round:${parts.last()}"
    val terminal = parts.getOrNull(1) == "t"
    val outputIndex = parts.getOrNull(2)?.toIntOrNull() ?: 0
}
