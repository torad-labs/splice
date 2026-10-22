package splice.control.mcp.hub

import splice.core.util.JsonScalars
import java.nio.file.Path

private const val SYSTEMD_RUN = "systemd-run"

internal class HubHolderLiveness(
    private val records: HubRecords,
    private val scope: HubHolderScope,
    private val lifecycle: HubHolderLifecycle,
) {
    private val ownership = HubHolderOwnership(records, scope)

    fun record(socket: String, expectedGeneration: String? = null): HubHolderRecord? =
        lifecycle.serial(socket) { recorded(socket, expectedGeneration) }

    fun attest(socket: String, expectedGeneration: String? = null): HubHolderRecord? =
        lifecycle.serial(socket) { recorded(socket, expectedGeneration)?.let(::attestLocked) }

    fun attest(record: HubHolderRecord): HubHolderRecord? =
        lifecycle.serial(records.files.socketOf(record.path).toString()) { attestLocked(record) }

    fun latest(record: HubHolderRecord): HubHolderRecord? =
        lifecycle.serial(records.files.socketOf(record.path).toString()) { latestLocked(record) }

    fun confirmExit(record: HubHolderRecord, unlaunched: Boolean = false): Boolean =
        lifecycle.serial(records.files.socketOf(record.path).toString()) { confirmExitLocked(record, unlaunched) }

    /** Confirms a retired generation from durable evidence even after its socket record names a newer holder. */
    fun confirmedExit(socket: String, generation: String): Boolean = lifecycle.serial(socket) {
        records.exits.confirmed(socket, generation) ||
            recorded(socket, generation)?.let { record -> confirmExitLocked(record, unlaunched = false) } == true
    }

    /** Stops only the still-attested generation-specific scope, never a PID that might have been reused. */
    fun requestStop(record: HubHolderRecord, owner: HubHolderOwner): Boolean =
        lifecycle.serial(records.files.socketOf(record.path).toString()) {
            latestLocked(record)?.let { current ->
                records.ownership.owner(current)?.takeIf { it == owner }?.let { ownership.stop(current, it) }
            } == true
        }

    /** An incomplete launch can retire only its recorded unique scope, never an inferred child PID. */
    fun requestLaunchStop(record: HubHolderRecord): Boolean =
        lifecycle.serial(records.files.socketOf(record.path).toString()) {
            latestLocked(record)?.let { current ->
                records.ownership.claim(current)
                    ?.takeIf { scope.running(it.scope) }
                    ?.let { scope.stop(it.scope) && scope.waitForExit(it.scope) }
            } == true
        }

    fun waitForExit(owner: HubHolderOwner): Boolean = ownership.waitForExit(owner)

    private fun recorded(socket: String, expectedGeneration: String?): HubHolderRecord? = records.files.all()
        .firstOrNull { record ->
            records.files.socketOf(record.path).toString() == socket &&
                (expectedGeneration == null || JsonScalars.str(record.body, HubFields.GENERATION) == expectedGeneration)
        }

    private fun attestLocked(record: HubHolderRecord): HubHolderRecord? {
        val current = latestLocked(record)?.takeIf(records.files::live) ?: return null
        val owner = records.ownership.owner(current)
        return if (owner == null) {
            ownership.unrecorded(current)?.let { records.ownership.attest(current, it) }
        } else {
            current.takeIf { ownership.live(it, owner) }
        }
    }

    private fun confirmExitLocked(record: HubHolderRecord, unlaunched: Boolean): Boolean {
        val current = latestLocked(record) ?: return false
        return if (records.exits.confirmed(record) || records.exits.confirmed(current)) {
            true
        } else {
            records.ownership.owner(current)?.let { owner -> ownership.confirmExit(current, owner) }
                ?: launchExited(current, unlaunched)
        }
    }

    private fun launchExited(record: HubHolderRecord, unlaunched: Boolean): Boolean {
        val claim = records.ownership.claim(record) ?: return false
        val launch = records.ownership.launch(record)
        val launcherGone = unlaunched && launch == null || launch?.let(ownership::launcherExited) == true
        return launcherGone && scope.exited(claim.scope) && records.exits.confirmScope(record)
    }

    private fun latestLocked(record: HubHolderRecord): HubHolderRecord? =
        records.files.read(record.path)?.let { current -> HubHolderRecord(record.path, current) }
            ?.takeIf { current ->
                records.ownership.claim(current) == records.ownership.claim(record) &&
                    records.ownership.launch(current) == records.ownership.launch(record)
            }
}

