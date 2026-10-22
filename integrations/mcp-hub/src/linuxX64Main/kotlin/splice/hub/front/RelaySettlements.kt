package splice.hub.front

import splice.hub.holder.HolderProtocol

/** Reconciles a holder's bounded receipt set with its generation-bound front ledgers. */
internal class RelaySettlements(
    private val calls: MutableMap<String, WireOutstanding>,
    private val requests: ServerRequests,
    private val persistence: HolderPersistence,
) {
    private val recoveries = HashMap<Holder, HolderRecovery>()
    private val acknowledgements = HolderAcknowledgements()

    fun answered(holder: Holder, key: String): WireOutstanding? {
        val call = calls[key]
            ?.takeIf { it.holder === holder && it.generation == holder.generation }
            ?: return null
        calls.remove(key)
        val persisted = if (holder.settlementProtocol) persistence.checkpoint() else true
        if (!persisted) {
            calls[key] = call
        } else if (holder.settlementProtocol) {
            acknowledgements.send(holder, call.generation, key)
        } else {
            persistence.changed()
        }
        return call
    }

    fun resume(holder: Holder, generation: String) {
        val link = holder.link
        when {
            holder.generation != generation || holder.attestedGeneration != generation -> Unit
            !holder.settlementProtocol -> requests.reconciliation.resume(holder, generation)
            link == null -> Unit
            recoveries[holder]?.matches(generation, link) == true -> Unit
            else -> start(holder, generation, link)
        }
    }

    fun control(holder: Holder, frame: HolderProtocol.Event) {
        when (frame) {
            HolderProtocol.Event.Ready -> ready(holder)
            is HolderProtocol.Event.State -> state(holder, frame)
            is HolderProtocol.Event.Settled -> settled(holder, frame.key)
            is HolderProtocol.Event.Refused -> refused(holder, frame.key)
            is HolderProtocol.Event.Complete -> complete(holder, frame.round)
        }
    }

    fun exited(holder: Holder, generation: String) {
        if (recoveries[holder]?.generation == generation) {
            recoveries.remove(holder)
            holder.settlementRecovery = null
        }
    }

    private fun start(holder: Holder, generation: String, link: HolderLink) {
        val recovery = HolderRecovery(
            generation,
            Ids.session(),
            link,
            calls.filterValues { it.holder === holder && it.generation == generation }.keys,
            requests.reconciliation.retainedKeys(holder, generation),
        )
        recoveries[holder] = recovery
        holder.settlementRecovery = recovery.round
        link.send(HolderProtocol.recover(recovery.round))
    }

    private fun ready(holder: Holder) {
        holder.settlementProtocol = true
        persistence.changed()
        holder.generation?.let { holder.resumeReverseResponses?.invoke(it) }
    }

    private fun state(holder: Holder, frame: HolderProtocol.Event.State) {
        val recovery = recoveries[holder]
        if (recovery?.matches(frame.round, holder.generation) == true) {
            recovery.states[frame.key] = frame.state
            if (frame.state == SETTLED) settled(holder, frame.key)
        }
    }

    private fun complete(holder: Holder, round: String) {
        val recovery = recoveries[holder]?.takeIf { it.matches(round, holder.generation) } ?: return
        recoveries.remove(holder)
        holder.settlementRecovery = null
        clearMissing(holder, recovery)
        replayReverse(holder, recovery)
        requests.reconciliation.resume(holder, recovery.generation)
    }

    private fun clearMissing(holder: Holder, recovery: HolderRecovery) {
        val removed = recovery.calls.mapNotNull { key ->
            calls[key]?.takeIf { it.holder === holder && it.generation == recovery.generation }?.let { key to it }
        }.filter { (key, _) -> key !in recovery.states }
        removed.forEach { (key, _) -> calls.remove(key) }
        if (removed.isNotEmpty() && !persistence.checkpoint()) removed.forEach { (key, call) -> calls[key] = call }
    }

    private fun replayReverse(holder: Holder, recovery: HolderRecovery) {
        val replay = recovery.requests.filter { key ->
            recovery.states[key] != QUEUED && recovery.states[key] != SETTLED
        }
        requests.reconciliation.recovered(holder, recovery.generation, replay.toSet())
    }

    private fun refused(holder: Holder, key: String) {
        val generation = holder.generation ?: return
        val call = calls[key]?.takeIf { it.holder === holder && it.generation == generation } ?: return
        calls.remove(key)
        if (persistence.checkpoint()) {
            refuse(call)
        } else {
            calls[key] = call
        }
    }

    private fun refuse(call: WireOutstanding) {
        call.conn?.let { conn ->
            conn.onGone = null
            call.clientId?.let { id ->
                conn.reply(UNAVAILABLE, Rpc.error(id, "the holder could not retain this request"))
            }
        }
    }

    private fun settled(holder: Holder, key: String) {
        val generation = holder.generation ?: return
        if (key.startsWith(FORWARD_PREFIX)) {
            forwardSettled(holder, generation, key)
        } else if (requests.reconciliation.settled(holder, generation, key)) {
            acknowledgements.send(holder, generation, key)
        }
    }

    private fun forwardSettled(holder: Holder, generation: String, key: String) {
        val call = calls[key]
        if (call == null) {
            acknowledgements.send(holder, generation, key)
        } else if (call.holder === holder && call.generation == generation) {
            calls.remove(key)
            if (persistence.checkpoint()) acknowledgements.send(holder, generation, key) else calls[key] = call
        }
    }
}

private class HolderAcknowledgements {
    fun send(holder: Holder, generation: String, key: String) {
        if (holder.generation == generation) holder.link?.send(HolderProtocol.acknowledge(key))
    }
}

internal class RelayResumeBinding(private val settlements: RelaySettlements) {
    fun remember(holder: Holder) {
        holder.resumeReverseResponses = { generation -> settlements.resume(holder, generation) }
    }
}

private data class HolderRecovery(
    val generation: String,
    val round: String,
    val link: HolderLink,
    val calls: Set<String>,
    val requests: Set<String>,
    val states: MutableMap<String, String> = HashMap(),
) {
    fun matches(expected: String, current: String?): Boolean =
        round == expected && generation == current

    fun matches(expected: String, current: HolderLink): Boolean =
        generation == expected && link === current
}

private const val FORWARD_PREFIX = "f:"
private const val QUEUED = "queued"
private const val SETTLED = "settled"
