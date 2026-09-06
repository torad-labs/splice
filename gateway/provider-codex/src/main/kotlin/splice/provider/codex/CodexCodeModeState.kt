// NEW: defines the durable record, native-history, result, and expiry state for code mode.
package splice.provider.codex

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import splice.spi.CodeModeResult

@Serializable
internal data class CodeModePersistedState(
    val version: Int = 1,
    val records: List<CodeModeRecordSnapshot> = emptyList(),
    val expired: List<CodeModeExpiredSnapshot> = emptyList(),
)

@Serializable
internal data class CodeModeExpiredSnapshot(
    val key: String,
    val lastDigest: String,
    val resultIds: Set<String>,
    val expiredAt: Long,
)

internal class CodeModeExpiredHistory(
    val entries: MutableList<CodeModeExpiredSnapshot>,
    private val limit: Int,
) {
    fun remember(record: CodeModeRecord, now: Long) {
        entries += CodeModeExpiredSnapshot(
            key = record.key,
            lastDigest = record.lastDigest,
            resultIds = record.clientIds(),
            expiredAt = now,
        )
        while (entries.size > limit) entries.removeAt(0)
    }

    fun trim(records: MutableList<CodeModeRecord>, now: Long): Boolean {
        var changed = false
        while (records.size > limit) {
            val index = records.indexOfFirst(CodeModeRecord::terminal)
            if (index < 0) return changed
            remember(records.removeAt(index), now)
            changed = true
        }
        return changed
    }
}

@Serializable
internal data class CodeModeRecordSnapshot(
    val id: String,
    val key: String,
    val outer: JsonObject,
    val outerCallId: String,
    val source: String,
    val phase: CodeModePhase,
    val pending: List<CodeModePending>,
    val results: Map<String, CodeModeResultSnapshot>,
    val output: String?,
    val error: String?,
    val totalCalls: Int,
    val rounds: Int,
    val updatedAt: Long,
    val lastDigest: String,
    val baselineInputCount: Int = 0,
    val baselineInputDigest: String = "",
    val metadataVersion: Int = 0,
    val baselineLogicalCount: Int = 0,
    val baselineLogicalDigest: String = "",
    val nativeSegments: List<CodeModeNativeSegment> = emptyList(),
    val continuity: List<JsonElement> = emptyList(),
    val continuityReplay: List<CodeModeNativeSegment> = emptyList(),
) {
    fun restore(): CodeModeRecord = CodeModeRecord(
        id = id,
        key = key,
        outer = outer,
        outerCallId = outerCallId,
        source = source,
        phase = when {
            phase == CodeModePhase.ACTIVE -> CodeModePhase.LOST
            metadataVersion != CODE_MODE_METADATA_VERSION -> CodeModePhase.LOST
            else -> phase
        },
        pending = pending.toMutableList(),
        results = results.mapValues { (id, value) -> value.restore(id) }.toMutableMap(),
        output = output,
        error = when {
            metadataVersion != CODE_MODE_METADATA_VERSION ->
                "code-mode replay metadata is unavailable; source was not rerun"
            phase == CodeModePhase.ACTIVE ->
                "code-mode process state was lost after completed client calls: ${results.keys}; source was not rerun"
            else -> error
        },
        totalCalls = totalCalls,
        rounds = rounds,
        updatedAt = updatedAt,
        lastDigest = lastDigest,
        baselineInputCount = baselineInputCount,
        baselineInputDigest = baselineInputDigest,
        metadataVersion = metadataVersion,
        baselineLogicalCount = baselineLogicalCount,
        baselineLogicalDigest = baselineLogicalDigest,
        nativeSegments = nativeSegments,
        continuity = continuity,
        continuityReplay = continuityReplay,
    )
}

internal data class CodeModeRecord(
    val id: String,
    val key: String,
    val outer: JsonObject,
    val outerCallId: String,
    val source: String,
    var phase: CodeModePhase,
    val pending: MutableList<CodeModePending> = mutableListOf(),
    val results: MutableMap<String, CodeModeResult> = linkedMapOf(),
    var output: String? = null,
    var error: String? = null,
    var totalCalls: Int = 0,
    var rounds: Int = 0,
    var updatedAt: Long,
    var lastDigest: String,
    val baselineInputCount: Int,
    val baselineInputDigest: String,
    val metadataVersion: Int,
    val baselineLogicalCount: Int,
    val baselineLogicalDigest: String,
    val nativeSegments: List<CodeModeNativeSegment>,
    val continuity: List<JsonElement>,
    val continuityReplay: List<CodeModeNativeSegment>,
) {
    fun visiblePending(): List<CodeModePending> = pending.filter(CodeModePending::exposed)

    fun clientIds(): Set<String> = (results.keys + pending.map(CodeModePending::clientId)).toSet()

    fun terminal(): Boolean = phase == CodeModePhase.COMPLETED || error != null

    fun snapshot(): CodeModeRecordSnapshot = CodeModeRecordSnapshot(
        id = id,
        key = key,
        outer = outer,
        outerCallId = outerCallId,
        source = source,
        phase = phase,
        pending = pending.map { it.copy() },
        results = results.mapValues { (_, result) -> CodeModeResultSnapshot(result.output, result.isError) },
        output = output,
        error = error,
        totalCalls = totalCalls,
        rounds = rounds,
        updatedAt = updatedAt,
        lastDigest = lastDigest,
        baselineInputCount = baselineInputCount,
        baselineInputDigest = baselineInputDigest,
        metadataVersion = metadataVersion,
        baselineLogicalCount = baselineLogicalCount,
        baselineLogicalDigest = baselineLogicalDigest,
        nativeSegments = nativeSegments,
        continuity = continuity,
        continuityReplay = continuityReplay,
    )
}

@Serializable
internal data class CodeModeNativeSegment(
    val logicalOffset: Int,
    val items: List<JsonElement>,
)

@Serializable
internal data class CodeModePending(
    val runtimeId: String,
    val clientId: String,
    val name: String,
    val arguments: JsonObject,
    var exposed: Boolean,
)

@Serializable
internal data class CodeModeResultSnapshot(val output: String, val isError: Boolean) {
    fun restore(id: String): CodeModeResult = CodeModeResult(id, output, isError)
}

@Serializable
internal enum class CodeModePhase { ACTIVE, COMPLETED, LOST }
