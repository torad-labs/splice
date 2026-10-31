// MCP hub holders survive daemon replacement in generation-specific systemd scopes.
package splice.control.mcp.hub

import kotlinx.coroutines.CoroutineDispatcher
import splice.control.mcp.McpResult
import splice.core.util.JsonScalars
import splice.core.util.LogSink
import java.nio.file.Path
import kotlin.time.Duration

/** One holder to start: [launch] is the session's launch id for a per-session copy, null for a shared one. */
internal data class HubStart(
    val key: String,
    val server: String,
    val launch: String?,
    val spec: HubLaunchSpec,
    val tuple: String,
)

/** One recorded holder as the sweep weighs it: its launch, exact generation, socket, and record epoch milliseconds. */
internal data class HubRecord(
    val launch: String?,
    val socket: String,
    val generation: String?,
    val recordedAt: Long,
)

/** Why the sweep stops a recorded holder, in words for the log, or null to keep it running. */
internal fun interface HubKeep {
    fun stopReason(record: HubRecord): String?
}

/** The holder façade keeps lifecycle operations coordinated without owning their implementations. */
internal class HubHolders(
    dir: Path,
    binary: Path,
    slice: String,
    io: CoroutineDispatcher,
    log: LogSink,
) {
    private val holder = HubHolderContext(dir)
    val records = holder.records
    private val stops = HubHolderStops(records, holder.liveness, holder.lifecycle, log)
    private val starts = HubHolderStarts(dir, binary, slice, io, holder, log)
    private val pins = HubDrainPins(dir)
    private val outcomes = HubHolderRetirements(dir)
    private val pinnedReplacements = HubReplacementPins(pins, outcomes)
    private val replacements = HubHolderReplacements(records, holder.liveness, stops, outcomes)

    fun socket(key: String): Path = starts.socket(key)

    fun running(key: String): Boolean = starts.running(key)

    suspend fun start(start: HubStart, bound: Duration): McpResult<Unit> = starts.start(start, bound)

    /** Attests a live legacy record before exposing a generation to its daemon owner. */
    fun generation(socket: String): String? =
        holder.liveness.attest(socket)?.let { JsonScalars.str(it.body, HubFields.GENERATION) }

    fun owns(socket: String, tuple: String? = null, expectedGeneration: String? = null): Boolean =
        holder.liveness.attest(socket, expectedGeneration)?.let { held ->
            tuple == null || JsonScalars.str(held.body, HubFields.TUPLE) == tuple
        } == true

    fun retire(values: ReplacementValues): McpResult<Unit> = holder.lifecycle.serial(
        setOf(values.old.oldSocket, values.candidateSocket),
    ) {
        pinnedReplacements.run(values, HubRetirementState.RETIRED) {
            replacements.retire(
                values.old.oldSocket,
                values.old.oldGeneration,
                values.candidateSocket,
                values.candidateTuple,
                values.candidateGeneration,
            )
        }
    }

    fun rollback(values: ReplacementValues): McpResult<Unit> = holder.lifecycle.serial(
        setOf(values.old.oldSocket, values.candidateSocket),
    ) {
        pinnedReplacements.run(values, HubRetirementState.ROLLED_BACK) {
            replacements.rollback(
                values.old.oldSocket,
                values.old.oldGeneration,
                values.candidateSocket,
                values.candidateTuple,
                values.candidateGeneration,
            )
        }
    }

    fun exited(socket: String, generation: String): McpResult<Unit> = replacements.exited(socket, generation)

    fun acquirePin(request: HubDrainPinRequest, claims: Set<HubDrainClaim>): McpResult<HubDrainPin> =
        holder.lifecycle.serial(request.oldSocket) {
            val recovered = pins.recover(request, claims)
            if (recovered != null) {
                McpResult.Served(recovered)
            } else if (holder.liveness.attest(request.oldSocket, request.oldGeneration) == null) {
                McpResult.Refused("the old holder is not the daemon owner's exact live generation")
            } else {
                pins.acquire(request, claims)?.let { McpResult.Served(it) }
                    ?: McpResult.Refused("the front drain ownership claim was not persisted")
            }
        }

    fun confirmPin(request: HubDrainPinRequest, claim: String, claims: Set<HubDrainClaim>): McpResult<Unit> =
        holder.lifecycle.serial(request.oldSocket) {
            val recovered = pins.recover(request, claims)?.claim == claim
            val live = recovered || holder.liveness.attest(request.oldSocket, request.oldGeneration) != null
            if (!live) {
                McpResult.Refused("the old holder is no longer the daemon owner's exact live generation")
            } else if (pins.confirm(request, claim, claims)) {
                McpResult.Served(Unit)
            } else {
                McpResult.Refused("the front drain ownership claim is not recoverable")
            }
        }

    fun releasePin(request: HubDrainPinRequest, claim: String): McpResult<Unit> =
        holder.lifecycle.serial(request.oldSocket) {
            if (pins.release(request, claim)) {
                McpResult.Served(Unit)
            } else {
                McpResult.Refused("the front drain ownership claim does not match its exact old generation")
            }
        }

    /** Called while [HubHolderStops.unkept] owns this socket's lifecycle lock. */
    fun pinned(socket: String, generation: String?, claims: Set<HubDrainClaim>): Boolean =
        generation != null && pins.holds(socket, generation, claims)

    fun pinStoreReadable(): Boolean = pins.readable()

    fun stopUnkept(keep: HubKeep) = stops.unkept(keep)
}
