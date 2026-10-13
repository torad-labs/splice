// NEW: MCP hub steps 3 and 4 — the holders the daemon starts and stops. A holder joins one server's stdin and stdout
// to a socket under <state>/mcp-hub, and outlives the daemon (proof (d)); the front talks to the socket. The spawn is
// splice-config's shape (Oct 12): `systemd-run --user --scope --quiet --slice=<slice> -- splice-mcp-holder <socket>
// <command> <args...>`, ALWAYS in its own scope, even under an uncapped slice, because a holder left in splice's cgroup
// dies with the unit's restart; the env and folder ride the ProcessBuilder, which a scope execs in place; and every
// standard stream is discarded, because a server holding a pipe to the JVM dies of EPIPE once the daemon is gone.
//
// Each holder has a record beside its socket (server, launch, pid), so a daemon that restarted still knows which
// per-session copies belong to which launch, and stops each once its launch is gone (SIGTERM to the holder, which
// passes it to the server). A pid is signalled only while its command line still names its own socket, so a reused
// pid is never hit.
package splice.control.mcp.hub

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.control.mcp.JsonRpcCodec
import splice.control.mcp.McpResult
import splice.core.util.JsonScalars
import splice.core.util.JsonWire
import splice.core.util.LogSafe
import splice.core.util.LogSink
import splice.core.util.SafeFailureText
import splice.core.util.SecureFile
import java.io.File
import java.io.IOException
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds
import java.nio.file.WatchService
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.time.Duration

// why: the longest a starting holder goes unchecked: the wait wakes at once when its socket appears, and this bound
// is only how soon a holder that died without binding is noticed.
private const val READY_CHECK_MS = 100L

// why: how much of a digest names a socket: 16 hex keep `<state>/mcp-hub/<key>.sock` far under the 108-byte Unix
// limit on any home, and 64 bits never collide between the servers one machine runs.
private const val KEY_HEX = 16

// why: what a scope needs to reach the user's systemd, kept even when a per-session copy takes the session's env.
private val MANAGER_ENV = listOf("XDG_RUNTIME_DIR", "DBUS_SESSION_BUS_ADDRESS")

/** One holder to start: [launch] is the session's launch id for a per-session copy, null for a shared one. */
internal data class HubStart(val key: String, val server: String, val launch: String?, val spec: HubLaunchSpec)

/** One recorded holder as the sweep weighs it: its launch (null for a shared one), its socket, and when it was
 *  recorded, in epoch milliseconds (0 for a record older than the field). */
internal data class HubRecord(val launch: String?, val socket: String, val recordedAt: Long)

/** Whether the sweep keeps a recorded holder running. */
internal fun interface HubKeep {
    fun keeps(record: HubRecord): Boolean
}

/** Where the holders live and how they start: [binary] is splice-mcp-holder beside splice.jar; [io] runs the blocking
 *  spawn and its wait for the socket. */
