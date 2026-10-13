// splice-mcp-front: the one place sessions send MCP calls. It holds the port and every session's stream, checks the
// bearer, and routes calls to holders. The daemon only says where servers run and starts them, so a daemon swap
// never touches a session's connection.
//
//   splice-mcp-front --hub <state>/mcp-hub --control-port <port> [--port <port>]
//
// The listening socket is systemd's when LISTEN_FDS says so (one TCP socket, fd 3), else --port on 127.0.0.1. Files in
// the hub folder: access-key (the bearer sessions present, written by the daemon) and front.json (this front's own
// state: routes, sessions, and the holders it has initialized). The daemon's management key is <state>/mgmt-key.
@file:OptIn(ExperimentalForeignApi::class)

package splice.hub.front

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import platform.posix.O_CREAT
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
import platform.posix.getenv
import platform.posix.getpid
import platform.posix.open
import platform.posix.rename
import platform.posix.signal
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
        val upgrade = Upgrade(sys, files, args)
        val listen = { fd: Int -> HttpListener(sys.nonblocking(fd), sys, loop, hub::handle).also(loop::add) }
        val listener = resume?.let { upgrade.resume(it, listen, hub) } ?: fresh(sys, files, listen) ?: return NO_SOCKET
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

/** The front's two ways out of the loop: an in-place upgrade asked by SIGUSR2, and a stop asked by SIGTERM. Both
 *  let the calls in flight finish first, up to a bound. */
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

    /** One loop turn's check; true once the front may exit. */
    fun done(): Boolean {
        if (upgradeAsked.value == 1 && upgradeBy == 0L) beginUpgrade()
        if (upgradeDue()) finishUpgrade()
        if (stopAsked.value == 1 && stopAt == 0L) beginStop()
        return stopAt != 0L && (hub.inFlight() == 0 || clock.now() > stopAt)
    }

    private fun beginUpgrade() {
        upgradeBy = clock.now() + DRAIN
        upgrade.drain(loop)
        files.say("upgrade asked: ${hub.inFlight()} calls in flight")
    }

    private fun upgradeDue(): Boolean = upgradeBy != 0L && (upgrade.settled(loop) || clock.now() > upgradeBy)

    /** Execs the new code; returns only when the exec failed, and then the running code carries on. */
    private fun finishUpgrade() {
        upgrade.exec(loop, listener, hub, upgrade.ownPath())
        upgradeAsked.value = 0
        upgradeBy = 0L
        hub.stopping = false
        loop.all().filterIsInstance<HttpConn>().forEach {
            it.draining = false
            it.parse()
        }
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
        val call = DaemonCall(sys, path, files.mgmtKey(), body, done, fd)
        if (fd >= 0) loop.add(call)
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

/** The hub folder: the keys the front reads, its own state, the daemon's kept listings, and its log line. */
class HubFiles(private val dir: String) {

    fun accessKey(): String = read("$dir/access-key")?.trim().orEmpty()

    fun mgmtKey(): String = read("${dir.substringBeforeLast('/')}/mgmt-key")?.trim().orEmpty()

    fun execPath(): String = "$dir/front-exec.json"

    fun saveExec(text: String) = write(execPath(), text)

    fun readJson(path: String): JsonObject? =
        read(path)?.let(JsonText::objectOrNull)

    fun remove(path: String) {
        unlink(path)
    }

    fun loadState(): JsonObject? = readJson("$dir/front.json")

    fun saveState(text: String) = write("$dir/front.json", text)

    private fun write(path: String, text: String) {
        val tmp = "$path.tmp"
        val fd = open(tmp, O_WRONLY or O_CREAT or O_TRUNC, OWNER_RW)
        if (fd < 0) return say("could not write $tmp")
        val bytes = text.encodeToByteArray()
        bytes.usePinned { write(fd, it.addressOf(0), bytes.size.convert()) }
        close(fd)
        rename(tmp, path)
    }

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

private const val SYSTEMD_FD = 3
private const val USAGE = 2
private const val NO_SOCKET = 3
private const val DRAIN = 10_000L
private const val OWNER_RW = 0x180 // 0600
