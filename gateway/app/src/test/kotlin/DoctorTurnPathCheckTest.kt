// 2026-08-12: doctor must not certify the wedge as healthy. During the 91h outage every head
// counter was perfect — 4 ready, 0 failed — while not a single turn could complete, so a doctor
// that reads only counters prints "Everything checks out." straight through a total outage. These
// pin the turn-path row: FAIL naming the head when the probe says stalled, and NO row at all on a
// pre-probe daemon (absent evidence must not be reported as health).
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.app.cli.CheckStatus
import splice.app.cli.ControlPlaneClient
import splice.app.cli.turnPathCheck

class DoctorTurnPathCheckTest {

    private fun health(ok: Boolean?, stalled: List<String> = emptyList()) = ControlPlaneClient.HealthView(
        version = "kt-1",
        heads = 4,
        readyHeads = 4, // the wedge's own numbers: everything "ready" while nothing works
        failedHeads = 0,
        ok = ok,
        turnPathStalled = stalled,
    )

    @Test
    fun `a stalled head is a FAIL that names the head and the outage signature`() {
        val row = turnPathCheck(health(ok = false, stalled = listOf("claudex")))!!
        assertEquals(CheckStatus.FAIL, row.status, "4-ready-0-failed must not outrank a wedged turn path")
        assertTrue("claudex" in row.detail, "the operator needs the head named")
        assertTrue(row.fix != null, "every FAIL carries its fix command")
    }

    @Test
    fun `a live turn path is OK`() {
        assertEquals(CheckStatus.OK, turnPathCheck(health(ok = true))!!.status)
    }

    @Test
    fun `ok false with no named head still refuses to certify health`() {
        assertEquals(CheckStatus.WARN, turnPathCheck(health(ok = false))!!.status)
    }

    @Test
    fun `a pre-probe daemon gets no row - absent evidence is not evidence of health`() {
        assertNull(turnPathCheck(health(ok = null)), "an omitted field must not be read as healthy")
    }
}
