// What an attach leaves in the store: the peer `codex-<thread>` under the thread's own session id, kept across a restart
// of the daemon, brought up to date in place, and revoked for an ended thread only when no attach came after the end.
package splice.sessions.peer.codex

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.sessions.peer.store.CodexAttach
import splice.sessions.peer.store.PeerRefusal
import splice.sessions.peer.store.PeerStore
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class CodexPeerStoreTest {
    private val thread = "019a2f6c-4b1e-7c3d-9e8f-0a1b2c3d4e5f"
    private val owner = NativeProcess(4242, "1234567", "linux:abc:pid:[4026531836]")

    private fun request(dir: Path, owner: NativeProcess? = this.owner) =
        CodexRequest(CodexBinding(thread, dir.resolve("codex").toString(), owner), dir.toString())

    private fun attached(store: PeerStore, request: CodexRequest): CodexAttach.Attached =
        assertInstanceOf(CodexAttach.Attached::class.java, runBlocking { store.attachCodex(request) })

    @Test
    fun `an attach names the peer after the thread and gives it the thread's session id and a key`(@TempDir dir: Path) {
        val store = PeerStore(dir.resolve("settings"))
        val first = attached(store, request(dir))
        assertEquals("codex-$thread", first.config.name)
        assertEquals(thread, first.config.sessionId)
        assertEquals(listOf("*"), first.config.sendTo)
        assertTrue(first.fresh)
        assertTrue(Files.readString(first.keyFile).startsWith("splp_"))
        val binding = store.codexThreads[first.config.name]
        assertEquals(CodexBinding(thread, dir.resolve("codex").toString(), owner), binding)
    }

    @Test
    fun `the daemon's next start still knows the codex peer and its thread`(@TempDir dir: Path) {
        val first = attached(PeerStore(dir.resolve("settings")), request(dir))
        val reloaded = PeerStore(dir.resolve("settings"))
        assertEquals(first.config, reloaded.get(first.config.name))
        assertEquals(owner, reloaded.codexThreads[first.config.name]?.owner)
        assertTrue(reloaded.wellFormed(first.config.name))
    }

    @Test
    fun `an attach that changes nothing keeps the peer and its key, and a new owner changes it in place`(
        @TempDir dir: Path,
    ) {
        val store = PeerStore(dir.resolve("settings"))
        val first = attached(store, request(dir))
        val key = Files.readString(first.keyFile)
        val again = attached(store, request(dir))
        assertFalse(again.fresh)
        val moved = NativeProcess(5151, "7654321", owner.pidDomain)
        val rebound = attached(store, request(dir, moved))
        assertTrue(rebound.fresh)
        assertEquals(moved, store.codexThreads[first.config.name]?.owner)
        assertEquals(key, Files.readString(rebound.keyFile))
        val unowned = attached(store, request(dir, owner = null))
        assertFalse(unowned.fresh)
        assertEquals(moved, store.codexThreads[first.config.name]?.owner)
    }

    @Test
    fun `an attach that names no thread id is refused`(@TempDir dir: Path) {
        val store = PeerStore(dir.resolve("settings"))
        val bad = CodexRequest(CodexBinding("not-a-thread", dir.toString(), null), dir.toString())
        val refused = assertInstanceOf(CodexAttach.Refused::class.java, runBlocking { store.attachCodex(bad) })
        assertEquals(PeerRefusal.INVALID, refused.why)
        assertTrue(store.list().isEmpty())
    }

    @Test
    fun `an ended thread's peer is revoked, unless its thread attached again after the end`(@TempDir dir: Path) {
        val attachedAt = Instant.parse("2026-10-27T12:00:00Z")
        val store = PeerStore(dir.resolve("settings"), clock = Clock.fixed(attachedAt, ZoneOffset.UTC))
        val name = attached(store, request(dir)).config.name
        assertFalse(runBlocking { store.revoke(name, threadEndedAt = attachedAt.toEpochMilli() - ONE_MINUTE_MS) })
        assertTrue(store.get(name) != null)
        assertTrue(runBlocking { store.revoke(name, threadEndedAt = attachedAt.toEpochMilli() + ONE_MINUTE_MS) })
        assertNull(store.get(name))
        assertNull(store.codexThreads[name])
        assertFalse(Files.exists(dir.resolve("settings/peers/$name")))
    }
}

private const val ONE_MINUTE_MS = 60_000L
