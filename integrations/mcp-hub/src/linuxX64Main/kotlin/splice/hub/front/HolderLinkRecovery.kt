package splice.hub.front

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

internal class HolderLinkRecovery(
    private val context: HolderLinkContext,
    private val known: MutableMap<String, Holder>,
    private val replacements: HolderReplacements,
    private val persistence: HolderPersistence,
    memory: HolderMemory,
    proof: HolderProof,
) {
    private val handshakes = HolderHandshakeFlow(context, replacements, persistence, memory, proof)
    private val loss =
        HolderLinkLoss(
            context,
            replacements,
            persistence,
            ::reconnect,
            Holder::cancelReverseResponseRetry,
        )
    private val connections = HolderConnections(context.connections, ::fromHolder, loss::lost)
    private val generations = HolderGenerationReads(context.routes.attestations, context.timers, context.clock)

    var stopping: Boolean
        get() = loss.stopping
        set(value) {
            loss.stopping = value
        }

    fun connectCandidate(holder: Holder) {
        val link = connections.connect(holder)
        if (link == null) {
            replacements.links.failed(holder, "the daemon-started candidate did not accept a front connection")
        } else {
            bound(holder, link) { handshakes.recovery.candidate(holder, link) }
            link.claim()
        }
    }

    fun reconnect(holder: Holder, generation: String): Boolean {
        if (!handshakes.retained(holder, generation)) return false
        val existing = holder.link
        val link = existing ?: connections.connect(holder) ?: return false
        holder.scheduleReverseResponseRetry = { exact -> retry(holder, exact) }
        generations.bind(holder, link) { attested ->
            val prior = holder.generation
            if (attested == generation) {
                holder.attestedGeneration = attested
                handshakes.recovery.recover(holder, link)
            } else {
                handshakes.reset(holder, attested)
                context.routes.ownership.adopt(holder.socket, prior, attested)
                holder.attestedGeneration = attested
                persistence.changed()
                link.close()
            }
        }
        if (existing == null) link.claim()
        return !link.closed
    }

    fun adopt(fd: Int, socket: String, saved: ByteArray) {
        val holder = known.getOrPut(socket) { Holder(socket) }
        val link = connections.adopt(holder, fd, saved)
        bound(holder, link) { handshakes.recovery.recover(holder, link) }
    }

    fun knownInit(route: Route, then: (JsonObject?, String?) -> Unit) {
        val holder = route.socket?.let(known::get)
        val retained = holder?.takeIf { handshakes.retained(it, route.generation) }?.init
        when {
            retained != null -> then(retained, null)
            holder?.recoveryUncertain == true -> then(null, "the retained holder initialization is unproven")
            else -> {
                val listing = context.files.listingInit(route.listing)
                if (listing == null) live(route) { then(knownInit(route), it) } else then(listing, null)
            }
        }
    }

    fun live(route: Route, then: (String?) -> Unit) {
        val socket = route.socket
        if (socket == null) {
            then("splice did not say where ${route.server} runs")
        } else {
            live(route, known.getOrPut(socket) { Holder(socket) }, then)
        }
    }

    private fun knownInit(route: Route): JsonObject? = route.socket?.let(known::get)?.init

    private fun live(route: Route, holder: Holder, then: (String?) -> Unit) {
        val expected = route.generation
        if (expected != null && expected != holder.generation) {
            if (!handshakes.baseline(holder)) handshakes.reset(holder, expected)
        }
        when {
            holder.recoveryUncertain -> then("the retained holder initialization is unproven")
            holder.ready && handshakes.retained(holder, route.generation) -> then(null)
            holder.connecting -> holder.waiters += then
            else -> {
                holder.waiters += then
                holder.connecting = true
                attach(route, holder, true)
            }
        }
    }

    private fun attach(route: Route, holder: Holder, startAllowed: Boolean) {
        val link = connections.connect(holder)
        when {
            link != null -> {
                connected(route, holder, link)
                link.claim()
            }
            startAllowed -> context.routes.start(route) { failure -> started(route, holder, failure) }
            else -> holder.settle("${route.server} did not start")
        }
    }

    private fun connected(route: Route, holder: Holder, link: HolderLink) {
        bound(holder, link) { handshakes.recovery.attached(route, holder, link) }
    }

    private fun bound(holder: Holder, link: HolderLink, activated: () -> Unit) {
        holder.scheduleReverseResponseRetry = { generation -> retry(holder, generation) }
        generations.bind(holder, link) { attested ->
            val knownGeneration = holder.generation
            val prior = knownGeneration ?: context.routes.generation(holder.socket)
            handshakes.adopt(holder, attested)
            context.routes.ownership.adopt(holder.socket, prior, attested)
            holder.attestedGeneration = attested
            if (knownGeneration != attested) persistence.changed()
            activated()
        }
    }

    private fun retry(holder: Holder, generation: String) {
        if (holder.reverseResponseRetryGeneration == generation) return
        holder.cancelReverseResponseRetry()
        holder.reverseResponseRetryGeneration = generation
        holder.reverseResponseRetryCancel = context.timers.at(context.clock.now() + REVERSE_RESPONSE_RETRY) {
            holder.cancelReverseResponseRetry()
            val currentGeneration = holder.generation == generation && holder.link?.closed == false
            if (currentGeneration && handshakes.observed(holder)) {
                holder.resumeReverseResponses?.invoke(generation)
            }
        }
    }

    private fun started(route: Route, holder: Holder, failure: String?) {
        if (failure == null) {
            attach(route, holder, false)
        } else {
            holder.settle(failure)
        }
    }

    private fun fromHolder(holder: Holder, link: HolderLink, line: String) {
        if (holder.link === link && context.relay.inbound.control(holder, line)) return
        if (holder.link === link && handshakes.observed(holder)) {
            message(holder, link, line)
        } else if (holder.link === link) {
            holder.recoveryUncertain = true
            persistence.changed()
        }
    }

    private fun message(holder: Holder, link: HolderLink, line: String) {
        val msg = JsonText.objectOrNull(line) ?: return
        val id = (msg[Rpc.ID] as? JsonPrimitive)?.contentOrNull
        when {
            id == null || id != holder.handshakeId -> context.relay.inbound.fromHolder(holder, msg)
            Rpc.str(msg, Rpc.METHOD) != null -> context.relay.inbound.fromHolder(holder, msg)
            Rpc.RESULT !in msg && "error" !in msg -> context.relay.inbound.fromHolder(holder, msg)
            else -> handshakes.recovery.received(holder, link, msg)
        }
    }
}

