package splice.sessions.peer.store

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.sessions.peer.journal.PeerOutboxLog
import java.nio.file.Files
import java.nio.file.Path

class PeerLimitsTest {

    private fun registered(dir: Path, name: String): PeerStore {
        val store = PeerStore(dir.resolve("settings"))
        val answer = runBlocking { store.register(PeerRequest(name, dir.toString(), listOf("*")), emptySet()) }
        assertTrue(answer is PeerRegistration.Registered, "$answer")
        return store
    }

    @Test
    fun `a peer sends 20 at once and 60 a minute until it is given a limit of its own`(@TempDir dir: Path) {
        val store = registered(dir, "temple")
        val limits = PeerLimits(store)

        assertEquals(PeerLimit(perMinute = 60, burst = 20), limits.of("temple"))
        assertTrue(limits.set("temple", PeerLimit(perMinute = 120, burst = 40)))
        assertEquals(PeerLimit(120, 40), limits.of("temple"))
        assertEquals(PeerLimit(120, 40), PeerLimits(PeerStore(dir.resolve("settings"))).of("temple"), "kept on disk")

        assertTrue(limits.set("temple", null))
        assertEquals(PeerLimit(), PeerLimits(PeerStore(dir.resolve("settings"))).of("temple"), "the default again")
        assertFalse(limits.set("nobody", PeerLimit()), "no such peer")
    }

    @Test
    fun `a limit past what a helper and a session can take is refused`() {
        assertNull(PeerLimit(600, 100).refusal())
        assertNotNull(PeerLimit(601, 20).refusal())
        assertNotNull(PeerLimit(60, 0).refusal())
        assertNotNull(PeerLimit(60, 101).refusal())
    }

    @Test
    fun `a send looked up by its id is the newest one made under it`(@TempDir dir: Path) {
        val outbox = PeerOutboxLog(dir)
        val to = PeerOutboxLog.Target("gary", "s-gary", "uds:/run/x/42.sock")
        outbox.append(PeerOutboxLog.Record(at = 1, id = "tell-7", to = to, bytes = 3, outcome = "refused"))
        outbox.append(PeerOutboxLog.Record(at = 2, id = "tell-7", to = to, bytes = 3, outcome = "submitted"))

        assertEquals("submitted", outbox.find("tell-7")?.outcome)
        assertTrue(Files.exists(dir.resolve("outbox.jsonl")))
    }
}
