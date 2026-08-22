// NEW (SH-14): persistentLogger rotate pins. The logger previously tracked `written` in memory
// only — one failed Files.move (external logrotate, read-only dir) left it >= the cap forever,
// every later line re-threw before reaching newBufferedWriter, and daemon.log went silent for
// the daemon's lifetime. Lines are drained through AsyncFileIo's single FIFO lane via a latch.
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.app.DaemonProcess
import splice.core.util.AsyncFileIo
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class MainTest {

    private val process = DaemonProcess()

    /** The file lane is one FIFO thread: a latch task submitted after N lines runs after them. */
    private fun drain() {
        val latch = CountDownLatch(1)
        AsyncFileIo.submit { latch.countDown() }
        check(latch.await(5, TimeUnit.SECONDS)) { "file lane did not drain" }
    }

    @Test
    fun `rotates at the cap - one rolled generation`(@TempDir tmp: Path) {
        val log = process.persistentLogger(tmp, maxBytes = 200)
        repeat(10) { log("x".repeat(60)) }
        drain()
        assertTrue(Files.exists(tmp.resolve("daemon.log.1")), "rotation past the cap must roll")
        assertTrue(Files.exists(tmp.resolve("daemon.log")), "a fresh file must be open after the roll")
    }

    @Test
    fun `a failed rotate reconciles and the NEXT line is written - SH-14`(@TempDir tmp: Path) {
        val log = process.persistentLogger(tmp, maxBytes = 100)
        log("A".repeat(120))
        drain() // written is now past the cap
        // External logrotate: the file vanishes, so the pending rotate's Files.move throws.
        Files.deleteIfExists(tmp.resolve("daemon.log"))
        val err = ByteArrayOutputStream()
        val realErr = System.err
        System.setErr(PrintStream(err, true))
        try {
            log("lost-to-the-failing-rotate")
            drain()
            log("recovered")
            drain()
        } finally {
            System.setErr(realErr)
        }
        // Pre-fix: `written` stayed >= cap, every line re-threw in the rotate branch, and
        // daemon.log never received another byte.
        val content = Files.readString(tmp.resolve("daemon.log"))
        assertTrue(content.contains("recovered"), "the logger must self-correct, got: $content")
        assertTrue(
            err.toString().contains("[daemon-log] write/rotate failed"),
            "the wedge must announce itself on stderr, got: $err",
        )
        assertEquals(false, Files.exists(tmp.resolve("daemon.log.1")), "nothing legitimate rolled here")
    }
}
