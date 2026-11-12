package splice.app

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.topology.Topology
import splice.core.util.EnvReader

class SocketHandoffTest {
    private val topology = Topology()

    private fun resolve(controlPort: Int, vararg env: Pair<String, String>) =
        SocketHandoff(EnvReader { env.toMap()[it] }, pid = 4242).resolve(topology, controlPort)

    @Test
    fun `a daemon started without a socket manager binds every port itself`() {
        assertEquals(Handoff.Nothing, resolve(7000))
    }

    @Test
    fun `descriptors addressed to a different process are not touched`() {
        assertEquals(
            Handoff.Nothing,
            resolve(7000, "LISTEN_PID" to "1", "LISTEN_FDS" to "1", "LISTEN_FDNAMES" to "control"),
        )
    }

    @Test
    fun `a hand-off without names is refused before anything is bound`() {
        val refused = resolve(7000, "LISTEN_PID" to "4242", "LISTEN_FDS" to "1") as Handoff.Refused
        assertTrue(refused.reasons.single().startsWith("malformed"))
    }
}