internal class HubHolders(
    private val dir: Path,
    private val binary: Path,
    private val slice: String,
    private val io: CoroutineDispatcher,
    private val log: LogSink,
) {
    private val starting = ConcurrentHashMap<String, Mutex>()

    fun socket(key: String): Path = dir.resolve("${key.take(KEY_HEX)}.sock")

    /** A holder is running when its recorded pid is alive and still names its socket. */
    fun running(key: String): Boolean = recordedPid(key)?.let { holderAlive(it, socket(key)) } == true

    /** Starts [start]'s holder unless it runs, and answers once its socket accepts a connection or the start failed. */
    suspend fun start(start: HubStart, bound: Duration): McpResult<Unit> =
        starting.computeIfAbsent(start.key) { Mutex() }.withLock {
            if (running(start.key)) McpResult.Served(Unit) else runInterruptible(io) { spawn(start, bound) }
        }

    /** Stops every holder [keep] no longer keeps: a per-session copy whose session ended, or a shared one no session
     *  needs any more. */
    fun stopUnkept(keep: HubKeep) {
        if (!Files.isDirectory(dir)) return
        val records = Files.list(dir).use { paths ->
            paths.filter { it.fileName.toString().endsWith(".json") }.toList()
        }
        records.forEach { record ->
            val body = read(record) ?: return@forEach
            val launch = JsonScalars.str(body, "launch")?.takeIf { it.isNotEmpty() }
            val socket = record.resolveSibling(record.fileName.toString().removeSuffix(".json") + ".sock")
            val held = HubRecord(launch, socket.toString(), JsonScalars.long(body, "at") ?: 0L)
            if (!keep.keeps(held)) stop(record, socket, JsonScalars.long(body, "pid"))
        }
    }

    private fun spawn(start: HubStart, bound: Duration): McpResult<Unit> {
        val socket = socket(start.key)
        Files.createDirectories(dir)
        Files.deleteIfExists(socket)
        return dir.fileSystem.newWatchService().use { watch ->
            dir.register(watch, StandardWatchEventKinds.ENTRY_CREATE)
            val process = try {
                builder(socket, start.spec).start()
            } catch (e: IOException) {
                return@use McpResult.Refused("cannot start '${start.server}': ${SafeFailureText.render(e)}")
            }
            record(start, process.pid())
            ready(start, process, watch, bound)
        }
    }

    /** Waits for [process]'s socket to accept, waking when a file appears in the folder or at [READY_CHECK_MS]. */
    private fun ready(start: HubStart, process: Process, watch: WatchService, bound: Duration): McpResult<Unit> {
        val socket = socket(start.key)
        val deadline = System.nanoTime() + bound.inWholeNanoseconds
        var outcome = settled(start, process, socket)
        while (outcome == null && System.nanoTime() < deadline) {
            watch.poll(READY_CHECK_MS, TimeUnit.MILLISECONDS)?.let { it.pollEvents(); it.reset() }
            outcome = settled(start, process, socket)
        }
        return outcome ?: McpResult.Refused(
            "'${start.server}' did not open its socket within ${bound.inWholeSeconds} s",
            timedOut = true,
        ).also { process.destroy() }
    }

    /** Served once the socket accepts, refused once the holder died, else null: still starting. */
    private fun settled(start: HubStart, process: Process, socket: Path): McpResult<Unit>? = when {
        accepts(socket) -> McpResult.Served(Unit).also {
            log(
                "[mcp-hub] ${LogSafe.str(start.server)}: holder " +
                    "${LogSafe.str(process.pid().toString())} serves\n",
            )
        }
        !process.isAlive ->
            McpResult.Refused("'${start.server}' exited with code ${process.exitValue()} while starting")
        else -> null
    }

    private fun builder(socket: Path, spec: HubLaunchSpec): ProcessBuilder {
        val argv = listOf("systemd-run", "--user", "--scope", "--quiet", "--slice=$slice", "--", binary.toString()) +
            listOf(socket.toString(), spec.command) + spec.args
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

    private fun record(start: HubStart, pid: Long) {
        val body = buildJsonObject {
            put("server", start.server)
            put("launch", start.launch.orEmpty())
            put("pid", pid)
            put("at", System.currentTimeMillis())
        }
        try {
            SecureFile.writeAtomic0600(dir.resolve("${start.key.take(KEY_HEX)}.json"), JsonWire.string(body))
        } catch (e: IOException) {
            log("[mcp-hub] could not record a holder (${LogSafe.str(SafeFailureText.render(e))})\n")
        }
    }

    private fun stop(record: Path, socket: Path, pid: Long?) {
        pid?.takeIf { holderAlive(it, socket) }?.let { live ->
            ProcessHandle.of(live).ifPresent { it.destroy() }
            log("[mcp-hub] stopped holder ${LogSafe.str(live.toString())}: no session needs it\n")
        }
        Files.deleteIfExists(record)
        Files.deleteIfExists(socket)
    }

    private fun recordedPid(key: String): Long? =
        JsonScalars.long(read(dir.resolve("${key.take(KEY_HEX)}.json")), "pid")

    private fun read(record: Path): JsonObject? = try {
        record.takeIf(Files::isRegularFile)?.let { JsonRpcCodec().parse(Files.readString(it)) }
    } catch (_: IOException) {
        null
    }

    private fun holderAlive(pid: Long, socket: Path): Boolean = ProcessHandle.of(pid)
        .filter { it.isAlive }
        .flatMap { it.info().arguments() }
        .map { args -> args.contains(socket.toString()) }
        .orElse(false)

    private fun accepts(socket: Path): Boolean = try {
        SocketChannel.open(StandardProtocolFamily.UNIX).use { it.connect(UnixDomainSocketAddress.of(socket)) }
    } catch (_: IOException) {
        false
    }
}
