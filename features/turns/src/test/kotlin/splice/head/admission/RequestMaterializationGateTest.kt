// NEW: weighted heap admission contracts, driven by held leases rather than timing.
package splice.head.admission

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import splice.upstream.TurnEnd

private const val MIB = 1024 * 1024L
private const val DAEMON_HEAP = 2048 * MIB

@OptIn(ExperimentalCoroutinesApi::class)
class RequestMaterializationGateTest {
    @Test
    fun `a small heap admits a tiny preflight body`() = runTest(UnconfinedTestDispatcher()) {
        val gate = RequestMaterializationGate(heapLimitBytes = 256 * MIB)
        assertEquals("preflight", gate.withLease(1024) { "preflight" })
    }

    @Test
    fun `a small heap reserves half for resident memory`() = runTest(UnconfinedTestDispatcher()) {
        val gate = RequestMaterializationGate(heapLimitBytes = 256 * MIB)
        val release = CompletableDeferred<Unit>()
        var entered = 0
        val requests = List(10) {
            async {
                gate.withLease(2 * MIB) {
                    entered++
                    release.await()
                }
            }
        }
        try {
            assertEquals(9, entered, "128 MiB admits nine 13 MiB materializations")
        } finally {
            release.complete(Unit)
            requests.forEach { it.await() }
        }
        assertEquals(10, entered)
    }

    @Test
    fun `an above-budget body enters when idle`() = runTest(UnconfinedTestDispatcher()) {
        val gate = RequestMaterializationGate(heapBudgetBytes = 13)
        assertEquals("exclusive", gate.withLease(3) { "exclusive" })
        assertEquals("overflow", gate.tryWithLease(Long.MAX_VALUE) { "overflow" })
    }

    @Test
    fun `an above-budget body waits for an empty gate and then runs alone`() =
        runTest(UnconfinedTestDispatcher()) {
            val gate = RequestMaterializationGate(heapBudgetBytes = 13)
            val releaseSmall = CompletableDeferred<Unit>()
            val releaseLarge = CompletableDeferred<Unit>()
            val small = async { gate.withLease(1) { releaseSmall.await() } }
            var entered = false
            val large = async {
                gate.withLease(3) {
                    entered = true
                    releaseLarge.await()
                }
            }
            try {
                assertFalse(large.isCompleted, "an oversized body must wait, not be refused")
                assertFalse(entered)
                assertNull(gate.tryWithLease(3) { "fast-fail must not wait or enter" })
                releaseSmall.complete(Unit)
                small.await()
                assertEquals(true, entered)
                assertNull(gate.tryWithLease(1) { "the oversized body must run alone" })
            } finally {
                releaseSmall.complete(Unit)
                releaseLarge.complete(Unit)
                small.await()
                large.await()
            }
            assertEquals("refunded", gate.tryWithLease(2) { "refunded" })
        }

    @Test
    fun `fifty everyday bodies can materialize together`() = runTest(UnconfinedTestDispatcher()) {
        val gate = RequestMaterializationGate(heapLimitBytes = DAEMON_HEAP)
        val release = CompletableDeferred<Unit>()
        var entered = 0
        val requests = List(50) {
            async {
                gate.withLease(2 * MIB) {
                    entered++
                    release.await()
                }
            }
        }
        try {
            assertEquals(50, entered, "small requests must not inherit the full-body count cap")
        } finally {
            release.complete(Unit)
            requests.forEach { it.await() }
        }
    }

    @Test
    fun `sixteen full bodies stay bounded by spare heap not request count`() = runTest(UnconfinedTestDispatcher()) {
        val gate = RequestMaterializationGate(heapLimitBytes = DAEMON_HEAP)
        val release = CompletableDeferred<Unit>()
        var entered = 0
        val requests = List(16) {
            async {
                gate.withLease(32 * MIB) {
                    entered++
                    release.await()
                }
            }
        }
        try {
            assertEquals(8, entered, "1664 MiB admits eight 208 MiB materializations")
            assertNull(gate.tryWithLease(1) { "no spare bytes" })
        } finally {
            release.complete(Unit)
            requests.forEach { it.await() }
        }
        assertEquals(16, entered, "queued full bodies eventually enter")
    }

    @Test
    fun `mixed bodies consume their own weight and fast failure never enters`() = runTest(UnconfinedTestDispatcher()) {
        val gate = RequestMaterializationGate(heapBudgetBytes = 208 * MIB)
        val release = CompletableDeferred<Unit>()
        val small = async { gate.withLease(2 * MIB) { release.await() } }
        var ran = false
        assertNull(gate.tryWithLease(32 * MIB) { ran = true })
        assertFalse(ran)
        assertEquals("small fits", gate.tryWithLease(2 * MIB) { "small fits" })
        release.complete(Unit)
        small.await()
        assertEquals("full fits", gate.tryWithLease(32 * MIB) { "full fits" })
    }

