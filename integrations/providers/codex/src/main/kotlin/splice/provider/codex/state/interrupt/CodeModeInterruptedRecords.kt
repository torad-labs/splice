package splice.provider.codex.state.interrupt

import splice.provider.codex.CodeModePhase
import splice.provider.codex.CodeModeRecord
import splice.provider.codex.state.CodeModeRecordChanges
import splice.provider.codex.state.CodeModeRegistryAccess
import kotlin.concurrent.withLock

/** Retires addressed executions while their exact accepted/issued callback identities remain durable. */
internal class CodeModeInterruptedRecords(
    private val access: CodeModeRegistryAccess,
    private val records: List<CodeModeRecord>,
    private val changes: CodeModeRecordChanges,
) {
    fun interrupt(conversation: String, cellIds: Set<String>) {
        val candidates = access.monitor.withLock { records.toList() }
        candidates.forEach { record ->
            access.withKey(record.key) {
                val addressed = record.conversationId == conversation && record.nativeCellId in cellIds
                if (addressed && record.phase == CodeModePhase.ACTIVE) {
                    changes.lose(
                        record,
                        "code-mode cell interrupted by explicit user request; source was not rerun",
                        null,
                    )
                }
            }
        }
    }
}
