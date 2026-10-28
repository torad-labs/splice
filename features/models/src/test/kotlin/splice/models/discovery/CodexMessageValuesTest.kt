package splice.models.discovery

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import splice.core.util.JsonScalars
import splice.core.util.JsonWire

class CodexMessageValuesTest {
    @Test
    fun `model messages and their persisted cache match immutable typed catalog values`() {
        val bytes = checkNotNull(javaClass.getResourceAsStream("/codex-model-messages-0.165.json"))
            .use { it.readAllBytes() }
        val vectors = Json.parseToJsonElement(bytes.decodeToString()).jsonObject
        assertEquals("360319d8dee62d87e2d59fc0c21805d79519cfed", JsonScalars.str(vectors, "sourceRevision"))
        for (element in vectors.getValue("cases").jsonArray) {
            val case = element.jsonObject
            val result = ModelMetadataProbe.evaluate(case.getValue("input").jsonObject)
            for (destination in listOf("metadata", "cached_metadata")) {
                val row = (result.getValue(destination) as JsonArray).single() as JsonObject
                val expected = case.getValue("expected")
                val actual = row.getValue("model_messages")
                assertEquals(expected, actual, "${JsonScalars.str(case, "id")}: $destination")
                assertEquals(JsonWire.string(expected), JsonWire.string(actual), "typed catalog key order")
            }
        }
    }
}
