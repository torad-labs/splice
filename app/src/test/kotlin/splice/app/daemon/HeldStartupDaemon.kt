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

/** The two other answers [awaitListenerClosed] can give, said rather than left as silence: the stop never reached the
 *  listener, or it reached it only after the daemon lock was already gone. Each is a different defect, and a reader of
 *  the child's output should not have to tell them apart by what is missing. */
private const val LISTENER_STILL_OPEN = "LISTENER_STILL_OPEN after_ms="
private const val LOCK_ALREADY_RELEASED = "PORT_CLOSED lock_held=false"

// why: the longest this client waits for the stop to reach the listener before reporting what it sees. A real stop
// closes it in well under a second (Ktor's grace is 100ms), so this is a ceiling for a stop that is not coming, not a
// guess at one that is: it sits far below the child's own 57s halt floor so the report always beats the watchdog.
private const val AWAIT_CLOSE_MS = 10_000

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

/** Waits for the stop to reach the control listener, then says whether the listener had stopped accepting and whether
 *  the daemon lock was still held at that moment — ALWAYS, bounded, whatever the stop does to this client.
 *
 *  THE WAIT IS BOUNDED AND NOTHING IT RAISES IS FATAL, because a held connection is not a reliable witness to its own
 *  listener closing. A stop ends it three ways, and each one killed or parked this thread before it could count the
 *  latch down: it resets the connection (the read throws), it closes the listener before this connect lands (the
 *  connect throws), or it closes the listener and leaves this established connection open (the read never returns).
 *  The held startup then never resumed, main's ordered stop waits on startup with no bound of its own by design, and
 *  the teardown watchdog halted the JVM at 57s with the listener never reported closed — measured 2026-10-10, all
 *  three ways in one afternoon, one standalone run in four and whole gradle runs together. So the read is given a
 *  timeout and its failures are swallowed; the PORT and the LOCK are what answer the question.
 *
 *  THE ORDER THIS PINS HOLDS THROUGH THE HANDSHAKE, not through timing: the daemon releases its lock only after
 *  startup resumes, startup resumes only on the countdown below, and the countdown comes after the lock is read. A
 *  listener still accepting when the wait is up is said out loud too, so a stop that truly did not reach it reads as
 *  that, here, instead of as a halt 57s later. */
private fun awaitListenerClosed(port: Int, lockFile: Path, resume: CountDownLatch) = thread(isDaemon = true) {
    try {
        Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = AWAIT_CLOSE_MS
            socket.getInputStream().read()
        }
    } catch (_: IOException) {
        // A refused connect, a reset, or the read's own timeout. None of them is this thread's answer to give.
    }
    when {
        portAccepts(port) -> say("$LISTENER_STILL_OPEN$AWAIT_CLOSE_MS")
        lockHeld(lockFile) -> say(PORT_CLOSED_LOCK_HELD)
        else -> say(LOCK_ALREADY_RELEASED)
    }
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
