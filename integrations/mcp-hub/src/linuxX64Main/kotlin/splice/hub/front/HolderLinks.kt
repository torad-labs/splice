package splice.hub.front

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

internal class HolderLinks(
    private val context: HolderLinkContext,
    private val known: MutableMap<String, Holder>,
    private val replacements: HolderReplacements,
    private val changed: () -> Unit,
) {
    private val memory = HolderMemory(known, context.clock)
    private val handshake = HolderHandshake(memory)

    fun connectCandidate(holder: Holder) = attachCandidate(holder)

    var stopping = false

    fun of(route: Route): Holder? = route.socket?.let(known::get)

    /** A holder connection carried across an exec. The holder was initialized before, so it is ready as it stands. */
    fun adopt(fd: Int, socket: String, saved: ByteArray) {
        val holder = known.getOrPut(socket) { Holder(socket) }
        holder.generation = context.routes.generation(socket) ?: holder.generation
        val link = context.connections.adopt(fd, socket, saved, { line -> fromHolder(holder, line) }, { lost(holder) })
        holder.link = link
        val candidate = replacements.links.candidate(holder)
        if (candidate?.phase == CANDIDATE_STARTING) {
            holder.ready = false
            holder.connecting = true
            link.send(Rpc.encode(handshake.hello()))
            context.timers.at(context.clock.now() + HANDSHAKE_BUDGET) {
                if (!holder.ready && holder.connecting) {
                    replacements.links.failed(holder, "the candidate did not resume initialize after upgrade")
                }
            }
        } else {
            holder.ready = holder.init != null
            if (!holder.ready) link.close()
        }
    }

    /** The server's initialize result without starting it: the front's memory, else the kept listing, else a start. */
    fun knownInit(route: Route, then: (JsonObject?, String?) -> Unit) {
        of(route)?.init?.let { return then(it, null) }
        context.files.listingInit(route.listing)?.let { return then(it, null) }
        live(route) { failure -> then(of(route)?.init, failure) }
    }

    /** [route]'s holder, connected and initialized; started through the daemon when nothing listens. */
    fun live(route: Route, then: (String?) -> Unit) {
        val socket = route.socket ?: return then("splice did not say where ${route.server} runs")
        val holder = known.getOrPut(socket) { Holder(socket) }
        if (route.generation != null && holder.generation != route.generation) {
            holder.generation = route.generation
            holder.ready = false
            holder.connecting = false
            holder.init = null
            holder.identity = null
        }
        if (holder.ready) return then(null)
        holder.waiters += then
        if (holder.connecting) return
        holder.connecting = true
        attach(route, holder, startAllowed = true)
    }

    fun touch(sockets: Set<String>) = memory.touch(sockets)

    fun restore(saved: JsonObject?) = memory.restore(saved, context.routes)

    fun state(): JsonObject = memory.state()

    private fun attachCandidate(holder: Holder) {
        val link = context.connections.connect(holder.socket, { line -> fromHolder(holder, line) }, { lost(holder) })
        if (link == null) {
            replacements.links.failed(holder, "the daemon-started candidate did not accept a front connection")
            return
        }
        holder.link = link
        holder.identity = Ids.socket(holder.socket)
        holder.init = null
        link.send(Rpc.encode(handshake.hello()))
        context.timers.at(context.clock.now() + HANDSHAKE_BUDGET) {
            if (!holder.ready && holder.connecting) {
                replacements.links.failed(holder, "the candidate did not answer initialize")
            }
        }
    }

    private fun attach(route: Route, holder: Holder, startAllowed: Boolean) {
        val link = context.connections.connect(holder.socket, { line -> fromHolder(holder, line) }, { lost(holder) })
        when {
            link != null -> handshake(route, holder, link)
            startAllowed -> context.routes.start(route) { failure ->
                if (failure == null) attach(route, holder, startAllowed = false) else holder.settle(failure)
            }
            else -> holder.settle("${route.server} did not start")
        }
    }

    private fun handshake(route: Route, holder: Holder, link: HolderLink) {
        holder.link = link
        val identity = Ids.socket(holder.socket)
        if (holder.init != null && holder.identity == identity) {
            context.files.say("adopted ${route.server} at ${holder.socket} without a handshake")
            return holder.settle(null)
        }
        holder.identity = identity
        holder.init = null
        link.send(Rpc.encode(handshake.hello()))
        context.timers.at(context.clock.now() + HANDSHAKE_BUDGET) {
            if (!holder.ready && holder.connecting) {
                holder.settle("${route.server} did not answer initialize")
            }
        }
    }

    private fun fromHolder(holder: Holder, line: String) {
        holder.generation = context.routes.generation(holder.socket) ?: holder.generation
        val msg = JsonText.objectOrNull(line) ?: return
        val key = (msg[Rpc.ID] as? JsonPrimitive)?.contentOrNull
        val handshake = Rpc.str(msg, Rpc.METHOD) == null && key?.startsWith(HELLO) == true
        if (handshake) handshaken(holder, msg) else context.relay.fromHolder(holder, msg)
    }

    private fun handshaken(holder: Holder, msg: JsonObject) {
        val init = msg[Rpc.RESULT] as? JsonObject
        if (init == null) {
            val words = if (replacements.links.candidate(holder) == null) {
                "the server refused initialize"
            } else {
                "the candidate refused initialize"
            }
            replacements.links.failed(holder, words)
            return
        }
        if (replacements.links.verified(holder, init) != null) return
        holder.init = init
        holder.lastCall = context.clock.epoch()
        holder.link?.send("""{"jsonrpc":"2.0","method":"notifications/initialized"}""")
        holder.settle(null)
        changed()
    }

    private fun lost(holder: Holder) {
        val generation = holder.generation ?: context.routes.generation(holder.socket)
        holder.generation = generation
        holder.link = null
        holder.ready = false
        holder.connecting = false
        if (stopping) return
        context.files.say("lost the holder at ${holder.socket}")
        holder.init = null
        holder.identity = null
        context.relay.lost(holder)
        generation?.let { exact ->
            context.routes.replacements.confirmExit(
                holder.socket,
                exact,
                { context.relay.awaitsExit(holder, exact) },
            ) {
                context.relay.exited(holder, exact)
            }
        }
        val candidate = replacements.links.candidate(holder)
        if (candidate != null) {
            replacements.links.failed(holder, "the candidate holder link closed; its process outcome is unknown")
            return
        }
        val old = replacements.links.old(holder, generation)
        if (old != null) {
            if (old.phase !in setOf(RETIRING, RETIRE_BLOCKED)) {
                old.phase = OLD_HOLDER_LOST
                old.blocker = "the old holder link closed; its process outcome is unknown"
            }
            context.routes.forget(holder.socket)
            holder.settle("${holder.socket} closed")
            changed()
            return
        }
        context.routes.forget(holder.socket)
        holder.settle("${holder.socket} closed")
    }
}

