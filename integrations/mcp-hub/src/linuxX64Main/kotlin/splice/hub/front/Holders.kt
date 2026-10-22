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
    var link: HolderLink? = null
    var identity: String? = null
    var init: JsonObject? = null
    var ready = false
    var connecting = false
    var listed = false

    /** When a session last sent this holder a request, in epoch milliseconds; the daemon stops a shared holder
     *  idle past its bound. */
    var lastCall = 0L
    val waiters = ArrayList<(String?) -> Unit>()

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
        if (moved.isNotEmpty()) put(MOVED, JsonArray(moved.map(::JsonPrimitive)))
        blocker?.let { put(BLOCKER, it) }
    }
}

class Holders(
    files: HubFiles,
    connections: Links,
    timers: Timers,
    clock: Clock,
    routes: Routes,
    relay: Relay,
    changed: () -> Unit,
) {
    private val known = HashMap<String, Holder>()
    internal val replacements = HolderReplacements(known, changed)

    /** Retains a restored ledger’s socket identity until exact exit evidence settles it. */
    fun remember(socket: String): Holder = known.getOrPut(socket) { Holder(socket) }
    internal val links = HolderLinks(
        HolderLinkContext(files, connections, timers, clock, routes, relay),
        known,
        replacements,
        changed,
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
internal const val CAPABILITIES = "capabilities"
internal const val PHASE = "phase"
internal const val BLOCKER = "blocker"
internal const val MOVED = "moved"
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
internal const val OLD_HOLDER_LOST = "old_holder_lost"
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
    OLD_HOLDER_LOST,
)
internal const val HANDSHAKE_BUDGET = 60_000L
internal const val NO_REPLACEMENT_PRIMITIVE =
    "no canonical replacement tuple is supplied by the running daemon; old holder left intact"
