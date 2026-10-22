package splice.hub.front

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

internal class HolderLinkRecovery(
    private val context: HolderLinkContext,
    private val known: MutableMap<String, Holder>,
    private val replacements: HolderReplacements,
    persistence: HolderPersistence,
    memory: HolderMemory,
    proof: HolderProof,
) {
    private val handshakes = HolderHandshakeFlow(context, replacements, persistence, memory, proof)
    private val loss = HolderLinkLoss(context, replacements, persistence)
    private val connections = HolderConnections(context.connections, ::fromHolder, loss::lost)
    private val generations = HolderGenerationReads(context.routes, context.timers, context.clock)

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
            generations.bind(holder, link)
            handshakes.candidate(holder, link)
        }
    }

    fun reconnect(holder: Holder, generation: String): Boolean {
        if (!handshakes.retained(holder, generation)) return false
        val link = holder.link ?: connections.connect(holder) ?: return false
        generations.bind(holder, link)
        holder.settle(null)
        holder.resumeReverseResponses?.invoke(generation)
        link.resumeReading()
        return holder.link === link && !link.closed && holder.ready
    }

    fun adopt(fd: Int, socket: String, saved: ByteArray) {
        val holder = known.getOrPut(socket) { Holder(socket) }
        val link = connections.adopt(holder, fd, saved)
        generations.bind(holder, link)
        handshakes.recover(holder, link)
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
        route.generation?.takeIf { it != holder.generation }?.let { handshakes.reset(holder, it) }
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
            link != null -> connected(route, holder, link)
            startAllowed -> context.routes.start(route) { failure -> started(route, holder, failure) }
            else -> holder.settle("${route.server} did not start")
        }
    }

    private fun connected(route: Route, holder: Holder, link: HolderLink) {
        generations.bind(holder, link)
        handshakes.attached(route, holder, link)
    }

    private fun started(route: Route, holder: Holder, failure: String?) {
        if (failure == null) {
            attach(route, holder, false)
        } else {
            holder.settle(failure)
        }
    }

    private fun fromHolder(holder: Holder, link: HolderLink, line: String) {
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
        if (id == holder.handshakeId) {
            handshakes.received(holder, link, msg)
        } else {
            context.relay.inbound.fromHolder(holder, msg)
        }
    }
}

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
                { context.relay.awaitsExit(holder, exact) },
            ) {
                context.relay.exited(holder, exact)
            }
        }
    }

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
        context.routes.forget(holder.socket)
        holder.settle("${holder.socket} closed")
        persistence.changed()
        return true
    }
}
