package splice.sessions.peer.codex

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.sessions.peer.store.CodexAttach
import splice.sessions.peer.store.PeerRegistration
import splice.sessions.peer.store.PeerRequest
import splice.sessions.peer.store.PeerStore
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

class CodexNamedPeerTest {
    private val thread = "019a2f6c-4b1e-7c3d-9e8f-0a1b2c3d4e5f"
    private val owner = NativeProcess(4242, "1234567", "linux:test:pid:[1]")

    @Test
    fun `a refused reattachment cannot keep an ended thread registered`(@TempDir dir: Path) = runBlocking {
        var now = 1_000L
        val clock = object : Clock() {
            override fun getZone(): ZoneId = ZoneOffset.UTC
            override fun withZone(zone: ZoneId): Clock = this
            override fun instant(): Instant = Instant.ofEpochMilli(now)
        }
        val store = PeerStore(dir.resolve("settings"), clock = clock)
        store.register(PeerRequest("research", dir.toString(), listOf("*")), emptySet())
        val request = CodexRequest(CodexBinding(thread, dir.toString(), null), dir.toString(), "research")
        store.attachCodex(request)
        now = 3_000L
        val wrong = request.copy(binding = request.binding.copy(thread = "019a2f6c-4b1e-7c3d-9e8f-0a1b2c3d4e60"))
        assertInstanceOf(CodexAttach.Refused::class.java, store.attachCodex(wrong))
        assertTrue(store.revoke("research", threadEndedAt = 2_000L))
    }

    @Test
    fun `a named attachment survives reload and retains one native owner and existing credentials`(@TempDir dir: Path) =
        runBlocking {
            val settings = dir.resolve("settings")
            val store = PeerStore(settings)
            store.register(PeerRequest("research", dir.toString(), listOf("reviewer"), owner), emptySet())
            val original = store.get("research")
            val key = Files.readString(store.keyFile("research"))
            val request = CodexRequest(CodexBinding(thread, dir.toString(), null), dir.toString(), "research")
            assertInstanceOf(CodexAttach.Attached::class.java, store.attachCodex(request))
            val loaded = PeerStore(settings)
            assertEquals(original, loaded.get("research"))
            assertEquals(key, Files.readString(loaded.keyFile("research")))
            assertEquals(owner, loaded.nativeOwners["research"])
            val moved = owner.copy(pid = 5151, procStart = "7654321")
            loaded.attachCodex(request.copy(binding = request.binding.copy(owner = moved), peerName = null))
            assertEquals(moved, loaded.nativeOwners["research"])
            assertEquals(1, loaded.list().size)
            assertInstanceOf(
                PeerRegistration.Refused::class.java,
                loaded.register(PeerRequest("research", dir.toString(), listOf("*")), emptySet()),
            )
            assertEquals(original, loaded.get("research"))
        }

    @Test
    fun `attachment refuses absent names path traversal duplicate threads and a different native owner`(
        @TempDir dir: Path,
    ) =
        runBlocking {
            val store = PeerStore(dir.resolve("settings"))
            val request = CodexRequest(CodexBinding(thread, dir.toString(), owner), dir.toString(), "research")
            for (name in listOf("missing", "../escape")) {
                assertInstanceOf(CodexAttach.Refused::class.java, store.attachCodex(request.copy(peerName = name)))
            }
            assertTrue(store.list().isEmpty())
            store.register(PeerRequest("research", dir.toString(), listOf("*"), owner), emptySet())
            val different = request.copy(binding = request.binding.copy(owner = owner.copy(pid = 5151)))
            assertInstanceOf(CodexAttach.Refused::class.java, store.attachCodex(different))
            assertNull(store.codexThreads["research"])
            assertInstanceOf(CodexAttach.Attached::class.java, store.attachCodex(request))
            store.register(PeerRequest("second", dir.toString(), listOf("*")), emptySet())
            assertInstanceOf(CodexAttach.Refused::class.java, store.attachCodex(request.copy(peerName = "second")))
            assertNull(store.codexThreads["second"])
            assertEquals(setOf("research"), store.codexThreads.keys)
        }
}
