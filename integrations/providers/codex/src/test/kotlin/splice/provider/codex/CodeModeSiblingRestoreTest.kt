package splice.provider.codex

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import splice.core.failure.CodeModeStorageFault
import splice.core.failure.FailureCause
import splice.core.failure.HeadVoice
import splice.provider.codex.state.CodeModeStateDirectory
import java.nio.file.Files
import kotlin.time.Duration.Companion.hours

class CodeModeSiblingRestoreTest : CodeModeBridgeTestSupport() {
    private val json = Json { encodeDefaults = true }
    private val clock = MutableClock(1_790_000_000_000L)

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `an unreadable cold sibling does not block healthy native admission or lookup`(admission: Boolean) {
        val registry = corruptedSibling()
        try {
            if (admission) {
                assertTrue(registry.add(record("healthy", 1)), "the healthy model must still admit an exec")
            } else {
                val found = registry.completed("healthy", "conversation")
                assertEquals(listOf("healthy-cell"), found.map(CodeModeRecord::nativeCellId))
                assertEquals(listOf("accepted healthy"), found.map { it.progress.output })
            }
            assertEquals("{broken\n", Files.readString(path("bad")), "sibling isolation must preserve the journal")
        } finally {
            registry.cache.release("healthy")
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `the caller's own unreadable journal remains fatal for admission and native lookup`(admission: Boolean) {
        val registry = corruptedSibling()
        try {
            val failure = assertThrows(CodeModePersistenceException::class.java) {
                if (admission) registry.add(record("bad", 1)) else registry.completed("bad", "conversation")
            }
            val outcome = failure.outcome()
            val voice = HeadVoice("claudex", null).anyTurn
            assertEquals(CodeModeStorageFault.INVALID, (outcome.cause as FailureCause.Splice.Protocol).storage)
            assertTrue("restored" in failure.message)
            assertTrue("invalid" in voice.ending(outcome.cause, outcome.at).text)
            assertFalse(voice.answer(outcome.cause, outcome.at).clientResends)
            assertEquals("{broken\n", Files.readString(path("bad")))
        } finally {
            registry.cache.release("healthy")
        }
    }

    private fun corruptedSibling(): CodexCodeModeRegistry {
        val records = listOf("bad", "healthy").map { key ->
            record(key, 0).also {
                it.phase = CodeModePhase.COMPLETED
                it.nativeCellId = "$key-cell"
                it.progress.output = "accepted $key"
            }
        }
        CodexCodeModeStore(stateLocation(), json, {}).save(records, emptyList())
        val config = CodeModeBridgeConfig(
            { error("no script is rerun during a registry restore") },
            stateLocation(),
            retention = CodeModeRetention(bytes = 1),
            clock = clock,
            log = { logLines += it },
        )
        val registry = CodexCodeModeRegistry(config, json, 1.hours)
        assertTrue(registry.records.isEmpty(), "both completed model keys must actually be cold")
        registry.cache.acquire("healthy")
        assertEquals(listOf("healthy"), registry.records.map(CodeModeRecord::key))
        Files.writeString(path("bad"), "{broken\n")
        return registry
    }

    private fun record(key: String, n: Int) = CodeModeRecords.of(key, n, clock.millis()).also {
        it.conversationId = "conversation"
    }

    private fun path(key: String) = CodeModeStateDirectory(stateLocation().dir, json, {}).path(key)
}
