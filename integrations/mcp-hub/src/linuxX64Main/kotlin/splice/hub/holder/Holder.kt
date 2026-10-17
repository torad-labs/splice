// splice-mcp-holder: joins one stdio MCP server's stdin and stdout to a Unix socket, and outlives whoever started it.
// It carries no splice logic: it forwards whole lines each way, keeps the server's output while no one is
// connected, and lets a new connection replace the old one, so a restarted daemon finds the same server in the
// same state.
@file:OptIn(ExperimentalForeignApi::class)

package splice.hub.holder

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CArrayPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.cstr
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.posix.AF_UNIX
import platform.posix.EAGAIN
import platform.posix.EINTR
import platform.posix.FD_CLOEXEC
import platform.posix.F_GETFL
import platform.posix.F_SETFD
import platform.posix.F_SETFL
import platform.posix.O_NONBLOCK
import platform.posix.POLLERR
import platform.posix.POLLHUP
import platform.posix.POLLIN
import platform.posix.POLLOUT
import platform.posix.SIGHUP
import platform.posix.SIGPIPE
import platform.posix.SIGTERM
import platform.posix.SIG_IGN
import platform.posix.SOCK_STREAM
import platform.posix._exit
import platform.posix.accept
import platform.posix.bind
import platform.posix.close
import platform.posix.dup2
import platform.posix.errno
import platform.posix.execvp
import platform.posix.exit
import platform.posix.fcntl
import platform.posix.fork
import platform.posix.kill
import platform.posix.listen
import platform.posix.pipe
import platform.posix.poll
import platform.posix.pollfd
import platform.posix.read
import platform.posix.setsid
import platform.posix.signal
import platform.posix.socket
import platform.posix.umask
import platform.posix.unlink
import platform.posix.waitpid
import platform.posix.write
import splice.hub.io.Bytes
import kotlin.concurrent.AtomicInt

fun main(args: Array<String>) {
    if (args.size < 2) {
        Say("usage: splice-mcp-holder <socket> <command> [args...]")
        exit(USAGE)
    }
    exit(Holder(args[0], args.drop(1)).run())
}

/** One line on stderr; the holder has nothing else to say. */
class Say(text: String) {
    init {
        val bytes = "splice-mcp-holder: $text\n".encodeToByteArray()
        bytes.usePinned { write(2, it.addressOf(0), bytes.size.convert()) }
    }
}

/** The file descriptors the holder reads and writes, nonblocking where a write could stall it. */
class Fd(val fd: Int) {
    fun nonblocking(): Fd = apply { fcntl(fd, F_SETFL, fcntl(fd, F_GETFL) or O_NONBLOCK) }

    fun cloexec(): Fd = apply { fcntl(fd, F_SETFD, FD_CLOEXEC) }

    /** Bytes read, 0 at end of file, -1 when nothing is ready now. */
    fun readInto(buf: ByteArray): Int {
        while (true) {
            val n = buf.usePinned { read(fd, it.addressOf(0), buf.size.convert()) }.toInt()
            if (n >= 0) return n
            if (errno == EINTR) continue
            return if (errno == EAGAIN) -1 else 0
        }
    }

    /** Bytes written from the front of [buf], 0 when the peer cannot take any now, -1 when it is gone. */
    fun writeFrom(buf: Bytes, limit: Int = buf.size): Int {
        if (limit == 0) return 0
        while (true) {
            val n = buf.data.usePinned { write(fd, it.addressOf(0), limit.convert()) }.toInt()
            if (n >= 0) return n
            if (errno == EINTR) continue
            return if (errno == EAGAIN) 0 else -1
        }
    }
}

/** The server: forked with its stdin and stdout on pipes, its stderr and environment the holder's own. */
class Server(command: List<String>) {
    val pid: Int
    val stdin: Fd
    val stdout: Fd

    init {
        val (inRead, inWrite) = pipes()
        val (outRead, outWrite) = pipes()
        pid = memScoped {
            val argv = allocArray<CPointerVar<ByteVar>>(command.size + 1)
            command.forEachIndexed { i, word -> argv[i] = word.cstr.ptr }
            argv[command.size] = null
            val child = fork()
            if (child == 0) {
                dup2(inRead, 0)
                dup2(outWrite, 1)
                execvp(command[0], argv)
                _exit(NOT_STARTED)
            }
            child
        }
        close(inRead)
        close(outWrite)
        if (pid < 0) {
            Say("could not start ${command[0]}")
            exit(NOT_STARTED)
        }
        stdin = Fd(inWrite).cloexec().nonblocking()
        stdout = Fd(outRead).cloexec()
    }

