// A document inside a tool_result cannot ride a chat `tool` message, which is string-only: the model must read that
// one was omitted rather than an empty result it may take for a tool that returned nothing.
package splice.dialect.chat

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.parse.AnthropicParse

class ChatToolResultDocumentTest {

    @Test
    fun `a document inside a tool_result leaves its marker in the tool message`() {
        val json = """{"model":"m","messages":[
            {"role":"assistant","content":[{"type":"tool_use","id":"t1","name":"Fetch","input":{}}]},
            {"role":"user","content":[{"type":"tool_result","tool_use_id":"t1","content":[
              {"type":"document","source":{"type":"base64","media_type":"application/pdf","data":"JVBERi0="}}]}]}]}"""
        val parsed = AnthropicParse.parseAnthropicBody(json)
        val req = ChatRequestBuilder(ChatQuirks(providerTag = "chat"))
            .build(parsed.typed, upstreamModel = "m", compact = false, raw = parsed.raw).req
        val tool = req["messages"]!!.jsonArray.map { it.jsonObject }
            .single { it["role"]?.jsonPrimitive?.content == "tool" }
        assertTrue(tool["content"]!!.jsonPrimitive.content.contains("document omitted"), "$tool")
    }
}
