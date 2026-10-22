package splice.hub.front

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

internal class HolderHandshakeFlow(
    private val context: HolderLinkContext,
    private val replacements: HolderReplacements,
    private val persistence: HolderPersistence,
    memory: HolderMemory,
    private val proof: HolderProof,
) {
    private val attempts = HolderHandshakeAttempts(context, persistence, memory, proof)
    private val outcomes = HolderHandshakeOutcomes(replacements, persistence)

    fun retained(holder: Holder, generation: String?): Boolean = proof.retained(holder, generation)

    fun observed(holder: Holder): Boolean = proof.observed(holder)

    fun recover(holder: Holder, link: HolderLink) {
        when {
            holder.recoveryUncertain -> outcomes.failed(holder, UNPROVEN_INITIALIZATION)
            holder.handshakeId != null -> resumed(holder)
            proof.retained(holder, null) -> outcomes.ready(holder)
            else -> outcomes.failed(holder, MISSING_INITIALIZATION)
        }
        if (!link.closed) link.resumeReading()
    }

    fun candidate(holder: Holder, link: HolderLink) {
        if (holder.handshakeId != null) {
            resumed(holder)
        } else {
            clearStaleAttempt(holder)
            holder.identity = Ids.socket(holder.socket)
            holder.init = null
            started(holder, link, CANDIDATE_INITIALIZATION)
        }
    }

    fun attached(route: Route, holder: Holder, link: HolderLink) {
        if (proof.retained(holder, route.generation)) {
            context.files.say("reconnected ${route.server} at ${holder.socket} without initialize")
            outcomes.ready(holder)
        } else if (holder.handshakeId != null) {
            resumed(holder)
        } else {
            holder.identity = Ids.socket(holder.socket)
            holder.init = null
            started(holder, link, "${route.server} has no durable initialize request")
        }
    }

    fun received(holder: Holder, link: HolderLink, msg: JsonObject) {
        if (!attempts.pending(holder)) return
        val init = msg[Rpc.RESULT] as? JsonObject
        when {
            init == null -> refused(holder)
            replacements.links.verified(holder, init) != null -> Unit
            else -> completed(holder, link, init)
        }
    }

    fun reset(holder: Holder, generation: String) {
        holder.generation = generation
        holder.ready = false
        holder.connecting = false
        holder.identity = null
        holder.init = null
        holder.recoveryUncertain = false
        attempts.clear(holder)
    }

    private fun started(holder: Holder, link: HolderLink, words: String) {
        if (!attempts.start(holder, link, ::expired)) outcomes.failed(holder, words)
    }

    private fun resumed(holder: Holder) {
        holder.ready = false
        holder.connecting = true
        if (!attempts.resume(holder, ::expired)) outcomes.failed(holder, UNPROVEN_INITIALIZATION)
    }

    private fun completed(holder: Holder, link: HolderLink, init: JsonObject) {
        holder.init = init
        attempts.clear(holder)
        if (persistence.checkpoint()) {
            holder.lastCall = context.clock.epoch()
            link.send(INITIALIZED)
            if (holder.link === link) outcomes.ready(holder)
        } else {
            outcomes.uncertain(holder)
        }
    }

    private fun refused(holder: Holder) {
        val words = if (replacements.links.candidate(holder) == null) {
            "the server refused initialize"
        } else {
            "the candidate refused initialize"
        }
        outcomes.failed(holder, words)
    }

    private fun expired(holder: Holder) {
        outcomes.failed(holder, "the holder did not answer its original initialize request")
    }

    private fun clearStaleAttempt(holder: Holder) {
        if (holder.handshakeGeneration != null) attempts.clear(holder)
    }
}

