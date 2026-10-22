// The holders the front talks to: each connected once, handshaken once, and remembered on disk, so a front restart or
// an in-place upgrade finds every server as it was and never sends a running server a second initialize.
package splice.hub.front

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** One holder's socket, as the front sees it. */
data class Holder(val socket: String) {
    var generation: String? = null
    var attestedGeneration: String? = null
    var link: HolderLink? = null
    var control: HolderLink? = null
    var identity: String? = null
    var init: JsonObject? = null
    var handshakeId: String? = null
    var handshakeGeneration: String? = null
    var handshakeIdentity: String? = null
    var recoveryUncertain = false
    var initializedPending = false
    var initializedUncertain = false
    var retirementFence: String? = null
    var exitConfirmedGeneration: String? = null
    var reverseResponseRetries = 0
    var reverseResponseRetryGeneration: String? = null
    var reverseResponseRetryCancel: (() -> Unit)? = null
    var resumeReverseResponses: ((String) -> Unit)? = null
    var scheduleReverseResponseRetry: ((String) -> Unit)? = null
    var settlementProtocol = false
    var settlementRecovery: String? = null
    var ready = false
    var connecting = false
    var listed = false

    /** When a session last sent this holder a request, in epoch milliseconds; the daemon stops a shared holder
     *  idle past its bound. */
    var lastCall = 0L
    val waiters = ArrayList<(String?) -> Unit>()

    fun resetGeneration(nextGeneration: String) {
        cancelReverseResponseRetry()
        generation = nextGeneration
        attestedGeneration = null
        ready = false
        connecting = false
        identity = null
        init = null
        handshakeId = null
        handshakeGeneration = null
        handshakeIdentity = null
        initializedPending = false
        initializedUncertain = false
        retirementFence = null
        exitConfirmedGeneration = null
        settlementProtocol = false
        settlementRecovery = null
        recoveryUncertain = false
    }

    fun cancelReverseResponseRetry() {
        reverseResponseRetryCancel?.invoke()
        reverseResponseRetryCancel = null
        reverseResponseRetryGeneration = null
    }

    /** Hands [failure], or null for ready, to everyone waiting on this holder. */
    fun settle(failure: String?) {
        connecting = false
        ready = failure == null
        if (failure != null) link?.close()
        val waiting = waiters.toList()
        waiters.clear()
        waiting.forEach { it(failure) }
    }
}

/** The exact retained server contract a future replacement must match after this target's wire calls settle. */
data class HolderDrain(
    val server: String,
    val launch: String,
    val socket: String,
    val generation: String,
    val protocolVersion: String,
    val capabilities: JsonObject,
) {
    var candidate: ReplacementCandidate? = null
    var ownerClaim: String? = null
    var moved = emptySet<String>()
    var phase = PAUSED
    var blocker: String? = null

    fun key(): String = "$server\n$launch"

    fun state(): JsonObject = buildJsonObject {
        put(SERVER, server)
        put(LAUNCH, launch)
        put(SOCKET, socket)
        put(GENERATION, generation)
        put(Rpc.PROTOCOL, protocolVersion)
        put(CAPABILITIES, capabilities)
        put(PHASE, phase)
        candidate?.let {
            put(CANDIDATE_SOCKET, it.socket)
            put(CANDIDATE_TUPLE, it.tuple)
            put(CANDIDATE_GENERATION, it.generation)
            put(CANDIDATE_SHARED, it.shared)
        }
        ownerClaim?.let { put(OWNER_CLAIM, it) }
        if (moved.isNotEmpty()) put(MOVED, JsonArray(moved.map(::JsonPrimitive)))
        blocker?.let { put(BLOCKER, it) }
    }
}

