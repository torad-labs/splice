package splice.sessions.registry

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class SessionRegistryTest {

    private val now = 1_789_312_411_660L

    private fun write(dir: Path, pid: Long, body: String) {
        Files.writeString(dir.resolve("$pid.json"), body)
    }

    private val hostDomain = "linux:652c492b8aae4140b9d078835b2ed12a:pid:[4026531836]"

    private fun identity(
        procStarts: Map<Long, String> = emptyMap(),
        domain: String? = hostDomain,
    ) = object : PidIdentity {
        override fun hostDomain(): String? = domain
        override fun procStart(pid: Long): String? = procStarts[pid]
    }

    private fun registry(
        dir: Path,
        alive: Set<Long>,
        started: Map<Long, Long> = emptyMap(),
        identity: PidIdentity = identity(),
    ) = SessionRegistry(
        sessionsDir = dir,
        headOf = { pid -> if (pid == 11L) "claudex" else null },
        pidAlive = { it in alive },
        pidStartedAt = { started[it] },
        clock = { now },
        staleAfterMs = 60_000L,
        identity = identity,
    )

    /** Claude Code writes pidDomain and procStart; they decide before the start-time tolerance does. */
    @Test
    fun `another domain's pid, or a pid whose start time moved, is GONE whatever process holds it - review 2026-09-14`(
        @TempDir dir: Path,
    ) {
        val other = "linux:ffffffffffffffffffffffffffffffff:pid:[4026531836]"
        val host = hostDomain
        write(dir, 11, """{"pid":11,"updatedAt":$now,"startedAt":$now,"pidDomain":"$host","procStart":"187740"}""")
        write(dir, 12, """{"pid":12,"updatedAt":$now,"startedAt":$now,"pidDomain":"$other","procStart":"187740"}""")
        write(dir, 13, """{"pid":13,"updatedAt":$now,"startedAt":$now,"pidDomain":"$host","procStart":"100"}""")
        write(dir, 14, """{"pid":14,"updatedAt":$now,"startedAt":$now,"pidDomain":"$host","procStart":"555"}""")
        val starts = mapOf(11L to "187740", 12L to "187740", 13L to "187740")
        val rows = registry(dir, alive = setOf(11L, 12L, 13L, 14L), identity = identity(starts)).read()
            .associateBy { it.pid }
        assertEquals(SessionAvailability.LIVE, rows.getValue(11L).availability, "same domain, same start")
        assertEquals(SessionAvailability.GONE, rows.getValue(12L).availability, "a container's pid: not this host's")
        assertEquals(SessionAvailability.GONE, rows.getValue(13L).availability, "the pid was reused since")
        assertEquals(SessionAvailability.LIVE, rows.getValue(14L).availability, "start unreadable here: trusted")
        val noHost = registry(dir, alive = setOf(12L), identity = identity(starts, domain = null)).read()
        val unjudged = noHost.single { it.pid == 12L }.availability
        assertEquals(SessionAvailability.LIVE, unjudged, "no host domain: not judged")
    }

    @Test
    fun `live, stale and gone are derived from the pid and updatedAt, never from status`(@TempDir dir: Path) {
        write(
            dir,
            11,
            """{"pid":11,"sessionId":"s-11","name":"alpha","status":"busy","updatedAt":${now - 5_000},"cwd":"/w/a"}""",
        )
        write(dir, 12, """{"pid":12,"sessionId":"s-12","name":"beta","status":"idle","updatedAt":${now - 600_000}}""")
        write(dir, 13, """{"pid":13,"sessionId":"s-13","name":"gamma","status":"busy","updatedAt":${now - 1_000}}""")
        val rows = registry(dir, alive = setOf(11L, 12L)).read().associateBy { it.pid }
        assertEquals(SessionAvailability.LIVE, rows.getValue(11L).availability)
        assertEquals(SessionAvailability.STALE, rows.getValue(12L).availability)
        assertEquals(SessionAvailability.GONE, rows.getValue(13L).availability)
        assertEquals("busy", rows.getValue(13L).status)
    }

    @Test
    fun `a live pid whose process is younger than the registration is a reused pid, so GONE`(@TempDir dir: Path) {
        val day = 86_400_000L
        write(dir, 11, """{"pid":11,"updatedAt":$now,"startedAt":${now - 10_000}}""")
        write(dir, 12, """{"pid":12,"updatedAt":$now,"startedAt":${now - day}}""")
        write(dir, 13, """{"pid":13,"updatedAt":$now,"startedAt":${now - day}}""")
        val started = mapOf(11L to now - 20_000, 12L to now - 60_000)
        val rows = registry(dir, alive = setOf(11L, 12L, 13L), started = started).read().associateBy { it.pid }
        assertEquals(SessionAvailability.LIVE, rows.getValue(11L).availability, "process older than the session")
        assertEquals(SessionAvailability.GONE, rows.getValue(12L).availability, "process a day younger: reused pid")
        assertEquals(SessionAvailability.LIVE, rows.getValue(13L).availability, "unknown start time: trusted")
    }

    @Test
    fun `the head join applies to sessions that still exist and reads unknown otherwise`(@TempDir dir: Path) {
        write(dir, 11, """{"pid":11,"updatedAt":$now}""")
        write(dir, 12, """{"pid":12,"updatedAt":$now}""")
        val rows = registry(dir, alive = setOf(11L, 12L)).read().associateBy { it.pid }
        assertEquals("claudex", rows.getValue(11L).head)
        assertNull(rows.getValue(12L).head)
    }

    @Test
    fun `missing fields are tolerated and malformed files are skipped`(@TempDir dir: Path) {
        write(dir, 21, """{"pid":21}""")
        write(dir, 22, """{"sessionId":"no-pid","updatedAt":$now}""")
        Files.writeString(dir.resolve("23.json"), "{ not json")
        Files.writeString(dir.resolve("24.key"), "ignored")
        // A runaway file in the shared directory is skipped by size, never read whole on every poll.
        val huge = """{"pid":25,"updatedAt":$now,"name":"""" + "x".repeat(70_000) + "\"}"
        Files.writeString(dir.resolve("25.json"), huge)
        val rows = registry(dir, alive = setOf(21L, 25L)).read()
        assertEquals(2, rows.size)
        val bare = rows.single { it.pid == 21L }
        assertEquals(SessionAvailability.STALE, bare.availability)
        assertNull(bare.name)
        assertNull(bare.address)
        assertEquals(SessionAvailability.GONE, rows.single { it.pid == null }.availability)
    }

    @Test
    fun `a non-positive pid is gone for that row only and never aborts the listing`(@TempDir dir: Path) {
        write(dir, 0, """{"pid":0,"name":"zero","updatedAt":$now}""")
        write(dir, 41, """{"pid":-5,"name":"neg","updatedAt":$now}""")
        write(dir, 42, """{"pid":42,"name":"real","updatedAt":$now}""")
        val rows = SessionRegistry(sessionsDir = dir, headOf = { null }, clock = { now }).read()
        assertEquals(3, rows.size, "the default liveness probe tolerates every row")
        rows.filter { (it.pid ?: 0L) <= 0L }.forEach {
            assertEquals(SessionAvailability.GONE, it.availability, it.name)
        }
    }

    @Test
    fun `rows come newest activity first and carry the messaging address`(@TempDir dir: Path) {
        write(dir, 31, """{"pid":31,"updatedAt":${now - 10},"messagingSocketPath":"/run/user/1000/cc-socks/31.sock"}""")
        write(dir, 32, """{"pid":32,"updatedAt":${now - 1}}""")
        val rows = registry(dir, alive = setOf(31L, 32L)).read()
        assertEquals(listOf(32L, 31L), rows.map { it.pid })
        assertEquals("uds:/run/user/1000/cc-socks/31.sock", rows[1].address)
    }

    @Test
    fun `a directory that cannot be listed is an error, a missing one is genuinely no sessions`(@TempDir dir: Path) {
        val file = Files.writeString(dir.resolve("sessions"), "not a directory")
        val blocked = registry(file, alive = emptySet()).list()
        assertTrue(blocked.sessions.isEmpty())
        assertTrue(checkNotNull(blocked.error).contains("sessions"), "names the directory: ${blocked.error}")
        val absent = registry(dir.resolve("never"), alive = emptySet()).list()
        assertTrue(absent.sessions.isEmpty())
        assertNull(absent.error, "absence is quiet")
    }
}
