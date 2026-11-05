package splice.provider.codex

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.provider.codex.state.CodeModeKeyLocks
import splice.provider.codex.state.CodeModeRegistryAccess
import splice.provider.codex.state.CodeModeStateDirectory
import splice.provider.codex.state.CodeModeStateJournal
import splice.provider.codex.state.save.CodeModeStateWrite
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class CodeModeColdSaveRaceTest {
    @Test
    fun `a head save queued before eviction preserves the cold conversation and its expiry markers`(
        @TempDir dir: Path,
    ) {
        val json = Json { encodeDefaults = true }
        val location = CodeModeStateLocation(dir.resolve("records"), dir.resolve("legacy.json"))
        val access = CodeModeRegistryAccess(ReentrantLock(), CodeModeKeyLocks())
        val blockedFile = CodeModeStateDirectory(location.dir, json, {}).path("alpha")
        val block = AtomicBoolean(false)
        val writing = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        val writer = CodeModeStateWrite { path, text ->
            if (block.get() && path == blockedFile) {
                writing.countDown()
                check(proceed.await(10, TimeUnit.SECONDS))
            }
            CodeModeStateJournal.write(path, text)
        }
        val store = CodexCodeModeStore(location, json, {}, writer = writer, access = access)
        val alpha = CodeModeRecords.of("alpha", 1).also { it.phase = CodeModePhase.COMPLETED }
        val beta = CodeModeRecords.of("beta", 1).also { it.phase = CodeModePhase.COMPLETED }
        val records = mutableListOf(alpha, beta)
        val markers = listOf(CodeModeExpiredSnapshot("beta", "older-retry", setOf("older-call"), 1L))
        access.monitor.withLock { store.save(records, markers) }
        alpha.origin.source = "updated alpha"
        block.set(true)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val save = executor.submit { access.monitor.withLock { store.save(records, markers) } }
            assertTrue(writing.await(10, TimeUnit.SECONDS), "the other conversation did not reach its disk write")
            access.withKey("beta") {
                assertTrue(store.commit("beta", records, markers))
                assertTrue(store.cold.evict("beta"))
                records.remove(beta)
            }
            proceed.countDown()
            save.get(10, TimeUnit.SECONDS)
            val restored = CodexCodeModeStore(location, json, {}).load()
            assertEquals(
                mapOf("alpha" to "updated alpha", "beta" to beta.origin.source),
                restored.records.associate { it.key to it.source },
            )
            assertEquals(markers, restored.expired)
        } finally {
            proceed.countDown()
            executor.shutdownNow()
        }
    }
}
