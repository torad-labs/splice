// NEW (2026-09-05): the per-session client window registry — what a session's status-line post
// teaches the head about the window that session's process really runs with.
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import splice.core.model.ClientWindows

class ClientWindowsTest {

    @Test
    fun `a recorded session answers with its window and an unknown one with null`() {
        val windows = ClientWindows()
        windows.record("s-old", 400_000)
        assertEquals(400_000L, windows.windowFor("s-old"))
        assertNull(windows.windowFor("s-new"))
        assertNull(windows.windowFor(null))
    }

    @Test
    fun `a later post overwrites and a blank id or non-positive window records nothing`() {
        val windows = ClientWindows()
        windows.record("s", 400_000)
        windows.record("s", 272_000)
        assertEquals(272_000L, windows.windowFor("s"))
        windows.record("", 500_000)
        windows.record(null, 500_000)
        windows.record("t", 0)
        windows.record("u", null)
        assertNull(windows.windowFor(""))
        assertNull(windows.windowFor("t"))
        assertNull(windows.windowFor("u"))
    }

    @Test
    fun `the registry is bounded - the least recently touched session goes first`() {
        val windows = ClientWindows(capacity = 2)
        windows.record("a", 1)
        windows.record("b", 2)
        windows.windowFor("a") // touch a, so b is the eldest
        windows.record("c", 3)
        assertEquals(1L, windows.windowFor("a"))
        assertNull(windows.windowFor("b"))
        assertEquals(3L, windows.windowFor("c"))
    }
}
