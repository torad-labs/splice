// The front's I/O: one poll loop over nonblocking descriptors, with deadlines. Everything the front does is a reaction
// to one of these becoming ready or a deadline passing, so it needs no threads.
@file:OptIn(ExperimentalForeignApi::class)

package splice.hub.front

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CArrayPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.UIntVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.posix.AF_INET
import platform.posix.AF_UNIX
import platform.posix.CLOCK_MONOTONIC
import platform.posix.CLOCK_REALTIME
import platform.posix.EAGAIN
import platform.posix.EINPROGRESS
import platform.posix.EINTR
import platform.posix.FD_CLOEXEC
import platform.posix.F_GETFL
import platform.posix.F_SETFD
import platform.posix.F_SETFL
import platform.posix.O_NONBLOCK
import platform.posix.POLLERR
import platform.posix.POLLHUP
import platform.posix.POLLIN
import platform.posix.POLLNVAL
import platform.posix.POLLOUT
import platform.posix.SOCK_STREAM
import platform.posix.SOL_SOCKET
import platform.posix.SO_ERROR
import platform.posix.SO_REUSEADDR
import platform.posix.accept
import platform.posix.bind
import platform.posix.clock_gettime
import platform.posix.close
import platform.posix.connect
import platform.posix.errno
import platform.posix.fcntl
import platform.posix.getsockopt
import platform.posix.listen
import platform.posix.poll
import platform.posix.pollfd
import platform.posix.read
import platform.posix.setsockopt
import platform.posix.socket
import platform.posix.timespec
import platform.posix.write
import splice.hub.io.Bytes

/** Milliseconds on the monotonic clock. */
class Clock {
    fun now(): Long = memScoped {
        val ts = alloc<timespec>()
        clock_gettime(CLOCK_MONOTONIC, ts.ptr)
        ts.tv_sec * MILLIS + ts.tv_nsec / NANOS_PER_MILLI
    }

    /** Milliseconds since the epoch, for times another process reads. */
    fun epoch(): Long = memScoped {
        val ts = alloc<timespec>()
        clock_gettime(CLOCK_REALTIME, ts.ptr)
        ts.tv_sec * MILLIS + ts.tv_nsec / NANOS_PER_MILLI
    }
}

/** One descriptor the loop watches. [events] is asked every turn; [ready] gets the revents. */
abstract class Watched(val fd: Int) {
    var closed = false
        private set

    abstract fun events(): Int

    abstract fun ready(revents: Int)

    /** The monotonic time this wants [due] called at, or null. */
    open fun deadline(): Long? = null

    open fun due() = Unit

    open fun onClose() = Unit

    fun close() {
        if (closed) return
        closed = true
        close(fd)
        onClose()
    }
}

/** The loop: every watched descriptor, polled until [stop] says so. */
class Loop(private val clock: Clock) {
    private val watched = ArrayList<Watched>()

    fun add(w: Watched) {
        watched += w
    }

    fun all(): List<Watched> = watched.filter { !it.closed }

    fun run(stop: () -> Boolean) {
        while (!stop()) {
            watched.removeAll { it.closed }
            val now = clock.now()
            watched.filter { (it.deadline() ?: Long.MAX_VALUE) <= now }.forEach { if (!it.closed) it.due() }
            watched.removeAll { it.closed }
            val nearest = watched.mapNotNull { it.deadline() }.minOrNull()
            val wait = nearest?.let { (it - clock.now()).coerceIn(0, MAX_WAIT) } ?: MAX_WAIT
            turn(watched.toList(), wait)
        }
    }

    /** One poll over [turn], waiting at most [wait] ms, then each ready descriptor told what it may do. */
    private fun turn(turn: List<Watched>, wait: Long) = memScoped {
        val fds = allocArray<pollfd>(turn.size)
        turn.forEachIndexed { i, w ->
            fds[i].fd = w.fd
            fds[i].events = w.events().toShort()
        }
        if (poll(fds, turn.size.convert(), wait.toInt()) > 0) {
            turn.forEachIndexed { i, w ->
                val revents = fds[i].revents.toInt()
                if (revents != 0 && !w.closed) w.ready(revents)
            }
        }
    }
}

/** Raw descriptor calls, each answering in the loop's terms. */
class Sys {
    /** Lets [fd] survive an exec, for the front's in-place upgrade. */
    fun inherit(fd: Int) {
        fcntl(fd, F_SETFD, 0)
    }

    fun nonblocking(fd: Int): Int = fd.also {
        fcntl(it, F_SETFL, fcntl(it, F_GETFL) or O_NONBLOCK)
        fcntl(it, F_SETFD, FD_CLOEXEC)
    }

