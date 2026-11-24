// NEW: #791 (server mode, F9 on the OpenAI route). What an older client's chat completion is answered with when
// `[server.client] outdated_status` is 200: the minimum client's notice as the assistant's whole answer, in OpenAI's
// shape, so an OpenAI SDK shows it as it shows any answer. A request that asks for a stream gets one chunk carrying
// the notice and `[DONE]`; any other gets one `chat.completion` body. Nothing reaches the provider.
package splice.server.client

import io.ktor.http.ContentType
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import splice.core.util.JsonScalars

internal class OpenAiNotice {
    /** Answers [call], whose request body was [sent], with [told] as the assistant's answer. */
    suspend fun answer(call: ApplicationCall, sent: ByteArray, told: String) {
        val asked = JsonScalars.objectOrNull(Json, sent.decodeToString())
        val model = JsonScalars.str(asked, ASKED_MODEL_FIELD).orEmpty()
        val streamed = (asked?.get(ASKED_STREAM_FIELD) as? JsonPrimitive)?.content == "true"
        if (!streamed) return call.respondText(whole(model, told).toString(), ContentType.Application.Json)
        val chunk = completion(model, CHUNK_OBJECT, DELTA_FIELD, told)
        call.respondText("data: $chunk\n\ndata: [DONE]\n\n", ContentType.Text.EventStream)
    }

    private fun whole(model: String, told: String): JsonObject = completion(model, WHOLE_OBJECT, MESSAGE_FIELD, told)

    /** One completion object, [kind] `chat.completion` or its chunk, whose one choice carries [told] under [field]. */
    private fun completion(model: String, kind: String, field: String, told: String): JsonObject = buildJsonObject {
        put("id", NOTICE_ID)
        put("object", kind)
        put("created", 0)
        put(ASKED_MODEL_FIELD, model)
        put(
            "choices",
            buildJsonArray {
                add(
                    buildJsonObject {
                        put("index", 0)
                        putJsonObject(field) {
                            put("role", CHAT_ANSWER_ROLE)
                            put("content", told)
                        }
                        put("finish_reason", "stop")
                    },
                )
            },
        )
    }
}

// The role a Chat Completions answer's message names, this wire's own word.
private const val CHAT_ANSWER_ROLE = "assistant"
private const val NOTICE_ID = "chatcmpl-splice-client-minimum"
private const val WHOLE_OBJECT = "chat.completion"
private const val CHUNK_OBJECT = "chat.completion.chunk"
private const val ASKED_MODEL_FIELD = "model"
private const val ASKED_STREAM_FIELD = "stream"
private const val MESSAGE_FIELD = "message"
private const val DELTA_FIELD = "delta"
