// NEW: Oct 11, 2026 — the folder a move in flight carries his trust for, while it is in flight (MovingTrust).
package splice.client.resume

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

private const val AT = 1_791_700_000_000L

class MovingTrustTest {
    @TempDir
    lateinit var dir: Path

    private val moving by lazy { MovingTrust(dir) }

    @Test
    fun `a move in flight names the folder it is moving out of, and its end takes it back`() {
        val home = Path.of("/home/me")
        moving.granted("s1", home, AT)

        assertEquals(home, moving.folderOf("s1"))
        assertNull(moving.folderOf("s2"), "another session's launch carries nothing")

        moving.done("s1")
        assertNull(moving.folderOf("s1"), "the move ended, so the grant is over")
    }

    @Test
    fun `a daemon start clears every record a daemon that died mid-move left`() {
        moving.granted("s1", Path.of("/home/me"), AT)
        moving.granted("s2", Path.of("/home/me/work"), AT)

        assertEquals(2, moving.sweep(), "no move is in flight across a start")
        assertNull(moving.folderOf("s1"))
        assertNull(moving.folderOf("s2"))
        assertEquals(0, moving.sweep(), "a second start finds nothing to clear")
    }

    @Test
    fun `a session id that is no id is never written, read or deleted`() {
        moving.granted("../escape", Path.of("/home/me"), AT)

        assertEquals(0, moving.sweep(), "nothing was written for it")
        assertNull(moving.folderOf("../escape"))
    }
}
