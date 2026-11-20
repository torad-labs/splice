// The template mode's handler: it says back the text it is given, and keeps what it said in each session.
package splice.modes.template

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import splice.core.wire.HttpStatus
import splice.modes.api.Mode
import splice.modes.api.ModeAnswer
import splice.modes.api.ModeCall
import splice.modes.api.ModeRequest
import splice.modes.api.ModeResponse
import splice.modes.api.ModeRoute

/** The route that lists what the mode said back in a session. */
private const val SAID_PATH = "/api/template/said"
private const val SAY_BACK = "say_back"

/** Answers say_back with its `text`, unchanged, and keeps every text it said back in the session's store. */
internal class TemplateMode : Mode {
    private val said = TemplateSaid()

    override fun answer(call: ModeCall): ModeAnswer {
        if (call.tool != SAY_BACK) return ModeAnswer.Error("there is no tool ${call.tool} in this mode")
        val text = (call.input["text"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: return ModeAnswer.Error("say_back needs a text, as a string")
        said.add(call.store, call.session.id, text)
        return ModeAnswer.Text(text)
    }

    override val routes: List<ModeRoute> = listOf(ModeRoute("GET", SAID_PATH, ::listSaid))

    /** `GET /api/template/said?session=<id>`: what the mode said back in that session, in order. */
    private fun listSaid(request: ModeRequest): ModeResponse {
        val session = request.query["session"]?.takeIf { it.isNotBlank() }
            ?: return ModeResponse(HttpStatus.BAD_REQUEST, json("error" to JsonPrimitive("session is required")))
        val list = said.of(request.store, session).map(::JsonPrimitive)
        return ModeResponse(HttpStatus.OK, json("session" to JsonPrimitive(session), "said" to JsonArray(list)))
    }

    private fun json(vararg pairs: Pair<String, JsonElement>): JsonObject = JsonObject(mapOf(*pairs))
}
