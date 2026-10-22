package splice.control.mcp.hub

import splice.core.util.JsonScalars
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

// why: a baseline holder starts immediately after its record, so a much newer PID cannot be adopted after reuse.
private const val LEGACY_LAUNCH_WINDOW_MS = 60_000L

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
        } else if (records.legacy.retire(current)) {
            true
        } else {
            records.ownership.owner(current)?.let { owner -> ownership.confirmExit(current, owner) }
                ?: launchExited(current, unlaunched)
        }
    }

    private fun launchExited(record: HubHolderRecord, unlaunched: Boolean): Boolean {
        val claim = records.ownership.claim(record) ?: return false
        val launch = records.ownership.launch(record)
        val launcherGone = launch?.let(ownership::launcherExited) == true ||
            unlaunched && records.ownership.unlaunched(record)
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
    private val proof = HubHolderProof(scope)

    fun live(record: HubHolderRecord, owner: HubHolderOwner): Boolean =
        records.files.read(record.path) == record.body &&
            records.files.live(record) &&
            proof.holderAlive(
                owner.holderPid,
                records.files.socketOf(record.path),
                owner.executable,
                owner.holderStartedAt,
            ) &&
            proof.inScope(owner.holderPid, owner.scope) &&
            proof.inScope(owner.childPid, owner.scope) &&
            scope.running(owner.scope)

    fun unrecorded(record: HubHolderRecord): HubHolderOwner? =
        candidate(record)?.let { candidate -> claimed(record, candidate) }

    private fun claimed(record: HubHolderRecord, candidate: UnrecordedCandidate): HubHolderOwner? {
        val migrated = records.legacy.claim(record, candidate.scope)
        val claim = records.ownership.claim(record) ?: migrated ?: return null
        val expected = JsonScalars.str(record.body, HubFields.EXECUTABLE)
            ?: candidate.executable.takeIf { migrated != null }
            ?: return null
        return candidate.takeIf { attested(it, claim, expected, migrated != null) }?.let {
            HubHolderOwner(
                claim.generation,
                candidate.holderPid,
                candidate.childPid,
                claim.scope,
                candidate.holderStartedAt,
                expected,
            )
        }
    }

    private fun attested(
        candidate: UnrecordedCandidate,
        claim: HubHolderScopeClaim,
        expected: String,
        legacy: Boolean,
    ): Boolean = claim.scope == candidate.scope &&
        (!legacy || candidate.holderStartedAt <= candidate.recordedAt + LEGACY_LAUNCH_WINDOW_MS) &&
        proof.holderAlive(candidate.holderPid, candidate.socket, expected, candidate.holderStartedAt) &&
        proof.inScope(candidate.childPid, candidate.scope)

    private fun candidate(record: HubHolderRecord): UnrecordedCandidate? = holder(record)?.let { holder ->
        val scopeName = activeScope(holder)
        val childPid = scopeName?.let { scope ->
            proof.childOf(holder.pid)?.takeIf { child -> proof.inScope(child, scope) }
        }
        val recordedAt = JsonScalars.long(record.body, HubFields.AT) ?: return@let null
        if (scopeName != null && childPid != null) {
            UnrecordedCandidate(
                holder.pid,
                childPid,
                scopeName,
                holder.startedAt,
                holder.executable,
                recordedAt,
                records.files.socketOf(record.path),
            )
        } else {
            null
        }
    }

    private fun holder(record: HubHolderRecord): LegacyHolder? {
        val recordedAt = JsonScalars.long(record.body, HubFields.AT) ?: return null
        val pid = JsonScalars.long(record.body, HubFields.PID) ?: return null
        return ProcessHandle.of(pid).filter(ProcessHandle::isAlive).flatMap { handle ->
            handle.info().startInstant().flatMap { started ->
                handle.info().command().map { executable ->
                    LegacyHolder(pid, started.toEpochMilli(), executable)
                }
            }
        }.filter { holder -> holder.startedAt >= recordedAt }.orElse(null)
    }

    private fun activeScope(holder: LegacyHolder): String? =
        scope.of(holder.pid)?.takeIf(scope::running)

    private data class LegacyHolder(
        val pid: Long,
        val startedAt: Long,
        val executable: String,
    )

    private data class UnrecordedCandidate(
        val holderPid: Long,
        val childPid: Long,
        val scope: String,
        val holderStartedAt: Long,
        val executable: String,
        val recordedAt: Long,
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
            !proof.holderAlive(
                owner.holderPid,
                records.files.socketOf(record.path),
                owner.executable,
                owner.holderStartedAt,
            ) &&
            proof.inScope(owner.childPid, owner.scope) &&
            scope.running(owner.scope)

    fun stop(record: HubHolderRecord, owner: HubHolderOwner): Boolean =
        records.files.read(record.path) == record.body &&
            records.files.live(record) &&
            records.ownership.owner(record) == owner &&
            scope.running(owner.scope) &&
            scope.stop(owner.scope)

    fun waitForExit(owner: HubHolderOwner): Boolean = scope.waitForExit(owner.scope)
}

/** `/proc` evidence binds each candidate to the executable and process birth its durable record expects. */
private class HubHolderProof(private val scope: HubHolderScope) {
    fun holderAlive(pid: Long, socket: Path, executable: String?, startedAt: Long?): Boolean =
        executable?.let { expected ->
            startedAt?.let { birth ->
                ProcessHandle.of(pid)
                    .filter(ProcessHandle::isAlive)
                    .filter { handle ->
                        handle.info().startInstant().map { it.toEpochMilli() == birth }.orElse(false)
                    }
                    .filter { handle -> matchesExecutable(handle, expected) }
                    .flatMap { handle -> handle.info().arguments() }
                    .map { args -> args.contains(socket.toString()) }
                    .orElse(false)
            }
        } == true

    private fun matchesExecutable(handle: ProcessHandle, executable: String): Boolean =
        handle.info().command().map { actual -> sameExecutable(actual, executable) }.orElse(false)

    private fun sameExecutable(actual: String, expected: String): Boolean = try {
        Files.isSameFile(Path.of(actual), Path.of(expected))
    } catch (_: IOException) {
        false
    } catch (_: IllegalArgumentException) {
        false
    } catch (_: SecurityException) {
        false
    }

    fun childOf(pid: Long): Long? = ProcessHandle.of(pid)
        .map { holder ->
            holder.children().use { children -> children.filter(ProcessHandle::isAlive).findFirst().orElse(null) }
        }
        .map(ProcessHandle::pid)
        .orElse(null)

    fun inScope(pid: Long, scopeName: String): Boolean =
        ProcessHandle.of(pid).filter(ProcessHandle::isAlive).isPresent && scope.of(pid) == scopeName
}