internal class HolderHandshakeOutcomes(
    private val replacements: HolderReplacements,
    private val persistence: HolderPersistence,
) {
    fun ready(holder: Holder) {
        holder.settle(null)
        holder.generation?.let { holder.resumeReverseResponses?.invoke(it) }
    }

    fun uncertain(holder: Holder) {
        holder.init = null
        holder.recoveryUncertain = true
        persistence.changed()
        failed(holder, "the initialize result could not be durably retained")
    }

    fun failed(holder: Holder, words: String) {
        if (replacements.links.candidate(holder) != null) {
            replacements.links.failed(holder, words)
        } else {
            holder.settle(words)
        }
    }
}

internal class HolderHandshakeAttempts(
    private val context: HolderLinkContext,
    private val persistence: HolderPersistence,
    private val memory: HolderMemory,
    private val proof: HolderProof,
) {
    private val request = HolderHandshakeRequest()

    fun start(holder: Holder, link: HolderLink, expired: (Holder) -> Unit): Boolean {
        val attempt = attempt(holder) ?: return false
        holder.handshakeId = attempt.id
        holder.handshakeGeneration = attempt.generation
        holder.handshakeIdentity = attempt.identity
        return persisted(holder, link, attempt.id, expired)
    }

    fun resume(holder: Holder, expired: (Holder) -> Unit): Boolean {
        val id = holder.handshakeId ?: return false
        if (!proof.pending(holder)) return false
        deadline(holder, id, expired)
        return true
    }

    fun pending(holder: Holder): Boolean = proof.pending(holder)

    fun clear(holder: Holder) {
        holder.handshakeId = null
        holder.handshakeGeneration = null
        holder.handshakeIdentity = null
    }

    private fun attempt(holder: Holder): HolderHandshakeAttempt? {
        val identity = holder.identity
        val generation = holder.generation
        return if (identity == null || generation == null) {
            null
        } else {
            HolderHandshakeAttempt(memory.hello(), generation, identity)
        }
    }

    private fun persisted(
        holder: Holder,
        link: HolderLink,
        id: String,
        expired: (Holder) -> Unit,
    ): Boolean {
        if (!persistence.checkpoint()) {
            clear(holder)
            return false
        }
        link.send(Rpc.encode(request.request(id)))
        deadline(holder, id, expired)
        return true
    }

    private fun deadline(holder: Holder, id: String, expired: (Holder) -> Unit) {
        context.timers.at(context.clock.now() + HANDSHAKE_BUDGET) {
            if (holder.handshakeId == id && holder.connecting) expired(holder)
        }
    }
}

internal class HolderProof(private val routes: Routes) {
    fun retained(holder: Holder, expected: String?): Boolean {
        val generation = holder.generation ?: return false
        if (!initialized(holder) || !trusted(holder)) return false
        return expected(expected, generation) && routeMatches(holder, generation)
    }

    fun pending(holder: Holder): Boolean {
        val generation = holder.handshakeGeneration ?: return false
        if (!sameAttempt(holder, generation) || !trusted(holder)) return false
        return routeMatches(holder, generation)
    }

    fun observed(holder: Holder): Boolean {
        val current = routes.generation(holder.socket) ?: return true
        if (holder.generation == null) {
            holder.generation = current
            return true
        }
        return holder.generation == current
    }

    fun unproven(holder: Holder): Boolean =
        listOf(
            holder.generation == null,
            changed(holder),
            missingIdentity(holder),
            incompleteAttempt(holder),
        ).any { it }

    private fun initialized(holder: Holder): Boolean =
        holder.init != null && holder.handshakeId == null

    private fun trusted(holder: Holder): Boolean {
        if (holder.recoveryUncertain) return false
        return identityMatches(holder)
    }

    private fun sameAttempt(holder: Holder, generation: String): Boolean {
        if (holder.handshakeId == null) return false
        if (generation != holder.generation) return false
        return holder.handshakeIdentity == holder.identity
    }

    private fun identityMatches(holder: Holder): Boolean {
        if (holder.identity != Ids.socket(holder.socket)) return false
        return holder.handshakeIdentity?.let { it == holder.identity } != false
    }

