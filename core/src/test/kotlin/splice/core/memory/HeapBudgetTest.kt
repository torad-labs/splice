// NEW: independent domains and handoffs spend one finite ledger.
package splice.core.memory

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class HeapBudgetTest {
    @Test
    fun `domains cannot each spend the same free heap`() {
        val budget = HeapBudget(heapLimitBytes = 256, budgetBytes = 100)
        val state = requireNotNull(budget.reserve(40))
        val trace = requireNotNull(budget.reserve(30))
        assertNull(budget.reserve(31))
        val request = requireNotNull(budget.reserve(30))
        assertEquals(0, budget.available.value)
        state.close()
        trace.close()
        request.close()
        assertEquals(100, budget.available.value)
    }

    @Test
    fun `handoff never refunds while another owner retains the same objects`() {
        val budget = HeapBudget(256, 100)
        val source = requireNotNull(budget.reserve(100))
        val detached = source.share()
        source.close()
        source.close()
        assertNull(budget.reserve(1))
        detached.close()
        detached.close()
        assertEquals(100, budget.available.value)
    }

    @Test
    fun `failed growth preserves ownership and shrinking returns only the difference`() {
        val budget = HeapBudget(256, 100)
        val first = requireNotNull(budget.reserve(60))
        val second = requireNotNull(budget.reserve(40))
        assertFalse(first.resize(61))
        assertEquals(60, first.bytes)
        assertTrue(second.resize(20))
        assertTrue(first.resize(80))
        assertNull(budget.reserve(1))
        first.close()
        second.close()
        assertEquals(100, budget.available.value)
        assertNull(budget.reserve(Long.MAX_VALUE))
        assertEquals(Long.MAX_VALUE, HeapWeights.request(Long.MAX_VALUE))
    }

    @Test
    fun `concurrent admissions cannot overspend`() {
        val budget = HeapBudget(256, 100)
        val executor = Executors.newFixedThreadPool(8)
        try {
            val leases = (1..100).map { executor.submit<HeapLease?> { budget.reserve(7) } }
                .mapNotNull { it.get(10, TimeUnit.SECONDS) }
            assertEquals(14, leases.size)
            assertEquals(2, budget.available.value)
            leases.forEach(HeapLease::close)
            assertEquals(100, budget.available.value)
        } finally {
            executor.shutdownNow()
        }
    }
}
