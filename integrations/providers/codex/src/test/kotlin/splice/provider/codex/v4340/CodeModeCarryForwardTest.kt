// NEW: V4-340 — the upgrade from the single file. Every daemon before it kept a head's code-mode records in
// one `<head>-code-mode.json`; live claudex sessions have records in it today, and they must survive the
// first start of the daemon that keeps one file per conversation. The rule pinned here: a per-conversation
// file that reads back whole is the copy the loader trusts; the single file fills only the conversations
// with no such file, each is written to its own file, and it is deleted after every one is written. So a
// crash between the writes and the delete loads each conversation once and loses none.
package splice.provider.codex.v4340

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.util.LogSink
import splice.provider.codex.CodeModeBridgeConfig
import splice.provider.codex.CodeModeExpiredSnapshot
import splice.provider.codex.CodeModePersistedState
import splice.provider.codex.CodeModePhase
import splice.provider.codex.CodeModeRecord
import splice.provider.codex.CodeModeRecords
import splice.provider.codex.CodeModeStateFiles
import splice.provider.codex.CodeModeStateLocation
import splice.provider.codex.CodexCodeModeRegistry
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.time.Duration.Companion.minutes

private const val NOW = 1_790_000_000_000L
private val CODE_MODE_JSON = Json { encodeDefaults = true }

class CodeModeCarryForwardTest {

    @TempDir
    lateinit var tempDir: Path

    private val logs = mutableListOf<String>()
    private val state by lazy { CodeModeStateFiles(tempDir.resolve("code-mode")) }
    private val legacyFile by lazy { tempDir.resolve("claudex-code-mode.json") }

