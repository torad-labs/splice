package splice.core.util

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class PerfWorkPhaseTest {
    @Test
    fun `cancelling exclusive waiters releases partial acquisition and keeps two query slots`() = runTest {
        PerfWorkPhase.query().use {
            val first = launch(start = CoroutineStart.UNDISPATCHED) {
                PerfWorkPhase.checkpoint().use { error("reader still owns a phase") }
            }
            val second = launch(start = CoroutineStart.UNDISPATCHED) {
                PerfWorkPhase.checkpoint().use { error("reader still owns a phase") }
            }
            val query = async(start = CoroutineStart.UNDISPATCHED) { PerfWorkPhase.query() }
            try {
                assertFalse(query.isCompleted)
                second.cancelAndJoin()
                first.cancelAndJoin()
                query.await().use { assertNull(PerfWorkPhase.tryCheckpoint()) }
            } finally {
                second.cancelAndJoin()
                first.cancelAndJoin()
                if (query.isCompleted && !query.isCancelled) query.await().close() else query.cancelAndJoin()
            }
        }
        val restored = PerfWorkPhase.tryCheckpoint()
        assertNotNull(restored)
        restored?.close()
    }

    @Test
    fun `transferred checkpoint outlives its producer and releases all permits exactly once`() = runTest {
        val producer = PerfWorkPhase.checkpoint()
        val accepted = producer.transferCheckpoint()
        producer.close()
        try {
            assertNull(PerfWorkPhase.tryCheckpoint())
            val waiting = async(start = CoroutineStart.UNDISPATCHED) { PerfWorkPhase.query() }
            try {
                assertFalse(waiting.isCompleted)
                accepted.close()
                waiting.await().close()
                accepted.close()
            } finally {
                if (waiting.isCompleted && !waiting.isCancelled) waiting.await().close() else waiting.cancelAndJoin()
            }
        } finally {
            accepted.close()
        }
        val restored = PerfWorkPhase.tryCheckpoint()
        assertNotNull(restored)
        restored?.close()
    }
}
