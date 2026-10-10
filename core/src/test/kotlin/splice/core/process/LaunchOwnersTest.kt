package splice.core.process

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

class LaunchOwnersTest(@param:TempDir private val state: Path) {
    private val birth = Instant.parse("2026-01-01T00:00:00Z")
    private val live = LaunchProcessIdentity { LaunchProcess(birth) }

    @Test
    fun `a non-proc record routes only the process that was launched`() {
        val owners = LaunchOwners(state, live)
        owners.write(42, "fixture", "http://127.0.0.1:3101", "session", "other")
        assertEquals("fixture", owners.read(42)?.head)
        val reused = LaunchOwners(state, LaunchProcessIdentity { LaunchProcess(birth.plusMillis(1)) })
        assertNull(reused.read(42))
        assertNull(LaunchOwners(state, LaunchProcessIdentity { LaunchProcess(null) }).read(42))
        assertEquals("fixture", owners.read(42)?.head, "an unknown start instant must not reap a live record")
    }

    @Test
    fun `gone records are reaped and unreadable records never identify a process`() {
        val owners = LaunchOwners(state, live)
        owners.write(43, "fixture", "http://127.0.0.1:3101", "login", "hook")
        assertEquals("hook", owners.read(43)?.origin)
        val file = state.resolve("launch-owners/43.json")
        assertNull(LaunchOwners(state, LaunchProcessIdentity { null }).read(43))
        assertFalse(Files.exists(file))
        Files.writeString(file, "{broken")
        assertNull(owners.read(43))
    }

    // Oct 10, 2026, console TMUX.md: a launch inside tmux records its pane, so the console finds the pane a session
    // runs in from the session's own process; a record names a tmux pane or no terminal at all.
    @Test
    fun `a launch in tmux keeps its pane and socket, and nothing else passes for one`() {
        val owners = LaunchOwners(state, live)
        val pane = LaunchTerminal("%12", "/tmp/tmux-1000/default")
        owners.write(44, LaunchDeclaration("fixture", "http://127.0.0.1:3101", "session", "other", pane))
        assertEquals(pane, owners.read(44)?.terminal)
        owners.write(45, "fixture", "http://127.0.0.1:3101", "session", "other")
        assertNull(owners.read(45)?.terminal, "a launch outside tmux has no terminal")
        assertThrows<IllegalArgumentException> {
            owners.write(46, LaunchDeclaration("fixture", "", "session", "other", LaunchTerminal("12; rm", "/s")))
        }
        val forged = """{"pid":47,"startedAt":"$birth","head":"fixture","baseUrl":"","kind":"session",""" +
            """"origin":"other","terminal":{"pane":"main","socket":"relative"}}"""
        Files.writeString(state.resolve("launch-owners/47.json"), forged)
        assertNull(owners.read(47), "a record naming no tmux pane is no evidence of a launch")
    }

    @Test
    fun `a real ProcessHandle start instant validates a record without proc`() {
        val pid = ProcessHandle.current().pid()
        val owners = LaunchOwners(state)
        owners.write(pid, "fixture", "http://127.0.0.1:3101", "session", "other")
        assertEquals("fixture", owners.read(pid)?.head)
        val file = state.resolve("launch-owners/$pid.json")
        assertEquals(
            "rw-------",
            java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(file)),
        )
    }
}
