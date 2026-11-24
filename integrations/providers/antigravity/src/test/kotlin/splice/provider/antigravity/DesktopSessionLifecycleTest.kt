// Regression tests for DesktopSession lifecycle hook ordering and idempotency:
// 1. Shutdown before session open never emits an orphan SESSION_END and cleans up built parts.
// 2. Shutdown racing during hooks.opened emits SESSION_START strictly before SESSION_END, once.
// 3. Repeated/concurrent closeRecord calls emit SESSION_END at most once.
package splice.provider.antigravity

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.client.ForegroundToolActivity
import splice.core.client.ForegroundToolPhase
import splice.core.util.EnvReader
import splice.core.util.LogSink
import splice.core.util.WallClock
import splice.provider.antigravity.hooks.AcpSessionHooks
import splice.provider.antigravity.hooks.AcpSessionStart
import splice.provider.antigravity.peer.DesktopPeerInbox
import splice.provider.antigravity.peer.DesktopPeerMessage
import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

internal class DesktopSessionLifecycleTest {
    @TempDir
    lateinit var tmp: Path

    @Test
    fun `shutdown before open publishes never emits orphan SESSION_END and cleans up record and inbox`() = runBlocking {
        val phases = Collections.synchronizedList(mutableListOf<ForegroundToolPhase>())
        val starts = AtomicInteger(0)
        val inbox = FakePeerInbox()
        val hooks = AcpSessionHooks(
            sessionStart = AcpSessionStart { _, _ -> starts.incrementAndGet() },
            foreground = ForegroundToolActivity { phases += it.phase },
        )
        lateinit var session: DesktopSession
        val partsFactory = PartsFactory { id ->
            val built = buildParts(id, inbox)
            // Simulate JVM shutdown hook firing after parts are constructed, before ensureOpen publishes open.
            session.closeRecord()
            built
        }
        session = buildSession(partsFactory, hooks)
        val built = session.ensureOpen(emptyList())
        session.closeRecord()
        assertEquals(0, starts.get(), "closed session must never fire sessionStart")
        assertEquals(emptyList<ForegroundToolPhase>(), phases, "closed session must never emit orphan SESSION_END")
        assertFalse(Files.exists(recordPath()), "session record must not remain on disk")
        assertTrue(inbox.closedCount.get() >= 1, "peer inbox must be closed on shutdown before open")
        assertEquals("s-preclose", built.frames.sessionId)
    }

    @Test
    fun `shutdown racing during SESSION_START emits START strictly before END and never twice`() = runBlocking {
        val phases = Collections.synchronizedList(mutableListOf<ForegroundToolPhase>())
        val inbox = FakePeerInbox()
        lateinit var session: DesktopSession
        val hooks = AcpSessionHooks(
            foreground = ForegroundToolActivity { call ->
                phases += call.phase
                if (call.phase == ForegroundToolPhase.SESSION_START) {
                    // Simulate concurrent JVM shutdown hook firing inside hooks.opened.
                    session.closeRecord()
                }
            },
        )
        session = buildSession(PartsFactory { id -> buildParts(id, inbox) }, hooks)
        session.ensureOpen(emptyList())
        // Simulate normal finally block also invoking closeRecord after the hook already closed.
        session.closeRecord()
        assertEquals(
            listOf(ForegroundToolPhase.SESSION_START, ForegroundToolPhase.SESSION_END),
            phases,
            "SESSION_START must strictly precede SESSION_END with no duplicates",
        )
        assertFalse(Files.exists(recordPath()), "session record must be removed before teardown finishes")
        assertEquals(1, inbox.closedCount.get(), "peer inbox must be closed exactly once")
    }

    @Test
    fun `shutdown hook during Opening blocks until record removal, inbox close, and SESSION_END finish`() =
        runBlocking {
            val phases = Collections.synchronizedList(mutableListOf<ForegroundToolPhase>())
            val inbox = FakePeerInbox()
            val insideOpened = CountDownLatch(1)
            val allowOpenedReturn = CountDownLatch(1)
            val hookEntered = CountDownLatch(1)
            val hookReturned = CountDownLatch(1)
            val hooks = AcpSessionHooks(
                foreground = ForegroundToolActivity { call ->
                    phases += call.phase
                    if (call.phase == ForegroundToolPhase.SESSION_START) {
                        insideOpened.countDown()
                        assertTrue(allowOpenedReturn.await(5, TimeUnit.SECONDS))
                    }
                },
            )
            val session = buildSession(PartsFactory { id -> buildParts(id, inbox) }, hooks)
            val openJob = async(Dispatchers.IO) { session.ensureOpen(emptyList()) }
            assertTrue(insideOpened.await(5, TimeUnit.SECONDS), "open must enter hooks.opened")
            assertTrue(Files.exists(recordPath()), "record exists while Opening")

            val hookJob = async(Dispatchers.IO) {
                hookEntered.countDown()
                session.closeRecord()
                hookReturned.countDown()
            }
            assertTrue(hookEntered.await(5, TimeUnit.SECONDS))
            assertFalse(
                hookReturned.await(150, TimeUnit.MILLISECONDS),
                "shutdown hook must wait while Opening is still in progress",
            )

            allowOpenedReturn.countDown()
            assertTrue(hookReturned.await(5, TimeUnit.SECONDS), "shutdown hook must return once teardown settles")
            assertEquals(
                listOf(ForegroundToolPhase.SESSION_START, ForegroundToolPhase.SESSION_END),
                phases,
                "SESSION_END must already be emitted when the shutdown hook returns",
            )
            assertFalse(Files.exists(recordPath()), "session record must already be removed before hook returns")
            assertEquals(1, inbox.closedCount.get(), "peer inbox must already be closed before hook returns")

            openJob.await()
            hookJob.await()
        }

