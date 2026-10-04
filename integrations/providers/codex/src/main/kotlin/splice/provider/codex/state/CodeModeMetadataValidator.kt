// NEW: replay metadata validation is independent of history placement and canonical rewriting.
package splice.provider.codex.state

import splice.provider.codex.CODE_MODE_LEGACY_METADATA_VERSION
import splice.provider.codex.CODE_MODE_METADATA_VERSION
import splice.provider.codex.CodeModeRecord

internal class CodeModeMetadataValidator {
    fun problem(record: CodeModeRecord): String? = when {
        record.metadataVersion !in CODE_MODE_LEGACY_METADATA_VERSION..CODE_MODE_METADATA_VERSION ->
            "code-mode replay metadata is unavailable"
        record.baselineLogicalCount < 0 -> "code-mode replay metadata has an invalid logical boundary"
        record.nativeSegments.any { it.logicalOffset !in 0..record.baselineLogicalCount } ->
            "code-mode replay metadata has an invalid native offset"
        record.continuityReplay.any { it.logicalOffset !in 0..record.continuity.size } ->
            "code-mode replay metadata has an invalid continuity offset"
        record.metadataVersion == CODE_MODE_LEGACY_METADATA_VERSION -> legacyProblem(record)
        else -> null
    }

    private fun legacyProblem(record: CodeModeRecord): String? = when {
        record.baselineLogicalDigest.isEmpty() -> "code-mode replay metadata has no logical digest"
        record.baselineInputDigest.isEmpty() -> "code-mode replay metadata has no wire digest"
        else -> null
    }
}
