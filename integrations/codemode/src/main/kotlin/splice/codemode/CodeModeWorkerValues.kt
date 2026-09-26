// NEW: V4-156 — the code-mode worker protocol's value types and JSON codec, moved verbatim out of
// CodeModeWire.kt. DECOMPOSED FOR THE CONCENTRATION RATIO, NOT FOR A DEFECT: CodeModeWire.kt barely
// changed (own 15 percent, --since 608f63b9) and was lifted into band HIGH by its neighbours getting
// smaller; the metric counts every declared type as a concern, and these three carry no logic.
package splice.codemode

import kotlinx.serialization.json.Json
import splice.upstream.codemode.CodeModeCall

internal object CodeModeJson {
    val codec: Json = Json { explicitNulls = true }
}

internal data class WorkerStart(val source: String, val tools: Set<String>)

internal data class WorkerReply(
    val calls: List<CodeModeCall>?,
    val output: String?,
    val error: String?,
)
