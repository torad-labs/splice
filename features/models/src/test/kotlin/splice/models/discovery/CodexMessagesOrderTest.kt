package splice.models.discovery

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CodexMessagesOrderTest {
    @Test
    fun `discovered and cached catalog messages preserve values in source declaration order`() {
        val input = request()
        val result = ModelMetadataProbe.evaluate(input)
        for (name in listOf("metadata", "cached_metadata")) {
            val row = (result.getValue(name) as JsonArray).single() as JsonObject
            val messages = row.getValue("model_messages") as JsonObject
            assertMessageOrder(messages)
            val original = ((input.getValue("models") as JsonArray).single() as JsonObject).getValue("model_messages")
            assertEquals(original, messages, "ordering must retain every supplied message value")
        }
    }

    private fun assertMessageOrder(messages: JsonObject) {
        assertEquals(
            listOf(
                "tools",
                "instructions_template",
                "approvals",
                "collaboration_modes",
                "auto_review",
                "permissions",
                "multi_agent",
                "future_messages",
            ),
            messages.keys.toList(),
        )
        val tools = messages.getValue("tools") as JsonObject
        assertEquals(listOf("functions_namespace_functions_description_prefixes", "code_mode"), tools.keys.toList())
        val codeMode = tools.getValue("code_mode") as JsonObject
        assertEquals(listOf("exec", "wait"), codeMode.keys.toList())
        assertEquals(listOf("description", "parameters"), (codeMode.getValue("wait") as JsonObject).keys.toList())
    }

    private fun request(): JsonObject = Json.parseToJsonElement(
        """{
              "provider":{"dialect":"openai-responses","base_url":"https://fixture.invalid","auth":{"kind":"api-key"}},
              "models":[{"slug":"messages","model_messages":{
                "instructions_template":"Synthetic instructions.","approvals":null,"collaboration_modes":null,
                "auto_review":null,"permissions":null,"multi_agent":null,
                "tools":{"code_mode":{"wait":{"parameters":"{}","description":"Wait"},
                  "exec":{"description":"Exec"}},"functions_namespace_functions_description_prefixes":{"read":"Keep"}},
                "future_messages":{"keep":[null,"raw"]}
              }}]
            }""",
    ) as JsonObject
}
