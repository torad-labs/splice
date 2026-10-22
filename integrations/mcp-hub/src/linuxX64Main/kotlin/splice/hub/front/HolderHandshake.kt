package splice.hub.front

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

internal class HolderHandshakeFlow(
    internal val context: HolderLinkContext,
    internal val replacements: HolderReplacements,
    internal val persistence: HolderPersistence,
    memory: HolderMemory,
    internal val proof: HolderProof,
) {
    internal val attempts = HolderHandshakeAttempts(context, persistence, memory, proof)
    internal val outcomes = HolderHandshakeOutcomes(replacements, persistence)
    internal val notifications = HolderInitializedDelivery(context, persistence, outcomes)
    val recovery = HolderHandshakeRecovery(this)

    fun retained(holder: Holder, generation: String?): Boolean = proof.retained(holder, generation)

    fun observed(holder: Holder): Boolean = proof.observed(holder)

    fun baseline(holder: Holder): Boolean = proof.baseline(holder)

    fun adopt(holder: Holder, generation: String) {
        if (holder.generation == null && proof.baseline(holder)) {
            holder.generation = generation
        } else if (holder.generation != generation) {
            reset(holder, generation)
        }
    }

    fun reset(holder: Holder, generation: String) {
        holder.resetGeneration(generation)
        attempts.clear(holder)
    }
}

internal class HolderHandshakeRecovery(private val flow: HolderHandshakeFlow) {
    fun recover(holder: Holder, link: HolderLink) {
        when {
            holder.recoveryUncertain -> flow.outcomes.failed(holder, UNPROVEN_INITIALIZATION)
            holder.initializedUncertain -> flow.outcomes.failed(holder, INITIALIZED_UNPROVEN)
            holder.handshakeId != null -> resumed(holder)
            flow.proof.retained(holder, null) -> flow.notifications.ready(holder, link)
            else -> flow.outcomes.failed(holder, MISSING_INITIALIZATION)
        }
    }

    fun candidate(holder: Holder, link: HolderLink) {
        when {
            holder.recoveryUncertain -> flow.outcomes.failed(holder, UNPROVEN_INITIALIZATION)
            flow.proof.retained(holder, holder.generation) -> retainedCandidate(holder, link)
            holder.handshakeId != null -> resumed(holder)
            else -> {
                clearStaleAttempt(holder)
                holder.identity = Ids.socket(holder.socket)
                holder.init = null
                started(holder, link, CANDIDATE_INITIALIZATION)
            }
        }
    }

    fun attached(route: Route, holder: Holder, link: HolderLink) {
        if (flow.proof.retained(holder, route.generation)) {
            flow.context.files.say("reconnected ${route.server} at ${holder.socket} without initialize")
            flow.notifications.ready(holder, link)
        } else if (holder.handshakeId != null) {
            resumed(holder)
        } else {
            holder.identity = Ids.socket(holder.socket)
            holder.init = null
            started(holder, link, "${route.server} has no durable initialize request")
        }
    }

    fun received(holder: Holder, link: HolderLink, msg: JsonObject) {
        if (!flow.attempts.pending(holder)) return
        val init = msg[Rpc.RESULT] as? JsonObject
        when {
            init == null -> refused(holder)
            flow.replacements.links.verified(holder, init) != null -> Unit
            else -> completed(holder, link, init)
        }
    }

    private fun retainedCandidate(holder: Holder, link: HolderLink) {
        holder.init?.takeIf { flow.replacements.links.verified(holder, it) == null }?.let {
            if (flow.persistence.checkpoint()) {
                flow.notifications.ready(holder, link)
            } else {
                flow.outcomes.failed(holder, "the retained candidate could not be durably marked ready")
            }
        }
    }

    private fun started(holder: Holder, link: HolderLink, words: String) {
        if (!flow.attempts.start(holder, link, ::expired)) flow.outcomes.failed(holder, words)
    }

    private fun resumed(holder: Holder) {
        holder.ready = false
        holder.connecting = true
        if (!flow.attempts.resume(holder, ::expired)) flow.outcomes.failed(holder, UNPROVEN_INITIALIZATION)
    }

    private fun completed(holder: Holder, link: HolderLink, init: JsonObject) {
        holder.init = init
        holder.initializedPending = true
        flow.attempts.clear(holder)
        if (flow.persistence.checkpoint()) {
            flow.notifications.pending(holder, link)
        } else {
            flow.outcomes.uncertain(holder)
        }
    }