    @Test
    fun `repeated closeRecord calls after open emit SESSION_END exactly once`() = runBlocking {
        val phases = Collections.synchronizedList(mutableListOf<ForegroundToolPhase>())
        val inbox = FakePeerInbox()
        val hooks = AcpSessionHooks(foreground = ForegroundToolActivity { phases += it.phase })
        val session = buildSession(PartsFactory { id -> buildParts(id, inbox) }, hooks)
        session.ensureOpen(emptyList())
        assertTrue(Files.exists(recordPath()), "open session must write its record")
        session.closeRecord()
        session.closeRecord()
        assertEquals(
            listOf(ForegroundToolPhase.SESSION_START, ForegroundToolPhase.SESSION_END),
            phases,
        )
        assertFalse(Files.exists(recordPath()))
        assertEquals(1, inbox.closedCount.get())
    }

    private fun buildSession(partsFactory: PartsFactory, hooks: AcpSessionHooks): DesktopSession {
        val claude = ClaudeArgsReader().of(listOf("--session-id=s-preclose", "--model=claude-antigravity--gem-4-neon"))
        val mcpState = McpState()
        val bridge = McpBridge(
            DesktopOut {},
            object : BridgeConnected {
                override fun connected(serverName: String) = Unit
            },
        )
        val envTarget = CoordinatorEnv(claude, strictMcp = false, cwd = project(), env = env(), log = LogSink {})
        val started = StartedAgy(connection = null, scratch = null, reason = "no agy in lifecycle test")
        val chan = CoordinatorIo(
            started = started,
            hello = null,
            helloFailure = "no agy in lifecycle test",
            lines = Channel(Channel.UNLIMITED),
            deferredLines = ArrayDeque(),
            scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined),
        )
        val coordinator = AgyMcpCoordinator(envTarget, chan, mcpState, bridge)
        return DesktopSession(claude, partsFactory, coordinator, mcpState, bridge, WallClock { 1_000L }, hooks)
    }

    private fun buildParts(id: String, inbox: DesktopPeerInbox): DesktopParts {
        val configDir = Files.createDirectories(tmp.resolve("config"))
        val projectDir = Files.createDirectories(project())
        val clock = WallClock { 1_000L }
        val record = ClaudeSessionRecord(
            configDir,
            SessionFacts(
                pid = ProcessHandle.current().pid(),
                sessionId = id,
                cwd = projectDir.toString(),
                version = "2.1.328",
                entrypoint = "claude-desktop",
                hostSessionId = null,
            ),
            clock,
            LogSink {},
        )
        val transcript = ClaudeTranscript(
            configDir,
            TranscriptOwner(id, projectDir.toString(), "2.1.328", "claude-desktop"),
            clock,
        )
        val look = SessionLook(
            cwd = projectDir.toString(),
            model = "claude-antigravity--gem-4-neon",
            permissionMode = "default",
            commands = emptyList(),
            version = "2.1.328",
        )
        return DesktopParts(DesktopFrames(id), DesktopOut {}, transcript, record, look, peerInbox = inbox)
    }

    private fun project(): Path = tmp.resolve("work")

    private fun recordPath(): Path = tmp.resolve("config/sessions/${ProcessHandle.current().pid()}.json")

    private fun env(): EnvReader = EnvReader { name -> if (name == "HOME") tmp.resolve("home").toString() else null }

    private class FakePeerInbox : DesktopPeerInbox {
        private val channel = Channel<DesktopPeerMessage>(Channel.UNLIMITED)
        val closedCount = AtomicInteger(0)

        override val socketPath: String = "/tmp/fake-peer.sock"
        override val pidDomain: String? = null
        override val procStart: String? = null
        override val incoming: ReceiveChannel<DesktopPeerMessage> = channel

        override fun close() {
            closedCount.incrementAndGet()
            channel.close()
        }
    }
}
