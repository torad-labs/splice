// NEW: MCP hub steps 3 and 4 — holders survive the daemon in generation-specific systemd scopes.
package splice.control.mcp.hub

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.control.mcp.McpResult
import splice.core.util.JsonScalars
import splice.core.util.LogSafe
import splice.core.util.LogSink
import splice.core.util.SafeFailureText
import java.io.File
import java.io.IOException
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds
import java.nio.file.WatchService
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.time.Duration

private const val READY_CHECK_MS = 100L
private const val STOP_WAIT_MS = 5_000L
private const val SYSTEMCTL_WAIT_MS = 1_000L
private const val SYSTEMCTL = "systemctl"
private const val USER_MANAGER = "--user"
private const val SCOPE_ACTIVE = "active"
private const val SCOPE_INACTIVE = "inactive"
private const val SCOPE_NOT_FOUND = "not-found"
private const val HOLDER_PID = "pid"
private const val HOLDER_AT = "at"
private const val HOLDER_TUPLE = "tuple"
private const val HOLDER_LAUNCH = "launch"
private const val HOLDER_SERVER = "server"
private const val HOLDER_SCOPE = "scope"
private val MANAGER_ENV = listOf("XDG_RUNTIME_DIR", "DBUS_SESSION_BUS_ADDRESS")

/** One holder to start: [launch] is the session's launch id for a per-session copy, null for a shared one. */
internal data class HubStart(
    val key: String,
    val server: String,
    val launch: String?,
    val spec: HubLaunchSpec,
    val tuple: String,
)

/** One recorded holder as the sweep weighs it: its launch, socket, and record epoch milliseconds. */
internal data class HubRecord(val launch: String?, val socket: String, val recordedAt: Long)

/** Why the sweep stops a recorded holder, in words for the log, or null to keep it running. */
internal fun interface HubKeep {
    fun stopReason(record: HubRecord): String?
}

/** Where holders live and how they start. */
internal class HubHolders(
    dir: Path,
    binary: Path,
    slice: String,
    io: CoroutineDispatcher,
    log: LogSink,
) {
    val records = HubRecords(dir)
    private val scope = HubHolderScope()
    private val liveness = HubHolderLiveness(records, scope)
    private val stops = HubHolderStops(records, liveness, log)
    private val starts = HubHolderStarts(dir, binary, slice, io, log)
    private val replacements = HubHolderReplacements(records, liveness, stops, HubHolderRetirements(dir))

    fun socket(key: String): Path = starts.socket(key)

    fun running(key: String): Boolean = starts.running(key)

    suspend fun start(start: HubStart, bound: Duration): McpResult<Unit> = starts.start(start, bound)

    /** Attests a live legacy record before exposing a generation to its daemon owner. */
    fun generation(socket: String): String? =
        liveness.attest(socket)?.let { JsonScalars.str(it.body, HOLDER_GENERATION) }

    fun owns(socket: String, tuple: String? = null, expectedGeneration: String? = null): Boolean =
        liveness.attest(socket, expectedGeneration)?.let { held ->
            (tuple == null || JsonScalars.str(held.body, HOLDER_TUPLE) == tuple) && liveness.ownerLive(held)
        } == true

    fun retire(
        oldSocket: String,
        oldGeneration: String,
        candidateSocket: String,
        candidateTuple: String,
        candidateGeneration: String,
    ): McpResult<Unit> = replacements.retire(
        oldSocket,
        oldGeneration,
        candidateSocket,
        candidateTuple,
        candidateGeneration,
    )

    fun rollback(
        oldSocket: String,
        oldGeneration: String,
        candidateSocket: String,
        candidateTuple: String,
        candidateGeneration: String,
    ): McpResult<Unit> = replacements.rollback(
        oldSocket,
        oldGeneration,
        candidateSocket,
        candidateTuple,
        candidateGeneration,
    )

    fun exited(socket: String, generation: String): McpResult<Unit> = replacements.exited(socket, generation)

    fun stopUnkept(keep: HubKeep) = stops.unkept(keep)
}

