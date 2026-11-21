package splice.modes.template

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.modes.api.ModeAnswer
import splice.modes.api.ModeCall
import splice.modes.api.ModeCaller
import splice.modes.api.ModeRequest
import splice.modes.api.ModeResponse
import splice.modes.api.ModeSession
import splice.modes.api.ModeStore

/** The template's handler and its route, through the contract, as splice calls them. */
class TemplateModeTest {
    private val store = MemoryStore()
    private val mode = TemplateMode()
    private val session = ModeSession("s-1", "/work", "astra", "model-1", ModeCaller.DOOR)

    private fun call(input: JsonObject): ModeAnswer = mode.answer(ModeCall("say_back", input, session, store))

    private fun text(value: String) = JsonObject(mapOf("text" to JsonPrimitive(value)))

    private fun said(query: Map<String, String>): ModeResponse {
        val route = mode.routes.single { it.method == "GET" && it.path == "/api/template/said" }
        return route.handler.handle(ModeRequest("GET", "/api/template/said", query, null, store))
    }

    @Test
    fun `say_back answers with the text it is given, unchanged`() {
        assertEquals(ModeAnswer.Text("  hello, there \n"), call(text("  hello, there \n")))
    }

    @Test
    fun `an input without a string text is an error answer that says so`() {
        for (input in listOf(JsonObject(emptyMap()), JsonObject(mapOf("text" to JsonPrimitive(7))))) {
            val answer = call(input)
            assertTrue(answer is ModeAnswer.Error) { "$input gave $answer" }
            assertTrue("text" in answer.text) { answer.text }
        }
    }

    @Test
    fun `the route lists what was said back in a session, in order, and a session it never saw is empty`() {
        call(text("one"))
        call(text("two"))
        val listed = said(mapOf("session" to "s-1"))
        assertEquals(200, listed.status)
        assertEquals("s-1", listed.body.jsonObject["session"]?.jsonPrimitive?.content)
        assertEquals(listOf("one", "two"), listed.body.jsonObject["said"]?.jsonArray?.map { it.jsonPrimitive.content })
        val other = said(mapOf("session" to "other"))
        assertEquals(emptyList<String>(), other.body.jsonObject["said"]?.jsonArray?.toList())
    }

    @Test
    fun `the route without a session answers 400 with an error`() {
        val answer = said(emptyMap())
        assertEquals(400, answer.status)
        assertTrue(answer.body.jsonObject["error"] != null) { "${answer.body}" }
    }

    private class MemoryStore : ModeStore {
        private val texts = HashMap<String, String>()

        override fun read(session: String): String? = texts[session]

        override fun write(session: String, text: String) {
            texts[session] = text
        }
    }
}
