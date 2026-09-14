import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.sessions.SessionAvailability
import splice.core.sessions.SessionRegistry
import java.nio.file.Files
import java.nio.file.Path

class SessionRegistryTest {

    private val now = 1_789_312_411_660L

    private fun write(dir: Path, pid: Long, body: String) {
        Files.writeString(dir.resolve("$pid.json"), body)
    }

    private fun registry(dir: Path, alive: Set<Long>) = SessionRegistry(
        sessionsDir = dir,
        headOf = { pid -> if (pid == 11L) "claudex" else null },
        pidAlive = { it in alive },
        clock = { now },
        staleAfterMs = 60_000L,
    )

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
        val rows = registry(dir, alive = setOf(21L)).read()
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
}
