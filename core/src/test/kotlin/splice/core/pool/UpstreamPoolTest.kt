// Server mode (docs/specs/server-mode.md, F4): where a provider's pool sends each turn, as an operator would see it
// in the upstream list and a person in which server answered.
package splice.core.pool

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.failure.Decline
import splice.core.topology.UpstreamConfig
import splice.core.util.LogSink

/** A place [lease] must give: every test here but the ones that expect none asks where room is left. */
private fun UpstreamPool.taken(
    preferred: String? = null,
    session: String? = null,
    turn: String? = null,
): UpstreamLease = checkNotNull(lease(preferred, session, turn)) { "the pool gave no place" }

class UpstreamPoolTest {
    private val logged = mutableListOf<String>()

    private fun pool(vararg names: String): UpstreamPool =
        UpstreamPool("box", names.map { UpstreamConfig(it, "http://$it.lan:8080/v1") }, LogSink { logged += it })

    private fun inflight(pool: UpstreamPool): Map<String, Int> = pool.readings().associate { it.name to it.inflight }

    @Test
    fun `turns in flight together are spread over the servers, the first listed first`() {
        val pool = pool("a", "b", "c")
        assertEquals(listOf("a", "b", "c", "a", "b", "c", "a"), List(7) { pool.taken().name })
        assertEquals(mapOf("a" to 3, "b" to 2, "c" to 2), inflight(pool))
    }

    @Test
    fun `a turn that ended frees its place, once however often it is given back`() {
        val pool = pool("a", "b")
        val first = pool.taken()
        val second = pool.taken()
        first.release()
        first.release()
        assertEquals(mapOf("a" to 0, "b" to 1), inflight(pool))
        assertEquals("a", pool.taken().name, "the server whose turn ended is the least busy again")
        second.release()
        assertEquals(mapOf("a" to 1, "b" to 0), inflight(pool))
    }

    @Test
    fun `a person's preferred server takes their turns however busy, while it is healthy`() {
        val pool = pool("a", "b")
        assertEquals(listOf("b", "b", "b"), List(3) { pool.taken("b").name })
        pool.checked("b", healthy = false, said = "HTTP 503")
        assertEquals("a", pool.taken("b").name, "a preferred server that is down gives its person's turn to another")
        pool.checked("b", healthy = true, said = "HTTP 200")
        assertEquals("b", pool.taken("b").name)
        assertEquals("a", pool.taken("nobody").name, "a name no server has is balanced like no preference")
    }

    @Test
    fun `a session's later turns go back to the server that served it, however busy, and new sessions are balanced`() {
        val pool = pool("a", "b")
        val first = listOf("s1", "s2", "s3", "s4").associateWith { pool.taken(session = it).name }
        assertEquals(mapOf("s1" to "a", "s2" to "b", "s3" to "a", "s4" to "b"), first)
        repeat(3) { pool.taken(session = "s1") }
        assertEquals(mapOf("a" to 5, "b" to 2), inflight(pool))
        assertEquals("a", pool.taken(session = "s3").name, "b has less in flight, but s3's prompt is cached on a")
        assertEquals("b", pool.taken(session = "s5").name, "a session not seen before goes to the least busy")
        assertEquals("b", pool.taken().name, "a turn that names no session is balanced as before")
    }

    @Test
    fun `a session whose server went down moves once, and stays where it moved when the old one is back`() {
        val pool = pool("a", "b")
        assertEquals("a", pool.taken(session = "mover").name)
        pool.checked("a", healthy = false, said = "HTTP 503")
        assertEquals("b", pool.taken(session = "mover").name)
        pool.checked("a", healthy = true, said = "HTTP 200")
        assertEquals(listOf("b", "b", "b"), List(3) { pool.taken(session = "mover").name })
    }

    @Test
    fun `a session's server outranks its person's preferred one, which places only a new session`() {
        val pool = pool("a", "b")
        assertEquals("a", pool.taken(session = "old").name)
        assertEquals("a", pool.taken("b", session = "old").name, "its prompt is cached on a")
        assertEquals("b", pool.taken("b", session = "new").name)
    }

    @Test
    fun `a turn sent while no server is up leaves its session's server as it was`() {
        val pool = pool("a", "b")
        assertEquals("b", pool.taken("b", session = "s").name)
        pool.checked("a", healthy = false, said = "HTTP 503")
        pool.checked("b", healthy = false, said = "HTTP 503")
        pool.taken(session = "s")
        pool.taken(session = "s")
        pool.checked("a", healthy = true, said = "HTTP 200")
        pool.checked("b", healthy = true, said = "HTTP 200")
        assertEquals("b", pool.taken(session = "s").name, "no down server served it, so its home is still b")
    }

    @Test
    fun `a server that failed its health check takes no turn until a check passes`() {
        val pool = pool("a", "b")
        pool.checked("a", healthy = false, said = "HTTP 503")
        assertEquals(listOf("b", "b", "b"), List(3) { pool.taken().name })
        assertEquals(listOf(false, true), pool.readings().map { it.healthy })
        pool.checked("a", healthy = true, said = "HTTP 200")
        assertEquals("a", pool.taken().name)
    }

