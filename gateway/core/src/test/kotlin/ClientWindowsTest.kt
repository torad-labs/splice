// NEW (2026-09-05): the per-session client window registry — what a session's status-line post
// teaches the head about the window that session's process really runs with, and (afternoon) the
// store that carries it across a daemon restart.
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.model.ClientWindows
import java.nio.file.Files
import java.nio.file.Path

class ClientWindowsTest {

    private fun store(): Path =
        Files.createTempDirectory("client-windows").resolve("state").resolve("codex-client-windows.json")

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

    @Test
    fun `a recorded window survives a restart through the store file`() {
        val store = store()
        ClientWindows(store = store).record("s-persist", 272_000)
        assertTrue(Files.exists(store), "the registry must be written through, parent dirs included")
        val reloaded = ClientWindows(store = store)
        assertEquals(272_000L, reloaded.windowFor("s-persist"))
        assertNull(reloaded.windowFor("s-other"))
    }

    @Test
    fun `an unchanged post does not rewrite the store but a changed window does`() {
        val store = store()
        val windows = ClientWindows(store = store)
        windows.record("s", 272_000)
        Files.delete(store)
        windows.record("s", 272_000)
        assertFalse(Files.exists(store), "the same window again is not a write")
        windows.record("s", 400_000)
        assertTrue(Files.exists(store), "a changed window is")
        assertEquals(400_000L, ClientWindows(store = store).windowFor("s"))
    }

    @Test
    fun `a corrupt store is logged by class name and ignored - the registry still learns and re-saves`() {
        val store = store()
        Files.createDirectories(store.parent)
        Files.writeString(store, "{not json")
        val lines = mutableListOf<String>()
        val windows = ClientWindows(store = store, log = { lines += it })
        assertNull(windows.windowFor("s"))
        // The class name, never the content: kotlinx names the concrete decoding failure.
        assertTrue(lines.single().contains("ignored (JsonDecodingException)"), lines.toString())
        windows.record("s", 272_000)
        assertEquals(272_000L, ClientWindows(store = store).windowFor("s"))
    }
}
