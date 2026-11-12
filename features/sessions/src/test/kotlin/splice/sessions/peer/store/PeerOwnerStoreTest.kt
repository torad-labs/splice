package splice.sessions.peer.store

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.sessions.peer.owner.PeerOwnerProcesses
import java.nio.file.Files
import java.nio.file.Path

class PeerOwnerStoreTest {
    @Test
    fun `a registration keeps its actual owner across reload and rejects stale-owner retirement`(@TempDir dir: Path) =
        runBlocking {
            val owner = checkNotNull(PeerOwnerProcesses().capture(ProcessHandle.current().pid()))
            val store = PeerStore(dir)
            assertTrue(
                store.register(PeerRequest("owned-peer", dir.toString(), listOf("*"), owner), emptySet())
                    is PeerRegistration.Registered,
            )
            val reloaded = PeerStore(dir)
            assertEquals(owner, reloaded.nativeOwners["owned-peer"])
            assertFalse(reloaded.revoke("owned-peer", expectedOwner = owner.copy(procStart = owner.procStart + "0")))
            assertEquals(owner, reloaded.nativeOwners["owned-peer"])
            assertTrue(Files.exists(dir.resolve("peers/owned-peer/peer.key")))
            assertTrue(reloaded.revoke("owned-peer", expectedOwner = owner))
            assertFalse(Files.exists(dir.resolve("peers/owned-peer/peer.key")))
        }

    @Test
    fun `an ordinary persistent registration gains no guessed process owner on reload`(
        @TempDir dir: Path,
    ) = runBlocking {
        val store = PeerStore(dir)
        assertTrue(
            store.register(PeerRequest("persistent-peer", dir.toString(), listOf("*")), emptySet())
                is PeerRegistration.Registered,
        )
        assertNull(PeerStore(dir).nativeOwners["persistent-peer"])
    }
}
