// The names no peer may register (docs/peers.md): the built-in roles on every install, and on top of them the names an
// install lists in `[daemon] reserved_peer_names`, such as an inbox of its own that a peer must not pose as.
package splice.sessions.peer.store

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class PeerReservedNamesTest {
    @Test
    fun `a name the install reserves is refused, and registers on an install that does not reserve it`(
        @TempDir dir: Path,
    ) = runBlocking<Unit> {
        val request = PeerRequest("inbox", dir.toString(), listOf("*"))

        val reserving = PeerStore(dir.resolve("reserving"), reservedNames = setOf("inbox"))
        val refused = assertInstanceOf(PeerRegistration.Refused::class.java, reserving.register(request, emptySet()))
        assertEquals(PeerRefusal.TAKEN, refused.why)
        assertEquals("'inbox' is reserved", refused.reason)

        val open = PeerStore(dir.resolve("open")).register(request, emptySet())
        assertInstanceOf(PeerRegistration.Registered::class.java, open)
    }

    @Test
    fun `a built-in role is reserved on an install that lists none of its own`(@TempDir dir: Path) = runBlocking {
        val request = PeerRequest("operator", dir.toString(), listOf("*"))
        val answer = PeerStore(dir.resolve("settings")).register(request, emptySet())
        assertEquals("'operator' is reserved", assertInstanceOf(PeerRegistration.Refused::class.java, answer).reason)
    }
}
