// NEW: 2026-09-23 — the ONE way a test gets a port it must know before anything binds it.
//
// THE RACE THIS CLOSES. A port leased with `ServerSocket(0).use { it.localPort }` comes from the
// kernel's EPHEMERAL range (net.ipv4.ip_local_port_range, 32768-60999 on Linux), and it is free only
// until the lease closes. Every other bind(0) on the machine — another test's mock upstream, a
// parallel Gradle worker — and every outbound connection's source port are drawn from that same range,
// so the number can be taken before the test's server binds it. CI run 35881955038 failed
// HeadServerLoadTest in @BeforeAll that way (BindException), and the full-daemon boots in :app held
// the same window for every port they rendered into a topology.
//
// A port BELOW the ephemeral range is never handed out by the kernel: no bind(0) and no outbound
// connection can land on it. The only thing that can take it is a process binding that exact number,
// which among tests means another reservation, and those are serialised by a lock file per port in a
// directory every JVM on this machine shares. A reservation is held until the JVM exits, so no two
// tests anywhere on the machine are ever handed the same port, and a port reserved to stay DEAD (a
// connection-refused probe) stays dead.
//
// A server a test CONSTRUCTS still binds port 0 and reads back what it bound (kt-tests-bind-port-zero).
// This is for what cannot: a daemon that renders its ports into launch specs before it binds, a login
// flow that renders redirect_uri before it listens, and a probe that needs a port nothing listens on.
package splice.core.testing

import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.random.Random

/** Ports reserved for tests, below the kernel's ephemeral range and locked across every JVM. */
object TestPorts {

    /** Below this sit the well-known and common development ports — splice's own daemon among them
     *  (3096-3110), 5432, 6379, 8080 — which a test must never collide with. */
    private const val FLOOR = 20_000

    /** Where the ephemeral range starts when the kernel does not say: Linux's default, and below
     *  macOS's and Windows' 49152. */
    private const val DEFAULT_EPHEMERAL_START = 32_768

    /** The smallest reservable range worth having: below it, a machine whose ephemeral range was
     *  widened down to FLOOR has no safe ports, and pretending otherwise reopens the race. */
    private const val MIN_RANGE = 1_000

    private const val ATTEMPTS = 200

    private val EPHEMERAL_RANGE_FILE = File("/proc/sys/net/ipv4/ip_local_port_range")

    private val lockDir: Path = Path.of(System.getProperty("java.io.tmpdir"), "splice-test-ports")

    /** Open channels whose locks are this JVM's reservations. Never closed: a lock lives exactly as
     *  long as its channel, and the reservation must outlive the test that took it. */
    private val held = HashMap<Int, FileChannel>()

    /** A port no test on this machine holds, that nothing was bound to when it was reserved, and that
     *  neither the kernel's bind(0) nor an outbound connection can be given. */
    @Synchronized
    fun reserve(): Int {
        val ceiling = ephemeralStart()
        check(ceiling - FLOOR >= MIN_RANGE) {
            "no test ports below the ephemeral range: it starts at $ceiling " +
                "(net.ipv4.ip_local_port_range), which leaves fewer than $MIN_RANGE ports above $FLOOR"
        }
        return reserveFrom(generateSequence { Random.nextInt(FLOOR, ceiling) }.take(ATTEMPTS).iterator())
    }

    /** The first of [candidates] that this JVM does not hold, no other JVM holds, and that binds.
     *  Split from [reserve] so a test can name the candidates instead of drawing them. */
    @Synchronized
    fun reserveFrom(candidates: Iterator<Int>): Int {
        for (port in candidates) {
            if (port !in held && lock(port) && bindable(port)) return port
        }
        error("no free test port among the candidates")
    }

    /** Take [port]'s lock file, or report that another JVM holds it. A port that is locked here but
     *  found bound stays locked: it is in use, and holding it keeps the next draw from re-probing it. */
    private fun lock(port: Int): Boolean {
        val file = lockFile(port)
        Files.createDirectories(file.parent)
        val channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE)
        if (channel.tryLock() == null) {
            channel.close()
            return false
        }
        held[port] = channel
        return true
    }

    /** The file whose lock is [port]'s reservation, shared by every JVM on this machine. */
    fun lockFile(port: Int): Path = lockDir.resolve("$port.lock")

    /** Whether [port] binds on loopback, where every splice server listens. A plain bind with no
     *  connection leaves nothing in TIME_WAIT, so the port is free again the moment this returns. */
    private fun bindable(port: Int): Boolean = runCatching {
        ServerSocket().use { it.bind(InetSocketAddress(InetAddress.getLoopbackAddress(), port)) }
    }.isSuccess

    private fun ephemeralStart(): Int = runCatching {
        EPHEMERAL_RANGE_FILE.readText().trim().split(Regex("\\s+")).first().toInt()
    }.getOrDefault(DEFAULT_EPHEMERAL_START)
}
