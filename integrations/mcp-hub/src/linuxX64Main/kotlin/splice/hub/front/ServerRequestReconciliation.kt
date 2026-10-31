package splice.hub.front

/** Recovery operations over the reverse ledger stay separate from live response staging. */
internal class ServerRequestReconciliation(
    private val ledger: ServerRequestLedger,
    private val persistence: HolderPersistence,
    private val delivery: ServerRequestDelivery,
) {
    fun retainedKeys(holder: Holder, generation: String): Set<String> = ledger.requests
        .filterValues { it.holder === holder && it.generation == generation }
        .keys

    fun resume(holder: Holder, generation: String) {
        ledger.requests
            .filterValues { resumable(it, holder, generation) }
            .forEach { (key, asked) -> delivery.send(key, asked) }
    }

    fun recovered(holder: Holder, generation: String, replay: Set<String>) {
        val reset = ledger.requests.filter { (key, asked) ->
            key in replay && asked.holder === holder && asked.generation == generation &&
                asked.response.delivery == RESPONSE_WRITE_UNCERTAIN && asked.response.recorded
        }
        if (reset.isNotEmpty()) prepareReplay(reset)
    }

    fun armed(holder: Holder, generation: String, key: String) {
        ledger.requests[key]?.takeIf { asked ->
            asked.holder === holder && asked.generation == generation &&
                asked.response.delivery == RESPONSE_WRITE_UNCERTAIN
        }?.let { delivery.armed(key, it) }
    }

    fun refused(holder: Holder, generation: String, key: String) {
        ledger.requests[key]?.takeIf { it.holder === holder && it.generation == generation }?.let(delivery::notArmed)
    }

    fun settled(holder: Holder, generation: String, key: String): Boolean {
        val asked = ledger.requests[key] ?: return true
        val exact = asked.holder === holder && asked.generation == generation
        return exact && settle(key, asked)
    }

    private fun resumable(asked: ServerRequestOutstanding, holder: Holder, generation: String): Boolean =
        asked.holder === holder &&
            asked.generation == generation &&
            asked.response.delivery == RESPONSE_PREPARED

    private fun prepareReplay(reset: Map<String, ServerRequestOutstanding>) {
        reset.values.forEach {
            it.response.delivery = RESPONSE_PREPARED
            it.response.sentOn = null
        }
        if (!persistence.checkpoint()) {
            reset.values.forEach { it.response.delivery = RESPONSE_WRITE_UNCERTAIN }
        }
    }

    private fun settle(key: String, asked: ServerRequestOutstanding): Boolean {
        val settled = ledger.settles(key, asked, persistence::checkpoint)
        if (settled) {
            asked.holder.reverseResponseRetries = 0
            persistence.changed()
        }
        return settled
    }
}
