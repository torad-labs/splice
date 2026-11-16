// NEW: server mode (docs/specs/server-mode.md, F4). `GET /<command>/v1/models/<id>`, the lookup of one model an
// OpenAI-style client makes before it uses it (`client.models.retrieve`). OpenAI's API answers the model's own object,
// or a 404. A self-hosted model server (llama-server, vLLM) answers its list of models and often no lookup of one, so
// the lookup is answered from the list: the listener asks the provider's server for its models exactly as the list
// route does, and what the client gets is read out of that answer here. A model the server lists is answered with the
// server's own object for it, and any other id is a 404 in OpenAI's error shape. A list the server refused goes back
// as its refusal, as it does to a client that asked for the list.
package splice.server

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import splice.core.util.JsonScalars
import splice.core.util.JsonWire
import splice.core.wire.HttpStatus
import splice.share.WholeAnswer
import splice.share.WholeEdit

/** Reads the models list of [command]'s provider for the one model [id] names. */
internal class ModelLookup(private val id: String, private val command: String) : WholeEdit {
    override fun read(body: ByteArray): WholeAnswer {
        val listed = JsonScalars.objectOrNull(Json, String(body))?.get(LIST_DATA) as? JsonArray
            ?: return WholeAnswer.Refused(HttpStatus.BAD_GATEWAY, "the models list of $command's server cannot be read")
        val model = listed.filterIsInstance<JsonObject>().firstOrNull { JsonScalars.str(it, MODEL_ID) == id }
            ?: return WholeAnswer.Refused(HttpStatus.NOT_FOUND, "$command serves no model named $id")
        // OpenAI's model object says what it is, so one from a list whose entries leave that out says it too.
        return WholeAnswer.Given(JsonWire.bytes(JsonObject(mapOf(OBJECT_FIELD to JsonPrimitive(MODEL_OBJECT)) + model)))
    }
}

// The fields of OpenAI's models list and of one model in it.
private const val LIST_DATA = "data"
private const val MODEL_ID = "id"
private const val OBJECT_FIELD = "object"
private const val MODEL_OBJECT = "model"