private class HolderHandshake(private val memory: HolderMemory) {
    fun hello(): JsonObject {
        val capabilities = buildJsonObject { put("roots", JsonObject(emptyMap())) }
        return buildJsonObject {
            put("jsonrpc", "2.0")
            put(Rpc.ID, "$HELLO${memory.nextHello()}")
            put(Rpc.METHOD, INITIALIZE)
            put(
                Rpc.PARAMS,
                buildJsonObject {
                    put(Rpc.PROTOCOL, Rpc.VERSIONS.first())
                    put("capabilities", capabilities)
                    put(
                        "clientInfo",
                        buildJsonObject {
                            put("name", "splice-mcp-front")
                            put("version", "1")
                        },
                    )
                },
            )
        }
    }
}

private class HolderMemory(
    private val known: MutableMap<String, Holder>,
    private val clock: Clock,
) {
    private var next = 0L

    fun nextHello(): Long = ++next

    /** Marks the holders at [sockets] as called now, so the daemon's idle stop never takes one mid-call. */
    fun touch(sockets: Set<String>) {
        val now = clock.epoch()
        sockets.mapNotNull(known::get).forEach { it.lastCall = now }
    }

    fun restore(saved: JsonObject?, routes: Routes) {
        saved?.forEach { (socket, value) ->
            val body = value as? JsonObject ?: return@forEach
            val holder = known.getOrPut(socket) { Holder(socket) }
            holder.generation = routes.generation(socket)
            holder.identity = Rpc.str(body, IDENTITY)
            holder.init = body[INITIALIZE] as? JsonObject
            holder.lastCall = (body[LAST_CALL] as? JsonPrimitive)?.longOrNull ?: 0L
        }
    }

    fun state(): JsonObject =
        JsonObject(
            known.values.mapNotNull { holder ->
                holder.init?.let { init ->
                    holder.socket to buildJsonObject {
                        put(IDENTITY, holder.identity)
                        put(INITIALIZE, init)
                        put(LAST_CALL, holder.lastCall)
                    }
                }
            }.toMap(),
        )
}
