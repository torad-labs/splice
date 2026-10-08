// The child JVM of DaemonStartupExitTest: the real daemon, the real shutdown hook and the real ordered stop, with
// startup held in its "up" log line after the control port has bound. It exits the JVM from another thread while
// startup is held, which is what System.exit during a boot looks like to the process.
package splice.app.daemon

import kotlinx.coroutines.CompletableDeferred
import splice.app.Daemon
import splice.app.DaemonProcess
import splice.core.config.StatePaths
import splice.core.util.LogSink
import splice.topology.TopologyLoader
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread

const val STARTUP_HELD = "STARTUP_HELD"

suspend fun main(args: Array<String>) {
    val state = Path.of(args[0])
    val controlPort = args[1].toInt()
    val topology = TopologyLoader.parse("[daemon]\ncontrol_port = $controlPort\n")
    val log = LogSink { line ->
        if (line.startsWith("[daemon] up:")) {
            println(STARTUP_HELD)
            System.out.flush()
            val exiting = thread { System.exit(0) }
            // Held here, as a startup stuck in a call that cannot be cancelled would be; the exit runs beside it.
            exiting.join()
            CountDownLatch(1).await()
        }
    }
    val paths = StatePaths(baseOverride = state)
    val daemon = Daemon(topology = topology, statePaths = paths, log = log)
    val lock = DaemonLock(paths.daemonLockFile)
    check(lock.tryAcquire()) { "the child could not take its own lock" }
    DaemonProcess().serve(daemon, lock, CompletableDeferred())
}
