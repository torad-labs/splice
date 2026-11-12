package splice.provider.codex.stream

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CodeModeFunctionOutputTest {
    @Test
    fun `a single text item is a string including empty text`() {
        for (value in listOf("", "Script terminated\nWall time 0.0 seconds\nOutput:\n")) {
            assertEquals(JsonPrimitive(value), CodeModeFunctionOutput.body(listOf(text(value))))
        }
    }

    @Test
    fun `empty multiple and media content keep their item arrays`() {
        val image = Json.parseToJsonElement("""{"type":"input_image","image_url":"fixture"}""").jsonObject
        val audio = Json.parseToJsonElement("""{"type":"input_audio","audio_url":"fixture"}""").jsonObject
        for (items in listOf(emptyList(), listOf(text("one"), text("two")), listOf(image), listOf(audio))) {
            assertEquals(JsonArray(items), CodeModeFunctionOutput.body(items))
        }
    }

    private fun text(value: String): JsonObject = buildJsonObject {
        put("type", "input_text")
        put("text", value)
    }
}
