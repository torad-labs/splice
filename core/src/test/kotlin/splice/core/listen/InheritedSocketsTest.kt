package splice.core.listen

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.util.EnvReader

class InheritedSocketsTest {
    private val sockets = InheritedSockets(pid = 42)
    private val expected = setOf("control", "head-a", "head-b")

    private fun plan(vararg env: Pair<String, String>) = sockets.plan(EnvReader { env.toMap()[it] }, expected)

    @Test
    fun `no variables means every listener binds itself`() {
        assertEquals(InheritedPlan.None, plan())
    }

    @Test
    fun `descriptors meant for another process are left alone`() {
        assertEquals(InheritedPlan.None, plan("LISTEN_PID" to "7", "LISTEN_FDS" to "1", "LISTEN_FDNAMES" to "control"))
    }

    @Test
    fun `names decide which descriptor is which, whatever the order`() {
        val result = plan("LISTEN_PID" to "42", "LISTEN_FDS" to "3", "LISTEN_FDNAMES" to "head-b:control:head-a")
        assertEquals(InheritedPlan.Adopt(mapOf("head-b" to 3, "control" to 4, "head-a" to 5)), result)
    }

    @Test
    fun `a missing head is allowed and binds itself`() {
        val result = plan("LISTEN_PID" to "42", "LISTEN_FDS" to "1", "LISTEN_FDNAMES" to "control")
        assertEquals(InheritedPlan.Adopt(mapOf("control" to 3)), result)
    }

    @Test
    fun `every defect is listed at once`() {
        val result = plan("LISTEN_PID" to "42", "LISTEN_FDS" to "3", "LISTEN_FDNAMES" to "head-a:head-a:other")
        val reasons = (result as InheritedPlan.Refuse).reasons
        assertEquals(2, reasons.size, "duplicate and missing control, while an unknown name is no defect: $reasons")
        assertTrue(reasons.any { it.startsWith("duplicate") } && reasons.any { it.startsWith("missing") })
    }

    // Splice lays its sockets from splice.toml at each `splice restart`, so a head removed from the file between two
    // restarts can still be handed over after a crash or a reboot. That daemon boots, and the socket stays with systemd.
    @Test
    fun `a socket for a head splice toml no longer names is left with the manager, and the rest are adopted`() {
        val result = plan("LISTEN_PID" to "42", "LISTEN_FDS" to "3", "LISTEN_FDNAMES" to "control:head-gone:head-a")

        val adopt = result as? InheritedPlan.Adopt ?: error("a stale head's socket refused the boot: $result")
        assertEquals(mapOf("control" to 3, "head-a" to 5), adopt.fds)
        assertTrue(adopt.left.single().contains("'head-gone'"), "the stale socket is not named: ${adopt.left}")
    }

    @Test
    fun `a count that disagrees with the names is refused`() {
        val fewerNames = plan("LISTEN_PID" to "42", "LISTEN_FDS" to "2", "LISTEN_FDNAMES" to "control")
        val noNames = plan("LISTEN_PID" to "42", "LISTEN_FDS" to "1")
        val countNotANumber = plan("LISTEN_PID" to "42", "LISTEN_FDS" to "x", "LISTEN_FDNAMES" to "control")
        assertTrue(fewerNames is InheritedPlan.Refuse)
        assertTrue(noNames is InheritedPlan.Refuse)
        assertTrue(countNotANumber is InheritedPlan.Refuse)
    }
}
