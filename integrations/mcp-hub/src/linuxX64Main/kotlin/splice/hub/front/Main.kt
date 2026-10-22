// splice-mcp-front: the one place sessions send MCP calls. It holds the port and every session's stream, checks the
// bearer, and routes calls to holders. The daemon only says where servers run and starts them, so a daemon swap
// never touches a session's connection.
//
//   splice-mcp-front --hub <state>/mcp-hub --control-port <port> [--port <port>]
//
// The listening socket is systemd's when LISTEN_FDS says so (one TCP socket, fd 3), else --port on 127.0.0.1. Files in
// the hub folder: access-key (the bearer sessions present, written by the daemon) and front.json (this front's own
// state: routes, sessions, initialized holders, generation-bound relay ledgers, and target drain contracts). The daemon's management key is <state>/mgmt-key.
@file:OptIn(ExperimentalForeignApi::class)

package splice.hub.front

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import platform.posix.O_CREAT
import platform.posix.O_DIRECTORY
import platform.posix.O_NOFOLLOW
import platform.posix.O_RDONLY
import platform.posix.O_TRUNC
import platform.posix.O_WRONLY
import platform.posix.SIGINT
import platform.posix.SIGPIPE
import platform.posix.SIGTERM
import platform.posix.SIGUSR2
import platform.posix.SIG_IGN
import platform.posix.close
import platform.posix.exit
import platform.posix.fstat
import platform.posix.fsync
import platform.posix.getenv
import platform.posix.geteuid
import platform.posix.getpid
import platform.posix.open
import platform.posix.rename
import platform.posix.signal
import platform.posix.stat
import platform.posix.unlink
import platform.posix.write
import splice.hub.io.Bytes
import kotlin.concurrent.AtomicInt

fun main(args: Array<String>) {
    val opts = args.toList().chunked(2).filter { it.size == 2 }.associate { it[0] to it[1] }
    val hub = opts["--hub"]
    val control = opts["--control-port"]?.toIntOrNull()
    if (hub == null || control == null) {
        HubFiles("").say("usage: splice-mcp-front --hub <dir> --control-port <port> [--port <port>]")
        exit(USAGE)
        return
    }
    exit(Front(hub, control, opts["--port"]?.toIntOrNull(), opts["--resume"], args.toList()).run())
}

/** Set by SIGTERM: stop accepting, finish the calls in flight, save, exit. */
val stopAsked = AtomicInt(0)

/** Set by SIGUSR2: finish the calls in flight, then exec the binary at this path with every descriptor kept. */
val upgradeAsked = AtomicInt(0)

