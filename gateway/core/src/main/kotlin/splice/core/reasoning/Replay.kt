// PORT-OF: server/src/reasoning/replay.mjs @ 4ca99f7 — invariants: envelope tag
// 'splice-reasoning' v1, encode/decode stay PAIRED (a tag/version bump strands in-flight
// transcripts — byte-compat is pinned against Node-produced fixtures); encode emits compact
// JSON with insertion order tag,v,item{id,encrypted_content,summary?} (summary omitted when
// empty — byte-identity with JSON.stringify); decode rejects foreign/garbled payloads with
// null (the block is silently dropped, exactly as under pure amnesia) and requires a
// non-empty id + encrypted_content; decoded items ALWAYS carry a summary array.
package splice.core.reasoning

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import splice.core.util.runCatchingCancellable
import java.util.Base64

public const val REASONING_ENVELOPE_TAG: String = "splice-reasoning"
public const val REASONING_ENVELOPE_VERSION: Int = 1

// envelope field names are the wire contract — named once, referenced everywhere so encode/decode
// can never drift apart.
private const val FIELD_TAG = "tag"
private const val FIELD_VERSION = "v"
private const val FIELD_ITEM = "item"
private const val FIELD_ID = "id"
private const val FIELD_ENCRYPTED = "encrypted_content"
private const val FIELD_SUMMARY = "summary"

private val lenient = Json { ignoreUnknownKeys = true }

/** Responses `reasoning` output item -> base64 envelope for a redacted_thinking block. */
public fun encodeReasoningEnvelope(item: JsonObject): String? {
    val id = (item[FIELD_ID] as? JsonPrimitive)?.content ?: return null
    val encrypted = (item[FIELD_ENCRYPTED] as? JsonPrimitive)?.content ?: return null
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
            },
        )
    }
    return Base64.getEncoder().encodeToString(envelope.toString().toByteArray(Charsets.UTF_8))
}

/** redacted_thinking `data` -> Responses `reasoning` input item, or null for foreign data. */
// foreign/garbled payloads pass through as null
public fun decodeReasoningEnvelope(data: String?): JsonObject? {
    val parsed = data?.takeIf { it.isNotEmpty() }?.let { encoded ->
        runCatchingCancellable {
            val text = Base64.getDecoder().decode(encoded).toString(Charsets.UTF_8)
            lenient.parseToJsonElement(text).jsonObject
        }.getOrNull()
    }
    val tagOk = (parsed?.get(FIELD_TAG) as? JsonPrimitive)?.content == REASONING_ENVELOPE_TAG
    val versionOk = (parsed?.get(FIELD_VERSION) as? JsonPrimitive)?.content == REASONING_ENVELOPE_VERSION.toString()
    val item = if (tagOk && versionOk) parsed?.get(FIELD_ITEM) as? JsonObject else null
    val id = (item?.get(FIELD_ID) as? JsonPrimitive)?.content
    val encrypted = (item?.get(FIELD_ENCRYPTED) as? JsonPrimitive)?.content
    if (id.isNullOrEmpty() || encrypted.isNullOrEmpty()) return null
    return buildJsonObject {
        put("type", "reasoning")
        put(FIELD_ID, id)
        put(FIELD_ENCRYPTED, encrypted)
        put(FIELD_SUMMARY, (item[FIELD_SUMMARY] as? JsonArray) ?: JsonArray(emptyList()))
    }
}
