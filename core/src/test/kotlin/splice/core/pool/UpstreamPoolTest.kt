// Server mode (docs/specs/server-mode.md, F4): where a provider's pool sends each turn, as an operator would see it
// in the upstream list and a person in which server answered.
package splice.core.pool

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.topology.UpstreamConfig
import splice.core.util.LogSink

class UpstreamPoolTest {
    private val logged = mutableListOf<String>()

    private fun pool(vararg names: String): UpstreamPool =
        UpstreamPool("box", names.map { UpstreamConfig(it, "http://$it.lan:8080/v1") }, LogSink { logged += it })

    private fun inflight(pool: UpstreamPool): Map<String, Int> = pool.readings().associate { it.name to it.inflight }

    @Test
    fun `turns in flight together are spread over the servers, the first listed first`() {
        val pool = pool("a", "b", "c")
        assertEquals(listOf("a", "b", "c", "a", "b", "c", "a"), List(7) { pool.lease().name })
        assertEquals(mapOf("a" to 3, "b" to 2, "c" to 2), inflight(pool))
    }

    @Test
    fun `a turn that ended frees its place, once however often it is given back`() {
        val pool = pool("a", "b")
        val first = pool.lease()
        val second = pool.lease()
        first.release()
        first.release()
        assertEquals(mapOf("a" to 0, "b" to 1), inflight(pool))
        assertEquals("a", pool.lease().name, "the server whose turn ended is the least busy again")
        second.release()
        assertEquals(mapOf("a" to 1, "b" to 0), inflight(pool))
    }

    @Test
    fun `a person's preferred server takes their turns however busy, while it is healthy`() {
        val pool = pool("a", "b")
        assertEquals(listOf("b", "b", "b"), List(3) { pool.lease("b").name })
        pool.checked("b", healthy = false, said = "HTTP 503")
        assertEquals("a", pool.lease("b").name, "a preferred server that is down gives its person's turn to another")
        pool.checked("b", healthy = true, said = "HTTP 200")
        assertEquals("b", pool.lease("b").name)
        assertEquals("a", pool.lease("nobody").name, "a name no server has is balanced like no preference")
    }

    @Test
    fun `a session's later turns go back to the server that served it, however busy, and new sessions are balanced`() {
        val pool = pool("a", "b")
        val first = listOf("s1", "s2", "s3", "s4").associateWith { pool.lease(session = it).name }
        assertEquals(mapOf("s1" to "a", "s2" to "b", "s3" to "a", "s4" to "b"), first)
        repeat(3) { pool.lease(session = "s1") }
        assertEquals(mapOf("a" to 5, "b" to 2), inflight(pool))
        assertEquals("a", pool.lease(session = "s3").name, "b has less in flight, but s3's prompt is cached on a")
        assertEquals("b", pool.lease(session = "s5").name, "a session not seen before goes to the least busy")
        assertEquals("b", pool.lease().name, "a turn that names no session is balanced as before")
    }

    @Test
    fun `a session whose server went down moves once, and stays where it moved when the old one is back`() {
        val pool = pool("a", "b")
        assertEquals("a", pool.lease(session = "mover").name)
        pool.checked("a", healthy = false, said = "HTTP 503")
        assertEquals("b", pool.lease(session = "mover").name)
        pool.checked("a", healthy = true, said = "HTTP 200")
        assertEquals(listOf("b", "b", "b"), List(3) { pool.lease(session = "mover").name })
    }

    @Test
    fun `a session's server outranks its person's preferred one, which places only a new session`() {
        val pool = pool("a", "b")
        assertEquals("a", pool.lease(session = "old").name)
        assertEquals("a", pool.lease("b", session = "old").name, "its prompt is cached on a")
        assertEquals("b", pool.lease("b", session = "new").name)
    }

    @Test
    fun `a turn sent while no server is up leaves its session's server as it was`() {
        val pool = pool("a", "b")
        assertEquals("b", pool.lease("b", session = "s").name)
        pool.checked("a", healthy = false, said = "HTTP 503")
        pool.checked("b", healthy = false, said = "HTTP 503")
        pool.lease(session = "s")
        pool.lease(session = "s")
        pool.checked("a", healthy = true, said = "HTTP 200")
        pool.checked("b", healthy = true, said = "HTTP 200")
        assertEquals("b", pool.lease(session = "s").name, "no down server served it, so its home is still b")
    }

    @Test
    fun `a server that failed its health check takes no turn until a check passes`() {
        val pool = pool("a", "b")
        pool.checked("a", healthy = false, said = "HTTP 503")
        assertEquals(listOf("b", "b", "b"), List(3) { pool.lease().name })
        assertEquals(listOf(false, true), pool.readings().map { it.healthy })
        pool.checked("a", healthy = true, said = "HTTP 200")
        assertEquals("a", pool.lease().name)
    }

    @Test
    fun `a server that refused a connection is out for every turn at once, before any health check`() {
        val pool = pool("a", "b")
        val turn = pool.lease()
        assertEquals("a", turn.name)
        turn.refused()
        assertFalse(turn.healthy)
        turn.release()
        assertEquals(listOf("b", "b"), List(2) { pool.lease().name })
        pool.checked("a", healthy = true, said = "HTTP 200")
        assertTrue(pool.readings().first().healthy, "only a health check that passes brings it back")
    }

    @Test
    fun `with no server healthy the pool says none is up, and the log said which went and why`() {
        val pool = pool("a", "b")
        assertTrue(pool.up)
        pool.checked("a", healthy = false, said = "HTTP 503")
        assertTrue(pool.up)
        pool.lease().refused()
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
    fun `the list shows each server as the file spells it, and a turn is sent under its API root`() {
        val spelled = UpstreamConfig("gpu-1", "http://10.0.0.21:8080/v1/")
        val pool = UpstreamPool("box", listOf(spelled), LogSink { })
        assertEquals(listOf(UpstreamReading("box", "gpu-1", "http://10.0.0.21:8080/v1/", true, 0)), pool.readings())
        assertEquals("http://10.0.0.21:8080/v1", pool.lease().url)
        assertEquals("http://10.0.0.21:8080/health", spelled.healthUrl)
        assertEquals("http://gpu-2.lan:9000/health", UpstreamConfig("gpu-2", "http://gpu-2.lan:9000").healthUrl)
    }
}
