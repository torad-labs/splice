// Oct 28, 2026 (Marcos: "yeah replcace the java process per peer"). Each peer ran a JVM of its own, 80 to 110 MB of
// memory apiece, so sixteen peers cost well over a gigabyte. One peer-host process now serves every peer of a state
// folder. Each peer keeps a pid of its own, a small anchor process the host started, so the session registry,
// ListAgents and SendMessage still see one live session per peer. A signal to that pid stops that peer alone, a host
// that dies takes its anchors with it so no peer looks alive with nothing behind it, and a host left with no peer
// exits.
package splice.app.peer

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.util.SecureFile
import splice.sessions.peer.helper.HelperFile
import splice.sessions.peer.helper.HelperFiles
import splice.sessions.peer.helper.PeerRegistryFiles
import splice.sessions.peer.helper.PeerStateFiles
import splice.sessions.peer.store.PeerRegistration
import splice.sessions.peer.store.PeerRequest
import splice.sessions.peer.store.PeerStore
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

private const val WAIT_MS = 20_000L

// why: the host exits 10 s after its last peer; this leaves it room to notice and to clean up.
private const val IDLE_EXIT_WAIT_MS = 30_000L

/** A state folder, its registry, and the one host process this test starts and always ends. */
private class HostFixture(dir: Path) : AutoCloseable {
    val settings: Path = dir.resolve("settings")
    val state: Path = dir.resolve("state")
    val registry: Path = Files.createDirectories(dir.resolve("registry"))
    val runtime: Path = dir.resolve("runtime")
    private val output = dir.resolve("host.out")
    private val store = PeerStore(settings)
    private val helpers = HelperFiles()
    private var host: Process? = null

    fun peers(vararg names: String) = runBlocking {
        for (name in names) {
            check(SecureFile.ownerOnlyDirectory(PeerStateFiles(state, name).dir) == null)
            val registered = store.register(PeerRequest(name, settings.parent.toString(), listOf("*")), emptySet())
            check(registered is PeerRegistration.Registered) { "$name: $registered" }
            PeerHostFiles(state).request(name, PeerBuild().current())
        }
    }

    fun start(): Process {
        val process = ProcessBuilder(
            ProcessHandle.current().info().command().orElse("java"),
            "-cp", System.getProperty("codeMode.testClasspath", System.getProperty("java.class.path")),
            HELPER_MAIN_CLASS, PEER_HOST_COMMAND,
            PEER_SETTINGS_FLAG, settings.toString(), PEER_STATE_FLAG, state.toString(),
            "--registry", registry.toString(), "--runtime", runtime.toString(), PEER_BUILD_FLAG, PeerBuild().current(),
        ).redirectErrorStream(true).redirectOutput(output.toFile()).start()
        host = process
        return process
    }

    /** [name]'s helper.json once the process it names runs. */
    fun running(name: String): HelperFile {
        val files = PeerStateFiles(state, name)
        until("$name never started") { helpers.read(files)?.let(helpers::alive) == true }
        return checkNotNull(helpers.read(files))
    }

    fun gone(name: String) = !Files.exists(PeerStateFiles(state, name).helperJson)

    fun record(pid: Long): Path = PeerRegistryFiles(listOf(registry)).record(registry, pid)

    fun until(why: String, waitMs: Long = WAIT_MS, done: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(waitMs)
        while (!done()) {
            check(System.nanoTime() < deadline) { "$why: ${report()}" }
            Thread.onSpinWait()
        }
    }

    fun report(): String = if (Files.exists(output)) Files.readString(output) else "the host wrote no output"

    override fun close() {
        host?.destroyForcibly()
        host?.waitFor(2, TimeUnit.SECONDS)
    }
}

class PeerHostProcessTest {
    @Test
    fun `one host serves every peer, each under a pid of its own`(@TempDir dir: Path) {
        HostFixture(dir).use { fixture ->
            fixture.peers("alpha", "bravo")
            val host = fixture.start()
            val alpha = fixture.running("alpha")
            val bravo = fixture.running("bravo")

            assertNotEquals(alpha.pid, bravo.pid, "each peer has a pid of its own")
            for (peer in listOf(alpha, bravo)) {
                val anchor = ProcessHandle.of(peer.pid).orElseThrow()
                assertEquals(host.pid(), anchor.parent().map { it.pid() }.orElse(-1L), "the host started it")
                assertFalse(anchor.info().command().orElse("").endsWith("java"), "a peer's pid is no JVM")
                assertTrue(Files.exists(fixture.record(peer.pid)), "the registry lists it under that pid")
                assertTrue(Files.exists(Path.of(peer.socket)), "it receives on its own socket")
            }

            ProcessHandle.of(alpha.pid).orElseThrow().destroy()
            fixture.until("alpha's files stay after its pid ended") {
                val files = listOf(fixture.record(alpha.pid), Path.of(alpha.socket))
                fixture.gone("alpha") && files.none { Files.exists(it) }
            }
            assertTrue(ProcessHandle.of(bravo.pid).map { it.isAlive }.orElse(false), "bravo still runs")
            assertTrue(Files.exists(fixture.record(bravo.pid)), "and is still listed")

            host.destroyForcibly()
            val anchor = ProcessHandle.of(bravo.pid).orElseThrow()
            assertTrue(anchor.onExit().completeOnTimeout(null, WAIT_MS, TimeUnit.MILLISECONDS).get() != null)
            assertFalse(anchor.isAlive, "a dead host leaves no peer that looks alive")
        }
    }

    @Test
    fun `a host left with no peer exits`(@TempDir dir: Path) {
        HostFixture(dir).use { fixture ->
            fixture.peers("alpha")
            val host = fixture.start()
            val alpha = fixture.running("alpha")
            ProcessHandle.of(alpha.pid).orElseThrow().destroy()
            assertTrue(host.waitFor(IDLE_EXIT_WAIT_MS, TimeUnit.MILLISECONDS), fixture.report())
            assertEquals(0, host.exitValue(), fixture.report())
            assertTrue(fixture.gone("alpha"))
        }
    }
}
