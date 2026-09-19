// NEW: V4-165 (2026-09-19) — how many slots a llama-server runs, read from the server itself.
//
// GET /props answers {"total_slots": N, ...} straight from the HTTP thread (llama.cpp
// tools/server/server-context.cpp get_props: `total_slots = params.n_parallel`), so the read does
// not wait behind a prefill. The number is the server's -np; reading it rather than configuring it
// means a changed -np cannot leave splice pinning conversations to slots that do not exist.
package splice.spi.local

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import splice.core.util.Cancellables
import splice.core.util.JsonScalars
import splice.spi.LocalHttp

/** [baseUrl] is the provider's OpenAI base (…/v1); /props lives at the server root beside it. */
public class LlamaServerSlots(baseUrl: String, private val http: LocalHttp) {
    private val props = baseUrl.trimEnd('/').removeSuffix("/v1") + "/props"
    private val json = Json { ignoreUnknownKeys = true }

    /** The server's slot count, or null when it is unreachable or answers without one. */
    public fun read(): Int? {
        // No status check: an error reply (llama-server answers {"error": ...}) has no total_slots,
        // so the field itself is the test.
        val reply = http("GET", props, null) ?: return null
        return JsonScalars.int(parse(reply.body), "total_slots")?.takeIf { it > 0 }
    }

    private fun parse(text: String): JsonObject? =
        // ast-grep-ignore: kt-no-silent-result-collapse -- 2026-09-19 (V4-165): a /props body that is not a JSON object carries no slot count, and null is the whole answer: the caller sends the turn unpinned, which is what every turn did before slot affinity.
        Cancellables.runCatchingCancellable { json.parseToJsonElement(text).jsonObject }.getOrNull()
}