private const val REVERSE_RESPONSE_RETRY = 250L

internal class HolderConnections(
    private val connections: Links,
    private val onLine: (Holder, HolderLink, String) -> Unit,
    private val onLost: (Holder, HolderLink) -> Unit,
) {
    fun connect(holder: Holder): HolderLink? = HolderLinkBinding(holder, onLine, onLost).connect(connections)

    fun adopt(holder: Holder, fd: Int, saved: ByteArray): HolderLink =
        HolderLinkBinding(holder, onLine, onLost).adopt(connections, fd, saved)
}

internal class HolderLinkBinding(
    private val holder: Holder,
    private val onLine: (Holder, HolderLink, String) -> Unit,
    private val onLost: (Holder, HolderLink) -> Unit,
) {
    private var link: HolderLink? = null

    fun connect(connections: Links): HolderLink? {
        val opened = connections.connect(holder.socket, ::line, ::lost) ?: return null
        link = opened
        return opened
    }

    fun adopt(connections: Links, fd: Int, saved: ByteArray): HolderLink {
        val opened = connections.adopt(fd, holder.socket, saved, ::line, ::lost)
        link = opened
        return opened
    }

    private fun line(line: String) {
        link?.let { onLine(holder, it, line) }
    }

    private fun lost() {
        link?.let { onLost(holder, it) }
    }
}

internal class HolderLinkLoss(
    private val context: HolderLinkContext,
    private val replacements: HolderReplacements,
    private val persistence: HolderPersistence,
    private val reconnect: (Holder, String) -> Boolean,
    private val cancelReverseResponseRetry: (Holder) -> Unit,
) {
    var stopping = false

    fun lost(holder: Holder, link: HolderLink) {
        if (holder.link !== link) return
        val generation = detached(holder)
        if (stopping) return
        context.files.say("lost the holder at ${holder.socket}")
        context.relay.lost(holder)
        confirmed(holder, generation)
        if (!replacementLost(holder, generation)) {
            context.routes.forget(holder.socket)
            holder.settle("${holder.socket} closed")
        }
    }

    private fun detached(holder: Holder): String? {
        cancelReverseResponseRetry(holder)
        val generation = holder.generation ?: context.routes.generation(holder.socket)
        holder.generation = generation
        holder.link = null
        holder.ready = false
        holder.connecting = false
        return generation
    }

    private fun confirmed(holder: Holder, generation: String?) {
        generation?.let { exact ->
            context.routes.replacements.confirmExit(
                holder.socket,
                exact,
                {
                    settlementPending(holder, exact).also { pending ->
                        if (pending) reconnect(holder, exact)
                    }
                },
            ) {
                context.relay.exited(holder, exact)
                replacements.retirements.oldExited(holder.socket, exact)
            }
        }
    }

    private fun settlementPending(holder: Holder, generation: String): Boolean =
        context.relay.awaitsExit(holder, generation) ||
            replacements.links.candidate(holder) != null ||
            replacements.links.old(holder, generation) != null

    private fun replacementLost(holder: Holder, generation: String?): Boolean {
        if (replacements.links.candidate(holder) != null) {
            replacements.links.failed(holder, "the candidate holder link closed; its process outcome is unknown")
            return true
        }
        val old = replacements.links.old(holder, generation) ?: return false
        if (old.phase !in setOf(RETIRING, RETIRE_BLOCKED, RETIRE_UNKNOWN)) {
            old.phase = OLD_HOLDER_LOST
            old.blocker = "the old holder link closed; its process outcome is unknown"
        }
        // The exact old route stays paused until confirmed() receives the daemon's durable generation-exit receipt.
        holder.settle("${holder.socket} closed")
        persistence.changed()
        return true
    }
}
