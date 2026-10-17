// NEW: synthetic foreground callbacks exercise the actual registry's freshness and lifecycle policy.
package splice.sessions.registry

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.client.ForegroundToolCall
import splice.core.client.ForegroundToolPhase
import java.nio.file.Files
import java.nio.file.Path

private const val START_AT = 1_800_000_000_000L
private const val MINUTE_MS = 60_000L
private const val EXPIRY_MS = 120L * MINUTE_MS
private const val SESSION = "synthetic-session"

class ForegroundToolsTest {
    private var now = START_AT
    private var alive = true
    private val tools = ForegroundTools(clock = { now }, expiresAfterMs = EXPIRY_MS)

    private fun record(session: String, tool: String?, phase: ForegroundToolPhase, owner: String = "synthetic-owner") {
        tools.record(ForegroundToolCall(session, tool, phase, owner))
    }

    private fun registry(dir: Path): SessionRegistry {
        Files.writeString(
            dir.resolve("11.json"),
            """{"pid":11,"sessionId":"$SESSION","status":"busy","updatedAt":${START_AT - 12 * 60 * MINUTE_MS}}""",
        )
        return SessionRegistry(
            dir,
            routeOf = { SessionRoute.Unknown },
            pidAlive = { alive },
            clock = { now },
            foreground = tools,
        )
    }

    private fun availability(registry: SessionRegistry): SessionAvailability = registry.read().single().availability

    @Test
    fun `a foreground tool remains live beyond the stale window without provider traffic`(@TempDir dir: Path) {
        val registry = registry(dir)
        assertEquals(SessionAvailability.STALE, availability(registry))
        record(SESSION, "tool-a", ForegroundToolPhase.START)
        now += 45 * MINUTE_MS
        assertEquals(SessionAvailability.LIVE, availability(registry))
    }

    @Test
    fun `a parallel completion clears only its own opaque id`(@TempDir dir: Path) {
        val registry = registry(dir)
        record(SESSION, "tool-a", ForegroundToolPhase.START)
        record(SESSION, "tool-b", ForegroundToolPhase.START)
        record(SESSION, "tool-a", ForegroundToolPhase.END)
        now += 45 * MINUTE_MS
        assertEquals(SessionAvailability.LIVE, availability(registry))
        record(SESSION, "tool-b", ForegroundToolPhase.END)
        now += 31 * MINUTE_MS
        assertEquals(SessionAvailability.STALE, availability(registry))
    }

    @Test
    fun `an async end arriving first cannot be reopened by its late start`(@TempDir dir: Path) {
        val registry = registry(dir)
        record(SESSION, "tool-a", ForegroundToolPhase.END)
        now += 45 * MINUTE_MS
        record(SESSION, "tool-a", ForegroundToolPhase.START)
        assertEquals(SessionAvailability.STALE, availability(registry))
        record(SESSION, "tool-b", ForegroundToolPhase.START)
        assertEquals(SessionAvailability.LIVE, availability(registry))
    }

    @Test
    fun `a missing end expires and duplicate starts cannot renew its lease`(@TempDir dir: Path) {
        val registry = registry(dir)
        record(SESSION, "tool-a", ForegroundToolPhase.START)
        now += 60 * MINUTE_MS
        record(SESSION, "tool-a", ForegroundToolPhase.START)
        now += 61 * MINUTE_MS
        assertEquals(SessionAvailability.STALE, availability(registry))
    }

    @Test
    fun `session end clears activity and ignores late tool callbacks until a new session start`(@TempDir dir: Path) {
        val registry = registry(dir)
        record(SESSION, "tool-a", ForegroundToolPhase.START)
        record(SESSION, null, ForegroundToolPhase.SESSION_END)
        record(SESSION, "tool-b", ForegroundToolPhase.START)
        assertEquals(SessionAvailability.STALE, availability(registry))
        record(SESSION, null, ForegroundToolPhase.SESSION_START)
        record(SESSION, "tool-c", ForegroundToolPhase.START)
        now += 45 * MINUTE_MS
        assertEquals(SessionAvailability.LIVE, availability(registry))
    }

