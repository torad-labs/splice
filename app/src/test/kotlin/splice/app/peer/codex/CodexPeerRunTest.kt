// A codex peer against an app-server on a real Unix socket: the message its inbox journaled reaches the thread as a
// turn in Claude Code's envelope, the peer's registry record shows the thread busy and carries the lifecycle binding
// Fence reads, and the run ends when the thread closes.
package splice.app.peer.codex

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.util.JsonScalars
import splice.core.util.LogSink
import splice.core.util.WallClock
import splice.sessions.peer.DeliveryStatus
import splice.sessions.peer.InboundFrame
import splice.sessions.peer.PeerOutbox
import splice.sessions.peer.PeerSender
import splice.sessions.peer.SendOutcome
import splice.sessions.peer.SenderCheck
import splice.sessions.peer.codex.CodexBinding
import splice.sessions.peer.codex.CodexRequest
import splice.sessions.peer.codex.CodexReturns
import splice.sessions.peer.codex.NativeProcess
import splice.sessions.peer.helper.HelperSetup
import splice.sessions.peer.helper.HelperStart
import splice.sessions.peer.helper.PeerHelper
import splice.sessions.peer.helper.PeerSockets
import splice.sessions.peer.journal.PeerInbox
import splice.sessions.peer.journal.PeerJournal
import splice.sessions.peer.store.PeerStore
import splice.sessions.registry.SessionRecord
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

private const val THREAD = "019a2f6c-4b1e-7c3d-9e8f-0a1b2c3d4e5f"
private const val GARY = "uds:/run/user/1000/cc-socks/42.sock"

// why: the stat field after the command name that holds a process's kernel start (field 22 of /proc/<pid>/stat).
private const val START_FIELD = 19

class CodexPeerRunTest {
    private val log = LogSink { }

    /** No receipt in this test reaches a socket: the senders' side is not what it checks. */
    private class Senders : PeerOutbox {
        override suspend fun send(
            target: SessionRecord,
            text: String,
            sender: PeerSender,
            msgId: String?,
        ): SendOutcome =
            SendOutcome.Submitted("unused")

        override suspend fun receipt(
            toAddress: String,
            origMsgId: String,
            status: DeliveryStatus,
            detail: String?,
            sender: PeerSender,
        ): Boolean = true
    }

    /** The Gradle worker's parent stands for the Codex process that hosts the thread: a live process of this user. */
    private fun owner(): NativeProcess {
        val pid = ProcessHandle.current().parent().orElseThrow().pid()
        val stat = Files.readString(Path.of("/proc/$pid/stat"))
        val start = stat.substringAfterLast(')').trim().split(' ')[START_FIELD]
        val machine = Files.readString(Path.of("/etc/machine-id")).trim()
        val namespace = Files.readSymbolicLink(Path.of("/proc/self/ns/pid")).toString()
        return NativeProcess(pid, start, "linux:$machine:$namespace")
    }

    @Test
    fun `a journaled message reaches the thread as a turn, the record shows it busy and bound, and a close ends it`(
        @TempDir dir: Path,
    ) {
        val home = dir.resolve("codex")
        val owner = owner()
        val binding = CodexBinding(THREAD, home.toString(), owner)
        CodexStandInAppServer(home.resolve("app-server-control/app-server-control.sock"), THREAD).use { server ->
            val settings = dir.resolve("settings")
            runBlocking { PeerStore(settings).attachCodex(CodexRequest(binding, dir.toString())) }
            val registry = Files.createDirectories(dir.resolve("registry"))
            val uid = (Files.getAttribute(Path.of("/proc/self"), "unix:uid") as Int).toLong()
            val sockets = PeerSockets(dir.resolve("run"), uid, System.getProperty("user.name"))
            val setup = HelperSetup("codex-$THREAD", settings, dir.resolve("state"), listOf(registry), sockets, "test")
            val start = PeerHelper(setup, WallClock { System.currentTimeMillis() }).start()
            val helper = assertInstanceOf(HelperStart.Started::class.java, start).helper
            try {
                val journal = PeerJournal(helper.files.dir, log)
                val sender = PeerSender(helper.parts.socket.toString(), "codex-$THREAD")
                runBlocking {
                    val gary = SenderCheck("gary", "s-gary", GARY, "gary", registered = true)
                    PeerInbox(journal, Senders(), sender, log)
                        .onMessage(InboundFrame.Message("m-1", GARY, "gary", "run the gate"), gary)
                }
                val parts = CodexPeerParts(helper, journal, CodexReturns(Senders(), sender), log)
                val ended = CompletableFuture<String>()
                Thread { ended.complete(runBlocking { CodexPeerRun(binding, parts).run(Dispatchers.IO) }) }
                    .apply { isDaemon = true }.start()

                val params = server.turnStart.get(WAIT_S, TimeUnit.SECONDS)
                assertEquals(THREAD, JsonScalars.str(params, "threadId"))
                assertEquals("m-1", JsonScalars.str(params, "clientUserMessageId"))
                val input = (params["input"] as JsonArray).single().jsonObject
                assertEquals(
                    "<cross-session-message from=\"$GARY\" from-name=\"gary\">\nrun the gate\n</cross-session-message>",
                    JsonScalars.str(input, "text"),
                )
                awaitStarted(journal)
                server.closeThread.complete(Unit)
                assertEquals("the thread closed", ended.get(WAIT_S, TimeUnit.SECONDS))
                assertBusyAndBound(registry, owner)
            } finally {
                helper.close()
            }
        }
    }

    /** The helper's record says the thread is busy and binds it to [owner], the Codex process that hosts it. */
    private fun assertBusyAndBound(registry: Path, owner: NativeProcess) {
        val record = Json.parseToJsonElement(
            Files.readString(registry.resolve("${ProcessHandle.current().pid()}.json")),
        ).jsonObject
        assertEquals(THREAD, JsonScalars.str(record, "sessionId"))
        assertEquals("busy", JsonScalars.str(record, "status"))
        val bound = record["lifecycleBinding"] as JsonObject
        assertEquals(owner.pid, JsonScalars.long(bound["nativeOwner"] as JsonObject, "pid"))
        assertEquals(ProcessHandle.current().pid(), JsonScalars.long(bound["peer"] as JsonObject, "pid"))
        assertEquals(THREAD, JsonScalars.str(bound, "sessionId"))
    }

    /** Waits, up to a deadline, until the journal says the thread took the message as turn-1. */
    private fun awaitStarted(journal: PeerJournal) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_S)
        var started = false
        while (!started && System.nanoTime() < deadline) {
            started = journal.read(0, PAGE, setOf("codex")).items
                .any { JsonScalars.str(it, "outcome") == "started" && JsonScalars.str(it, "turn") == "turn-1" }
            if (!started) Thread.onSpinWait()
        }
        assertTrue(started, "the journal never said the thread took the message")
    }
}

// why: one read of the journal in this test holds a few records; the journal's own cap is 200.
private const val PAGE = 50
