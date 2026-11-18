package splice.usage.statusline

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.head.HeldSaying

/** What a session's status line reads while turns of it are held (docs/specs/harness.md, decision 7). */
class StatuslineHeldTest {
    private val now = 1_794_542_400_000L
    private val renderer = StatuslineRenderer("Astra", now = { now })
    private val today = "● astra-large"

    private fun plain(s: String) = s.replace(Regex("\u001B\\[[0-9;]*m"), "")

    @Test
    fun `a held turn's message leads the line with how long ago it came, in its colour`() {
        val line = renderer.led(today, listOf(HeldSaying("s", "Reading the request", "cyan", now - 4_000L)))
        assertEquals("Reading the request · 4s ago   $today", plain(line))
        assertTrue(line.startsWith("\u001B[36mReading the request"), line)
    }

    @Test
    fun `a minute or more reads in minutes`() {
        val line = renderer.led(today, listOf(HeldSaying("s", "Checking the request", "yellow", now - 125_000L)))
        assertEquals("Checking the request · 2m ago   $today", plain(line))
    }

    @Test
    fun `a wait of hours reads in hours, as splice status and doctor say it`() {
        val waited = 5L * 60 * 60 * 1_000 + 7 * 60 * 1_000
        val line = renderer.led(today, listOf(HeldSaying("s", "⏸ plan limit reached", "yellow", now - waited)))
        assertEquals("⏸ plan limit reached · 5h ago   $today", plain(line))
    }

    @Test
    fun `a gray message draws in the terminal's gray`() {
        val gray = renderer.led(today, listOf(HeldSaying("s", "Waiting on the index", "gray", now - 1_000L)))
        assertTrue(gray.startsWith("\u001B[90mWaiting on the index"), gray)
    }

    @Test
    fun `with two turns held the line says the latest message and how many`() {
        val held = listOf(
            HeldSaying("s", "Opening src/main.ts", "cyan", now - 9_000L),
            HeldSaying("s", "Reading the tests", "cyan", now - 2_000L),
        )
        assertEquals("Reading the tests · 2s ago · 2 turns   $today", plain(renderer.led(today, held)))
    }

    @Test
    fun `with no turn held the line is what it is`() {
        assertEquals(today, renderer.led(today, emptyList()))
    }
}
