// NEW (JW-05): the doctor runtime section. Every other section reads configuration and
// presence; this one reads what HAPPENED — the G20 health counters from /api/heads and the
// per-head perf JSONL outcome tail — so a fully-configured install with dying turns can no
// longer print "Everything checks out."
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.app.cli.DoctorCommand
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

class DoctorRuntimeSectionTest {

    private fun runDoctor(env: Map<String, String?>): Pair<Boolean, String> {
        val buf = ByteArrayOutputStream()
        val original = System.out
        System.setOut(PrintStream(buf, true))
        return try {
            DoctorCommand().doctor { name -> env[name] } to buf.toString()
        } finally {
            System.setOut(original)
        }
    }

    private fun baseEnv(tmp: Path, port: Int): Map<String, String?> {
        val state = Files.createDirectories(tmp.resolve("state"))
        Files.writeString(state.resolve("mgmt-key"), "test-key\n")
        return mapOf(
            "XDG_CONFIG_HOME" to tmp.resolve("config").toString(),
            "SPLICE_BIN_DIR" to tmp.resolve("bin").toString(),
            "SPLICE_SHARE_DIR" to tmp.resolve("share").toString(),
            "PATH" to tmp.resolve("bin").toString(),
            "CLAUDEX_STATE_DIR" to state.toString(),
            "SPLICE_CONTROL_PORT" to port.toString(),
        )
    }

    // DR-41a: an EXISTING-but-unreadable mgmt key reported as "missing"/"minted on first launch",
    // sending the operator to re-mint a key sitting there behind a permission error. No daemon is
    // needed: with the daemon stopped the old code said INFO minted-on-first-launch; unreadable
    // must out-rank that guess.
    @Test
    fun `an unreadable mgmt key is reported unreadable, not missing`(@TempDir tmp: Path) {
        val env = baseEnv(tmp, port = 1) // nothing listens on port 1: daemon not running
        val keyFile = tmp.resolve("state").resolve("mgmt-key")
        Files.setPosixFilePermissions(keyFile, PosixFilePermissions.fromString("-wx------"))
        try {
            val (_, out) = runDoctor(env)
            assertTrue(out.contains("unreadable at"), out)
            assertTrue(!out.contains("minted on first launch"), out)
        } finally {
            Files.setPosixFilePermissions(keyFile, PosixFilePermissions.fromString("rw-------"))
        }
    }

    @Test
    fun `an inaccessible mgmt key parent is unreadable, not missing`(@TempDir tmp: Path) {
        val env = baseEnv(tmp, port = 1)
        val stateDir = tmp.resolve("state")
        val original = Files.getPosixFilePermissions(stateDir)
        Files.setPosixFilePermissions(stateDir, PosixFilePermissions.fromString("---------"))
        try {
            val (_, out) = runDoctor(env)
            assertTrue(out.contains("unreadable at"), out)
            assertTrue(!out.contains("minted on first launch"), out)
        } finally {
            Files.setPosixFilePermissions(stateDir, original)
        }
    }

    @Test
    fun `non-zero provider errors and a failing perf tail are WARN rows with fixes - JW-05`(@TempDir tmp: Path) {
        val server = com.sun.net.httpserver.HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val version = splice.core.GATEWAY_VERSION
        server.createContext("/health") { ex ->
            val b = """{"ok":true,"version":"$version","heads":1,"readyHeads":1,"failedHeads":0}""".toByteArray()
            ex.sendResponseHeaders(200, b.size.toLong())
            ex.responseBody.use { it.write(b) }
        }
        server.createContext("/api/heads") { ex ->
            val headsJson = """{"heads":[{"key":"codex","health":{"localOriginErrors":1,"providerErrors":7}}]}"""
            val b = headsJson.toByteArray()
            ex.sendResponseHeaders(200, b.size.toLong())
            ex.responseBody.use { it.write(b) }
        }
        server.start()
        try {
            val env = baseEnv(tmp, server.address.port)
            // perf tail: three clean turns, then an upstream failure ~4 minutes ago
            val perf = tmp.resolve("state").resolve("codex-perf.jsonl")
            val now = System.currentTimeMillis()
            val perfLines = listOf(
                """{"ts":${now - 600_000},"outcome":"ok"}""",
                """{"ts":${now - 500_000},"outcome":"ok"}""",
                """{"ts":${now - 400_000},"outcome":"ok"}""",
                """{"ts":${now - 240_000},"outcome":"error:conn-reset"}""",
            )
            Files.writeString(perf, perfLines.joinToString("\n") + "\n")
            val (_, out) = runDoctor(env)
            assertTrue(out.contains("7 provider / 1 local error(s) since last restart"), out)
            assertTrue(out.contains("splice logs --head codex --tail 50"), out)
            assertTrue(out.contains("1 of last 4 turn(s) failed"), out)
            assertTrue(out.contains("error:conn-reset"), out)
            assertTrue(!out.contains("Everything checks out"), out)
        } finally {
            server.stop(0)
        }
    }