    @Test
    fun `a server that refused a connection is out for every turn at once, before any health check`() {
        val pool = pool("a", "b")
        val turn = pool.taken()
        assertEquals("a", turn.name)
        turn.refused()
        assertFalse(turn.healthy)
        turn.release()
        assertEquals(listOf("b", "b"), List(2) { pool.taken().name })
        pool.checked("a", healthy = true, said = "HTTP 200")
        assertTrue(pool.readings().first().healthy, "only a health check that passes brings it back")
    }

    @Test
    fun `with no server healthy the pool says none is up, and the log said which went and why`() {
        val pool = pool("a", "b")
        assertTrue(pool.up)
        pool.checked("a", healthy = false, said = "HTTP 503")
        assertTrue(pool.up)
        pool.taken().refused()
        assertFalse(pool.up)
        pool.checked("a", healthy = false, said = "HTTP 503")
        assertEquals(2, logged.size, "a server that stays down is said once: $logged")
        assertTrue(logged[0].contains("upstream a failed its health check (HTTP 503)"), logged[0])
        assertTrue(logged[1].contains("upstream b took no connection") && logged[1].contains("none is up"), logged[1])
        pool.checked("b", healthy = true, said = "HTTP 200")
        assertTrue(pool.up)
        assertTrue(logged.last().contains("upstream b passed its health check"), logged.last())
    }

    @Test
    fun `under a per-server cap a full preferred server gives a new session's turn to the least busy with room`() {
        val pool = pool("a", "b", "c")
        listOf("t1", "t2", "t3").forEach { assertTrue(pool.reserve(it, 1)) }
        assertEquals("b", pool.taken("b", turn = "t1").name)
        assertEquals("a", pool.taken("b", turn = "t2").name, "b is full: the least busy with room")
        assertEquals("c", pool.taken("b", turn = "t3").name)
        assertEquals(mapOf("a" to 1, "b" to 1, "c" to 1), inflight(pool), "no server went past its cap")
    }

    @Test
    fun `a session whose server is full goes to the least busy healthy server with room and keeps its home`() {
        val pool = pool("a", "b", "c")
        assertTrue(pool.reserve("first", 2))
        val home = pool.taken(session = "s", turn = "first")
        assertEquals("a", home.name)
        pool.taken("a")
        pool.taken("b")
        // a, the session's home, is full at 2; b, which its person prefers, holds 1; c holds none.
        assertEquals(mapOf("a" to 2, "b" to 1, "c" to 0), inflight(pool))
        assertTrue(pool.reserve("second", 2))
        assertEquals("c", pool.taken("b", session = "s", turn = "second").name, "the least busy with room, not b")
        home.release()
        assertTrue(pool.reserve("third", 2))
        assertEquals("a", pool.taken(session = "s", turn = "third").name, "its prompt is cached on a, which has room")
    }

    @Test
    fun `turns admitted before any of them leased never add up past the cap, a lease already taken included`() {
        val pool = pool("a", "b")
        // A turn of this machine's own holds a; one place is left, on b.
        pool.taken()
        assertTrue(pool.reserve("first", 1), "b has a place")
        assertFalse(pool.reserve("second", 1), "the one place left is the first turn's, though it has not leased")
        assertEquals("b", pool.taken(turn = "first").name)
        assertEquals(mapOf("a" to 1, "b" to 1), inflight(pool), "no server went past its cap")
    }

    @Test
    fun `a lease that holds no reserved place never takes the one place left for a turn that does`() {
        val pool = pool("a", "b")
        // A turn of this machine's own holds a; the one place left, on b, is reserved for a turn server mode admitted.
        pool.taken()
        assertTrue(pool.reserve("admitted", 1))
        assertNull(pool.lease(), "a lease with no reserved place took the admitted turn's place on b")
        assertEquals(Decline.UpstreamsFull("box", 1), pool.full())
        assertEquals("b", pool.taken(turn = "admitted").name)
        assertEquals(mapOf("a" to 1, "b" to 1), inflight(pool), "no server went past its cap")
    }

    @Test
    fun `a reserved turn whose place went down with its server is given none past the cap`() {
        val pool = pool("a", "b")
        pool.taken()
        assertTrue(pool.reserve("admitted", 1), "b has a place")
        pool.checked("b", healthy = false, said = "HTTP 503")
        assertNull(pool.lease(turn = "admitted"), "the only healthy server, a, is full: the turn must be sent nowhere")
        assertEquals(mapOf("a" to 1, "b" to 0), inflight(pool), "no server went past its cap")
    }

    @Test
    fun `a place reserved and never leased is free again once its turn ends`() {
        val pool = pool("a")
        assertTrue(pool.reserve("refused-at-its-head", 1))
        assertFalse(pool.reserve("next", 1))
        pool.unreserve("refused-at-its-head")
        assertTrue(pool.reserve("next", 1), "the turn that never leased still held its place")
    }

    @Test
    fun `the list shows each server as the file spells it, and a turn is sent under its API root`() {
        val spelled = UpstreamConfig("gpu-1", "http://10.0.0.21:8080/v1/")
        val pool = UpstreamPool("box", listOf(spelled), LogSink { })
        assertEquals(listOf(UpstreamReading("box", "gpu-1", "http://10.0.0.21:8080/v1/", true, 0)), pool.readings())
        assertEquals("http://10.0.0.21:8080/v1", pool.taken().url)
        assertEquals("http://10.0.0.21:8080/health", spelled.healthUrl)
        assertEquals("http://gpu-2.lan:9000/health", UpstreamConfig("gpu-2", "http://gpu-2.lan:9000").healthUrl)
    }
}
