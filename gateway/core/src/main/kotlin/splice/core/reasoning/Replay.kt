// PORT-OF: server/src/reasoning/replay.mjs @ 4ca99f7 — invariants: envelope tag
// 'splice-reasoning' v1, encode/decode stay PAIRED (a tag/version bump strands in-flight
// transcripts — byte-compat is pinned against Node-produced fixtures); encode emits compact
// JSON with insertion order tag,v,item{id,encrypted_content,summary?} (summary omitted when
// empty — byte-identity with JSON.stringify); decode rejects foreign/garbled payloads with
// null (the block is silently dropped, exactly as under pure amnesia) and requires a
// non-empty id + encrypted_content; decoded items ALWAYS carry a summary array.
@file:Suppress("StringLiteralDuplication") // envelope field names are the wire contract

package splice.core.reasoning

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.util.Base64

public const val REASONING_ENVELOPE_TAG: String = "splice-reasoning"
public const val REASONING_ENVELOPE_VERSION: Int = 1

private val lenient = Json { ignoreUnknownKeys = true }

/** Responses `reasoning` output item -> base64 envelope for a redacted_thinking block. */
public fun encodeReasoningEnvelope(item: JsonObject): String? {
    val id = (item["id"] as? JsonPrimitive)?.content ?: return null
    val encrypted = (item["encrypted_content"] as? JsonPrimitive)?.content ?: return null
    val summary = item["summary"] as? JsonArray
    val envelope = buildJsonObject {
        put("tag", REASONING_ENVELOPE_TAG)
        put("v", REASONING_ENVELOPE_VERSION)
        put(
            "item",
            buildJsonObject {
                put("id", id)
                put("encrypted_content", encrypted)
                if (summary != null && summary.isNotEmpty()) put("summary", summary)
            },
        )
    }
    return Base64.getEncoder().encodeToString(envelope.toString().toByteArray(Charsets.UTF_8))
}

/** redacted_thinking `data` -> Responses `reasoning` input item, or null for foreign data. */
@Suppress("TooGenericExceptionCaught", "ReturnCount", "InstanceOfCheckForException")
// foreign/garbled payloads pass through as null
public fun decodeReasoningEnvelope(data: String?): JsonObject? {
    if (data.isNullOrEmpty()) return null
    val parsed = try {
        val text = Base64.getDecoder().decode(data).toString(Charsets.UTF_8)
        lenient.parseToJsonElement(text).jsonObject
    } catch (e: Exception) {
        if (e is java.util.concurrent.CancellationException) throw e
        return null
    }
    val tagOk = (parsed["tag"] as? JsonPrimitive)?.content == REASONING_ENVELOPE_TAG
    val versionOk = (parsed["v"] as? JsonPrimitive)?.content == REASONING_ENVELOPE_VERSION.toString()
    val item = if (tagOk && versionOk) parsed["item"] as? JsonObject else null
    val id = (item?.get("id") as? JsonPrimitive)?.content
    val encrypted = (item?.get("encrypted_content") as? JsonPrimitive)?.content
    if (id.isNullOrEmpty() || encrypted.isNullOrEmpty()) return null
    return buildJsonObject {
        put("type", "reasoning")
        put("id", id)
        put("encrypted_content", encrypted)
        put("summary", (item["summary"] as? JsonArray) ?: JsonArray(emptyList()))
    }
}