    // DR-174: the runtime section held its own private mgmt-key reader that collapsed absence and
    // denied access, and then rendered BOTH as "skipped (mgmt-key unreadable)". So a live daemon on
    // a box that has simply never minted a key told the operator the key could not be READ — the
    // mirror of the restart defect, pointing at permissions on a file that is not there. The state
    // dir is deliberately readable here: the ONLY thing wrong is that the key does not exist yet.
    @Test
    fun `a live daemon with no key minted says so, not unreadable - DR-174`(@TempDir tmp: Path) {
        val server = com.sun.net.httpserver.HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val version = splice.core.GATEWAY_VERSION
        server.createContext("/health") { ex ->
            val b = """{"ok":true,"version":"$version","heads":0,"readyHeads":0,"failedHeads":0}""".toByteArray()
            ex.sendResponseHeaders(200, b.size.toLong())
            ex.responseBody.use { it.write(b) }
        }
        server.start()
        try {
            val env = baseEnv(tmp, server.address.port)
            Files.delete(tmp.resolve("state").resolve("mgmt-key"))
            val (_, out) = runDoctor(env)
            assertTrue(out.contains("not minted yet"), "an unminted key must be named as such:\n$out")
            assertTrue(
                !out.contains("mgmt-key unreadable"),
                "a key that was never written is not a key that cannot be read:\n$out",
            )
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `runtime section is INFO-skipped when the daemon is stopped - JW-05`(@TempDir tmp: Path) {
        val freePort = ServerSocket(0).use { it.localPort }
        val (_, out) = runDoctor(baseEnv(tmp, freePort))
        assertTrue(out.contains("skipped (daemon stopped)"), out)
    }

    // DR-173 (grok-splice source sweep): a LIVE daemon with zero heads crashed `splice doctor`
    // outright. DoctorRuntime legitimately returns an empty list there — daemon up, key readable,
    // /api/heads answering with an empty array, and DaemonLock.headsRuntime reserves null for a
    // FAILED request — and DoctorCommand.renderSection called maxOf on it, which throws
    // NoSuchElementException. The render loop runs OUTSIDE guarded(), so nothing caught it: an
    // install whose only sin was having no heads yet got a stack trace instead of a report.
    @Test
    fun `a live daemon with zero heads still prints a report - DR-173`(@TempDir tmp: Path) {
        val server = com.sun.net.httpserver.HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val version = splice.core.GATEWAY_VERSION
        server.createContext("/health") { ex ->
            val b = """{"ok":true,"version":"$version","heads":0,"readyHeads":0,"failedHeads":0}""".toByteArray()
            ex.sendResponseHeaders(200, b.size.toLong())
            ex.responseBody.use { it.write(b) }
        }
        // The whole fixture: a well-formed EMPTY heads array, which is not an error condition.
        server.createContext("/api/heads") { ex ->
            val b = """{"heads":[]}""".toByteArray()
            ex.sendResponseHeaders(200, b.size.toLong())
            ex.responseBody.use { it.write(b) }
        }
        server.start()
        try {
            val (_, out) = runDoctor(baseEnv(tmp, server.address.port))
            // Reaching an assertion at all is half the arm — before DR-173 runDoctor threw.
            assertTrue(out.contains("runtime"), "the runtime section must still be rendered:\n$out")
            assertTrue(out.contains("nothing to report"), "an empty section must say so:\n$out")
            // ...and the report must still COMPLETE, not stop at the section that was empty.
            assertTrue(
                out.contains("Everything checks out.") || out.contains("issue(s)") || out.contains("No blockers"),
                "the summary line must still be reached:\n$out",
            )
        } finally {
            server.stop(0)
        }
    }
}
