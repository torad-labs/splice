package splice.app.mods.handover

/**
 * The last batch accepted from each sender, kept for the [MOD_HANDOVER_RECEIPTS] most recent senders. A sender that
 * retries a batch whose reply was lost is recognised by it, and the list travels to the next daemon so a second
 * handover does not forget it.
 */
internal class ModAcceptedBatches {
    private val last = LinkedHashMap<String, Long>()

    fun of(sender: String): Long = last[sender] ?: -1L

    fun record(sender: String, ordinal: Long) {
        val known = last.remove(sender) ?: -1L
        last[sender] = maxOf(known, ordinal)
        while (last.size > MOD_HANDOVER_RECEIPTS) last.remove(last.keys.first())
    }

    fun record(received: Map<String, Long>) {
        received.forEach { (sender, ordinal) -> record(sender, ordinal) }
    }

    fun snapshot(): Map<String, Long> = LinkedHashMap(last)
}
