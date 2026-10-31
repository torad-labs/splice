package splice.dialect.responses.reasoning

/** A positioned item owns one stored record, independently of its live and terminal observations. */
internal class ReasoningCachePositions {
    private val indexes = ReasoningPositionIndex()

    fun metadataBytes(index: String): Long = indexes.metadataBytes(index)

    /** Reconcile installed live/terminal duplicates before they consume restore budgets. */
    fun normalized(rounds: List<StoredRound>): List<StoredRound> {
        val keyed = linkedMapOf<String, StoredRound>()
        rounds.forEachIndexed { number, round ->
            val identity = round.ids.singleOrNull()?.let(indexes::identity) ?: "legacy-$number"
            val before = keyed[identity]
            keyed[identity] = if (before == null) {
                round
            } else {
                before.copy(ids = listOf(indexes.preferredIndex(before.ids.single(), round.ids.single())))
            }
        }
        return keyed.values.toList()
    }

    fun admitted(conversation: ReasoningCacheConvo, key: String, round: ReasoningCacheRound) {
        round.toolIds.singleOrNull()?.let(indexes::identity)?.let { conversation.positioned[it] = key }
    }

    /** Null is new admission; rejected metadata leaves all admitted reasoning and its stable order intact. */
    fun update(conversation: ReasoningCacheConvo, ids: List<String>, availableBytes: Long): ReasoningPositionUpdate? {
        val index = ids.singleOrNull() ?: return null
        val key = indexes.identity(index)?.let { conversation.positioned[it] } ?: return null
        val released = stabilize(conversation, index)
        val before = conversation.rounds.getValue(key)
        val merged = indexes.preferredIndex(before.toolIds.single(), index)
        val growth = indexes.metadataBytes(merged) - indexes.metadataBytes(before.toolIds.single())
        return when {
            merged == before.toolIds.single() -> ReasoningPositionUpdate(released != 0L, released)
            growth > availableBytes - released -> ReasoningPositionUpdate(released != 0L, released)
            else -> {
                replaceIndex(conversation, key, merged)
                ReasoningPositionUpdate(true, released + growth)
            }
        }
    }

    /** Reclaim only optional ordering metadata, preserving the complete cohort's accepted native order. */
    fun releaseMetadata(conversation: ReasoningCacheConvo): Long = applyAliases(
        conversation,
        indexes.withoutMetadata(conversation.rounds.values.mapNotNull { it.toolIds.singleOrNull() }),
    )

    /** Optional offered proof bytes cannot crowd out the round's retained reasoning payload. */
    fun admission(round: ReasoningCacheRound, availableBytes: Long): ReasoningCacheRound {
        if (round.bytes <= availableBytes) return round
        val ids = payloadIndexes(round.toolIds)
        val bytes = round.bytes - round.toolIds.sumOf(indexes::metadataBytes) + ids.sumOf(indexes::metadataBytes)
        return round.copy(toolIds = ids, bytes = bytes)
    }

    private fun payloadIndexes(ids: List<String>): List<String> {
        val aliases = indexes.withoutMetadata(ids, wholeCohort = false)
        return ids.map { aliases[it] ?: it }
    }

    private fun stabilize(conversation: ReasoningCacheConvo, incoming: String): Long = applyAliases(
        conversation,
        indexes.stableBefore(conversation.rounds.values.mapNotNull { it.toolIds.singleOrNull() }, incoming),
    )

    private fun applyAliases(conversation: ReasoningCacheConvo, aliases: Map<String, String>): Long =
        conversation.rounds.entries.sumOf { (key, round) ->
            val after = round.toolIds.singleOrNull()?.let(aliases::get)
            if (after == null) 0L else replaceIndex(conversation, key, after)
        }

    private fun replaceIndex(conversation: ReasoningCacheConvo, key: String, index: String): Long {
        val before = conversation.rounds.getValue(key)
        val growth = indexes.metadataBytes(index) - indexes.metadataBytes(before.toolIds.single())
        before.toolIds.forEach { conversation.byToolId.remove(it) }
        conversation.byToolId[index] = key
        conversation.rounds[key] = before.copy(toolIds = listOf(index), bytes = before.bytes + growth)
        conversation.bytes += growth
        return growth
    }

    fun promote(conversation: ReasoningCacheConvo, aliases: Map<String, String>): Boolean {
        var changed = false
        conversation.rounds.entries.forEach { entry ->
            val before = entry.value
            val ids = before.toolIds.map { index ->
                val position = index.substringBeforeLast(':')
                aliases[position]?.let { it + index.removePrefix(position) } ?: index
            }
            if (ids != before.toolIds) {
                before.toolIds.forEach { conversation.byToolId.remove(it) }
                ids.forEach { conversation.byToolId[it] = entry.key }
                entry.setValue(before.copy(toolIds = ids))
                changed = true
            }
        }
        return changed
    }

    fun stored(conversation: ReasoningCacheConvo): List<StoredRound> {
        val rounds = conversation.rounds.values.map { StoredRound(it.toolIds, it.envelopes) }
        return if (conversation.resultProofs.isEmpty()) {
            rounds
        } else {
            rounds + StoredRound(emptyList(), emptyList(), conversation.resultProofs.toMap())
        }
    }
}

internal data class ReasoningPositionUpdate(val changed: Boolean, val bytes: Long)
