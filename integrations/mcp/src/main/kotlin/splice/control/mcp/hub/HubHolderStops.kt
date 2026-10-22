package splice.control.mcp.hub

import splice.control.mcp.McpResult
import splice.core.util.JsonScalars
import splice.core.util.LogSafe
import splice.core.util.LogSink
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

internal class HubHolderStops(
    private val records: HubRecords,
    private val liveness: HubHolderLiveness,
    private val lifecycle: HubHolderLifecycle,
    private val log: LogSink,
) {
    fun unkept(keep: HubKeep) {
        records.files.all().filter(records.files::live).forEach { record ->
            val socket = records.files.socketOf(record.path).toString()
            lifecycle.serial(socket) {
                val current = liveness.latest(record) ?: return@serial
                val held = HubRecord(
                    JsonScalars.str(current.body, HubFields.LAUNCH)?.takeIf { it.isNotEmpty() },
                    socket,
                    JsonScalars.str(current.body, HubFields.GENERATION),
                    JsonScalars.long(current.body, HubFields.AT) ?: 0L,
                )
                keep.stopReason(held)?.let { reason -> stopLocked(current, reason) }
            }
        }
    }

    fun stop(record: HubHolderRecord, reason: String): McpResult<Unit> =
        lifecycle.serial(records.files.socketOf(record.path).toString()) { stopLocked(record, reason) }

    private fun stopLocked(record: HubHolderRecord, reason: String): McpResult<Unit> {
        val current = liveness.latest(record) ?: return McpResult.Served(Unit)
        if (confirmed(current)) return confirmedStop(current, reason)
        return records.ownership.owner(current)?.let { owner -> retire(current, owner, reason) }
            ?: retireIncomplete(current, reason)
    }

    private fun confirmedStop(record: HubHolderRecord, reason: String): McpResult<Unit> =
        records.ownership.owner(record)?.let { owner -> stopped(record, owner, reason) }
            ?: stoppedLaunch(record, reason)

    private fun retire(record: HubHolderRecord, owner: HubHolderOwner, reason: String): McpResult<Unit> = when {
        !liveness.requestStop(record, owner) ->
            McpResult.Refused("the holder's exact systemd scope did not accept retirement")
        !liveness.waitForExit(owner) ->
            McpResult.Refused("the old holder, child, or systemd scope did not exit after retirement")
        !records.exits.confirm(record, owner) ->
            McpResult.Refused("the holder record changed before its exact exit was confirmed")
        else -> stopped(record, owner, reason)
    }

    private fun retireIncomplete(record: HubHolderRecord, reason: String): McpResult<Unit> = when {
        !liveness.requestLaunchStop(record) ->
            McpResult.Refused("the incomplete holder's recorded systemd scope did not accept retirement")
        !confirmed(record) ->
            McpResult.Refused("the incomplete holder's recorded systemd scope did not exit after retirement")
        else -> confirmedStop(record, reason)
    }

    private fun stoppedLaunch(record: HubHolderRecord, reason: String): McpResult<Unit> {
        val claim = records.ownership.claim(record) ?: return McpResult.Served(Unit)
        if (records.ownership.currentScopeExit(record)) removeSocket(records.files.socketOf(record.path))
        log("[mcp-hub] stopped holder scope ${LogSafe.str(claim.scope)}: ${LogSafe.str(reason)}\n")
        return McpResult.Served(Unit)
    }

    private fun stopped(record: HubHolderRecord, owner: HubHolderOwner, reason: String): McpResult<Unit> {
        if (records.ownership.currentExit(record, owner)) removeSocket(records.files.socketOf(record.path))
        log("[mcp-hub] stopped holder ${LogSafe.str(owner.holderPid.toString())}: ${LogSafe.str(reason)}\n")
        return McpResult.Served(Unit)
    }

    fun confirmed(record: HubHolderRecord): Boolean = liveness.confirmExit(record)

    private fun removeSocket(socket: Path) {
        try {
            Files.deleteIfExists(socket)
        } catch (_: IOException) {
            // The exact scope and record already prove retirement. A stale socket is not an owner.
        }
    }
}
