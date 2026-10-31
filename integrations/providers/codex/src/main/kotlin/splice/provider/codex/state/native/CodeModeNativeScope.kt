// NEW: captured native owner scope keeps partial ancestry neutral and post-capture aliases outside history.
package splice.provider.codex.state.native

import kotlinx.serialization.json.JsonElement
import splice.provider.codex.CodeModeNativeSegment
import splice.provider.codex.CodeModeRecord
import splice.provider.codex.state.CodeModeHistoryIndex
import splice.provider.codex.state.CodeModeNativeChain
import splice.provider.codex.state.diagnostics.CodeModeNativeBranch

/** Captured positions and raw callback bounds share an owner, never a numeric coordinate system. */
internal class CodeModeNativeScope(source: CodeModeRecord, private val bounds: IntRange, index: CodeModeHistoryIndex) {
    private val owner = generateSequence(source) { it.nativeParent }
        .firstOrNull { index.owned(it).firstOrNull() == bounds.last } ?: source
    private val lower = source.replayAnchors?.native.orEmpty().entries.filter { (_, anchor) ->
        index.resolve(anchor.copy(logicalTail = 0)) == bounds.first
    }.minOfOrNull { (offset, anchor) -> offset - anchor.logicalTail } ?: 0
    private val captured = CodeModeNativeChain.normalized(
        CodeModeNativeChain.replay(owner) +
            if (owner === source) emptyList() else CodeModeNativeChain.continuity(owner),
    )
    private val terminal = owner.origin.baseline.logicalCount + if (owner === source) 0 else owner.carry.continuity.size
    private val shortened = owner.replayAnchors?.baseline?.let { anchor ->
        index.resolve(anchor.copy(logicalTail = 0), bounds.last)?.let { prior ->
            prior + anchor.logicalTail > bounds.last
        }
    } == true
    val orderFailure: CodeModeNativeBranch = if (
        generateSequence(owner) { it.nativeParent }.any { it.nativeBaseId != null && it.nativeParent == null }
    ) {
        CodeModeNativeBranch.COUNT
    } else {
        CodeModeNativeBranch.NATIVE_ORDER
    }
    val expected: List<CodeModeNativeSegment> = captured.filter { it.logicalOffset in lower..terminal }

    fun actual(
        replay: Map<Int, List<JsonElement>>,
        nativeReplay: Map<Int, List<JsonElement>>,
    ): Map<Int, List<JsonElement>> = if (shortened || expected.any { it.logicalOffset == terminal }) {
        replay
    } else {
        // Only a witnessed complete tail makes this callback alias post-capture.
        // A shortened owned tail can carry captured natives at the callback's raw position.
        replay + (bounds.last to nativeReplay[bounds.last].orEmpty())
    }
}
