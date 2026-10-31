package splice.provider.codex.stream

import splice.provider.codex.CodeModeRecord

/** Expected preflight refusal never uses an exception or implies that a runtime opened. */
internal sealed class CodeModeRuntimeSelection {
    abstract val record: CodeModeRecord
    data class Ready(override val record: CodeModeRecord) : CodeModeRuntimeSelection()
    data class Refused(override val record: CodeModeRecord, val reason: String) : CodeModeRuntimeSelection()
}
