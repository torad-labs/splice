// Regression tests for DesktopPeerReceipts security and concurrency invariants:
// 1. Failed registration or unowned explicit peer name never journals receipts to that peer's state.
// 2. Ownership revocation stops further receipt writes to the revoked peer's journal.
// 3. Concurrent first receipts on a cold sink allocate strictly unique monotonic sequence numbers.
// 4. Symlinked peer directories, journal files, or lock files are refused without modifying targets.
package splice.app.desktop.acp

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.util.JsonScalars
import splice.core.util.LogSink
import splice.core.util.WallClock
import splice.sessions.note.PeerNoteSocket
import splice.sessions.peer.DeliveryStatus
import splice.sessions.peer.InboundFrame
import splice.sessions.peer.codex.NativeProcess
import splice.sessions.peer.helper.PeerStateFiles
import splice.sessions.peer.journal.PeerJournal
import splice.sessions.peer.owner.PeerOwnerProcesses
import splice.sessions.peer.store.PeerRegistration
import splice.sessions.peer.store.PeerRequest
import splice.sessions.peer.store.PeerStore
import java.nio.file.Files
import java.nio.file.Path

class DesktopPeerReceiptsTest {
    @TempDir
    lateinit var tmp: Path

    @Test
    fun `failed registration or unowned explicit peer name never journals receipts`() = runBlocking {
        val stateDir = Files.createDirectories(tmp.resolve("state-unowned"))
        val settingsDir = Files.createDirectories(tmp.resolve("settings-unowned"))
        val sessionsDir = Files.createDirectories(tmp.resolve("sessions-unowned"))
        val pid = ProcessHandle.current().pid()
        val store = PeerStore(settingsDir)
        val otherOwner = NativeProcess(pid = pid + 10_000L, procStart = "other-start", pidDomain = "other-domain")
        val reg = store.register(
            PeerRequest("other-peer", tmp.toString(), listOf("*"), otherOwner, nativeInbox = true),
            emptySet(),
        )
        assertTrue(reg is PeerRegistration.Registered)

        for (candidate in listOf("other-peer", "failed-peer", "../escape")) {
            val sink = receiptSink(DesktopPeerReceiptStore(stateDir, settingsDir, candidate), sessionsDir, pid)
            sink.record(receipt("m-$candidate"))
        }
        assertFalse(Files.exists(PeerStateFiles(stateDir, "other-peer").dir.resolve("journal.jsonl")))
        assertFalse(Files.exists(PeerStateFiles(stateDir, "failed-peer").dir.resolve("journal.jsonl")))
        assertFalse(Files.exists(stateDir.resolve("escape")))
    }

    @Test
    fun `revoking peer ownership stops further receipt writes for that peer`() = runBlocking {
        val stateDir = Files.createDirectories(tmp.resolve("state-revoke"))
        val settingsDir = Files.createDirectories(tmp.resolve("settings-revoke"))
        val sessionsDir = Files.createDirectories(tmp.resolve("sessions-revoke"))
        val pid = ProcessHandle.current().pid()
        val self = checkNotNull(PeerOwnerProcesses().capture(pid))
        val store = PeerStore(settingsDir)
        val peerName = "agy-revoke"
        assertTrue(
            store.register(PeerRequest(peerName, tmp.toString(), listOf("*"), self, nativeInbox = true), emptySet())
                is PeerRegistration.Registered,
        )
        val sink = receiptSink(DesktopPeerReceiptStore(stateDir, settingsDir, peerName = null), sessionsDir, pid)
        sink.record(receipt("m-before"))
        assertTrue(store.revoke(peerName))
        sink.record(receipt("m-after"))

        val journal = PeerJournal(PeerStateFiles(stateDir, peerName).dir, LogSink {}, WallClock { 1_000L })
        val records = journal.read(0L, 10, setOf("receipt")).items
        assertEquals(listOf("m-before"), records.mapNotNull { JsonScalars.str(it, "origMsgId") })
    }

    @Test
    fun `concurrent first receipts allocate strictly unique monotonic sequence numbers`() = runBlocking {
        val stateDir = Files.createDirectories(tmp.resolve("state-concurrent"))
        val settingsDir = Files.createDirectories(tmp.resolve("settings-concurrent"))
        val sessionsDir = Files.createDirectories(tmp.resolve("sessions-concurrent"))
        val pid = ProcessHandle.current().pid()
        val self = checkNotNull(PeerOwnerProcesses().capture(pid))
        val peerName = "agy-concurrent"
        registerOwned(settingsDir, peerName, self)

        val sink = receiptSink(DesktopPeerReceiptStore(stateDir, settingsDir, peerName = null), sessionsDir, pid)
        val gate = CompletableDeferred<Unit>()
        val count = 16
        val jobs = (1..count).map { index ->
            async(Dispatchers.Default) {
                gate.await()
                sink.record(receipt("m-conc-$index"))
            }
        }
        gate.complete(Unit)
        jobs.awaitAll()

        val journalFile = PeerStateFiles(stateDir, peerName).dir.resolve("journal.jsonl")
        val lines = Files.readAllLines(journalFile).filter(String::isNotBlank)
        assertEquals(count, lines.size, "every concurrent first receipt must be journaled")
        val seqs = lines.map { line ->
            checkNotNull(JsonScalars.long(Json.parseToJsonElement(line).jsonObject, "seq"))
        }
        assertEquals((1L..count.toLong()).toList(), seqs, "sequence numbers must be strictly unique and monotonic")
    }

