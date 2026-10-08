// The child JVM of DaemonStartupExitTest: the real daemon, the real shutdown hook and the real ordered stop, with
// startup held at a chosen log line and the JVM exiting from another thread while it is held. That is what a
// System.exit during a boot looks like to the process. Startup resumes only once the control port has closed, so the
// stop has to reach the listener while startup is still held, and the lock has to still be held at that moment.
package splice.app.daemon

import kotlinx.coroutines.CompletableDeferred
import splice.app.Daemon
import splice.app.DaemonProcess
import splice.app.DaemonRun
import splice.core.config.StatePaths
import splice.core.util.LogSink
import splice.topology.TopologyLoader
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

const val STARTUP_HELD = "STARTUP_HELD"
const val PORT_CLOSED_LOCK_HELD = "PORT_CLOSED lock_held=true"
const val UP_PORT_LIVE = "UP_PORT_LIVE="
const val HOLD_BOUND = "[daemon] control plane bound"
const val HOLD_UP = "[daemon] up:"

private const val CONNECT_TIMEOUT_MS = 500

private fun portAccepts(port: Int): Boolean = try {
    Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), CONNECT_TIMEOUT_MS) }
    true
} catch (_: IOException) {
    false
}

/** This JVM's own hold on [lockFile]: a second lock request overlaps it exactly while the daemon lock is held. */
private fun lockHeld(lockFile: Path): Boolean = FileChannel.open(lockFile, StandardOpenOption.WRITE).use { channel ->
    try {
        channel.tryLock()?.release()
        false
    } catch (_: OverlappingFileLockException) {
        true
    }
}

private fun say(line: String) {
    println(line)
    System.out.flush()
}

/** Blocks until the listener on [port] closes the connection a client holds open, which only a stop does. */
private fun awaitListenerClosed(port: Int, lockFile: Path, resume: CountDownLatch) = thread(isDaemon = true) {
    Socket("127.0.0.1", port).use { it.getInputStream().read() }
    if (!portAccepts(port) && lockHeld(lockFile)) say(PORT_CLOSED_LOCK_HELD)
    resume.countDown()
}

suspend fun main(args: Array<String>) {
    val state = Path.of(args[0])
    val controlPort = args[1].toInt()
    val holdAt = args[2]
    val topology = TopologyLoader.parse("[daemon]\ncontrol_port = $controlPort\n")
    val paths = StatePaths(baseOverride = state)
    val resume = CountDownLatch(1)
    val held = AtomicBoolean(false)
    val log = LogSink { line ->
        when {
            line.startsWith(holdAt) && held.compareAndSet(false, true) -> {
                say(STARTUP_HELD)
                awaitListenerClosed(controlPort, paths.daemonLockFile, resume)
                Thread { System.exit(0) }.start()
                resume.await()
            }
            line.startsWith(HOLD_UP) -> say(UP_PORT_LIVE + portAccepts(controlPort))
        }
    }
    val daemon = Daemon(topology = topology, statePaths = paths, log = log)
    val lock = DaemonLock(paths.daemonLockFile)
    check(lock.tryAcquire()) { "the child could not take its own lock" }
    DaemonRun(DaemonProcess()).serve(daemon, lock, CompletableDeferred())
}
