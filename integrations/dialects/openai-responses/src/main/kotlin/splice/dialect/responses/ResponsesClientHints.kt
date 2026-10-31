// NEW: (CX-03) the two request fields splice sends to LOOK like a well-behaved Responses client,
// both measured from codex-cli 0.145.0 on 2026-08-11 (capture:
// .dev/research/wire-compare/codex-0.145.0-responses-request.json).
//
// Both are LITE-ONLY, and that gating is load-bearing twice over: lite is where the real client was
// observed sending them, AND every migration-oracle fixture is a non-lite gpt-5-codex scenario, so
// lite-gating is what keeps those 11 byte-exact recordings green. Leaving client_metadata ungated
// dropped the oracle to 3/11 — it caught the mistake before it shipped. The one exception is a
// verbosity the person set for the head (fix 5, Oct 27): it rides every turn, as Codex's
// model_verbosity does, and a head with no setting keeps the lite-only bytes.
//
// They live in their own file because ResponsesRequestBuilder.kt is at detekt's 15-function ceiling
// for the builder class; they ride a type of their own because Kotlin main sources carry no
// top-level functions. Both members keep their old names and argument order, so the builder's call
// sites only gained a receiver.
package splice.dialect.responses

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.dialect.responses.request.BuildOptions

internal class ResponsesClientHints {

    /** text.verbosity: the head's own setting on every turn, as Codex sends model_verbosity before a model's default
     *  (fix 5 of the Oct 27 Responses fixes). With no setting, the vendor's lite verbosity on a lite turn and nothing
     *  otherwise, so a head nobody set sends the bytes it sent before the setting existed. */
    fun textBlock(quirks: ResponsesQuirks, lite: Boolean): JsonObject? {
        val verbosity = quirks.request.textVerbosity ?: quirks.lite.liteTextVerbosity?.takeIf { lite } ?: return null
        return buildJsonObject { put("verbosity", verbosity) }
    }

    /** SPLICE's identifiers, not codex's. codex sends installation/window ids from its own install;
     *  synthesising those would impersonate a client we are not. We send what we actually have:
     *  who we are, and the conversation/session this turn belongs to. */
    fun clientMetadataBlock(
        quirks: ResponsesQuirks,
        lite: Boolean,
        opts: BuildOptions,
        threadKey: String?,
    ): JsonObject? {
        // LITE-ONLY, for the same two reasons as text.verbosity: lite is where codex-cli was
        // measured sending it, and the 11 migration-oracle fixtures are all non-lite gpt-5-codex.
        // Leaving this ungated dropped the oracle to 3/11 — it is the instrument that caught it.
        if (!lite || !quirks.backend.sendClientMetadata) return null
        if (opts.sessionId == null && threadKey == null) return null
        return buildJsonObject {
            put("client", "splice")
            opts.sessionId?.let { put("session_id", it) }
            // The same value that rides as prompt_cache_key — splice's conversation identity.
            threadKey?.let { put("thread_id", it) }
        }
    }
}
