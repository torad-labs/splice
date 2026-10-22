package splice.control.mcp.hub

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.control.mcp.McpResult
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
import java.util.concurrent.TimeUnit
import kotlin.time.Duration

// why: socket creation wakes this interval; the same interval bounds startup readiness without busy polling.
private const val READY_CHECK_MS = 100L
private val MANAGER_ENV = listOf("XDG_RUNTIME_DIR", "DBUS_SESSION_BUS_ADDRESS")

internal class HubHolderStarts(
    private val dir: Path,
    private val binary: Path,
    private val slice: String,
    private val io: CoroutineDispatcher,
    private val holder: HubHolderContext,
    private val log: LogSink,
) {
    fun socket(key: String): Path = dir.resolve("${key.take(KEY_HEX)}.sock")

    fun running(key: String): Boolean = holder.liveness.attest(socket(key).toString()) != null

    suspend fun start(start: HubStart, bound: Duration): McpResult<Unit> = runInterruptible(io) {
        holder.lifecycle.serial(socket(start.key).toString()) { existing(start) ?: spawn(start, bound) }
    }

    private fun existing(start: HubStart): McpResult<Unit>? {
        val current = holder.liveness.record(socket(start.key).toString())
        return when {
            current == null -> null
            holder.records.exits.confirmed(current) -> null
            holder.liveness.attest(current) != null -> McpResult.Served(Unit)
            holder.liveness.confirmExit(current) -> null
            holder.records.ownership.owner(current) == null &&
                holder.liveness.requestLaunchStop(current) &&
                holder.liveness.confirmExit(current) -> null
            else -> McpResult.Refused("the recorded holder cannot attest its exact child and scope ownership")
        }
    }

    private fun spawn(start: HubStart, bound: Duration): McpResult<Unit> {
        val socket = socket(start.key)
        val at = System.currentTimeMillis()
        val scopeName = holder.scope.name(start.key, at)
        val claim = HubHolderScopeClaim(UUID.randomUUID().toString(), scopeName)
        Files.createDirectories(dir)
        val intent = record(start, claim, at)
            ?: return McpResult.Refused("cannot persist '${start.server}' holder startup intent")
        Files.deleteIfExists(socket)
        return dir.fileSystem.newWatchService().use { watch ->
            dir.register(watch, StandardWatchEventKinds.ENTRY_CREATE)
            val process = try {
                builder(socket, start.spec, scopeName).start()
            } catch (error: IOException) {
                holder.liveness.confirmExit(intent, unlaunched = true)
                return@use McpResult.Refused("cannot start '${start.server}': ${SafeFailureText.render(error)}")
            }
            val launch = HubHolderLaunch(
                claim.generation,
                process.pid(),
                scopeName,
                process.toHandle().info().startInstant().map { it.toEpochMilli() }.orElse(null),
            )
            val record = holder.records.ownership.started(intent, launch)
                ?: return@use failedStart(start, intent, "could not persist its holder launch")
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
        is McpResult.Served -> served(start, process, record, outcome)
        is McpResult.Refused -> outcome.also { abandon(record) }
        null -> failedStart(start, record, "did not open its socket within ${bound.inWholeSeconds} s", true)
    }

    private fun served(
        start: HubStart,
        process: Process,
        record: HubHolderRecord,
        outcome: McpResult.Served<Unit>,
    ): McpResult<Unit> = if (holder.liveness.attest(record) != null) {
        log("[mcp-hub] ${LogSafe.str(start.server)}: holder ${LogSafe.str(process.pid().toString())} serves\n")
        outcome
    } else {
        failedStart(start, record, "could not attest its child and scope")
    }

    private fun failedStart(
        start: HubStart,
        record: HubHolderRecord?,
        reason: String,
        timedOut: Boolean = false,
    ): McpResult<Unit> {
        abandon(record)
        return McpResult.Refused("'${start.server}' $reason", timedOut = timedOut)
    }

    private fun abandon(record: HubHolderRecord?) {
        record?.let { held ->
            holder.liveness.requestLaunchStop(held)
            holder.liveness.confirmExit(held)
        }
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

    private fun record(start: HubStart, claim: HubHolderScopeClaim, at: Long): HubHolderRecord? =
        holder.records.files.begin(
            start.key,
            buildJsonObject {
                put(HubFields.SERVER, start.server)
                put(HubFields.LAUNCH, start.launch.orEmpty())
                put(HubFields.TUPLE, start.tuple)
                put(HubFields.AT, at)
                put(HubFields.GENERATION, claim.generation)
                put(HubFields.SCOPE, claim.scope)
                put(HubFields.STATE, HOLDER_STARTING)
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