    @Test
    fun `GONE clears activity and asynchronous starts never revive an ended process`(@TempDir dir: Path) {
        val registry = registry(dir)
        record(SESSION, "tool-a", ForegroundToolPhase.START)
        alive = false
        assertEquals(SessionAvailability.GONE, availability(registry))
        alive = true
        record(SESSION, "tool-b", ForegroundToolPhase.START)
        assertEquals(SessionAvailability.STALE, availability(registry))
    }

    @Test
    fun `the same tool id in another session cannot end this session's call`(@TempDir dir: Path) {
        val registry = registry(dir)
        record(SESSION, "tool-a", ForegroundToolPhase.START)
        record("synthetic-other", "tool-a", ForegroundToolPhase.END)
        now += 45 * MINUTE_MS
        assertEquals(SessionAvailability.LIVE, availability(registry))
    }

    @Test
    fun `a dead duplicate registration cannot close the live resumed owner`(@TempDir dir: Path) {
        val registry = registry(dir)
        Files.writeString(
            dir.resolve("10.json"),
            """{"pid":10,"sessionId":"$SESSION","updatedAt":${START_AT - 43_200_000L}}""",
        )
        val resumed = SessionRegistry(
            dir,
            routeOf = { SessionRoute.Unknown },
            pidAlive = { it == 11L },
            clock = { now },
            foreground = tools,
        )
        record(SESSION, "tool-a", ForegroundToolPhase.START)
        now += 45 * MINUTE_MS
        assertEquals(SessionAvailability.LIVE, resumed.read().single { it.process.pid == 11L }.availability)
        assertEquals(SessionAvailability.LIVE, registry.read().single { it.process.pid == 11L }.availability)
    }

    @Test
    fun `completed calls cannot evict the only still-running foreground call`(@TempDir dir: Path) {
        val registry = registry(dir)
        record(SESSION, "long-tool", ForegroundToolPhase.START)
        repeat(4096) { record("synthetic-other", "ended-$it", ForegroundToolPhase.END) }
        now += 45 * MINUTE_MS
        assertEquals(SessionAvailability.LIVE, availability(registry))
    }

    @Test
    fun `old owner end cannot clear a resumed owner's foreground tool`(@TempDir dir: Path) {
        val registry = registry(dir)
        record(SESSION, null, ForegroundToolPhase.SESSION_START, "old-owner")
        record(SESSION, "old-tool", ForegroundToolPhase.START, "old-owner")
        record(SESSION, null, ForegroundToolPhase.SESSION_START, "new-owner")
        record(SESSION, "new-tool", ForegroundToolPhase.START, "new-owner")
        record(SESSION, null, ForegroundToolPhase.SESSION_END, "old-owner")
        now += 45 * MINUTE_MS
        assertEquals(SessionAvailability.LIVE, availability(registry))
        record(SESSION, null, ForegroundToolPhase.SESSION_END, "new-owner")
        assertEquals(SessionAvailability.STALE, availability(registry))
    }

    @Test
    fun `finished sessions cannot evict a still-running foreground session`(@TempDir dir: Path) {
        val registry = registry(dir)
        record(SESSION, "long-tool", ForegroundToolPhase.START)
        repeat(1024) { record("finished-session-$it", null, ForegroundToolPhase.SESSION_END) }
        now += 45 * MINUTE_MS
        assertEquals(SessionAvailability.LIVE, availability(registry))
    }

    @Test
    fun `session end arriving before a delayed first start cannot resurrect it`(@TempDir dir: Path) {
        val registry = registry(dir)
        record(SESSION, null, ForegroundToolPhase.SESSION_END)
        record(SESSION, "late-tool", ForegroundToolPhase.START)
        assertEquals(SessionAvailability.STALE, availability(registry))
    }
}