    /** Bytes read into [into], 0 at end of file or on an error, -1 when nothing is ready now. */
    fun readInto(fd: Int, into: Bytes): Int {
        val chunk = ByteArray(CHUNK)
        while (true) {
            val n = chunk.usePinned { read(fd, it.addressOf(0), CHUNK.convert()) }.toInt()
            if (n > 0) into.append(chunk, n)
            if (n >= 0) return n
            if (errno == EINTR) continue
            return if (errno == EAGAIN) -1 else 0
        }
    }

    /** Writes what it can of [from], dropping what was written. False when the peer is gone. */
    fun flush(fd: Int, from: Bytes): Boolean {
        while (from.size > 0) {
            val n = from.data.usePinned { write(fd, it.addressOf(0), from.size.convert()) }.toInt()
            // a write that stopped: EAGAIN waits for the next turn, anything but EINTR means the peer is gone
            if (n > 0) {
                from.drop(n)
            } else if (n == 0 || errno != EINTR) {
                return n < 0 && errno == EAGAIN
            }
        }
        return true
    }

    fun gone(revents: Int): Boolean = revents and (POLLERR or POLLHUP or POLLNVAL) != 0 && revents and POLLIN == 0

    /** A listening TCP socket on 127.0.0.1:[port]. */
    fun listenTcp(port: Int): Int = memScoped {
        val fd = socket(AF_INET, SOCK_STREAM, 0)
        val one = alloc<IntVar>()
        one.value = 1
        setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, one.ptr, IntSize.convert())
        val addr = loopback(allocArray(SOCKADDR_IN), port)
        val bound = bind(fd, addr.reinterpret(), SOCKADDR_IN.convert()) == 0 && listen(fd, BACKLOG) == 0
        if (bound) nonblocking(fd) else -1
    }

    fun accept(listener: Int): Int = accept(listener, null, null).let { if (it < 0) it else nonblocking(it) }

    /** Gives back a descriptor that was accepted and is not wanted. */
    fun release(fd: Int) {
        close(fd)
    }

    /** A nonblocking connect to 127.0.0.1:[port]; the loop learns the outcome when it is writable. */
    fun connectTcp(port: Int): Int = memScoped {
        val fd = nonblocking(socket(AF_INET, SOCK_STREAM, 0))
        val addr = loopback(allocArray(SOCKADDR_IN), port)
        val r = connect(fd, addr.reinterpret(), SOCKADDR_IN.convert())
        if (r == 0 || errno == EINPROGRESS) fd else -1.also { close(fd) }
    }

    /** A connect to the Unix socket at [path]; -1 when nothing listens there. Local, so it is done at once. */
    fun connectUnix(path: String): Int = memScoped {
        val fd = socket(AF_UNIX, SOCK_STREAM, 0)
        val addr = allocArray<ByteVar>(FAMILY + SUN_PATH)
        addr[0] = AF_UNIX.toByte()
        addr[1] = 0
        val bytes = path.encodeToByteArray()
        if (bytes.size >= SUN_PATH) return@memScoped -1.also { close(fd) }
        bytes.forEachIndexed { i, b -> addr[FAMILY + i] = b }
        addr[FAMILY + bytes.size] = 0
        val connected = connect(fd, addr.reinterpret(), (FAMILY + SUN_PATH).convert()) == 0
        if (connected) nonblocking(fd) else -1.also { close(fd) }
    }

    /** sockaddr_in for 127.0.0.1:[port], by hand: family, port in network order, then the address. */
    private fun loopback(addr: CArrayPointer<ByteVar>, port: Int): CArrayPointer<ByteVar> {
        for (i in 0 until SOCKADDR_IN) addr[i] = 0
        addr[0] = AF_INET.toByte()
        addr[AT_PORT] = (port shr BYTE).toByte()
        addr[AT_PORT + 1] = port.toByte()
        addr[AT_ADDR] = LOOPBACK_FIRST
        addr[AT_ADDR + LOOPBACK_LAST] = 1
        return addr
    }

    fun connectFailed(fd: Int): Boolean = memScoped {
        val err = alloc<IntVar>()
        val len = alloc<UIntVar>()
        len.value = IntSize.toUInt()
        getsockopt(fd, SOL_SOCKET, SO_ERROR, err.ptr, len.ptr) != 0 || err.value != 0
    }
}

const val IN = POLLIN
const val OUT = POLLOUT
private const val CHUNK = 64 * 1024
private const val MILLIS = 1000L
private const val NANOS_PER_MILLI = 1_000_000L
private const val MAX_WAIT = 1000L
private const val SOCKADDR_IN = 16
private const val SUN_PATH = 108
private const val FAMILY = 2
private const val BYTE = 8
private const val BACKLOG = 128
private const val IntSize = 4
private const val LOOPBACK_FIRST: Byte = 127
private const val LOOPBACK_LAST = 3
private const val AT_PORT = 2
private const val AT_ADDR = 4
