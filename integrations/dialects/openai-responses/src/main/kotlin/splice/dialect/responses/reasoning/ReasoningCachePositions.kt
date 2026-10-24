package splice.dialect.responses.reasoning

/** A positioned item owns one stored record, independently of its live and terminal observations. */
internal class ReasoningCachePositions {
    private val indexes = ReasoningPositionIndex()

    /** Reconcile installed live/terminal duplicates before they consume restore budgets. */
    fun normalized(rounds: List<StoredRound>): List<StoredRound> {
        val keyed = linkedMapOf<String, StoredRound>()
        rounds.forEachIndexed { number, round ->
            val identity = round.ids.singleOrNull()?.let(indexes::identity) ?: "legacy-$number"
            val before = keyed[identity]
            keyed[identity] = if (before != null &&
                indexes.preferredIndex(before.ids.single(), round.ids.single()) == before.ids.single()
            ) {
                before
            } else {
                round
            }
        }
        return keyed.values.toList()
    }

    fun admitted(conversation: ReasoningCacheConvo, key: String, round: ReasoningCacheRound) {
        round.toolIds.singleOrNull()?.let(indexes::identity)?.let { conversation.positioned[it] = key }
    }

    /** Null is new admission; false is an unchanged observation; true changes metadata only. */
    fun update(conversation: ReasoningCacheConvo, ids: List<String>): Boolean? {
        val index = ids.singleOrNull() ?: return null
        val key = indexes.identity(index)?.let { conversation.positioned[it] } ?: return null
        val before = conversation.rounds.getValue(key)
        return if (indexes.preferredIndex(before.toolIds.single(), index) == before.toolIds.single()) {
            false
        } else {
            before.toolIds.forEach { conversation.byToolId.remove(it) }
            conversation.byToolId[index] = key
            conversation.rounds[key] = before.copy(toolIds = ids)
            true
        }
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

    fun stored(conversation: ReasoningCacheConvo): List<StoredRound> =
        conversation.rounds.values.map { StoredRound(it.toolIds, it.envelopes) }
}
