package splice.provider.codex

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import splice.dialect.responses.request.ResponsesCodeModeProjection
import splice.provider.codex.history.CodeModeClientAcknowledgements

class CodeModeAcknowledgedMediaTest {
    private val user = item("""{"role":"user","content":"continue"}""")

    @Test
    fun `verified cleared image follow-ups do not change an acknowledged baseline`() {
        val image =
            item("""{"role":"user","content":[{"type":"input_image","image_url":"data:image/png;base64,AA=="}]}""")
        val original = listOf(user) + callback("old") + image
        val acknowledgement = CodeModeClientAcknowledgements.capture(original, mapOf("old" to listOf(image)))
        val marker =
            item("""{"type":"function_call_output","call_id":"old","output":"[Old tool result content cleared]"}""")
        val cleared = listOf(user, callback("old").first(), marker)
        val projection = ResponsesCodeModeProjection().project(JsonArray(cleared))
        assertEquals(cleared.size, acknowledgement.boundary(projection, setOf("old")))
        assertEquals(null, acknowledgement.boundary(projection))
    }

    private fun callback(id: String): List<JsonElement> = listOf(
        item("""{"type":"function_call","call_id":"$id","name":"Read","arguments":"{}"}"""),
        item("""{"type":"function_call_output","call_id":"$id","output":"result"}"""),
    )

    private fun item(text: String): JsonElement = Json.parseToJsonElement(text)
}
