// NEW: V4-183 — the per-head session index: what a launch reads to bound a bare -c. Pinned on the
// contract HeadBoundedContinue relies on: newest first, this cwd only, a gone transcript skipped,
// a damaged or absent file read as empty, an id that is not a session id never entered.
package splice.core.launch

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class SessionOwnershipTest {

    private val log = StringBuilder()

    private class TickingClock(private var now: Long) : Clock() {
        override fun instant(): Instant = Instant.ofEpochMilli(now++)
        override fun getZone(): ZoneOffset = ZoneOffset.UTC
        override fun withZone(zone: java.time.ZoneId): Clock = this
    }

    private fun ownership(configDir: Path) = SessionOwnership(configDir, TickingClock(1_000), log = { log.append(it) })

    private fun transcript(home: Path, id: String): Path {
        val file = home.resolve("projects/-work/$id.jsonl")
        Files.createDirectories(file.parent)
        return Files.writeString(file, "")
    }

    @Test
    fun `the newest recorded session for a cwd is returned, other cwds are not`(@TempDir home: Path) {
        val own = ownership(home)
        val work = Files.createDirectories(home.resolve("work")).toString()
        val other = Files.createDirectories(home.resolve("other")).toString()
        own.record("older", work, transcript(home, "older"))
        own.record("newer", work, transcript(home, "newer"))
        own.record("elsewhere", other, transcript(home, "elsewhere"))

        assertEquals("newer", own.newestFor(work)?.id)
        assertEquals("elsewhere", own.newestFor(other)?.id)
        assertNull(own.newestFor(home.resolve("never").toString()))
    }

    @Test
    fun `re-recording an id moves it to newest, a gone transcript is skipped`(@TempDir home: Path) {
        val own = ownership(home)
        val work = Files.createDirectories(home.resolve("work")).toString()
        val first = transcript(home, "first")
        own.record("first", work, first)
        own.record("second", work, transcript(home, "second"))
        own.record("first", work, first)
        assertEquals("first", own.newestFor(work)?.id, "a resumed session is the newest again")

        Files.delete(first)
        assertEquals("second", own.newestFor(work)?.id, "a deleted transcript cannot be continued")
    }

    @Test
    fun `the cwd is matched by real path, so a symlinked spelling finds the same sessions`(@TempDir home: Path) {
        val own = ownership(home)
        val real = Files.createDirectories(home.resolve("real"))
        val link = Files.createSymbolicLink(home.resolve("link"), real)
        own.record("s1", link.toString(), transcript(home, "s1"))

        assertEquals("s1", own.newestFor(real.toString())?.id)
        assertEquals(real.toString(), own.newestFor(link.toString())?.cwd)
    }

    @Test
    fun `an absent or damaged index reads as empty and is said once, never thrown`(@TempDir home: Path) {
        val own = ownership(home)
        assertNull(own.newestFor(home.toString()), "no file yet")

        Files.writeString(home.resolve(SESSION_OWNERSHIP_FILE), "{not json")
        assertNull(own.newestFor(home.toString()))
        assertTrue(log.contains("is unreadable"), log.toString())

        own.record("fresh", home.toString(), transcript(home, "fresh"))
        assertEquals("fresh", own.newestFor(home.toString())?.id, "the next record overwrites the damage")
    }

    @Test
    fun `an id that is not a session id is refused and never written`(@TempDir home: Path) {
        val own = ownership(home)
        own.record("../../etc/passwd", home.toString(), transcript(home, "x"))
        own.record("", home.toString(), transcript(home, "y"))

        assertNull(own.newestFor(home.toString()))
        assertFalse(Files.exists(home.resolve(SESSION_OWNERSHIP_FILE)), "nothing is written for a refused id")
        assertTrue(log.contains("not a session id"), log.toString())
    }
}