private class HubHolderStarts(
    private val dir: Path,
    private val binary: Path,
    private val slice: String,
    private val io: CoroutineDispatcher,
    private val log: LogSink,
) {
    private val records = HubRecords(dir)
    private val scope = HubHolderScope()
    private val liveness = HubHolderLiveness(records, scope)
    private val starting = ConcurrentHashMap<String, Mutex>()

    fun socket(key: String): Path = dir.resolve("${key.take(KEY_HEX)}.sock")

    fun running(key: String): Boolean = liveness.attest(socket(key).toString()) != null

    suspend fun start(start: HubStart, bound: Duration): McpResult<Unit> =
        starting.computeIfAbsent(start.key) { Mutex() }.withLock {
            existing(start)?.let { return@withLock it }
            runInterruptible(io) { spawn(start, bound) }
        }

    private fun existing(start: HubStart): McpResult<Unit>? {
        val current = liveness.record(socket(start.key).toString())
        return when {
            current == null -> null
            records.isConfirmedExit(current) -> null
            liveness.attest(current) != null -> McpResult.Served(Unit)
            liveness.confirmExit(current) -> null
            else -> McpResult.Refused("the recorded holder cannot attest its exact child and scope ownership")
        }
    }

    private fun spawn(start: HubStart, bound: Duration): McpResult<Unit> {
        val socket = socket(start.key)
        val at = System.currentTimeMillis()
        val scopeName = scope.name(start.key, at)
        Files.createDirectories(dir)
        Files.deleteIfExists(socket)
        return dir.fileSystem.newWatchService().use { watch ->
            dir.register(watch, StandardWatchEventKinds.ENTRY_CREATE)
            val process = try {
                builder(socket, start.spec, scopeName).start()
            } catch (error: IOException) {
                return@use McpResult.Refused("cannot start '${start.server}': ${SafeFailureText.render(error)}")
            }
            val record = record(start, process.pid(), at, scopeName)
                ?: return@use failedStart(start, process, scopeName, "could not persist its holder record")
            ready(start, process, watch, bound, record)
        }
    }

    private fun ready(
        start: HubStart,
        process: Process,
        watch: WatchService,
        bound: Duration,
        record: HubHolderRecord,
    ): McpResult<Unit> {
        val socket = socket(start.key)
        val deadline = System.nanoTime() + bound.inWholeNanoseconds
        var outcome = settled(start, process, socket)
        while (outcome == null && System.nanoTime() < deadline) {
            watch.poll(READY_CHECK_MS, TimeUnit.MILLISECONDS)?.let { event ->
                event.pollEvents()
                event.reset()
            }
            outcome = settled(start, process, socket)
        }
        return settledOutcome(start, process, record, outcome, bound)
    }

    private fun settledOutcome(
        start: HubStart,
        process: Process,
        record: HubHolderRecord,
        outcome: McpResult<Unit>?,
        bound: Duration,
    ): McpResult<Unit> = when (outcome) {
        is McpResult.Served -> {
            if (liveness.attest(record) != null) {
                log("[mcp-hub] ${LogSafe.str(start.server)}: holder ${LogSafe.str(process.pid().toString())} serves\n")
                outcome
            } else {
                failedStart(
                    start,
                    process,
                    JsonScalars.str(record.body, HOLDER_SCOPE).orEmpty(),
                    "could not attest its child and scope",
                )
            }
        }
        is McpResult.Refused -> outcome
        null -> failedStart(
            start,
            process,
            JsonScalars.str(record.body, HOLDER_SCOPE).orEmpty(),
            "did not open its socket within ${bound.inWholeSeconds} s",
            true,
        )
    }

    private fun failedStart(
        start: HubStart,
        process: Process,
        scopeName: String,
        reason: String,
        timedOut: Boolean = false,
    ): McpResult<Unit> {
        process.destroy()
        scope.stop(scopeName)
        return McpResult.Refused("'${start.server}' $reason", timedOut = timedOut)
    }

    private fun settled(start: HubStart, process: Process, socket: Path): McpResult<Unit>? = when {
        accepts(socket) -> McpResult.Served(Unit)
        !process.isAlive -> McpResult.Refused(
            "'${start.server}' exited with code ${process.exitValue()} while starting",
        )
        else -> null
    }

    private fun builder(socket: Path, spec: HubLaunchSpec, scopeName: String): ProcessBuilder {
        val argv = listOf(
            "systemd-run",
            "--user",
            "--scope",
            "--quiet",
            "--slice=$slice",
            "--unit=${scopeName.removeSuffix(".scope")}",
            "--",
            binary.toString(),
            socket.toString(),
            spec.command,
        ) + spec.args
        val builder = ProcessBuilder(argv)
            .directory(spec.folder.toFile())
            .redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
        val env = builder.environment()
        val manager = MANAGER_ENV.mapNotNull { name -> env[name]?.let { name to it } }.toMap()
        if (!spec.inheritEnv) env.clear()
        env.putAll(manager)
        env.putAll(spec.env)
        return builder
    }

    private fun record(start: HubStart, pid: Long, at: Long, scopeName: String): HubHolderRecord? =
        records.begin(
            start.key,
            buildJsonObject {
                put(HOLDER_SERVER, start.server)
                put(HOLDER_LAUNCH, start.launch.orEmpty())
                put(HOLDER_PID, pid)
                put(HOLDER_TUPLE, start.tuple)
                put(HOLDER_AT, at)
                put(HOLDER_GENERATION, "$pid:$at")
                put(HOLDER_SCOPE, scopeName)
                put(HOLDER_STATE, HOLDER_LIVE)
            },
        )

    private fun accepts(socket: Path): Boolean = try {
        SocketChannel.open(StandardProtocolFamily.UNIX).use { channel ->
            channel.connect(UnixDomainSocketAddress.of(socket))
        }
        true
    } catch (_: IOException) {
        false
    }
}

