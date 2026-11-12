package splice.provider.codex

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.provider.codex.state.CodeModeStateJournal
import splice.provider.codex.state.save.CodeModeStateWrite
import splice.provider.codex.stream.CodeModeSourceState
import splice.provider.codex.stream.CodeModeStreamAdmission
import splice.provider.codex.stream.batch.CodeModeBatchAdmissions
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.time.Duration.Companion.minutes

class CodeModeBatchPublicationTest : CodeModeResponseBatchSupport() {
    @Test
    fun `every forced crash boundary contains a complete source membership and prepared model prefix`() {
        val durable = mutableListOf<String>()
        val config = CodeModeBridgeConfig(
            { error("no runtime starts") },
            stateLocation(),
            clock = Clock.fixed(Instant.ofEpochMilli(1000), ZoneOffset.UTC),
        )
        val registry = CodexCodeModeRegistry(
            config,
            Json,
            5.minutes,
            CodeModeStateWrite { path, text ->
                CodeModeStateJournal.write(path, text)
                durable += Files.readString(path)
            },
        )
        val admission = CodeModeBatchAdmissions(
            registry,
            CodeModeStreamAdmission { call ->
                val record = CodeModeRecords.of("atomic", if (call.callId == firstCall().callId) 0 else 1).copy(
                    origin = CodeModeOrigin(call.raw, call.callId, "", CodeModeRecords.of("atomic", 0).origin.baseline),
                )
                record.sourceState = CodeModeSourceState()
                assertTrue(registry.add(record))
                record
            },
        )
        listOf(firstCall(), secondCall()).forEachIndexed { index, call ->
            val record = admission.admit(call)
            admission.seal(
                record,
                call,
                CodeModeContinuity(emptyList(), emptyList()),
                batch(listOf(firstCall(), secondCall()).take(index + 1)),
            )
        }
        val states = durable.mapIndexed { index, text ->
            val file = tempDir.resolve("crash-$index.jsonl")
            Files.writeString(file, text)
            CodeModeStateJournal.read(file, Json).records
        }
        assertTrue(states.any { rows -> rows.any { it.nativeBatch?.size == 2 } })
        states.forEach { rows -> verify(rows) }
        registry.onHeadStop()
    }

    private fun verify(rows: List<CodeModeRecordSnapshot>) {
        rows.filter { it.id == it.nativeBatch?.ownerId }.forEach { owner ->
            val batch = checkNotNull(owner.nativeBatch)
            val members = rows.filter { it.nativeBatch?.ownerId == owner.id }.sortedBy { it.nativeBatch?.ordinal }
            assertEquals(batch.size, members.size, "durable batch header was published before its member")
            assertEquals(members.indices.toList(), members.map { it.nativeBatch?.ordinal })
            assertTrue(members.all { it.sourceState?.itemComplete == true })
            assertEquals(
                members.map {
                    it.outerCallId
                },
                batch.items.orEmpty().mapNotNull { splice.core.util.JsonScalars.str(it, "call_id") },
            )
            assertTrue(members.all { it.nativeBatch?.dispatched == true || it.phase == CodeModePhase.STARTING })
        }
    }
}