    /** The server's exit code, once it has exited. */
    fun exitCode(): Int = memScoped {
        val status = alloc<IntVar>()
        waitpid(pid, status.ptr, 0)
        val raw = status.value
        if (raw and LOW_SEVEN == 0) (raw shr EIGHT) and LOW_BYTE else SIGNALLED + (raw and LOW_SEVEN)
    }

    private fun pipes(): Pair<Int, Int> = memScoped {
        val ends = allocArray<IntVar>(2)
        if (pipe(ends) != 0) {
            Say("could not open a pipe")
            exit(NOT_STARTED)
        }
        // close-on-exec before the fork, so the server holds only its own ends: it sees end of file on stdin the moment
        // the holder is gone (dup2 clears the flag on the server's 0 and 1)
        Fd(ends[0]).cloexec()
        Fd(ends[1]).cloexec()
        ends[0] to ends[1]
    }
}

/** The socket the daemon connects to: owner-only, replaced at bind, removed at exit. */
class Listener(private val path: String) {
    val fd: Fd

    init {
        umask(OWNER_ONLY.convert())
        unlink(path)
        val sock = socket(AF_UNIX, SOCK_STREAM, 0)
        val bound = sock >= 0 && memScoped {
            // sockaddr_un by hand, since no platform library carries sys/un.h: a two-byte family, then the path.
            val addr = allocArray<ByteVar>(FAMILY + SUN_PATH)
            addr[0] = AF_UNIX.toByte()
            addr[1] = 0
            val bytes = path.encodeToByteArray()
            if (bytes.size >= SUN_PATH) return@memScoped false
            bytes.forEachIndexed { i, b -> addr[FAMILY + i] = b }
            addr[FAMILY + bytes.size] = 0
            bind(sock, addr.reinterpret(), (FAMILY + SUN_PATH).convert()) == 0
        }
        if (!bound || listen(sock, BACKLOG) != 0) {
            Say("could not listen on $path")
            exit(NOT_LISTENING)
        }
        fd = Fd(sock).cloexec()
    }

    fun accept(): Fd? = accept(fd.fd, null, null).takeIf { it >= 0 }?.let { Fd(it).cloexec().nonblocking() }

    fun remove() {
        unlink(path)
    }
}

/** The pump. One client at a time; a new one replaces the old. Client bytes reach the server only as whole lines,
 *  so a daemon that dies mid-request leaves no fragment in front of the next one; server bytes leave the buffer only
 *  as whole lines, so a line cut by a disconnect is sent again whole to the next client. */
class Holder(private val socketPath: String, private val command: List<String>) {
    private val fromClient = Bytes()
    private val toServer = Bytes()
    private val toClient = Bytes()
    private val scratch = ByteArray(CHUNK)
    private var sent = 0
    private var client: Fd? = null
    private var waiting: Fd? = null
    private var serverDone = false

    fun run(): Int {
        signal(SIGPIPE, SIG_IGN)
        signal(SIGHUP, SIG_IGN)
        setsid()
        val server = Server(command)
        serverPid.value = server.pid
        // a stop for the holder is a stop for its server, passed on before the holder goes
        signal(SIGTERM, staticCFunction { _: Int -> kill(serverPid.value, SIGTERM); _exit(STOPPED) })
        val listener = Listener(socketPath)
        while (!serverDone || flushing()) {
            if (!turn(server, listener)) break
        }
        listener.remove()
        client?.let { close(it.fd) }
        close(server.stdin.fd)
        return server.exitCode()
    }

    /** Unsent server output still owed to a connected client, which keeps the holder up after the server ends. */
    private fun flushing(): Boolean = client != null && toClient.size > 0