    private fun registry() = CodexCodeModeRegistry(
        CodeModeBridgeConfig(
            { error("no script runs in a registry test") },
            CodeModeStateLocation(state.dir, legacyFile),
            clock = Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC),
            log = LogSink { logs += it },
        ),
        CODE_MODE_JSON,
        5.minutes,
    )

    private fun finished(key: String, output: String): CodeModeRecord =
        CodeModeRecords.of(key, 1, updatedAt = NOW).also {
            it.phase = CodeModePhase.COMPLETED
            it.output = output
        }

    /** The single file as an older daemon wrote it: the given conversations' finished record, and [markers]. */
    private fun writeLegacy(
        vararg outputs: Pair<String, String>,
        markers: List<CodeModeExpiredSnapshot> = emptyList(),
    ) {
        val records = outputs.map { (key, output) -> finished(key, output).snapshot() }
        Files.writeString(
            legacyFile,
            CODE_MODE_JSON.encodeToString(CodeModePersistedState(records = records, expired = markers)),
        )
    }

    private fun CodexCodeModeRegistry.outputs(key: String): List<String?> = completed(key).map(CodeModeRecord::output)

    @Test
    fun `a single file an older daemon wrote is carried into one file per conversation, then deleted`() {
        val gone = CodeModeExpiredSnapshot("gamma", "digest-gamma", setOf("id-gamma"), expiredAt = NOW - 1)
        writeLegacy("alpha" to "a", "beta" to "b", markers = listOf(gone))

        val registry = registry()

        assertEquals(listOf("a"), registry.outputs("alpha"))
        assertEquals(listOf("b"), registry.outputs("beta"))
        assertTrue(registry.expiredHistory("gamma", "digest-gamma", emptySet()), "a marker is carried too")
        assertFalse(Files.exists(legacyFile), "the single file is deleted once every conversation is written")
        assertEquals(3, state.files().size, "alpha, beta and gamma's marker each have their file")
        assertEquals(emptyList<String>(), logs)
    }

    @Test
    fun `after the carry-forward a restart reads the files alone`() {
        writeLegacy("alpha" to "a", "beta" to "b")
        registry()

        val restarted = registry()

        assertEquals(listOf("a"), restarted.outputs("alpha"))
        assertEquals(listOf("b"), restarted.outputs("beta"))
    }

    @Test
    fun `a crash after every file is written but before the single file is deleted loads each conversation once`() {
        writeLegacy("alpha" to "a", "beta" to "b")
        val bytes = Files.readAllBytes(legacyFile)
        registry()
        Files.write(legacyFile, bytes)

        val restarted = registry()

        assertEquals(listOf("a"), restarted.outputs("alpha"), "a conversation in both places is loaded once")
        assertEquals(listOf("b"), restarted.outputs("beta"))
        assertEquals(2, state.records().size)
        assertFalse(Files.exists(legacyFile), "the next start finishes the delete")
    }

    @Test
    fun `a crash part way through the writes loses no conversation`() {
        writeLegacy("alpha" to "a", "beta" to "b", "gamma" to "c")
        val bytes = Files.readAllBytes(legacyFile)
        registry()
        Files.write(legacyFile, bytes)
        val written = state.files()
        Files.delete(written.first())
        Files.delete(written.last())

        val restarted = registry()

        listOf("alpha" to "a", "beta" to "b", "gamma" to "c").forEach { (key, output) ->
            assertEquals(listOf(output), restarted.outputs(key), "conversation $key after the crash")
        }
        assertEquals(3, state.files().size, "the two files the crash did not write are written now")
        assertFalse(Files.exists(legacyFile))
    }

    @Test
    fun `the copy that reads back whole is the one trusted, whichever is older`() {
        val first = registry()
        val newer = CodeModeRecords.of("alpha", 1)
        assertTrue(first.add(newer))
        first.complete(newer, "new")
        writeLegacy("alpha" to "old", "beta" to "b")

        val restarted = registry()

        assertEquals(listOf("new"), restarted.outputs("alpha"), "alpha's own file wins over the single file's copy")
        assertEquals(listOf("b"), restarted.outputs("beta"), "beta has no file, so the single file fills it")
        assertFalse(Files.exists(legacyFile))
    }

    @Test
    fun `after the single file is gone a corrupt file drops only its conversation`() {
        writeLegacy("alpha" to "a", "beta" to "b", "gamma" to "c")
        registry()
        assertFalse(Files.exists(legacyFile))
        val corrupted = state.files()[1]
        Files.writeString(corrupted, "not json at all")

        val restarted = registry()

        val restored = listOf("alpha", "beta", "gamma").count { restarted.outputs(it).isNotEmpty() }
        assertEquals(2, restored, "the other two conversations are restored")
        assertFalse(Files.exists(corrupted), "the unreadable file is removed")
        assertEquals(1, logs.count { corrupted.fileName.toString() in it && "unreadable" in it }, "$logs")
    }

    @Test
    fun `a file that does not read back does not keep the single file's copy of its conversation out`() {
        writeLegacy("alpha" to "a", "beta" to "b")
        val bytes = Files.readAllBytes(legacyFile)
        registry()
        Files.write(legacyFile, bytes)
        state.files().forEach { Files.writeString(it, "{") }

        val restarted = registry()

        assertEquals(listOf("a"), restarted.outputs("alpha"), "the unreadable file is dropped, the single file fills")
        assertEquals(listOf("b"), restarted.outputs("beta"))
        assertEquals(2, state.records().size)
    }

    @Test
    fun `a single file that is not code-mode state is removed and the head starts without it`() {
        Files.writeString(legacyFile, """{"records": [{"id": 7""")

        val registry = registry()

        assertEquals(emptyList<String>(), registry.outputs("alpha"))
        assertFalse(Files.exists(legacyFile))
        assertTrue(logs.any { "claudex-code-mode.json" in it && "not code-mode state" in it }, "$logs")
    }

    @Test
    fun `a conversation that could not be written stays in the single file, and the next save writes it`() {
        writeLegacy("alpha" to "a", "beta" to "b")
        state.block()

        val registry = registry()

        assertEquals(listOf("a"), registry.outputs("alpha"), "the records are live in memory even so")
        assertTrue(Files.exists(legacyFile), "not every conversation is written, so the single file stays")
        assertEquals(2, logs.count { "not written" in it }, "$logs")

        state.unblock()
        registry.save()

        assertEquals(2, state.files().size, "the save writes what the carry-forward could not")
        assertEquals(listOf("b"), registry().outputs("beta"))
        assertFalse(Files.exists(legacyFile), "the next start finds every conversation in its file and deletes it")
    }
}
