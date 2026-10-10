package splice.sessions.registry

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.perf.HistoryWindow
import splice.core.perf.KeptHistory
import splice.core.util.AgedFiles
import java.nio.file.Files
import java.nio.file.Path
import java.time.ZoneOffset

class RecordedSessionsTest {
    @TempDir
    lateinit var state: Path

    private var listed = listOf<SessionRecord>()
    private var window = HistoryWindow(7, ZoneOffset.UTC)
    private val now = 1_791_600_000_000L
    private val dir get() = state.resolve(SEEN_SESSIONS_DIR)
    private val sessions get() = RecordedSessions(
        object : SessionSource {
            override fun read() = listed
            override fun list() = SessionListing(listed)
        },
        SeenSessions(dir, KeptHistory { window }) { now },
    )

    @Test
    fun `a session Claude Code forgot is listed as ended with its name, folder and head, and nothing it said`() {
        listed = listOf(live("aaaa-1", at = now - 60_000))
        sessions.list()

        listed = emptyList()
        val ended = sessions.list().sessions.single()

        assertEquals("aaaa-1", ended.sessionId)
        assertEquals("tax-rounding", ended.name)
        assertEquals("/work/billing-api", ended.process.cwd)
        assertEquals("claude-splice", ended.head)
        assertEquals(SessionAvailability.GONE, ended.availability)
        assertEquals(now - 60_000, ended.process.updatedAt, "its last activity is when it was last heard")
        val kept = Files.readString(dir.resolve("aaaa-1.json"))
        assertEquals(setOf("session_id", "name", "cwd", "head", "last_activity_ms"), keysOf(kept))
    }

    @Test
    fun `a session still registered is listed once, as the registry has it`() {
        listed = listOf(live("aaaa-1", at = now))
        sessions.list()

        val again = sessions.list().sessions

        assertEquals(listOf(SessionAvailability.LIVE), again.map { it.availability })
    }

    @Test
    fun `the history window decides what is kept, live, and its cut deletes the record with the rest`() {
        listed = listOf(live("old", at = now - 3 * DAY), live("new", at = now - HOUR))
        sessions.list()
        listed = emptyList()

        window = HistoryWindow(1, ZoneOffset.UTC)
        assertEquals(listOf("new"), sessions.list().sessions.map { it.sessionId }, "a shorter window hides at once")

        val cut = requireNotNull(window.cutoffMs(now))
        AgedFiles(dir).deleteBefore(cut)
        assertFalse(Files.exists(dir.resolve("old.json")), "the cut removes what the window no longer keeps")
        assertTrue(Files.exists(dir.resolve("new.json")))
    }

    @Test
    fun `an id that is not one Claude Code writes is never a file name`() {
        listed = listOf(live("../escape", at = now))
        sessions.list()

        assertFalse(Files.exists(state.resolve("escape.json")))
        assertFalse(Files.exists(dir) && Files.list(dir).use { it.count() } > 0)
    }

    private fun live(id: String, at: Long) = SessionRecord(
        sessionId = id,
        name = "tax-rounding",
        status = SessionStatus("idle", at),
        route = SessionRoute.Head("claude-splice"),
        availability = SessionAvailability.LIVE,
        process = SessionProcess(pid = 42, cwd = "/work/billing-api", startedAt = at - HOUR, updatedAt = at, null),
        client = SessionClient("claude-code", "2.1.296"),
    )

    private fun keysOf(json: String) = Regex("\"([a-z_]+)\":").findAll(json).map { it.groupValues[1] }.toSet()

    private companion object {
        const val HOUR = 3_600_000L
        const val DAY = 24 * HOUR
    }
}