/** /proc binds a record to a live scope; systemd remains the source of truth for that generation's lifetime. */
private class HubHolderOwnership(
    private val records: HubRecords,
    private val scope: HubHolderScope,
) {
    fun live(record: HubHolderRecord, owner: HubHolderOwner): Boolean =
        records.files.read(record.path) == record.body &&
            records.files.live(record) &&
            holderAlive(owner.holderPid, records.files.socketOf(record.path), owner.scope, owner.holderStartedAt) &&
            inScope(owner.holderPid, owner.scope) &&
            inScope(owner.childPid, owner.scope) &&
            scope.running(owner.scope)

    fun unrecorded(record: HubHolderRecord): HubHolderOwner? = candidate(record)?.let { candidate ->
        records.ownership.claim(record)?.let { claim ->
            candidate.takeIf {
                holderAlive(candidate.holderPid, candidate.socket, candidate.scope, candidate.holderStartedAt) &&
                    inScope(candidate.childPid, candidate.scope)
            }?.let {
                HubHolderOwner(
                    claim.generation,
                    candidate.holderPid,
                    candidate.childPid,
                    claim.scope,
                    candidate.holderStartedAt,
                )
            }
        }
    }

    private fun candidate(record: HubHolderRecord): UnrecordedCandidate? {
        val claim = records.ownership.claim(record) ?: return null
        val holder = holder(record) ?: return null
        return recordedScope(claim, holder)?.let { scopeName ->
            childOf(holder.pid)?.takeIf { childPid -> inScope(childPid, scopeName) }?.let { childPid ->
                UnrecordedCandidate(
                    holder.pid,
                    childPid,
                    scopeName,
                    holder.startedAt,
                    records.files.socketOf(record.path),
                )
            }
        }
    }

    private fun holder(record: HubHolderRecord): LegacyHolder? {
        val recordedAt = JsonScalars.long(record.body, HubFields.AT) ?: return null
        val pid = JsonScalars.long(record.body, HubFields.PID) ?: return null
        return ProcessHandle.of(pid).filter(ProcessHandle::isAlive)
            .flatMap { handle -> handle.info().startInstant() }
            .map { started -> started.toEpochMilli() }
            .filter { startedAt -> startedAt >= recordedAt }
            .map { startedAt -> LegacyHolder(pid, startedAt) }
            .orElse(null)
    }

    private fun recordedScope(claim: HubHolderScopeClaim, holder: LegacyHolder): String? =
        scope.of(holder.pid)?.takeIf { scopeName -> scopeName == claim.scope && scope.running(scopeName) }

    private data class LegacyHolder(val pid: Long, val startedAt: Long)

    private data class UnrecordedCandidate(
        val holderPid: Long,
        val childPid: Long,
        val scope: String,
        val holderStartedAt: Long,
        val socket: Path,
    )

    fun exited(owner: HubHolderOwner): Boolean = scope.exited(owner.scope)

    fun launcherExited(launch: HubHolderLaunch): Boolean {
        val current = ProcessHandle.of(launch.holderPid).filter(ProcessHandle::isAlive).orElse(null) ?: return true
        return launch.holderStartedAt?.let { expected ->
            current.info().startInstant().map { it.toEpochMilli() != expected }.orElse(false)
        } == true
    }

    fun confirmExit(record: HubHolderRecord, owner: HubHolderOwner): Boolean = when {
        exited(owner) -> records.exits.confirm(record, owner)
        orphaned(record, owner) -> scope.stop(owner.scope) &&
            scope.waitForExit(owner.scope) &&
            records.exits.confirm(record, owner)
        else -> false
    }

    fun orphaned(record: HubHolderRecord, owner: HubHolderOwner): Boolean =
        records.files.read(record.path) == record.body &&
            records.files.live(record) &&
            !holderAlive(owner.holderPid, records.files.socketOf(record.path), owner.scope, owner.holderStartedAt) &&
            inScope(owner.childPid, owner.scope) &&
            scope.running(owner.scope)

    fun stop(record: HubHolderRecord, owner: HubHolderOwner): Boolean =
        records.files.read(record.path) == record.body &&
            records.files.live(record) &&
            records.ownership.owner(record) == owner &&
            scope.running(owner.scope) &&
            scope.stop(owner.scope)

    fun waitForExit(owner: HubHolderOwner): Boolean = scope.waitForExit(owner.scope)

    private fun holderAlive(pid: Long, socket: Path, scopeName: String, startedAt: Long?): Boolean =
        startedAt?.let { expected ->
            ProcessHandle.of(pid)
                .filter(ProcessHandle::isAlive)
                .filter { handle ->
                    handle.info().startInstant().map { it.toEpochMilli() == expected }.orElse(false)
                }
                .filter { handle ->
                    handle.info().command().map { command ->
                        Path.of(command).fileName.toString() == SYSTEMD_RUN
                    }.orElse(false)
                }
                .flatMap { handle -> handle.info().arguments() }
                .map { args ->
                    args.contains(socket.toString()) && args.contains("--unit=${scopeName.removeSuffix(".scope")}")
                }
                .orElse(false)
        } == true

    private fun childOf(pid: Long): Long? = ProcessHandle.of(pid)
        .map { holder ->
            holder.children().use { children -> children.filter(ProcessHandle::isAlive).findFirst().orElse(null) }
        }
        .map(ProcessHandle::pid)
        .orElse(null)

    private fun inScope(pid: Long, scopeName: String): Boolean =
        ProcessHandle.of(pid).filter(ProcessHandle::isAlive).isPresent && scope.of(pid) == scopeName
}
