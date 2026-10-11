// NEW: one native-history omission observer logs existing evidence and retires completed lineage after emission.
package splice.provider.codex.state.diagnostics

import splice.core.util.LogSink
import splice.provider.codex.CODE_MODE_RECORD_LOG_CHARS
import splice.provider.codex.CodeModeOmission
import splice.provider.codex.CodeModeRewrite
import java.util.concurrent.ConcurrentHashMap

internal fun interface CodeModeNativeRetirement {
    operator fun invoke(omissions: List<CodeModeOmission>)
}

/** A rewrite's body is already built. Lifecycle changes cannot alter that request's emitted bytes. */
internal class CodeModeHistoryOmissions(
    private val log: LogSink,
    private val retireNative: CodeModeNativeRetirement,
) {
    private val announced: MutableSet<String> = ConcurrentHashMap.newKeySet()

    fun observe(rewrite: CodeModeRewrite) {
        rewrite.omitted.filter { announced.add(it.record.id) }.forEach { omission ->
            log(
                "[code-mode] history rewrite skipped record ${omission.record.id.take(CODE_MODE_RECORD_LOG_CHARS)} " +
                    "(outer ${omission.record.origin.outerCallId}): ${omission.reason}; its client calls stay in " +
                    "the history as ordinary tool calls; " +
                    CodeModeHistoryLog.context(omission.record, omission.nativeRejection),
            )
        }
        retireNative(rewrite.omitted)
    }
}
