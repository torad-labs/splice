// NEW: independent domains and handoffs spend one finite ledger.
package splice.core.memory

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class HeapBudgetTest {
    @Test
    fun `read views cannot expose root availability or root shutdown`() {
        val root = HeapBudget(256, 100)
        val view = root.readShare()
        try {
            assertFalse(
                HeapBudget::class.java.isAssignableFrom(view.javaClass),
                "a read view must not satisfy a root-only waiting gate",
            )
            assertFalse(view.javaClass.methods.any { it.name == "getAvailable" })
            assertFalse(AutoCloseable::class.java.isAssignableFrom(HeapBudget::class.java))
        } finally {
            view.close()
        }
    }

    @Test
    fun `process reservations leave proportional room for uncharged live objects and copies`() {
        val heap = 8L * 1024 * 1024 * 1024
        val budget = HeapBudget(heap)
        assertNull(budget.reserve(heap / 2 + 1), "a ledger cannot reserve most of the process heap")
        assertEquals(heap / 2, budget.limitBytes)
        assertEquals(budget.limitBytes, budget.available.value)
    }

    @Test
    fun `a saturated read family leaves room for a maximum sized ingress request`() {
        val root = HeapBudget(8L * 1024 * 1024 * 1024)
        val domain = root.readShare()
        val trace = requireNotNull(domain.reserve(domain.limitBytes))
        val request = root.reserve(HeapWeights.request(32L * 1024 * 1024))
        try {
            assertNotNull(request, "trace saturation cannot exclude a maximum sized request")
        } finally {
            request?.close()
            trace.close()
            domain.close()
        }
        assertEquals(root.limitBytes, root.available.value)
    }

    @Test
    fun `a read share refuses cumulative reservations and growth without taking the root's remaining heap`() {
        val root = HeapBudget(256, 100)
        val share = root.readShare()
        val first = requireNotNull(share.reserve(50))
        val second = requireNotNull(share.reserve(25))
        assertNull(share.reserve(1))
        assertFalse(first.resize(51))
        assertEquals(25, root.available.value)
        val unrelated = requireNotNull(root.reserve(25))
        assertNull(share.reserve(1))
        second.close()
        assertFalse(first.resize(76))
        assertTrue(first.resize(75))
        share.close()
        first.close()
        unrelated.close()
        assertEquals(100, root.available.value)
    }

    @Test
    fun `a closed read keeps split shared outputs charged until their last explicit owner releases`() {
        val root = HeapBudget(256, 100)
        val domain = root.readShare()
        val view = domain.readShare()
        val peak = requireNotNull(view.reserve(75))
        val retained = peak.split(20)
        val escaped = retained.share()
        peak.close()
        retained.close()
        view.close()
        assertNull(view.reserve(0), "closing ends admission, not escaped ownership")
        assertEquals(80, root.available.value)
        assertNull(domain.reserve(56), "the closed view still debits its family's quota")
        escaped.close()
        escaped.close()
        assertEquals(100, root.available.value)
        val reused = domain.reserve(75)
        assertNotNull(reused, "the released output restores the whole domain quota")
        reused?.close()
        assertEquals(100, root.available.value)
    }

    @Test
    fun `parallel reads cannot each spend their domain's same free quota`() {
        val root = HeapBudget(256, 100)
        val domain = root.readShare()
        val views = List(4) { domain.readShare() }
        val executor = Executors.newFixedThreadPool(8)
        try {
            val leases = (0 until 100).map { number ->
                executor.submit<HeapLease?> { views[number % views.size].reserve(7) }
            }.mapNotNull { it.get(10, TimeUnit.SECONDS) }
            assertEquals(10, leases.size)
            assertEquals(30, root.available.value)
            leases.forEach(HeapLease::close)
            views.forEach(HeapReadView::close)
            val reused = domain.reserve(75)
            assertNotNull(reused, "parallel output release restores the domain's whole quota")
            reused?.close()
            assertEquals(100, root.available.value)
        } finally {
            executor.shutdownNow()
        }
    }

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
    fun `empty and overflowing ingress sizes cannot escape metadata charging`() {
        val empty = HeapWeights.ingress(0)
        assertTrue(empty > HeapWeights.request(0))
        assertEquals(empty, HeapWeights.ingress(-1))
        assertEquals(Long.MAX_VALUE, HeapWeights.ingress(Long.MAX_VALUE))
        val heap = HeapBudget(Long.MAX_VALUE, empty + HeapWeights.CONNECTION_BYTES)
        val connection = requireNotNull(heap.reserve(HeapWeights.CONNECTION_BYTES))
        val request = requireNotNull(heap.reserve(empty))
        assertNull(heap.reserve(1))
        assertNull(heap.reserve(HeapWeights.ingress(Long.MAX_VALUE)))
        request.close()
        connection.close()
        assertEquals(heap.limitBytes, heap.available.value)
    }

    @Test
    fun `partitioning a peak never refunds its retained owner during handoff`() {
        val budget = HeapBudget(256, 100)
        val peak = requireNotNull(budget.reserve(100))
        val retained = peak.split(30)
        assertEquals(70, peak.bytes)
        assertEquals(30, retained.bytes)
        assertNull(budget.reserve(1))
        val escaped = retained.share()
        peak.close()
        assertEquals(70, budget.available.value)
        retained.close()
        assertEquals(70, budget.available.value)
        escaped.close()
        assertEquals(100, budget.available.value)
    }

    @Test
    fun `a shared peak refuses partition without changing either ownership or capacity`() {
        val budget = HeapBudget(256, 100)
        val peak = requireNotNull(budget.reserve(100))
        val shared = peak.share()
        assertThrows(IllegalStateException::class.java) { peak.split(30) }
        assertEquals(100, peak.bytes)
        assertEquals(0, budget.available.value)
        shared.close()
        assertThrows(IllegalArgumentException::class.java) { peak.split(101) }
        assertThrows(IllegalArgumentException::class.java) { peak.split(-1) }
        assertEquals(100, peak.bytes)
        peak.close()
        assertEquals(100, budget.available.value)
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
