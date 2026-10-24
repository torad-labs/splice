// NEW: LAN share, round 11 of PR #419's review. The goal: sharing follows what splice.toml says last. Each re-read of
// the file hands its `[share]` table to the listener off the read's thread, and those jobs may run in any order; the
// listener ends up serving the table of the latest read whatever order they ran in. A dispatcher that holds the jobs
// and runs the newest first stands in for a busy pool, over the real listener and a real re-read of a temp file. Round
// 13 added a table boot would refuse: sharing stays as it is, and the log says why, as boot would say it.
package splice.app.daemon

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.config.ConfigService
import splice.core.config.MgmtKey
import splice.core.config.StatePaths
import splice.core.testing.TestPorts
import splice.core.topology.Topology
import splice.core.util.EnvReader
import splice.share.MachinesJoined
import splice.share.ShareListener
import splice.share.ShareStart
import splice.share.SharedCatalogs
import splice.share.SharedQuotas
import splice.topology.TopologyLoader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext

// why: a listener opening mints a certificate and binds; twenty seconds covers that on a loaded machine.
private const val JOBS_END_MS = 20_000L
private const val NEXT_JOB_MS = 20L

/** Holds every job dispatched to it until [drain] runs them, the newest first. */
private class NewestFirst : CoroutineDispatcher() {
    private val held = LinkedBlockingDeque<Runnable>()

    override fun dispatch(context: CoroutineContext, block: Runnable) = held.addLast(block)

    /** Runs what is held, the newest first, and what those jobs dispatch in turn, until [done]. */
    fun drain(done: () -> Boolean) {
        val until = System.currentTimeMillis() + JOBS_END_MS
        while (!done() && System.currentTimeMillis() < until) held.pollLast(NEXT_JOB_MS, TimeUnit.MILLISECONDS)?.run()
    }
}

class TopologyWindowsShareTest {
    @TempDir
    lateinit var tmp: Path

    private var stamp = 1_000L

    private val file by lazy { tmp.resolve("splice.toml").also { Files.writeString(it, "") } }

    /** Save [text] the way an editor does: new bytes and a modification time that moved. */
    private fun save(text: String) {
        Files.writeString(file, text)
        stamp += 1_000
        Files.setLastModifiedTime(file, FileTime.fromMillis(stamp))
    }

    /** The daemon's share listener over a state folder in [tmp], as it opens at boot. */
    private fun listener(): ShareListener {
        val vars = mapOf("SPLICE_STATE_DIR" to tmp.resolve("state").toString(), "HOME" to tmp.toString())
        val paths = StatePaths(envReader = EnvReader { vars[it] })
        return ShareListener(
            ShareStart(Topology(), paths, MgmtKey(paths), ConfigService(paths)) { },
            SharedCatalogs { null },
            SharedQuotas { null },
            paths,
            MachinesJoined { emptySet() },
            { },
        )
    }

    /** The daemon's re-read of splice.toml, handing each `[share]` table to [listener] by a job in [scope], and saying
     *  what it says in [log]. */
    private fun windows(listener: ShareListener, scope: CoroutineScope, log: (String) -> Unit = { }): TopologyWindows {
        val windows = TopologyWindows(
            file,
            TopologyLoader.parse(""),
            TopologyLoader.sha256Hex(ByteArray(0)),
            HeadCatalogs { _, head, provider, _ -> provider.catalogFor(head) },
            { log(it) },
            LocalWindowCheck { _, _, _ -> emptyList() },
            scope,
        )
        windows.sharing = listener
        return windows
    }

    @Test
    fun `sharing serves the table splice toml said last, whatever order the reads' jobs run in`() {
        val listener = listener()
        val jobs = NewestFirst()
        val supervisor = SupervisorJob()
        val scope = CoroutineScope(jobs + supervisor)
        val windows = windows(listener, scope)
        val port = TestPorts.reserve()
        try {
            save("[share]\nenabled = true\nport = $port\n")
            assertFalse(windows.stale(), "a read that sees sharing turned on")
            save("[share]\nenabled = false\nport = $port\n")
            assertFalse(windows.stale(), "and a later read that sees it turned off")

            jobs.drain { supervisor.children.none(Job::isActive) }

            assertTrue(supervisor.children.none(Job::isActive), "every read's job ran")
            assertFalse(listener.state().listening, "the file says off last: ${listener.state()}")
        } finally {
            scope.cancel()
            runBlocking { listener.stop() }
        }
    }

    @Test
    fun `a port boot would refuse leaves sharing on the port it serves, and the log says why`() {
        val listener = listener()
        val jobs = NewestFirst()
        val supervisor = SupervisorJob()
        val scope = CoroutineScope(jobs + supervisor)
        val said = ConcurrentLinkedQueue<String>()
        val windows = windows(listener, scope) { said += it }
        val port = TestPorts.reserve()
        try {
            save("[share]\nenabled = true\nport = $port\n")
            assertFalse(windows.stale(), "a read that sees sharing turned on")
            jobs.drain { listener.state().listening }
            assertTrue(listener.state().listening, "sharing opened: ${listener.state()}")

            save("[share]\nenabled = true\nport = 70000\n")
            assertFalse(windows.stale(), "a read that sees a port no machine has")
            jobs.drain { supervisor.children.none(Job::isActive) }

            assertEquals(port, listener.state().share.port, "${listener.state()}")
            assertTrue(listener.state().listening, "still sharing on it: ${listener.state()}")
            assertTrue(said.any { "70000" in it }, "the log says why: $said")
        } finally {
            scope.cancel()
            runBlocking { listener.stop() }
        }
    }
}