    @Test
    fun `second lease waits until the first releases enough bytes`() = runTest(UnconfinedTestDispatcher()) {
        val gate = RequestMaterializationGate(heapBudgetBytes = 13)
        val release = CompletableDeferred<Unit>()
        val order = mutableListOf<String>()
        val first = async {
            gate.withLease(2) {
                order += "first-enter"
                release.await()
                order += "first-exit"
            }
        }
        val second = async { gate.withLease(2) { order += "second-enter" } }
        try {
            assertEquals(listOf("first-enter"), order)
        } finally {
            release.complete(Unit)
            first.await()
            second.await()
        }
        assertEquals(listOf("first-enter", "first-exit", "second-enter"), order)
    }

    @Test
    fun `cancelling a waiting request does not consume heap bytes`() = runTest(UnconfinedTestDispatcher()) {
        val gate = RequestMaterializationGate(heapBudgetBytes = 13)
        val release = CompletableDeferred<Unit>()
        val first = async { gate.withLease(2) { release.await() } }
        val waiting = async { gate.withLease(2) { error("cancelled waiter ran") } }
        waiting.cancelAndJoin()
        release.complete(Unit)
        first.await()
        assertEquals("free", gate.tryWithLease(2) { "free" })
    }

    @Test
    fun `cancellation inside a lease refunds its complete weight`() = runTest(UnconfinedTestDispatcher()) {
        val gate = RequestMaterializationGate(heapBudgetBytes = 13)
        val first = async { gate.withLease(2) { CompletableDeferred<Unit>().await() } }
        first.cancelAndJoin()
        assertEquals("free", gate.tryWithLease(2) { "free" })
    }

    @Test
    fun `failed materialization refunds its complete weight`() = runTest(UnconfinedTestDispatcher()) {
        val gate = RequestMaterializationGate(heapBudgetBytes = 13)
        assertThrows(IllegalStateException::class.java) {
            kotlinx.coroutines.runBlocking { gate.withLease(2) { error("decode failed") } }
        }
        assertEquals("free", gate.tryWithLease(2) { "free" })
    }

    @Test
    fun `odd byte weights round upward and oversized fast-fail leases never wait`() =
        runTest(UnconfinedTestDispatcher()) {
            val gate = RequestMaterializationGate(heapBudgetBytes = 13)
            val release = CompletableDeferred<Unit>()
            val first = async { gate.withLease(1) { release.await() } }
            try {
                assertNull(gate.tryWithLease(1) { "two odd bodies exceed thirteen bytes" })
                assertNull(gate.tryWithLease(3) { "larger bodies cannot enter a busy gate" })
                assertNull(gate.tryWithLease(Long.MAX_VALUE) { "overflow cannot enter a busy gate" })
            } finally {
                release.complete(Unit)
                first.await()
            }
        }

    @Test
    fun `retained request trees hold admission through turn end and release only once`() =
        runTest(UnconfinedTestDispatcher()) {
            val gate = RequestMaterializationGate(heapBudgetBytes = 13)
            var end: TurnEnd? = null
            val owner = MaterializationOwner { end = it }
            assertEquals("prepared", gate.withLease(2, owner) { "prepared" })
            assertNull(gate.tryWithLease(2) { "prepared request still live" })
            requireNotNull(end).ended()
            requireNotNull(end).ended()
            val release = CompletableDeferred<Unit>()
            val first = async { gate.withLease(2) { release.await() } }
            try {
                assertNull(gate.tryWithLease(1) { "a double refund would admit" })
            } finally {
                release.complete(Unit)
                first.await()
            }
        }

    @Test
    fun `failed owner registration refunds its lease`() = runTest(UnconfinedTestDispatcher()) {
        val gate = RequestMaterializationGate(heapBudgetBytes = 13)
        val owner = MaterializationOwner { error("owner registration failed") }
        assertThrows(IllegalStateException::class.java) {
            kotlinx.coroutines.runBlocking { gate.withLease(2, owner) { "must not enter" } }
        }
        assertEquals("free", gate.tryWithLease(2) { "free" })
    }

    @Test
    fun `a configured budget cannot exceed spare JVM heap`() = runTest(UnconfinedTestDispatcher()) {
        val gate = RequestMaterializationGate(heapBudgetBytes = Long.MAX_VALUE, heapLimitBytes = DAEMON_HEAP)
        val release = CompletableDeferred<Unit>()
        val full = async { gate.withLease(256 * MIB) { release.await() } }
        try {
            assertNull(gate.tryWithLease(1) { "the spare heap is fully reserved" })
        } finally {
            release.complete(Unit)
            full.await()
        }
        assertEquals("fits", gate.tryWithLease(256 * MIB) { "fits" })
    }
}
