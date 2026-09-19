// NEW: V4-165 (2026-09-19) — how many slots a llama-server runs, read from the server itself.
//
// GET /props answers {"total_slots": N, ...} straight from the HTTP thread (llama.cpp
// tools/server/server-context.cpp get_props: `total_slots = params.n_parallel`), so the read does
// not wait behind a prefill. The number is the server's -np; reading it rather than configuring it
// means a changed -np is picked up from the server that runs it (RuntimeSlotCount re-reads it).
//
// V4-166 (review, 2026-09-19): every "no" says why. A router-mode llama-server answers /props without
// total_slots unless asked for a model, a non-llama runtime answers 404, and a server started with
// --api-key answers 401; V4-165 folded all of them into one silent null, so slot_affinity could stay
// off for good with nothing in the log.
package splice.spi.local

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import splice.core.util.Cancellables
import splice.core.util.JsonScalars
import splice.spi.LocalHttp

/** What a runtime said about its slots: how many, or why it gave no count. */
public sealed class SlotsReading {
    public data class Count(val slots: Int) : SlotsReading()

    /** [why] names the endpoint and what it answered, for the log line — never a body. */
    public data class Unreadable(val why: String) : SlotsReading()
}

/** [baseUrl] is the provider's OpenAI base (…/v1); /props lives at the server root beside it. */
public class LlamaServerSlots(baseUrl: String, private val http: LocalHttp) {
    private val props = baseUrl.trimEnd('/').removeSuffix("/v1") + "/props"
    private val json = Json { ignoreUnknownKeys = true }

    /** The server's slot count, or why there is none. */
    public fun read(): SlotsReading {
        // No status check: an error reply (llama-server answers {"error": ...}) has no total_slots,
        // so the field itself is the test, and the status only explains its absence.
        val reply = http("GET", props, null) ?: return SlotsReading.Unreadable("$props is unreachable")
        val count = JsonScalars.int(parse(reply.body), "total_slots")?.takeIf { it > 0 }
        return count?.let(SlotsReading::Count)
            ?: SlotsReading.Unreadable("$props answered HTTP ${reply.status} without a slot count")
    }

    private fun parse(text: String): JsonObject? =
        // ast-grep-ignore: kt-no-silent-result-collapse -- 2026-09-19 (V4-165): a /props body that is not a JSON object carries no slot count; read() reports that absence with the reply's status, and the caller sends the turn unpinned, which is what every turn did before slot affinity.
        Cancellables.runCatchingCancellable { json.parseToJsonElement(text).jsonObject }.getOrNull()
}
