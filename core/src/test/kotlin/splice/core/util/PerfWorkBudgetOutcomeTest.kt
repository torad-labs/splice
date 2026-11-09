package splice.core.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class PerfWorkBudgetOutcomeTest {
    @Test
    fun `admission carries its own refusal after the process latest refusal changes`() {
        val first = PerfWorkBudget.acquireWithOutcome(Long.MAX_VALUE)
        val captured = requireNotNull(first.refusal)
        PerfWorkBudget.acquireWithOutcome(Long.MAX_VALUE - 1)

        assertNull(first.lease)
        assertEquals(Long.MAX_VALUE, captured.requestedBytes)
        assertEquals(Long.MAX_VALUE, first.refusal.requestedBytes)
        assertEquals(captured.accounting, first.refusal.accounting)
    }

    @Test
    fun `transfer outcome reports the failed marginal reservation and exact accounting`() {
        val lease = requireNotNull(PerfWorkBudget.acquireWithOutcome(0).lease)
        try {
            val outcome = lease.transferWithOutcome(Long.MAX_VALUE)
            assertNull(outcome.lease)
            assertNotNull(outcome.refusal)
            assertEquals(Long.MAX_VALUE, outcome.refusal?.requestedBytes)
        } finally {
            lease.close()
        }
    }
}
