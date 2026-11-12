package splice.hub.front

import splice.hub.holder.HolderControlWire
import splice.hub.holder.HolderPairClaim
import splice.hub.holder.HolderPairProtocol
import splice.hub.holder.HolderProtocol

/** Claims data first, then negotiates control without publishing unpaired control as settlement evidence. */
internal class HolderControlLinks(
    private val connections: Links,
    private val relay: RelayInbound,
    private val timers: Timers,
    private val clock: Clock,
) {
    fun connect(holder: Holder, data: HolderLink, activated: () -> Unit) {
        if (data.closed || data.evidenceControl?.closed == false) return
        HolderControlPair(holder, data, relay, timers, clock).connect(connections, activated)
    }
}

/** All callbacks retain their exact data link and generation, including the tentative negotiation deadline. */
private class HolderControlPair(
    private val holder: Holder,
    private val data: HolderLink,
    private val relay: RelayInbound,
    private val timers: Timers,
    private val clock: Clock,
) {
    private val generation = holder.attestedGeneration
    private val evidenceExpected = holder.settlementProtocol || data.recorded
    private var control: HolderLink? = null
    private var claim: HolderPairClaim? = null
    private var cancel: (() -> Unit)? = null
    private var activated: (() -> Unit)? = null

    fun connect(connections: Links, activate: () -> Unit) {
        if (generation == null || holder.link !== data) return
        activated = activate
        data.negotiating = true
        val link = connections.connect(HolderProtocol.controlSocket(holder.socket), ::received, ::lost)
        if (link == null) {
            if (evidenceExpected) data.close() else legacy()
        } else {
            control = link
            data.evidenceControl = link
            link.claim()
            awaitOffer(link)
        }
    }

    private fun received(line: String) {
        val link = control ?: return
        if (!current(link)) return
        val identity = HolderPairProtocol.offered(line)
        when {
            identity != null && claim == null -> offered(link, identity)
            claim != null && HolderPairProtocol.pairedToken(line) == claim?.token -> {
                cancel?.invoke()
                cancel = null
                data.recorded = true
                data.negotiating = false
                holder.control = link
                holder.settlementProtocol = true
                data.beginRecovery = { recoveryBudget(link) }
                data.endRecovery = {
                    cancel?.invoke()
                    cancel = null
                }
                activate()
                holder.resumeReverseResponses?.invoke(requireNotNull(generation))
            }
            holder.control === link -> relay.control(holder, line)
            claim == null -> legacyReceipt(link, line)
        }
    }

    private fun offered(link: HolderLink, identity: String) {
        if (Ids.socket(holder.socket) != identity) return link.close()
        val proof = requireNotNull(generation).encodeToByteArray().joinToString("") {
            it.toUByte().toString(HEX).padStart(2, '0')
        }
        val paired = HolderPairClaim(Ids.session(), identity, proof)
        data.negotiating = true
        data.reading = false
        data.legacyReceipts = null
        claim = paired
        cancel?.invoke()
        cancel = timers.at(clock.now() + PAIR_BUDGET) { if (holder.control !== link) link.close() }
        val frame = HolderPairProtocol.pair(paired)
        if (!data.send(frame) || !link.send(frame)) link.close()
    }

    private fun awaitOffer(link: HolderLink) {
        cancel = timers.at(clock.now() + OFFER_BUDGET) {
            if (claim == null && current(link)) {
                if (!evidenceExpected) legacy()
                legacyDeadline(link)
            }
        }
    }

    private fun legacyDeadline(link: HolderLink) {
        if (current(link) && data.legacyReceipts !== link) {
            cancel = timers.at(clock.now() + PAIR_BUDGET) {
                if (data.legacyReceipts !== link && current(link)) link.close()
            }
        }
    }

    private fun recoveryBudget(link: HolderLink) {
        cancel?.invoke()
        cancel = timers.at(clock.now() + PAIR_BUDGET) { if (current(link)) link.close() }
    }

    /** V1 receipts may free old terminal holder records, but never authorize replay or absence-based release. */
    private fun legacyReceipt(link: HolderLink, line: String) {
        when (val event = HolderProtocol.event(line)) {
            HolderProtocol.Event.Ready -> {
                cancel?.invoke()
                cancel = null
                legacy()
                data.legacyReceipts = link
                link.send(HolderControlWire.recover(Ids.session()))
            }
            is HolderProtocol.Event.Settled -> {
                if (data.legacyReceipts === link) link.send(HolderControlWire.acknowledge(event.key))
            }
            is HolderProtocol.Event.State -> {
                if (data.legacyReceipts === link && event.state == "settled") {
                    link.send(HolderControlWire.acknowledge(event.key))
                }
            }
            else -> Unit
        }
    }

    private fun current(link: HolderLink): Boolean =
        holder.link === data && !data.closed && holder.attestedGeneration == generation && data.evidenceControl === link

    private fun lost() {
        cancel?.invoke()
        cancel = null
        val link = control
        val owned = data.evidenceControl === link
        val legacyProof = data.legacyReceipts === link
        if (holder.control === link) holder.control = null
        if (data.evidenceControl === link) data.evidenceControl = null
        if (data.legacyReceipts === link) data.legacyReceipts = null
        data.beginRecovery = null
        data.endRecovery = null
        val sameGeneration = holder.link === data && holder.attestedGeneration == generation
        if (owned && sameGeneration) {
            if (requiresEvidence(legacyProof)) data.close() else legacy()
        }
    }

    private fun requiresEvidence(legacyProof: Boolean): Boolean = claim != null || evidenceExpected && !legacyProof

    private fun legacy() {
        data.negotiating = false
        data.recorded = false
        holder.settlementProtocol = false
        holder.settlementRecovery = null
        activate()
        generation?.let { holder.resumeReverseResponses?.invoke(it) }
    }

    private fun activate() {
        val ready = activated
        activated = null
        ready?.invoke()
        if (holder.link === data && !data.closed) data.resumeReading()
    }
}

private const val OFFER_BUDGET = 250L
private const val PAIR_BUDGET = 60_000L
private const val HEX = 16