class Front(
    private val hubDir: String,
    private val controlPort: Int,
    private val port: Int?,
    private val resume: String?,
    private val args: List<String>,
) {
    fun run(): Int {
        signal(SIGPIPE, SIG_IGN)
        signal(SIGTERM, staticCFunction { _: Int -> stopAsked.value = 1 })
        signal(SIGINT, staticCFunction { _: Int -> stopAsked.value = 1 })
        signal(SIGUSR2, staticCFunction { _: Int -> upgradeAsked.value = 1 })
        val clock = Clock()
        val sys = Sys()
        val loop = Loop(clock)
        val files = HubFiles(hubDir)
        val timers = Timers(clock).also(loop::add)
        val daemon = Daemon(sys, loop, controlPort, files)
        val hub = Hub(files, daemon, Links(sys, loop), timers, clock)
        hub.restore()
        val control = HolderDrainControl(hub)
        val upgrade = Upgrade(sys, files, args)
        val admission = UpgradeAdmission()
        val listen = { fd: Int ->
            HttpListener(sys.nonblocking(fd), sys, loop, clock, files::admits) { conn, request ->
                when {
                    upgradeAsked.value == 1 && !admission.permits(request) ->
                        conn.reply(
                            UNAVAILABLE,
                            Rpc.error(JsonNull, "the MCP front is draining for an in-place upgrade"),
                        )
                    control.handles(request.path) -> control.handle(conn, request)
                    else -> hub.http.handle(conn, request)
                }
            }.also(loop::add)
        }
        val listener = resume?.let { upgrade.resume(it, listen, hub) } ?: fresh(sys, files, listen) ?: return NO_SOCKET
        hub.reconcile()
        hub.keepAlive()
        loop.run(Lifecycle(clock, loop, files, hub, upgrade, listener)::done)
        hub.stopping = true
        hub.save()
        files.say("stopped")
        return 0
    }

    /** The listening socket of a front started fresh: systemd's when LISTEN_FDS says so, else --port. */
    private fun fresh(sys: Sys, files: HubFiles, listen: (Int) -> HttpListener): HttpListener? {
        val inherited = getenv("LISTEN_FDS")?.toKString() == "1" &&
            getenv("LISTEN_PID")?.toKString() == getpid().toString()
        val fd = if (inherited) SYSTEMD_FD else port?.let(sys::listenTcp) ?: -1
        if (fd < 0) {
            files.say("no listening socket: give --port or start under a systemd socket")
            return null
        }
        files.say("serving on ${if (inherited) "systemd's socket" else "127.0.0.1:$port"}")
        return listen(fd)
    }
}

/** Management-key control of a single retained holder's admission boundary. No process action is available here. */
class HolderDrainControl(private val hub: Hub) {
    fun handles(path: String): Boolean =
        path == HOLDER_DRAIN || path == HOLDER_DRAIN_STATUS || path == HOLDER_DRAIN_REPLACE

    fun handle(conn: HttpConn, request: Request) {
        val target = target(conn, request) ?: return
        action(conn, request, target)
    }

    private fun target(conn: HttpConn, request: Request): HolderDrainTarget? {
        val body = JsonText.objectOrNull(request.body)
        return parseTarget(body) ?: rejectTarget(conn, body)
    }

    private fun parseTarget(body: JsonObject?): HolderDrainTarget? {
        val server = server(body) ?: return null
        val launch = Rpc.str(body, "launch") ?: return null
        return HolderDrainTarget(server, launch)
    }

    private fun rejectTarget(conn: HttpConn, body: JsonObject?): HolderDrainTarget? {
        val words = when {
            body == null -> "the drain target must be one JSON object"
            server(body) == null -> "the drain target needs server"
            else -> "the drain target needs launch"
        }
        conn.reply(BAD_REQUEST, Rpc.error(JsonNull, words))
        return null
    }

    private fun server(body: JsonObject?): String? =
        Rpc.str(body, "server")?.takeIf(String::isNotEmpty)

    private fun action(conn: HttpConn, request: Request, target: HolderDrainTarget) {
        when (request.path to request.method) {
            HOLDER_DRAIN to "POST" -> pause(conn, target)
            HOLDER_DRAIN_STATUS to "POST" -> status(conn, target)
            HOLDER_DRAIN_REPLACE to "POST" -> replace(conn, target)
            HOLDER_DRAIN to "DELETE" -> resume(conn, target)
            else -> conn.reply(NOT_ALLOWED)
        }
    }

    private fun pause(conn: HttpConn, target: HolderDrainTarget) {
        hub.replacements.pause(target.server, target.launch) { blocker ->
            val status = hub.replacements.status(target.server, target.launch)
            when {
                blocker != null -> conn.reply(UNAVAILABLE, Rpc.error(JsonNull, blocker))
                status == null -> conn.reply(UNAVAILABLE, Rpc.error(JsonNull, "the drain state was not retained"))
                else -> conn.reply(OK, Rpc.encode(status))
            }
        }
    }

    private fun status(conn: HttpConn, target: HolderDrainTarget) {
        val status = hub.replacements.status(target.server, target.launch)
        if (status == null) {
            conn.reply(NOT_FOUND, Rpc.error(JsonNull, "no replacement drain for this target"))
        } else {
            conn.reply(OK, Rpc.encode(status))
        }
    }

    private fun replace(conn: HttpConn, target: HolderDrainTarget) {
        hub.replacements.replace(target.server, target.launch) { failure ->
            val status = hub.replacements.status(target.server, target.launch)
            when {
                failure != null -> conn.reply(UNAVAILABLE, Rpc.error(JsonNull, failure))
                status == null -> conn.reply(NOT_FOUND, Rpc.error(JsonNull, "no replacement drain for this target"))
                else -> conn.reply(OK, Rpc.encode(status))
            }
        }
    }

    private fun resume(conn: HttpConn, target: HolderDrainTarget) {
        hub.replacements.resume(target.server, target.launch) { failure ->
            if (failure == null) conn.reply(OK) else conn.reply(UNAVAILABLE, Rpc.error(JsonNull, failure))
        }
    }

    private data class HolderDrainTarget(val server: String, val launch: String)
}

/** The front's two ways out of the loop: an in-place upgrade asked by SIGUSR2, and a stop asked by SIGTERM. Both
 *  wait for every wire call the front admitted. */
class Lifecycle(
    private val clock: Clock,
    private val loop: Loop,
    private val files: HubFiles,
    private val hub: Hub,
    private val upgrade: Upgrade,
    private val listener: HttpListener,
) {
    private var stopAt = 0L
    private var upgradeBy = 0L
    private var upgradeBlocked = false

    /** One loop turn's check; true once the front may exit. */
    fun done(): Boolean {
        if (upgradeAsked.value == 1 && upgradeBy == 0L) beginUpgrade()
        if (upgradeDue()) finishUpgrade()
        if (stopAsked.value == 1 && stopAt == 0L) beginStop()
        return stopAt != 0L && (hub.inFlight() == 0 || clock.now() > stopAt)
    }

    private fun beginUpgrade() {
        upgradeBy = clock.now() + DRAIN
        upgradeBlocked = false
        files.say("upgrade asked: ${hub.inFlight()} calls in flight")
    }

    private fun upgradeDue(): Boolean {
        if (upgradeBy == 0L) return false
        if (upgrade.settled(loop, hub.inFlight())) return true
        if (clock.now() > upgradeBy && !upgradeBlocked) {
            upgradeBlocked = true
            files.say("upgrade blocked: ${hub.inFlight()} wire calls remain; old front retained")
        }
        return false
    }

    /** Execs the new code; returns only when the exec failed, and then the running code carries on. */
    private fun finishUpgrade() {
        upgrade.exec(loop, listener, hub, upgrade.ownPath())
        upgradeAsked.value = 0
        upgradeBy = 0L
        upgradeBlocked = false
        hub.stopping = false
        loop.all().filterIsInstance<HttpConn>().forEach(HttpConn::parse)
    }

    private fun beginStop() {
        stopAt = clock.now() + DRAIN
        listener.accepting = false
        loop.all().filterIsInstance<HttpConn>().forEach { it.closing = true }
        files.say("stopping: ${hub.inFlight()} calls in flight")
    }
}

/** Deadlines without a descriptor: poll skips a negative fd, so this only ever wakes the loop on time. */
class Timers(private val clock: Clock) : Watched(-1) {
    private val due = ArrayList<Pair<Long, () -> Unit>>()

    fun at(time: Long, action: () -> Unit) {
        due += time to action
    }

    override fun events(): Int = 0

    override fun ready(revents: Int) = Unit

    override fun deadline(): Long? = due.minOfOrNull { it.first }

    override fun due() {
        val now = clock.now()
        val fire = due.filter { it.first <= now }
        due.removeAll(fire)
        fire.forEach { it.second() }
    }
}

/** Calls to the daemon's control port, each one connection. */
class Daemon(private val sys: Sys, private val loop: Loop, private val port: Int, private val files: HubFiles) {
    fun call(path: String, body: String, done: (Int, String) -> Unit) {
        val fd = sys.connectTcp(port)
        if (fd < 0) return done(0, "")
        loop.add(DaemonCall(sys, path, files.mgmtKey(), body, done, fd))
    }
}

/** Connections to holders' sockets. */
class Links(private val sys: Sys, private val loop: Loop) {
    fun connect(path: String, onLine: (String) -> Unit, onLost: () -> Unit): HolderLink? {
        val fd = sys.connectUnix(path)
        if (fd < 0) return null
        return HolderLink(fd, path, sys, onLine, onLost).also(loop::add)
    }

    fun adopt(fd: Int, path: String, saved: ByteArray, onLine: (String) -> Unit, onLost: () -> Unit): HolderLink =
        HolderLink(fd, path, sys, onLine, onLost).also {
            it.inbox.append(saved)
            loop.add(it)
        }
}

/** Same owner-only publication as the daemon. Missing, unreadable, linked, foreign-owned or
 *  malformed state fails closed; a read error never returns a partial credential. No key is cached. */
private class McpAccessRead(private val dir: String) {
    fun key(): String? {
        if (!ownerDirectory()) return null
        val fd = open("$dir/access-key", O_RDONLY or O_NOFOLLOW)
        if (fd < 0) return null
        try {
            return if (ownerHeld(fd, REGULAR_KIND)) readKey(fd) else null
        } finally {
            close(fd)
        }
    }

    private fun ownerDirectory(): Boolean {
        val fd = open(dir, O_RDONLY or O_DIRECTORY or O_NOFOLLOW)
        if (fd < 0) return false
        try {
            return ownerHeld(fd, DIRECTORY_KIND)
        } finally {
            close(fd)
        }
    }

    private fun readKey(fd: Int): String? {
        val bytes = ByteArray(ACCESS_KEY_LENGTH + KEY_READ_SLACK)
        var size = 0
        while (size < bytes.size) {
            val n = bytes.usePinned { platform.posix.read(fd, it.addressOf(size), (bytes.size - size).convert()) }
            if (n < 0) return null
            if (n == 0L) break
            size += n.toInt()
        }
        if (size !in ACCESS_KEY_LENGTH..ACCESS_KEY_LENGTH + 1) return null
        return bytes.decodeToString(endIndex = size).trim().takeIf { key ->
            key.length == ACCESS_KEY_LENGTH && key.all { it in '0'..'9' || it in 'a'..'f' }
        }
    }

    private fun ownerHeld(fd: Int, kind: UInt): Boolean = memScoped {
        val held = alloc<stat>()
        fstat(fd, held.ptr) == 0 && held.st_uid == geteuid() &&
            held.st_mode and FILE_KIND_MASK == kind && held.st_mode and FOREIGN_MODE_BITS == 0u
    }
}

/** The hub folder: the keys the front reads, its own state, the daemon's kept listings, and its log line. */
class HubFiles(private val dir: String) {
    private val access = McpAccessRead(dir)
    private val stateWriter = HubStateWriter(::say)

    val accessKey: String? get() = access.key()

    /** Authenticates MCP session traffic or the front's narrow management controls before their body is buffered. */
    fun admits(path: String, headers: Map<String, String>): Boolean =
        when {
            path.startsWith(MCP_PATH) -> accessKey?.let { headers["authorization"] == "Bearer $it" } == true
            path in setOf(HOLDER_DRAIN, HOLDER_DRAIN_STATUS, HOLDER_DRAIN_REPLACE) -> managementAdmits(headers)
            else -> false
        }

    fun mgmtKey(): String = read("${dir.substringBeforeLast('/')}/mgmt-key")?.trim().orEmpty()

    private fun managementAdmits(headers: Map<String, String>): Boolean =
        mgmtKey().takeIf(String::isNotEmpty)?.let { headers["authorization"] == "Bearer $it" } == true

    fun execPath(): String = "$dir/front-exec.json"

    fun saveExec(text: String) = stateWriter.write(execPath(), text)

    fun readJson(path: String): JsonObject? =
        read(path)?.let(JsonText::objectOrNull)

    fun remove(path: String) {
        unlink(path)
    }

    fun loadState(): JsonObject? = HubStateReader().load("$dir/front.json")

    fun saveState(text: String): Boolean = stateWriter.write("$dir/front.json", text)

    /** The kept tools/list answer, or null when there is none. */
    fun listing(path: String?): JsonElement? = path?.let(::read)?.let { text ->
        JsonText.objectOrNull(text)?.get("tools")
    }

    fun listingInit(path: String?): JsonObject? = path?.let(::read)?.let { text ->
        JsonText.objectOrNull(text)?.get("initialize") as? JsonObject
    }

    fun say(text: String) {
        val bytes = "splice-mcp-front: $text\n".encodeToByteArray()
        bytes.usePinned { write(2, it.addressOf(0), bytes.size.convert()) }
    }

    private fun read(path: String): String? {
        val fd = open(path, O_RDONLY)
        if (fd < 0) return null
        val out = Bytes()
        val sys = Sys()
        while (sys.readInto(fd, out) > 0) Unit
        close(fd)
        return out.text(0, out.size)
    }
}

/** The front's durable state-file primitive. A false result means a caller must not expose its new ledger state. */
private class HubStateWriter(private val report: (String) -> Unit) {
    fun write(path: String, text: String): Boolean {
        val temporary = "$path.tmp"
        val fd = open(temporary, O_WRONLY or O_CREAT or O_TRUNC, OWNER_RW)
        if (fd < 0) return failed("could not write $temporary")
        val bytes = text.encodeToByteArray()
        val flushed = writeAll(fd, bytes) && fsync(fd) == 0
        val closed = close(fd) == 0
        return (flushed && closed && rename(temporary, path) == 0 && syncDirectory(path))
            .takeIf { it }
            ?: failed("could not save $path").also { unlink(temporary) }
    }

    private fun writeAll(fd: Int, bytes: ByteArray): Boolean = bytes.usePinned { pinned ->
        var offset = 0
        while (offset < bytes.size) {
            val count = write(fd, pinned.addressOf(offset), (bytes.size - offset).convert())
            if (count <= 0) return@usePinned false
            offset += count.toInt()
        }
        true
    }

    private fun syncDirectory(path: String): Boolean {
        val fd = open(path.substringBeforeLast('/'), O_RDONLY or O_DIRECTORY)
        if (fd < 0) return false
        val synced = fsync(fd) == 0
        val closed = close(fd) == 0
        return synced && closed
    }

    private fun failed(words: String): Boolean {
        report(words)
        return false
    }
}

private const val MCP_PATH = "/mcp/"
private const val HOLDER_DRAIN = "/mgmt/mcp/holders/drain"
private const val HOLDER_DRAIN_STATUS = "/mgmt/mcp/holders/drain/status"
private const val HOLDER_DRAIN_REPLACE = "/mgmt/mcp/holders/drain/replace"
private const val SYSTEMD_FD = 3
private const val USAGE = 2
private const val NO_SOCKET = 3
private const val DRAIN = 10_000L
private const val OWNER_RW = 0x180 // 0600
private const val ACCESS_KEY_LENGTH = 64
private const val KEY_READ_SLACK = 2 // one allowed newline, one byte to detect an oversized publication
private const val FILE_KIND_MASK = 0xF000u
private const val REGULAR_KIND = 0x8000u
private const val DIRECTORY_KIND = 0x4000u
private const val FOREIGN_MODE_BITS = 0x3Fu // group/other rwx bits
