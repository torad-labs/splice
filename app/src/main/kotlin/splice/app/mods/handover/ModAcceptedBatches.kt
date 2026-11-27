package splice.app.mods.handover

import splice.core.util.WallClock

/** What one daemon hands its successor: the last ordinal accepted per sender, and the wall time it was accepted. */
internal data class ModReceipts(val ordinals: Map<String, Long> = emptyMap(), val at: Map<String, Long> = emptyMap())

/**
 * The last batch accepted from each sender. A sender that retries a batch whose reply was lost is recognised by it, and
 * the list travels to the next daemon so a later handover does not forget it.
 *
 * A receipt lives until [MOD_HANDOVER_RECEIPT_MS] after its batch was accepted. That time is written where the batch
 * first arrived and every daemon after it carries it unchanged, so passing the list on never moves the horizon. A
 * sender is not dropped for being one of many: only age ends a receipt, or the high [MOD_HANDOVER_RECEIPT_CAP] that
 * only a runaway list could reach. A sender from before receipts carried a time is dated by its arrival here.
 */
internal class ModAcceptedBatches(private val clock: WallClock = WallClock(System::currentTimeMillis)) {
    private val last = LinkedHashMap<String, Receipt>()

    fun of(sender: String): Long = last[sender]?.takeIf { it.liveAt(clock()) }?.ordinal ?: -1L

    fun record(sender: String, ordinal: Long) {
        record(sender, ordinal, clock())
    }

    fun record(received: ModReceipts) {
        received.ordinals.forEach { (sender, ordinal) -> record(sender, ordinal, received.at[sender] ?: clock()) }
    }

    fun snapshot(): ModReceipts {
        last.values.removeIf { !it.liveAt(clock()) }
        return ModReceipts(last.mapValues { it.value.ordinal }, last.mapValues { it.value.at })
    }

    private fun record(sender: String, ordinal: Long, at: Long) {
        val known = last.remove(sender)?.takeIf { it.liveAt(clock()) }
        // The same batch told again keeps the time it was first accepted; only a later batch moves it.
        val receipt = if (known != null && known.ordinal >= ordinal) known else Receipt(ordinal, at)
        if (!receipt.liveAt(clock())) return
        last[sender] = receipt
        while (last.size > MOD_HANDOVER_RECEIPT_CAP) last.remove(last.keys.first())
    }

    private data class Receipt(val ordinal: Long, val at: Long) {
        fun liveAt(now: Long) = at + MOD_HANDOVER_RECEIPT_MS > now
    }
}