    private fun expected(expected: String?, generation: String): Boolean =
        expected == null || expected == generation

    private fun routeMatches(holder: Holder, generation: String): Boolean {
        val current = routes.generation(holder.socket)
        return current == null || current == generation
    }

    private fun changed(holder: Holder): Boolean {
        val current = routes.generation(holder.socket)
        return current != null && current != holder.generation
    }

    private fun missingIdentity(holder: Holder): Boolean =
        holder.init != null && holder.identity == null

    private fun incompleteAttempt(holder: Holder): Boolean {
        if (holder.handshakeId == null) return false
        return holder.handshakeGeneration == null || holder.handshakeIdentity == null
    }
}

internal data class HolderHandshakeAttempt(
    val id: String,
    val generation: String,
    val identity: String,
)

internal class HolderHandshakeRequest {
    fun request(id: String): JsonObject {
        val capabilities = buildJsonObject { put("roots", JsonObject(emptyMap())) }
        return buildJsonObject {
            put("jsonrpc", "2.0")
            put(Rpc.ID, id)
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

internal class HolderMemory(
    private val known: MutableMap<String, Holder>,
    private val clock: Clock,
    private val proof: HolderProof,
) {
    private var next = 0L

    fun hello(): String = "$HELLO${++next}"

    fun touch(sockets: Set<String>) {
        val now = clock.epoch()
        sockets.mapNotNull(known::get).forEach { it.lastCall = now }
    }

    fun restore(saved: JsonObject?) {
        saved?.forEach { (socket, value) ->
            (value as? JsonObject)?.let { restore(known.getOrPut(socket) { Holder(socket) }, it) }
        }
    }

    fun state(): JsonObject = JsonObject(known.values.mapNotNull(::remembered).toMap())

    private fun restore(holder: Holder, body: JsonObject) {
        holder.generation = Rpc.str(body, GENERATION)
        holder.identity = Rpc.str(body, IDENTITY)
        holder.init = body[INITIALIZE] as? JsonObject
        holder.handshakeId = Rpc.str(body, HANDSHAKE_ID)
        holder.handshakeGeneration = Rpc.str(body, HANDSHAKE_GENERATION)
        holder.handshakeIdentity = Rpc.str(body, HANDSHAKE_IDENTITY)
        holder.lastCall = (body[LAST_CALL] as? JsonPrimitive)?.longOrNull ?: 0L
        holder.recoveryUncertain = Rpc.str(body, RECOVERY_UNCERTAIN) == "true" || proof.unproven(holder)
        holder.handshakeId?.removePrefix(HELLO)?.toLongOrNull()?.let { next = maxOf(next, it) }
    }

    private fun remembered(holder: Holder): Pair<String, JsonObject>? {
        val hasHandshake = holder.init != null || holder.handshakeId != null
        return if (hasHandshake || holder.recoveryUncertain) {
            holder.socket to record(holder)
        } else {
            null
        }
    }

    private fun record(holder: Holder): JsonObject = buildJsonObject {
        holder.generation?.let { put(GENERATION, it) }
        holder.identity?.let { put(IDENTITY, it) }
        holder.init?.let { put(INITIALIZE, it) }
        holder.handshakeId?.let { put(HANDSHAKE_ID, it) }
        holder.handshakeGeneration?.let { put(HANDSHAKE_GENERATION, it) }
        holder.handshakeIdentity?.let { put(HANDSHAKE_IDENTITY, it) }
        put(LAST_CALL, holder.lastCall)
        put(RECOVERY_UNCERTAIN, holder.recoveryUncertain)
    }
}

private const val INITIALIZED = """{"jsonrpc":"2.0","method":"notifications/initialized"}"""
private const val UNPROVEN_INITIALIZATION = "the retained holder initialization is unproven"
private const val MISSING_INITIALIZATION = "the retained holder has no durable initialization evidence"
private const val CANDIDATE_INITIALIZATION = "the candidate has no durable initialize request"
