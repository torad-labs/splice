package splice.app.mods.handover

import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/** A frozen list of receipts. Only their ages change while the sender waits for the next daemon. */
internal class ModReceipts(private val batches: Map<String, ModAcceptedBatch> = emptyMap()) {
    val ordinals = batches.mapValues { it.value.ordinal }

    fun ages(): Map<String, Long> = batches.mapValues { it.value.accepted.elapsedNow().inWholeMilliseconds }
}

internal data class ModAcceptedBatch(val ordinal: Long, val accepted: TimeMark) {
    fun live(): Boolean = accepted.elapsedNow() < MOD_HANDOVER_RECEIPT_MS.milliseconds
}

/**
 * Keep the last accepted ordinal per sender beyond its retry window. Each daemon measures elapsed time locally
 * and the next daemon rebases the age onto its own clock. Neither a clock correction nor a quick handover renews
 * or expires a receipt. The count cap only bounds memory if the sender list runs away.
 */
internal class ModAcceptedBatches {
    private val last = LinkedHashMap<String, ModAcceptedBatch>()

    fun of(sender: String): Long = last[sender]?.takeIf(ModAcceptedBatch::live)?.ordinal ?: -1L

    fun record(sender: String, ordinal: Long) {
        record(sender, ModAcceptedBatch(ordinal, TimeSource.Monotonic.markNow()))
    }

    fun record(ordinals: Map<String, Long>, ages: Map<String, Long>) {
        val now = TimeSource.Monotonic.markNow()
        ordinals.forEach { (sender, ordinal) ->
            val age = ages.getValue(sender)
            if (age < MOD_HANDOVER_RECEIPT_MS) {
                record(sender, ModAcceptedBatch(ordinal, now - age.milliseconds))
            }
        }
    }

    fun snapshot(): ModReceipts {
        last.values.removeIf { !it.live() }
        return ModReceipts(LinkedHashMap(last))
    }

    private fun record(sender: String, receipt: ModAcceptedBatch) {
        val known = last.remove(sender)?.takeIf(ModAcceptedBatch::live)
        // A repeat of the same ordinal never renews it.
        last[sender] = if (known != null && known.ordinal >= receipt.ordinal) known else receipt
        while (last.size > MOD_HANDOVER_RECEIPT_CAP) last.remove(last.keys.first())
    }
}
