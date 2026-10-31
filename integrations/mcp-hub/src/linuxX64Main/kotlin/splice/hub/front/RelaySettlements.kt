package splice.hub.front

import kotlinx.serialization.json.JsonObject
import splice.hub.holder.HolderControlWire
import splice.hub.holder.HolderPairProtocol
import splice.hub.holder.HolderProtocol

/** Reconciles a holder's bounded receipt set with its generation-bound front ledgers. */
internal class RelaySettlements(
    private val calls: MutableMap<String, WireOutstanding>,
    private val requests: ServerRequests,
    private val persistence: HolderPersistence,
    private val toolResults: NativeMcpResults,
) {
    private val recoveries = HashMap<Holder, HolderRecovery>()
    private val acknowledgements = HolderAcknowledgements()
    private val recoveryCompletion = HolderRecoveryCompletion(calls, requests, persistence)

    fun answered(holder: Holder, key: String, answer: JsonObject): WireOutstanding? {
        val call = calls[key]
            ?.takeIf { it.holder === holder && it.generation == holder.generation }
            ?: return null
        // The holder must not discard its durable response until the exact raw reply is captured.
        toolResults.capture(call.toolResult, answer)
        call.backendSettled = true
        calls.remove(key)
        val persisted = persistence.checkpoint()
        if (!persisted) {
            calls[key] = call
        } else {
            acknowledgements.send(holder, call.generation, key)
            persistence.changed()
        }
        return call
    }

    /** Detaching releases routing only. Backend ownership needs response/receipt proof or exact exit. */
    fun detached(key: String, call: WireOutstanding) {
        call.conn?.onGone = null
        call.conn = null
        if (calls[key] !== call || !call.backendSettled) return
        calls.remove(key)
        if (persistence.checkpoint()) {
            acknowledgements.send(call.holder, call.generation, key)
        } else {
            calls[key] = call
        }
    }

    fun resume(holder: Holder, generation: String) {
        val control = holder.control
        when {
            holder.generation != generation || holder.attestedGeneration != generation -> Unit
            !holder.settlementProtocol -> requests.reconciliation.resume(holder, generation)
            control == null -> Unit
            recoveries[holder]?.matches(generation, control) == true -> Unit
            else -> start(holder, generation, control)
        }
    }

    fun control(holder: Holder, frame: HolderProtocol.Event) {
        when (frame) {
            HolderProtocol.Event.Ready -> ready(holder)
            is HolderProtocol.Event.Armed -> armed(holder, frame.key)
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
        val data = holder.link
        data?.beginRecovery?.invoke()
        val barrierSent = data?.recorded == true && data.send(HolderPairProtocol.barrier(recovery.round))
        if (!barrierSent || !link.send(HolderControlWire.recover(recovery.round))) {
            link.close()
        }
    }

    private fun ready(holder: Holder) {
        if (!holder.settlementProtocol) {
            holder.settlementProtocol = true
            persistence.changed()
        }
        holder.generation?.let { generation -> resume(holder, generation) }
    }

    private fun armed(holder: Holder, key: String) {
        if (holder.settlementRecovery == null) {
            holder.generation?.let { generation -> requests.reconciliation.armed(holder, generation, key) }
        }
    }

    private fun state(holder: Holder, frame: HolderProtocol.Event.State) {
        val recovery = recoveries[holder]
        if (recovery?.matches(frame.round, holder.generation) == true) {
            if (recovery.states.size >= MAX_RECOVERED_KEYS && frame.key !in recovery.states) {
                holder.control?.close()
                return
            }
            recovery.states[frame.key] = frame.state
            if (frame.state == SETTLED) settled(holder, frame.key)
        }
    }

    private fun complete(holder: Holder, round: String) {
        val recovery = recoveries[holder]?.takeIf { it.matches(round, holder.generation) } ?: return
        recoveries.remove(holder)
        holder.link?.endRecovery?.invoke()
        holder.settlementRecovery = null
        recoveryCompletion.reconcile(holder, recovery)
    }

    private fun refused(holder: Holder, key: String) {
        val generation = holder.generation ?: return
        if (!key.startsWith(FORWARD_PREFIX)) {
            requests.reconciliation.refused(holder, generation, key)
            return
        }
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
            call.backendSettled = true
            if (call.conn == null) detached(key, call)
        }
    }
}

/** Applies one recovered receipt snapshot before live reverse delivery resumes. */
private class HolderRecoveryCompletion(
    private val calls: MutableMap<String, WireOutstanding>,
    private val requests: ServerRequests,
    private val persistence: HolderPersistence,
) {
    fun reconcile(holder: Holder, recovery: HolderRecovery) {
        releaseAbsent(holder, recovery)
        val replay = recovery.requests.filter { key ->
            recovery.states[key] == null || recovery.states[key] == ARMED
        }
        requests.reconciliation.recovered(holder, recovery.generation, replay.toSet())
        requests.reconciliation.resume(holder, recovery.generation)
    }

    private fun releaseAbsent(holder: Holder, recovery: HolderRecovery) {
        val removed = recovery.calls.mapNotNull { key ->
            calls[key]
                ?.takeIf { it.holder === holder && it.generation == recovery.generation }
                ?.let { key to it }
        }.filter { (key, call) -> call.recorded && call.conn == null && key !in recovery.states }
        removed.forEach { (key, _) -> calls.remove(key) }
        if (removed.isNotEmpty() && !persistence.checkpoint()) {
            removed.forEach { (key, call) -> calls[key] = call }
        }
    }
}

private class HolderAcknowledgements {
    fun send(holder: Holder, generation: String, key: String) {
        if (holder.generation == generation) {
            val control = holder.control ?: holder.link?.legacyReceipts
            control?.send(HolderControlWire.acknowledge(key))
        }
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

private const val MAX_RECOVERED_KEYS = 512
private const val FORWARD_PREFIX = "f:"
private const val ARMED = "armed"
private const val SETTLED = "settled"
