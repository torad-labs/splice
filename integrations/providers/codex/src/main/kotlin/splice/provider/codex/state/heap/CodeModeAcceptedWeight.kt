package splice.provider.codex.state.heap

import kotlinx.serialization.json.JsonElement
import splice.provider.codex.CodeModeAcceptedResult
import splice.provider.codex.CodeModeRecordSnapshot
import splice.provider.codex.state.CodeModeWeight
import splice.upstream.codemode.CodeModeResult

/** Weighs an acceptance before mutation, including payloads still shared by a durable snapshot. */
internal class CodeModeAcceptedWeight(private val entries: Map<String, CodeModeAcceptedResult>) {
    fun growth(
        supplied: Map<String, CodeModeResult>,
        media: Map<String, List<JsonElement>>,
        clientOutputDigests: Map<String, String>,
    ): Long = supplied.entries.sumOf { (id, result) ->
        val prior = entries[id]
        val before = prior?.let { bytes(it.result, it.media.orEmpty(), it.clientOutputDigest) } ?: 0L
        val after = bytes(result, media[id].orEmpty(), clientOutputDigests[id]) +
            if (prior == null) CodeModeWeight.STORED.text(id) else 0L
        (after - before).coerceAtLeast(0L)
    }

    fun kept(snapshot: CodeModeRecordSnapshot, supplied: Map<String, CodeModeResult>): Long =
        supplied.keys.sumOf { id ->
            val prior = entries[id]
            val held = snapshot.results[id]
            if (prior == null || held == null) return@sumOf 0L
            val output = if (held.output === prior.result.output) CodeModeWeight.STORED.text(held.output) else 0L
            val media = if (held.media === prior.media) held.media.orEmpty().sumOf(CodeModeWeight.STORED::json) else 0L
            val proof = CodeModeWeight.STORED.clientOutputDigest(
                held.clientOutputDigest?.takeIf { it === prior.clientOutputDigest },
            )
            val payload = held.payload?.takeIf { it === prior.result.payload }?.let(CodeModeWeight.STORED::json) ?: 0L
            output + media + proof + payload
        }

    private fun bytes(result: CodeModeResult, media: List<JsonElement>, clientOutputDigest: String?): Long =
        CodeModeWeight.STORED.text(result.output) + media.sumOf(CodeModeWeight.STORED::json) +
            CodeModeWeight.STORED.clientOutputDigest(clientOutputDigest) +
            (result.payload?.let(CodeModeWeight.STORED::json) ?: 0L)
}
