// PORT-OF: server/src/reasoning/replay.mjs @ pre-public-port-baseline — invariants: envelope tag
// 'splice-reasoning' v1, encode/decode stay PAIRED (a tag/version bump strands in-flight
// transcripts — byte-compat is pinned against Node-produced fixtures); encode emits compact
// JSON with insertion order tag,v,item{id,encrypted_content,summary?} (summary omitted when
// empty — byte-identity with JSON.stringify); decode rejects foreign/garbled payloads with
// null (the block is silently dropped, exactly as under pure amnesia) and requires a
// non-empty id + encrypted_content; decoded items ALWAYS carry a summary array.
// The pair were top-level functions until the 2026-08-16 style migration (HD-M8); they now live on
// the ReasoningReplay object, which makes "encode and decode stay PAIRED" structural rather than a
// comment. Same names, same bodies, same bytes.
// 2026-10-23: a run of reasoning items GPT gave in a row reaches Claude Code as ONE redacted_thinking block, whose
// data is the items' v1 envelopes in order, joined by REASONING_RUN_SEPARATOR, which base64 never writes. Claude
// Code draws each block as an empty "Thinking…" row, and gpt-6.1-sol gave nine items before one exec call (turn
// de23cca4b4c3). A one-item block stays the v1 envelope alone, byte for byte.
package splice.core.reasoning

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.util.DaemonLog
import splice.core.util.JsonScalars
import splice.core.util.JsonWire
import splice.core.util.LogSink
import java.io.ByteArrayOutputStream
import java.util.Base64

public const val REASONING_ENVELOPE_TAG: String = "splice-reasoning"
public const val REASONING_ENVELOPE_VERSION: Int = 1

/** Between the envelopes of one block. Standard base64 writes only A-Z, a-z, 0-9, + and / and =, so no envelope
 *  holds it, and a joined block reads as no envelope to a build that knows only v1 (it drops the block). */
private const val REASONING_RUN_SEPARATOR: String = "."

// envelope field names are the wire contract — named once, referenced everywhere so encode/decode
// can never drift apart.
private const val FIELD_TAG = "tag"
private const val FIELD_VERSION = "v"
private const val FIELD_ITEM = "item"
private const val FIELD_ID = "id"
private const val FIELD_ENCRYPTED = "encrypted_content"
private const val FIELD_SUMMARY = "summary"

private val lenient = Json { ignoreUnknownKeys = true }

public object ReasoningReplay {

    /** Responses `reasoning` output item -> base64 envelope for a redacted_thinking block. */
    public fun encodeReasoningEnvelope(item: JsonObject): String? {
        // DR-76: JsonNull IS a JsonPrimitive whose .content is the literal "null" — the cast read
        // minted an envelope whose ciphertext was those four bytes. JsonScalars makes JSON-null
        // and absent the same no-envelope answer (empty joins them: decode drops it anyway, and
        // the Node source's falsy check never encoded either).
        val id = JsonScalars.strOrEmpty(item[FIELD_ID]).ifEmpty { return null }
        val encrypted = JsonScalars.strOrEmpty(item[FIELD_ENCRYPTED]).ifEmpty { return null }
        val summary = item[FIELD_SUMMARY] as? JsonArray
        val envelope = buildJsonObject {
            put(FIELD_TAG, REASONING_ENVELOPE_TAG)
            put(FIELD_VERSION, REASONING_ENVELOPE_VERSION)
            put(
                FIELD_ITEM,
                buildJsonObject {
                    put(FIELD_ID, id)
                    put(FIELD_ENCRYPTED, encrypted)
                    if (summary != null && summary.isNotEmpty()) put(FIELD_SUMMARY, summary)
                    readableContent(
                        item,
                    )?.takeIf { it.second.isNotEmpty() }?.let { (key, content) -> put(key, content) }
                },
            )
        }
        return ByteArrayOutputStream().use { encoded ->
            Base64.getEncoder().wrap(encoded).use { output -> JsonWire.write(envelope, output) }
            encoded.toString(Charsets.US_ASCII)
        }
    }

    /** redacted_thinking `data` -> Responses `reasoning` input item, or null for foreign data. */
    // foreign/garbled payloads pass through as null
    public fun decodeReasoningEnvelope(data: String?, log: LogSink = LogSink(DaemonLog::write)): JsonObject? {
        val parsed = parseEnvelope(data)
        val tagOk = (parsed?.get(FIELD_TAG) as? JsonPrimitive)?.content == REASONING_ENVELOPE_TAG
        val versionOk = (parsed?.get(FIELD_VERSION) as? JsonPrimitive)?.content == REASONING_ENVELOPE_VERSION.toString()
        val item = if (tagOk && versionOk) parsed[FIELD_ITEM] as? JsonObject else null
        // DR-76: same JsonNull hazard as encode — a null-carrying envelope must be dropped, not
        // decoded into an item whose ciphertext is the literal string "null".
        val id = JsonScalars.str(item?.get(FIELD_ID))
        val encrypted = JsonScalars.str(item?.get(FIELD_ENCRYPTED))
        if (id.isNullOrEmpty() || encrypted.isNullOrEmpty()) {
            // CMP-002: keep the drop (a foreign/garbled payload must still pass through as null, not
            // throw) but stop it being silent — a dropped block is prior reasoning vanishing from the
            // replayed transcript with zero indication.
            if (!data.isNullOrEmpty()) {
                log(
                    "[reasoning-replay] dropped an unusable reasoning envelope " +
                        "(len=${data.length}); omitted from replay\n",
                )
            }
            return null
        }
        return buildJsonObject {
            put("type", "reasoning")
            put(FIELD_ID, id)
            put(FIELD_ENCRYPTED, encrypted)
            put(FIELD_SUMMARY, (item?.get(FIELD_SUMMARY) as? JsonArray) ?: JsonArray(emptyList()))
            readableContent(item)?.let { (key, content) -> put(key, content) }
        }
    }

    private fun parseEnvelope(data: String?): JsonObject? = data?.takeIf { it.isNotEmpty() }?.let { encoded ->
        try {
            JsonScalars.objectOrNull(lenient, Base64.getDecoder().decode(encoded).toString(Charsets.UTF_8))
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    /** The optional readable content field, validated identically by both halves of the envelope codec. */
    private fun readableContent(item: JsonObject?): Pair<String, JsonArray>? =
        (item?.get("content") as? JsonArray)?.let { "content" to it }

    /** The data of one redacted_thinking block holding [envelopes], the reasoning GPT gave in a row, in order. */
    public fun joinReasoningEnvelopes(envelopes: List<String>): String =
        envelopes.joinToString(REASONING_RUN_SEPARATOR)

    /** The envelopes a redacted_thinking block's [data] holds, in order: one for a block an older build wrote. */
    public fun splitReasoningEnvelopes(data: String?): List<String> =
        data?.split(REASONING_RUN_SEPARATOR)?.filter { it.isNotEmpty() }.orEmpty()

    /** Every reasoning input item a redacted_thinking block's [data] holds, in order; none for foreign data. */
    public fun decodeReasoningEnvelopes(data: String?, log: LogSink = LogSink(DaemonLog::write)): List<JsonObject> =
        splitReasoningEnvelopes(data).mapNotNull { decodeReasoningEnvelope(it, log) }
}
