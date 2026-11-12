package splice.app.peer.owner

import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.app.peer.HELPER_MAIN_CLASS
import splice.app.peer.PEER_BUILD_FLAG
import splice.app.peer.PEER_HOST_COMMAND
import splice.app.peer.PEER_SETTINGS_FLAG
import splice.app.peer.PEER_STATE_FLAG
import splice.app.peer.PeerBuild
import splice.app.peer.PeerHostFiles
import splice.app.peer.PeerPlaces
import splice.app.peer.PeerSupervisor
import splice.core.util.EnvReader
import splice.core.util.LogSink
import splice.core.util.SecureFile
import splice.sessions.peer.helper.HelperFile
import splice.sessions.peer.helper.HelperFiles
import splice.sessions.peer.helper.PeerRegistryFiles
import splice.sessions.peer.helper.PeerStateFiles
import splice.sessions.peer.owner.PeerOwnerProcesses
import splice.sessions.peer.store.PeerRegistration
import splice.sessions.peer.store.PeerRequest
import splice.sessions.peer.store.PeerStore
import splice.upstream.Ticker
import splice.upstream.codemode.ProcessDispatchers
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds
import java.util.concurrent.TimeUnit

private const val PROCESS_PEER = "owner-fixture"
private const val PROCESS_WAIT_MS = 15_000L
private const val HOST_EXIT_WAIT_MS = 30_000L

/** Two real, isolated processes; only these handles are ever destroyed by this fixture. */
private class OwnedPeerProcesses(private val dir: Path) : AutoCloseable {
    val settings: Path = dir.resolve("settings")
    val state: Path = dir.resolve("state")
    val registry: Path = Files.createDirectories(dir.resolve("registry"))
    val runtime: Path = dir.resolve("runtime")
    val store = PeerStore(settings)
    private val output = dir.resolve("helper.out")
    private var helper: Process? = null

    init {
        check(SecureFile.ownerOnlyDirectory(PeerStateFiles(state, PROCESS_PEER).dir) == null)
    }

    val harness: Process = ProcessBuilder("sleep", "120").start()

    suspend fun start(): Process {
        val owner = checkNotNull(PeerOwnerProcesses().capture(harness.pid()))
        check(
            store.register(PeerRequest(PROCESS_PEER, dir.toString(), listOf("*"), owner), emptySet())
                is PeerRegistration.Registered,
        )
        // Oct 28, 2026: one peer-host process serves every peer, so the fixture asks the host for this one.
        PeerHostFiles(state).request(PROCESS_PEER, PeerBuild().current())
        val process = ProcessBuilder(
            ProcessHandle.current().info().command().orElse("java"),
            "-cp", System.getProperty("codeMode.testClasspath", System.getProperty("java.class.path")),
            HELPER_MAIN_CLASS, PEER_HOST_COMMAND,
            PEER_SETTINGS_FLAG, settings.toString(), PEER_STATE_FLAG, state.toString(),
            "--registry", registry.toString(), "--runtime", runtime.toString(), PEER_BUILD_FLAG, PeerBuild().current(),
        ).redirectErrorStream(true).redirectOutput(output.toFile()).start()
        helper = process
        return process
    }

    fun registered(): HelperFile {
        val helpers = HelperFiles()
        val files = PeerStateFiles(state, PROCESS_PEER)
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(PROCESS_WAIT_MS)
        files.dir.fileSystem.newWatchService().use { watch ->
            files.dir.register(watch, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_MODIFY)
            while (helpers.read(files)?.let(helpers::alive) != true) {
                check(helper?.isAlive == true) { report() }
                val remaining = deadline - System.nanoTime()
                check(remaining > 0L) { "the helper never registered: ${report()}" }
                val event = checkNotNull(watch.poll(remaining, TimeUnit.NANOSECONDS)) {
                    "the helper never registered: ${report()}"
                }
                event.pollEvents()
                check(event.reset()) { "the helper's state directory was removed before registration" }
            }
        }
        return checkNotNull(helpers.read(files))
    }

    fun report(): String = if (Files.exists(output)) Files.readString(output) else "the helper wrote no output"

    override fun close() {
        helper?.destroyForcibly()
        helper?.waitFor(2, TimeUnit.SECONDS)
        harness.destroyForcibly()
        harness.waitFor(2, TimeUnit.SECONDS)
    }
}

class PeerOwnerLifecycleProcessTest {
    @Test
    fun `a dead harness loses its actual helper and registration without resurrection`(
        @TempDir dir: Path,
    ) = runBlocking {
        OwnedPeerProcesses(dir).use { fixture ->
            val helper = fixture.start()
            val registered = fixture.registered()
            val records = PeerRegistryFiles(listOf(fixture.registry))
            val record = records.record(fixture.registry, registered.pid)
            val socket = Path.of(registered.socket)
            val key = records.key(fixture.registry, registered.pid, socket)
            assertTrue(Files.exists(record))
            assertTrue(Files.exists(key))
            fixture.harness.destroyForcibly()
            assertTrue(fixture.harness.waitFor(5, TimeUnit.SECONDS))
            // The host exits once it serves no peer, 10 s after this one ended.
            assertTrue(helper.waitFor(HOST_EXIT_WAIT_MS, TimeUnit.MILLISECONDS), fixture.report())
            assertEquals(0, helper.exitValue(), fixture.report())
            assertFalse(Files.exists(record))
            assertFalse(Files.exists(key))
            assertFalse(Files.exists(socket))
            assertFalse(Files.exists(PeerStateFiles(fixture.state, PROCESS_PEER).helperJson))
            assertNotNull(
                fixture.store.get(PROCESS_PEER),
                "the helper withdrew itself before the supervisor retired it",
            )
            retire(fixture)
            assertNull(fixture.store.get(PROCESS_PEER))
            assertFalse(Files.exists(fixture.settings.resolve("peers/$PROCESS_PEER/peer.json")))
            assertFalse(Files.exists(record))
        }
    }

    private suspend fun retire(fixture: OwnedPeerProcesses) = kotlinx.coroutines.coroutineScope {
        val places = PeerPlaces(fixture.settings, fixture.state, listOf(fixture.registry), fixture.runtime)
        val supervisor =
            PeerSupervisor(fixture.store, places, EnvReader { null }, ProcessDispatchers().io(), LogSink { })
        val reconciling = launch { supervisor.run(Ticker { false }) }
        try {
            withTimeout(PROCESS_WAIT_MS) { reconciling.join() }
        } finally {
            reconciling.cancelAndJoin()
        }
    }
}
