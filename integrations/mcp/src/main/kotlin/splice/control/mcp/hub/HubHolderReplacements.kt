package splice.control.mcp.hub

import splice.control.mcp.McpResult
import splice.core.util.JsonScalars

internal class HubHolderReplacements(
    private val records: HubRecords,
    private val liveness: HubHolderLiveness,
    private val stops: HubHolderStops,
    private val retirements: HubHolderRetirements,
) {
    fun retire(
        oldSocket: String,
        oldGeneration: String,
        candidateSocket: String,
        candidateTuple: String,
        candidateGeneration: String,
    ): McpResult<Unit> {
        val replacement = HubReplacement(
            oldSocket,
            oldGeneration,
            candidateSocket,
            candidateTuple,
            candidateGeneration,
        )
        return when (retirements.state(replacement)) {
            HubRetirementState.RETIRED -> McpResult.Served(Unit)
            HubRetirementState.ROLLED_BACK -> McpResult.Refused("this replacement was already rolled back")
            HubRetirementState.RETIRING -> reconcile(replacement)
            HubRetirementState.UNKNOWN -> McpResult.Refused(
                "the replacement outcome is unreadable; refusing retirement",
            )
            null -> beginRetirement(replacement)
        }
    }

    fun rollback(
        oldSocket: String,
        oldGeneration: String,
        candidateSocket: String,
        candidateTuple: String,
        candidateGeneration: String,
    ): McpResult<Unit> {
        val replacement = HubReplacement(
            oldSocket,
            oldGeneration,
            candidateSocket,
            candidateTuple,
            candidateGeneration,
        )
        return when (retirements.state(replacement)) {
            HubRetirementState.RETIRED -> McpResult.Refused("the old holder retirement already completed")
            HubRetirementState.RETIRING -> rollbackAfterRetirementStarted(replacement)
            HubRetirementState.ROLLED_BACK -> McpResult.Served(Unit)
            HubRetirementState.UNKNOWN -> McpResult.Refused("the replacement outcome is unreadable; refusing rollback")
            null -> rollbackBeforeRetirement(replacement)
        }
    }

    fun exited(socket: String, generation: String): McpResult<Unit> =
        if (liveness.confirmedExit(socket, generation)) {
            McpResult.Served(Unit)
        } else {
            McpResult.Refused("the holder generation has not confirmed its child and scope exit")
        }

    private fun beginRetirement(replacement: HubReplacement): McpResult<Unit> {
        val candidate = candidate(replacement)
        val old = liveness.attest(replacement.oldSocket, replacement.oldGeneration)
        return when {
            candidate == null -> McpResult.Refused("the candidate holder no longer owns its captured generation")
            old == null -> McpResult.Refused("the old holder generation is no longer live")
            else -> claimedRetirement(replacement)
        }
    }

    private fun claimedRetirement(replacement: HubReplacement): McpResult<Unit> =
        when (retirements.claim(replacement, HubRetirementState.RETIRING)) {
            HubRetirementState.RETIRED -> McpResult.Served(Unit)
            HubRetirementState.ROLLED_BACK -> McpResult.Refused("this replacement was already rolled back")
            HubRetirementState.RETIRING -> reconcile(replacement)
            HubRetirementState.UNKNOWN -> McpResult.Refused(
                "the replacement outcome is unreadable; refusing retirement",
            )
            null -> McpResult.Refused("the replacement outcome is being persisted; retry retirement")
        }

    /** A durable retirement owns its old generation after claim; candidate loss cannot turn it into a rollback. */
    private fun reconcile(replacement: HubReplacement): McpResult<Unit> {
        if (liveness.confirmedExit(replacement.oldSocket, replacement.oldGeneration)) return completed(replacement)
        val old = liveness.record(replacement.oldSocket, replacement.oldGeneration)
            ?: return McpResult.Refused("the old holder generation is no longer recorded")
        return stop(old, replacement)
    }

    private fun stop(old: HubHolderRecord, replacement: HubReplacement): McpResult<Unit> =
        when (val stopped = stops.stop(old, "drained replacement")) {
            is McpResult.Served -> completed(replacement)
            is McpResult.Refused -> stopped
        }

    private fun rollbackAfterRetirementStarted(replacement: HubReplacement): McpResult<Unit> {
        if (liveness.confirmedExit(replacement.oldSocket, replacement.oldGeneration)) {
            retirements.complete(replacement, HubRetirementState.RETIRED)
            return McpResult.Refused("the old holder retirement already completed")
        }
        return McpResult.Refused("the old holder retirement had already started; retry retirement to reconcile it")
    }

    private fun rollbackBeforeRetirement(replacement: HubReplacement): McpResult<Unit> {
        val candidate = liveness.record(replacement.candidateSocket, replacement.candidateGeneration)
        val old = liveness.attest(replacement.oldSocket, replacement.oldGeneration)
        return when {
            !matches(candidate, replacement) -> McpResult.Refused(
                "the candidate holder has not owner-confirmed its exact exit",
            )
            old == null -> McpResult.Refused("the old holder's exact generation is not live")
            else -> claimedRollback(replacement)
        }
    }

    private fun matches(candidate: HubHolderRecord?, replacement: HubReplacement): Boolean =
        candidateMatches(candidate, replacement) && records.exits.confirmed(
            replacement.candidateSocket,
            replacement.candidateGeneration,
            replacement.candidateTuple,
        )

    private fun candidateMatches(candidate: HubHolderRecord?, replacement: HubReplacement): Boolean = when (candidate) {
        null -> true
        else -> JsonScalars.str(candidate.body, HubFields.TUPLE) == replacement.candidateTuple &&
            stops.confirmed(candidate)
    }

    private fun claimedRollback(replacement: HubReplacement): McpResult<Unit> =
        when (retirements.claim(replacement, HubRetirementState.ROLLED_BACK)) {
            HubRetirementState.ROLLED_BACK -> McpResult.Served(Unit)
            HubRetirementState.RETIRED -> McpResult.Refused("the old holder retirement already completed")
            HubRetirementState.RETIRING -> rollbackAfterRetirementStarted(replacement)
            HubRetirementState.UNKNOWN -> McpResult.Refused("the replacement outcome is unreadable; refusing rollback")
            null -> McpResult.Refused("the replacement outcome is being persisted; retry rollback")
        }

    private fun candidate(replacement: HubReplacement): HubHolderRecord? =
        liveness.attest(replacement.candidateSocket, replacement.candidateGeneration)
            ?.takeIf { JsonScalars.str(it.body, HubFields.TUPLE) == replacement.candidateTuple }

    private fun completed(replacement: HubReplacement): McpResult<Unit> =
        if (retirements.complete(replacement, HubRetirementState.RETIRED)) {
            McpResult.Served(Unit)
        } else {
            McpResult.Refused("the exact retirement completed but its durable outcome was not recorded")
        }
}