internal class Holders(
    files: HubFiles,
    connections: Links,
    timers: Timers,
    clock: Clock,
    private val routes: Routes,
    private val relay: Relay,
    private val persistence: HolderPersistence,
) {
    private val known = HashMap<String, Holder>()
    internal val replacements = HolderReplacements(known, persistence::changed, persistence::checkpoint)

    /** Retains a restored ledger’s socket identity until exact exit evidence settles it. */
    fun remember(socket: String): Holder = known.getOrPut(socket) { Holder(socket) }

    /** Restored disconnected lifetimes still need exact exit probes even when their initialization outcome is unknown. */
    fun exitCandidates(): List<Pair<Holder, String>> = known.values.mapNotNull { holder ->
        holder.generation?.takeIf { generation -> exitPending(holder, generation) }?.let { holder to it }
    }

    /** Probing is read-only. Only a positive exit receipt may mark state; actual pruning checks every remaining owner. */
    fun exitPending(holder: Holder, generation: String): Boolean = listOf(
        known[holder.socket] === holder,
        holder.generation == generation,
        holder.exitConfirmedGeneration != generation,
        holder.link == null,
        holder.control == null,
        !holder.connecting,
        holder.waiters.isEmpty(),
    ).all { it }

    /** Removes only generations whose retirement or rollback exit is durable and has released every front owner. */
    fun prune() {
        known.values.toList().forEach(::releaseExited)
        val evicted = known.values.filter(::evictable)
        evicted.forEach { known.remove(it.socket) }
        if (evicted.isNotEmpty()) persistence.changed()
    }

    private fun releaseExited(holder: Holder) {
        val generation = holder.exitConfirmedGeneration ?: return
        holder.cancelReverseResponseRetry()
        if (relay.awaitsExit(holder, generation) || holder.settlementRecovery != null) {
            relay.exited(holder, generation)
        }
    }

    private fun evictable(holder: Holder): Boolean {
        val generation = holder.exitConfirmedGeneration ?: return false
        return listOf(
            holder.generation == generation,
            holder.retirementFence == null,
            holder.link == null,
            !holder.connecting,
            holder.waiters.isEmpty(),
            !routes.ownership.owns(holder.socket, generation),
            !replacements.owns(holder, generation),
            !relay.awaitsExit(holder, generation),
            holder.settlementRecovery == null,
            holder.reverseResponseRetries == 0,
            holder.reverseResponseRetryGeneration == null,
        ).all { it }
    }

    internal val links = HolderLinks(
        HolderLinkContext(files, connections, timers, clock, routes, relay),
        known,
        replacements,
        persistence,
    )

    /** Set while the front stops or upgrades: a link that closes then is the front's doing, not a lost holder. */
    var stopping: Boolean
        get() = links.stopping
        set(value) {
            links.stopping = value
        }
}

internal const val HELLO = "i:"
internal const val IDENTITY = "identity"
internal const val INITIALIZE = "initialize"
internal const val LAST_CALL = "last_call"
internal const val HANDSHAKE_ID = "handshake_id"
internal const val HANDSHAKE_GENERATION = "handshake_generation"
internal const val HANDSHAKE_IDENTITY = "handshake_identity"
internal const val RECOVERY_UNCERTAIN = "recovery_uncertain"
internal const val INITIALIZED_UNCERTAIN = "initialized_uncertain"
internal const val RETIREMENT_FENCE = "retirement_fence"
internal const val EXIT_CONFIRMED_GENERATION = "exit_confirmed_generation"
internal const val REVERSE_RESPONSE_RETRIES = "reverse_response_retries"
internal const val SETTLEMENT_PROTOCOL = "settlement_protocol"
internal const val CAPABILITIES = "capabilities"
internal const val PHASE = "phase"
internal const val BLOCKER = "blocker"
internal const val MOVED = "moved"
internal const val OWNER_CLAIM = "owner_claim"
internal const val CANDIDATE_SHARED = "candidate_shared"
internal const val PAUSED = "admission_paused"
internal const val CANDIDATE_STARTING = "candidate_starting"
internal const val CANDIDATE_READY = "candidate_ready"
internal const val CANDIDATE_BLOCKED = "candidate_blocked"
internal const val COMMITTING = "candidate_committing"
internal const val ROUTED = "candidate_routed"
internal const val RETIRING = "retiring_old_holder"
internal const val RETIRE_BLOCKED = "retire_blocked"
internal const val ROLLBACK_PENDING = "rollback_pending"
internal const val ROLLING_BACK = "rolling_back"
internal const val RETIRE_UNKNOWN = "retirement_unknown"
internal const val ROLLBACK_UNKNOWN = "rollback_unknown"
internal const val OLD_HOLDER_LOST = "old_holder_lost"
internal const val UNPINNING = "unpinning"
internal val pausingPhases = setOf(
    PAUSED,
    CANDIDATE_STARTING,
    CANDIDATE_READY,
    CANDIDATE_BLOCKED,
    COMMITTING,
    ROUTED,
    RETIRING,
    RETIRE_BLOCKED,
    ROLLBACK_PENDING,
    ROLLING_BACK,
    RETIRE_UNKNOWN,
    ROLLBACK_UNKNOWN,
    OLD_HOLDER_LOST,
    UNPINNING,
)
internal const val HANDSHAKE_BUDGET = 60_000L
internal const val NO_REPLACEMENT_PRIMITIVE =
    "no canonical replacement tuple is supplied by the running daemon; old holder left intact"