private class HubHolderLiveness(
    private val records: HubRecords,
    private val scope: HubHolderScope,
) {
    fun record(socket: String, expectedGeneration: String? = null): HubHolderRecord? = records.all()
        .firstOrNull { record ->
            records.socketOf(record.path).toString() == socket &&
                (expectedGeneration == null || JsonScalars.str(record.body, HOLDER_GENERATION) == expectedGeneration)
        }

    fun attest(socket: String, expectedGeneration: String? = null): HubHolderRecord? =
        record(socket, expectedGeneration)?.let(::attest)

    fun attest(record: HubHolderRecord): HubHolderRecord? {
        if (!records.isLive(record)) return null
        val owner = records.owner(record)
        return if (owner != null) {
            record.takeIf { ownerLive(it, owner) }
        } else {
            unrecordedOwner(record)?.let { records.attest(record, it) }
        }
    }

    fun ownerLive(record: HubHolderRecord): Boolean =
        records.owner(record)?.let { ownerLive(record, it) } == true

    fun confirmExit(record: HubHolderRecord): Boolean {
        if (records.isConfirmedExit(record)) return true
        val owner = records.owner(record) ?: return false
        return ownerExited(owner) && records.confirmExit(record, owner)
    }

    fun ownerExited(owner: HubHolderOwner): Boolean =
        !inScope(owner.holderPid, owner.scope) &&
            !inScope(owner.childPid, owner.scope) &&
            scope.exited(owner.scope)

    fun requestStop(owner: HubHolderOwner): Boolean {
        ProcessHandle.of(owner.holderPid).filter(ProcessHandle::isAlive).ifPresent { holder ->
            holder.destroy()
        }
        return scope.stop(owner.scope)
    }

    fun waitForExit(owner: HubHolderOwner): Boolean =
        exited(owner.holderPid) && exited(owner.childPid) && ownerExited(owner)

    private fun unrecordedOwner(record: HubHolderRecord): HubHolderOwner? {
        val socket = records.socketOf(record.path)
        return JsonScalars.long(record.body, HOLDER_PID)
            ?.takeIf { holderAlive(it, socket) }
            ?.let { pid ->
                childOf(pid)?.let { childPid ->
                    scope.of(pid)?.takeIf(scope::running)?.let { scopeName ->
                        val at = JsonScalars.long(record.body, HOLDER_AT) ?: System.currentTimeMillis()
                        HubHolderOwner("$pid:$at", pid, childPid, scopeName)
                    }
                }
            }
    }

    private fun ownerLive(record: HubHolderRecord, owner: HubHolderOwner): Boolean =
        records.isLive(record) &&
            holderAlive(owner.holderPid, records.socketOf(record.path)) &&
            inScope(owner.holderPid, owner.scope) &&
            inScope(owner.childPid, owner.scope) &&
            scope.running(owner.scope)

    private fun holderAlive(pid: Long, socket: Path): Boolean = ProcessHandle.of(pid)
        .filter { it.isAlive }
        .flatMap { it.info().arguments() }
        .map { args -> args.contains(socket.toString()) }
        .orElse(false)

    private fun childOf(pid: Long): Long? = ProcessHandle.of(pid)
        .map { holder ->
            holder.children().use { children ->
                children.filter(ProcessHandle::isAlive).findFirst().orElse(null)
            }
        }
        .map(ProcessHandle::pid)
        .orElse(null)

    private fun inScope(pid: Long, scopeName: String): Boolean =
        ProcessHandle.of(pid).filter(ProcessHandle::isAlive).isPresent && scope.of(pid) == scopeName

    private fun exited(pid: Long): Boolean = ProcessHandle.of(pid).map { process ->
        try {
            process.onExit().get(STOP_WAIT_MS, TimeUnit.MILLISECONDS)
            !process.isAlive
        } catch (_: TimeoutException) {
            false
        } catch (_: ExecutionException) {
            false
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }.orElse(true)
}

/** systemd is the scope's source of truth, while /proc binds legacy records to the live scope that owns them. */
private class HubHolderScope {
    fun name(key: String, at: Long): String =
        "splice-mcp-${key.take(KEY_HEX)}-$at-${UUID.randomUUID().toString().replace("-", "")}.scope"

    fun of(pid: Long): String? = try {
        Files.readAllLines(Path.of("/proc", pid.toString(), "cgroup"))
            .asSequence()
            .map { line -> line.substringAfterLast('/') }
            .firstOrNull { unit -> unit.endsWith(".scope") }
    } catch (_: IOException) {
        null
    }

    fun running(scopeName: String): Boolean = state(scopeName) == SCOPE_ACTIVE

    fun exited(scopeName: String): Boolean = state(scopeName) in setOf(SCOPE_INACTIVE, SCOPE_NOT_FOUND)

    fun stop(scopeName: String): Boolean = command(listOf("stop", "--no-block", scopeName)) == 0

    private fun state(scopeName: String): String? {
        return try {
            val process = ProcessBuilder(
                SYSTEMCTL,
                USER_MANAGER,
                "show",
                "--property=ActiveState",
                "--value",
                scopeName,
            ).redirectError(ProcessBuilder.Redirect.DISCARD).start()
            if (!process.waitFor(SYSTEMCTL_WAIT_MS, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly()
                null
            } else {
                process.inputStream.bufferedReader().use { it.readText().trim() }
                    .takeIf { process.exitValue() == 0 }
            }
        } catch (_: IOException) {
            null
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        }
    }

    private fun command(args: List<String>): Int = try {
        val process = ProcessBuilder(listOf(SYSTEMCTL, USER_MANAGER) + args)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
        if (process.waitFor(SYSTEMCTL_WAIT_MS, TimeUnit.MILLISECONDS)) {
            process.exitValue()
        } else {
            process.destroyForcibly()
            -1
        }
    } catch (_: IOException) {
        -1
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        -1
    }
}

private class HubHolderStops(
    private val records: HubRecords,
    private val liveness: HubHolderLiveness,
    private val log: LogSink,
) {
    fun unkept(keep: HubKeep) {
        records.all().filter(records::isLive).forEach { record ->
            val launch = JsonScalars.str(record.body, HOLDER_LAUNCH)?.takeIf { it.isNotEmpty() }
            val held = HubRecord(
                launch,
                records.socketOf(record.path).toString(),
                JsonScalars.long(record.body, HOLDER_AT) ?: 0L,
            )
            keep.stopReason(held)?.let { reason -> stop(liveness.attest(record) ?: record, reason) }
        }
    }

    fun stop(record: HubHolderRecord, reason: String): McpResult<Unit> {
        val owner = records.owner(record)
        return when {
            confirmed(record) -> McpResult.Served(Unit)
            owner == null -> McpResult.Refused("the holder has no attested child and scope owner")
            !liveness.requestStop(owner) -> McpResult.Refused(
                "the holder's exact systemd scope did not accept retirement",
            )
            !liveness.waitForExit(owner) -> McpResult.Refused(
                "the old holder, child, or systemd scope did not exit after retirement",
            )
            !records.confirmExit(record, owner) -> McpResult.Refused(
                "the holder record changed before its exact exit was confirmed",
            )
            else -> stopped(record, owner, reason)
        }
    }

    private fun stopped(record: HubHolderRecord, owner: HubHolderOwner, reason: String): McpResult<Unit> {
        removeSocket(records.socketOf(record.path))
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

private class HubHolderReplacements(
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
            null -> rollbackBeforeRetirement(replacement)
        }
    }

    fun exited(socket: String, generation: String): McpResult<Unit> {
        val record = liveness.record(socket, generation)
            ?: return McpResult.Refused("the holder generation is no longer recorded")
        return if (stops.confirmed(record)) {
            McpResult.Served(Unit)
        } else {
            McpResult.Refused("the holder generation has not confirmed its child and scope exit")
        }
    }

    private fun beginRetirement(replacement: HubReplacement): McpResult<Unit> {
        val candidate = candidate(replacement)
        val old = liveness.attest(replacement.oldSocket, replacement.oldGeneration)
        return when {
            candidate == null -> McpResult.Refused("the candidate holder no longer owns its captured generation")
            old == null -> McpResult.Refused("the old holder generation is no longer live")
            !liveness.ownerLive(old) -> McpResult.Refused("the old holder does not own its exact child and scope")
            else -> claimedRetirement(replacement)
        }
    }

    private fun claimedRetirement(replacement: HubReplacement): McpResult<Unit> =
        when (retirements.claim(replacement, HubRetirementState.RETIRING)) {
            HubRetirementState.RETIRED -> McpResult.Served(Unit)
            HubRetirementState.ROLLED_BACK -> McpResult.Refused("this replacement was already rolled back")
            HubRetirementState.RETIRING -> reconcile(replacement)
            null -> McpResult.Refused("the replacement outcome is being persisted; retry retirement")
        }

    private fun reconcile(replacement: HubReplacement): McpResult<Unit> {
        val old = liveness.record(replacement.oldSocket, replacement.oldGeneration)
        val candidate = candidate(replacement)
        return when {
            old == null -> McpResult.Refused("the old holder generation is no longer recorded")
            stops.confirmed(old) -> completed(replacement)
            candidate == null -> McpResult.Refused("the candidate holder no longer owns its captured generation")
            else -> stop(old, replacement)
        }
    }

    private fun stop(old: HubHolderRecord, replacement: HubReplacement): McpResult<Unit> =
        when (val stopped = stops.stop(old, "drained replacement")) {
            is McpResult.Served -> completed(replacement)
            is McpResult.Refused -> stopped
        }

    private fun rollbackAfterRetirementStarted(replacement: HubReplacement): McpResult<Unit> {
        val old = liveness.record(replacement.oldSocket, replacement.oldGeneration)
        if (old != null && stops.confirmed(old)) {
            retirements.complete(replacement, HubRetirementState.RETIRED)
            return McpResult.Refused("the old holder retirement already completed")
        }
        return McpResult.Refused(
            "the old holder retirement had already started; retry retirement to reconcile it",
        )
    }

    private fun rollbackBeforeRetirement(replacement: HubReplacement): McpResult<Unit> {
        val candidate = liveness.record(replacement.candidateSocket, replacement.candidateGeneration)
        val old = liveness.attest(replacement.oldSocket, replacement.oldGeneration)
        return when {
            candidate == null -> McpResult.Refused("the candidate holder's exact generation is unknown")
            !matches(candidate, replacement) -> McpResult.Refused(
                "the candidate holder has not owner-confirmed its exact exit",
            )
            old == null -> McpResult.Refused("the old holder's exact generation is not live")
            !liveness.ownerLive(old) -> McpResult.Refused(
                "the old holder and child are not both live for rollback",
            )
            else -> claimedRollback(replacement)
        }
    }

    private fun matches(candidate: HubHolderRecord, replacement: HubReplacement): Boolean =
        JsonScalars.str(candidate.body, HOLDER_TUPLE) == replacement.candidateTuple &&
            stops.confirmed(candidate)

    private fun claimedRollback(replacement: HubReplacement): McpResult<Unit> =
        when (retirements.claim(replacement, HubRetirementState.ROLLED_BACK)) {
            HubRetirementState.ROLLED_BACK -> McpResult.Served(Unit)
            HubRetirementState.RETIRED -> McpResult.Refused("the old holder retirement already completed")
            HubRetirementState.RETIRING -> rollbackAfterRetirementStarted(replacement)
            null -> McpResult.Refused("the replacement outcome is being persisted; retry rollback")
        }

    private fun candidate(replacement: HubReplacement): HubHolderRecord? =
        liveness.attest(replacement.candidateSocket, replacement.candidateGeneration)
            ?.takeIf { JsonScalars.str(it.body, HOLDER_TUPLE) == replacement.candidateTuple }
            ?.takeIf(liveness::ownerLive)

    private fun completed(replacement: HubReplacement): McpResult<Unit> =
        if (retirements.complete(replacement, HubRetirementState.RETIRED)) {
            McpResult.Served(Unit)
        } else {
            McpResult.Refused("the exact retirement completed but its durable outcome was not recorded")
        }
}