    private fun turn(server: Server, listener: Listener): Boolean = memScoped {
        val fds = allocArray<pollfd>(POLLED)
        val current = client
        arm(fds, server, listener)
        if (poll(fds, POLLED.convert(), -1) < 0) return@memScoped errno == EINTR
        val ready = { i: Int -> fds[i].revents.toInt() and (POLLIN or POLLOUT or POLLHUP or POLLERR) != 0 }
        if (ready(AT_LISTENER)) listener.accept()?.let(::hold)
        waiting?.takeIf { ready(AT_WAITING) }?.let(::claim)
        if (ready(AT_SERVER_OUT)) fromServer(server)
        current?.takeIf { it === client && ready(AT_CLIENT) }?.let(::serveClient)
        if (ready(AT_SERVER_IN)) feedServer(server)
        true
    }

    /** What each descriptor is polled for this turn; a full buffer stops reading the side that fills it. */
    private fun arm(fds: CArrayPointer<pollfd>, server: Server, listener: Listener) {
        watch(fds[AT_LISTENER], listener.fd.fd, POLLIN)
        watch(fds[AT_SERVER_OUT], if (serverDone || toClient.size > CAP) -1 else server.stdout.fd, POLLIN)
        val wantsIn = if (toServer.size > CAP) 0 else POLLIN
        val wantsOut = if (toClient.size > sent) POLLOUT else 0
        watch(fds[AT_CLIENT], client?.fd ?: -1, wantsIn or wantsOut)
        watch(fds[AT_SERVER_IN], if (toServer.size > 0) server.stdin.fd else -1, POLLOUT)
        watch(fds[AT_WAITING], waiting?.fd ?: -1, POLLIN)
    }

    private fun watch(slot: pollfd, fd: Int, events: Int) {
        slot.fd = fd
        slot.events = events.convert()
    }

    /** A new connection waits until it sends something: a check that connects and closes takes nothing over. Only the
     *  newest waits; an older one still silent is closed. */
    private fun hold(next: Fd) {
        waiting?.let { close(it.fd) }
        waiting = next
    }

    private fun claim(next: Fd) {
        waiting = null
        val n = next.readInto(scratch)
        if (n <= 0) {
            if (n == 0) close(next.fd) else waiting = next
            return
        }
        adopt(next)
        fromClient.append(scratch, n)
        val whole = fromClient.wholeLines()
        toServer.append(fromClient.data, whole)
        fromClient.drop(whole)
    }

    private fun adopt(next: Fd) {
        client?.let { close(it.fd) }
        client = next
        fromClient.clear()
        sent = 0
    }

    private fun fromServer(server: Server) {
        val n = server.stdout.readInto(scratch)
        if (n > 0) {
            toClient.append(scratch, n)
        } else if (n == 0) {
            serverDone = true
        }
    }

    private fun serveClient(current: Fd) {
        if (toClient.size > sent) {
            val n = current.writeFrom(toClient.tail(sent))
            if (n < 0) return drop(current)
            sent += n
            val whole = toClient.wholeLines(sent)
            toClient.drop(whole)
            sent -= whole
        }
        val n = current.readInto(scratch)
        if (n == 0) return drop(current)
        if (n > 0) {
            fromClient.append(scratch, n)
            val whole = fromClient.wholeLines()
            toServer.append(fromClient.data, whole)
            fromClient.drop(whole)
        }
    }

    private fun feedServer(server: Server) {
        val n = server.stdin.writeFrom(toServer)
        if (n > 0) {
            toServer.drop(n)
        } else if (n < 0) {
            serverDone = true
        }
    }

    private fun drop(current: Fd) {
        close(current.fd)
        client = null
        fromClient.clear()
        sent = 0
    }
}

/** The server's pid, for the SIGTERM handler, which can reach nothing else. */
val serverPid = AtomicInt(0)

private const val STOPPED = 143
private const val CHUNK = 64 * 1024
private const val CAP = 64 * 1024 * 1024
private const val POLLED = 5
private const val AT_LISTENER = 0
private const val AT_SERVER_OUT = 1
private const val AT_CLIENT = 2
private const val AT_SERVER_IN = 3
private const val AT_WAITING = 4
private const val BACKLOG = 4
private const val SUN_PATH = 108
private const val FAMILY = 2
private const val OWNER_ONLY = 0x3F // 077
private const val USAGE = 2
private const val NOT_STARTED = 127
private const val NOT_LISTENING = 126
private const val SIGNALLED = 128
private const val LOW_SEVEN = 0x7f
private const val LOW_BYTE = 0xff
private const val EIGHT = 8
