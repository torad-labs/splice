// NEW: one request projection owner separates selected capture authority from legacy record canonicalization.
package splice.provider.codex.state.native

import kotlinx.serialization.json.JsonElement
import splice.dialect.responses.request.ResponsesCodeModeInput
import splice.provider.codex.CODE_MODE_METADATA_VERSION
import splice.provider.codex.CodeModeBody
import splice.provider.codex.CodeModeEmitted
import splice.provider.codex.CodeModeOmission
import splice.provider.codex.CodeModeRecord
import splice.provider.codex.CodeModeRewrite
import splice.provider.codex.CodexCodeModeHistoryCodec
import splice.provider.codex.state.CodeModeCanonicalHistory
import splice.provider.codex.state.CodeModeNativeChain
import splice.provider.codex.state.diagnostics.CodeModeProjectedRewrite

internal fun interface CodeModeLegacyCanonicalization {
    operator fun invoke(
        input: ResponsesCodeModeInput,
        record: CodeModeRecord,
        media: Map<String, List<JsonElement>>,
    ): CodeModeProjectedRewrite
}

/** The null-capture route retains the original projection, legacy ordering and rebuilt request bytes. */
internal class CodeModeCanonicalRequests(private val codec: CodexCodeModeHistoryCodec) {
    fun rewrite(
        body: CodeModeBody,
        records: List<CodeModeRecord>,
        replayMedia: Map<String, List<JsonElement>>,
        capture: CodeModeRecord?,
        legacy: CodeModeLegacyCanonicalization,
    ): CodeModeRewrite {
        val root = body.request ?: return CodeModeRewrite(null, "code mode requires a Responses input array")
        val previous = body.emission?.previous(root.first, records)
        val conversation = codec.conversation(codec.projection.project(root.second))
        var input = conversation.body
        val omitted = previous?.omitted.orEmpty().toMutableList()
        val eligible = records.filter(CodeModeNativeChain::rewritable)
        val pending = eligible.filterNot { previous?.processed(it) == true }
        pending.filter { it.origin.baseline.metadataVersion != CODE_MODE_METADATA_VERSION }.forEach { record ->
            val rewritten = legacy(input, record, replayMedia)
            val error = rewritten.error
            if (error == null) input = checkNotNull(rewritten.input) else omitted += CodeModeOmission(record, error)
        }
        val anchored = CodeModeCanonicalHistory(codec).rewrite(
            input,
            pending.filter { it.origin.baseline.metadataVersion == CODE_MODE_METADATA_VERSION },
            replayMedia,
            capture,
        )
        omitted += anchored.omitted
        return codec.rebuilt(root.first, conversation, anchored.input, body, CodeModeEmitted(eligible, omitted))
            .copy(omitted = omitted)
    }
}
