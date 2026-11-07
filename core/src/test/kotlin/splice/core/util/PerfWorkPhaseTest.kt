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
    fun `export ownership does not consume query slots and checkpoint intent blocks new exports`() = runTest {
        val export = PerfWorkPhase.tryExportWork()
        assertNotNull(export)
        val firstQuery = PerfWorkPhase.query()
        val secondQuery = PerfWorkPhase.query()
        firstQuery.close()
        secondQuery.close()

        val checkpoint = async(start = CoroutineStart.UNDISPATCHED) { PerfWorkPhase.checkpoint() }
        assertNull(PerfWorkPhase.tryExportWork())
        export?.close()
        checkpoint.await().close()
        assertNotNull(PerfWorkPhase.tryExportWork()?.also { it.close() })
    }

    @Test
    fun `checkpoint waits for active export before taking both query permits`() = runTest {
        val export = PerfWorkPhase.tryExportWork()
        assertNotNull(export)
        val checkpoint = async(start = CoroutineStart.UNDISPATCHED) { PerfWorkPhase.checkpoint() }
        assertFalse(checkpoint.isCompleted)
        assertNull(PerfWorkPhase.tryExportWork())

        export?.close()
        checkpoint.await().use {
            assertNull(PerfWorkPhase.tryCheckpoint())
            val blockedQuery = async(start = CoroutineStart.UNDISPATCHED) { PerfWorkPhase.query() }
            assertFalse(blockedQuery.isCompleted)
            it.close()
            blockedQuery.await().close()
        }
    }

    @Test
    fun `canceled checkpoint waiters release intent and leave export and query admission usable`() = runTest {
        requireNotNull(PerfWorkPhase.tryCheckpoint()).use {
            val behindCheckpoint = launch(start = CoroutineStart.UNDISPATCHED) {
                PerfWorkPhase.checkpoint().close()
            }
            behindCheckpoint.cancelAndJoin()
        }

        requireNotNull(PerfWorkPhase.tryExportWork()).use {
            val behindExport = launch(start = CoroutineStart.UNDISPATCHED) {
                PerfWorkPhase.checkpoint().close()
            }
            behindExport.cancelAndJoin()
        }

        requireNotNull(PerfWorkPhase.tryExportWork()).close()
        val firstQuery = PerfWorkPhase.query()
        val secondQuery = PerfWorkPhase.query()
        firstQuery.close()
        secondQuery.close()
    }

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