    @Test
    fun `refuses symlinked journal file and lock file without modifying outside targets`() = runBlocking {
        val pid = ProcessHandle.current().pid()
        val self = checkNotNull(PeerOwnerProcesses().capture(pid))

        val fileState = Files.createDirectories(tmp.resolve("state-sym-file"))
        val fileSettings = Files.createDirectories(tmp.resolve("settings-sym-file"))
        val fileSessions = Files.createDirectories(tmp.resolve("sessions-sym-file"))
        registerOwned(fileSettings, "agy-sym-file", self)
        val outside = tmp.resolve("outside-file.txt")
        Files.writeString(outside, "DO-NOT-TOUCH")
        val peerDir = Files.createDirectories(PeerStateFiles(fileState, "agy-sym-file").dir)
        Files.createSymbolicLink(peerDir.resolve("journal.jsonl"), outside)
        val logs = mutableListOf<String>()
        val fileStore = DesktopPeerReceiptStore(fileState, fileSettings, "agy-sym-file")
        val fileSink = receiptSink(fileStore, fileSessions, pid, logs)
        fileSink.record(receipt("m-sym-file"))
        assertEquals("DO-NOT-TOUCH", Files.readString(outside))
        assertTrue(logs.any { "refused" in it }, "expected symlink refusal log: $logs")

        val lockState = Files.createDirectories(tmp.resolve("state-sym-lock"))
        val lockSettings = Files.createDirectories(tmp.resolve("settings-sym-lock"))
        val lockSessions = Files.createDirectories(tmp.resolve("sessions-sym-lock"))
        registerOwned(lockSettings, "agy-sym-lock", self)
        val outsideLock = tmp.resolve("outside-lock.txt")
        Files.writeString(outsideLock, "LOCK-SENTINEL")
        val lockPeerDir = Files.createDirectories(PeerStateFiles(lockState, "agy-sym-lock").dir)
        Files.createSymbolicLink(lockPeerDir.resolve("journal.jsonl.lock"), outsideLock)
        val lockSink = receiptSink(DesktopPeerReceiptStore(lockState, lockSettings, "agy-sym-lock"), lockSessions, pid)
        lockSink.record(receipt("m-sym-lock"))
        assertEquals("LOCK-SENTINEL", Files.readString(outsideLock))
        assertFalse(Files.exists(lockPeerDir.resolve("journal.jsonl")))
    }

    @Test
    fun `refuses symlinked peer directory and peers root without writing outside`() = runBlocking {
        val pid = ProcessHandle.current().pid()
        val self = checkNotNull(PeerOwnerProcesses().capture(pid))

        val dirState = Files.createDirectories(tmp.resolve("state-sym-dir"))
        val dirSettings = Files.createDirectories(tmp.resolve("settings-sym-dir"))
        val dirSessions = Files.createDirectories(tmp.resolve("sessions-sym-dir"))
        registerOwned(dirSettings, "agy-sym-dir", self)
        val outsideDir = Files.createDirectories(tmp.resolve("outside-dir"))
        val peersRoot = Files.createDirectories(dirState.resolve("peers"))
        Files.createSymbolicLink(peersRoot.resolve("agy-sym-dir"), outsideDir)
        val dirSink = receiptSink(DesktopPeerReceiptStore(dirState, dirSettings, "agy-sym-dir"), dirSessions, pid)
        dirSink.record(receipt("m-sym-dir"))
        assertFalse(Files.exists(outsideDir.resolve("journal.jsonl")))

        val rootState = Files.createDirectories(tmp.resolve("state-sym-root"))
        val rootSettings = Files.createDirectories(tmp.resolve("settings-sym-root"))
        val rootSessions = Files.createDirectories(tmp.resolve("sessions-sym-root"))
        registerOwned(rootSettings, "agy-sym-root", self)
        val outsidePeers = Files.createDirectories(tmp.resolve("outside-peers"))
        Files.createSymbolicLink(rootState.resolve("peers"), outsidePeers)
        val rootSink = receiptSink(DesktopPeerReceiptStore(rootState, rootSettings, "agy-sym-root"), rootSessions, pid)
        rootSink.record(receipt("m-sym-root"))
        assertFalse(Files.exists(outsidePeers.resolve("agy-sym-root")))
    }

    private suspend fun registerOwned(settingsDir: Path, peerName: String, owner: NativeProcess) {
        val reg = PeerStore(settingsDir).register(
            PeerRequest(peerName, tmp.toString(), listOf("*"), owner, nativeInbox = true),
            emptySet(),
        )
        assertTrue(reg is PeerRegistration.Registered)
    }

    private fun receiptSink(
        store: DesktopPeerReceiptStore,
        sessionsDir: Path,
        pid: Long,
        logs: MutableList<String> = mutableListOf(),
    ): DesktopPeerReceiptSink = DesktopPeerReceiptSink(
        store = store,
        sessionsDir = sessionsDir,
        socket = tmp.resolve("self.sock"),
        outbox = PeerNoteSocket(Dispatchers.IO, registryDirs = listOf(sessionsDir)),
        pid = pid,
        log = LogSink { logs += it },
        clock = WallClock { 1_795_300_000_000L },
    )

    private fun receipt(origMsgId: String): InboundFrame.Receipt = InboundFrame.Receipt(
        origMsgId = origMsgId,
        status = DeliveryStatus.DELIVERED,
        detail = null,
        claimedAddress = "uds:/tmp/target.sock",
    )
}