    private fun refused(holder: Holder) {
        val words = if (flow.replacements.links.candidate(holder) == null) {
            "the server refused initialize"
        } else {
            "the candidate refused initialize"
        }
        flow.outcomes.failed(holder, words)
    }

    private fun expired(holder: Holder) {
        flow.outcomes.failed(holder, "the holder did not answer its original initialize request")
    }

    private fun clearStaleAttempt(holder: Holder) {
        if (holder.handshakeGeneration != null) flow.attempts.clear(holder)
    }
}

internal class HolderInitializedDelivery(
    private val context: HolderLinkContext,
    private val persistence: HolderPersistence,
    private val outcomes: HolderHandshakeOutcomes,
) {
    fun ready(holder: Holder, link: HolderLink) {
        when {
            holder.initializedUncertain -> outcomes.failed(holder, INITIALIZED_UNPROVEN)
            holder.initializedPending -> pending(holder, link)
            else -> outcomes.ready(holder)
        }
    }

    fun pending(holder: Holder, link: HolderLink) {
        holder.initializedPending = false
        holder.initializedUncertain = true
        if (!persistence.checkpoint()) {
            holder.initializedPending = true
            holder.initializedUncertain = false
            outcomes.failed(holder, INITIALIZED_UNPROVEN)
        } else if (!link.send(INITIALIZED, HolderWritten { delivered(holder, link) })) {
            outcomes.failed(holder, INITIALIZED_UNPROVEN)
        }
    }

    private fun delivered(holder: Holder, link: HolderLink) {
        if (holder.link !== link || !holder.initializedUncertain) return
        holder.initializedUncertain = false
        if (persistence.checkpoint()) {
            holder.lastCall = context.clock.epoch()
            outcomes.ready(holder)
        } else {
            holder.initializedUncertain = true
            outcomes.failed(holder, INITIALIZED_UNPROVEN)
        }
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
        holder.initializedPending = false
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

    fun observed(holder: Holder): Boolean = holder.attestedGeneration == holder.generation

    fun baseline(holder: Holder): Boolean =
        holder.generation == null && initialized(holder) && identityMatches(holder)

    fun unproven(holder: Holder): Boolean =
        listOf(
            holder.generation == null && !baseline(holder),
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

    private fun routeMatches(holder: Holder, generation: String): Boolean =
        holder.attestedGeneration == generation || routes.generation(holder.socket) == generation

    private fun changed(holder: Holder): Boolean {
        val generation = holder.generation ?: return false
        return routes.generation(holder.socket)?.let { it != generation } == true
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
    private val records = HolderStateRecord()

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
        holder.initializedPending = Rpc.str(body, INITIALIZED_PENDING) == "true"
        holder.initializedUncertain = Rpc.str(body, INITIALIZED_UNCERTAIN) == "true"
        holder.retirementFence = Rpc.str(body, RETIREMENT_FENCE)
        holder.reverseResponseRetries = Rpc.str(body, REVERSE_RESPONSE_RETRIES)?.toIntOrNull() ?: 0
        holder.settlementProtocol = Rpc.str(body, SETTLEMENT_PROTOCOL) == "true"
        holder.recoveryUncertain = Rpc.str(body, RECOVERY_UNCERTAIN) == "true" || proof.unproven(holder)
        holder.handshakeId?.removePrefix(HELLO)?.toLongOrNull()?.let { next = maxOf(next, it) }
    }

    private fun remembered(holder: Holder): Pair<String, JsonObject>? {
        val hasHandshake = holder.init != null || holder.handshakeId != null
        val persisted = listOf(
            hasHandshake,
            holder.recoveryUncertain,
            holder.initializedUncertain,
            holder.retirementFence != null,
            holder.reverseResponseRetries != 0,
            holder.settlementProtocol,
        ).any { it }
        return if (persisted) holder.socket to records.record(holder) else null
    }
}

private const val INITIALIZED = """{"jsonrpc":"2.0","method":"notifications/initialized"}"""
internal const val INITIALIZED_PENDING = "initialized_pending"
private const val UNPROVEN_INITIALIZATION = "the retained holder initialization is unproven"
private const val INITIALIZED_UNPROVEN = "the initialized notification could not be durably retained"
private const val MISSING_INITIALIZATION = "the retained holder has no durable initialization evidence"
private const val CANDIDATE_INITIALIZATION = "the candidate has no durable initialize request"
