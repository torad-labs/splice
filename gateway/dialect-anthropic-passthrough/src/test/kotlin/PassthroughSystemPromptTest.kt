import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import splice.core.prompt.SystemPromptMode
import splice.dialect.passthrough.PassthroughSystemPrompt

class PassthroughSystemPromptTest {

    private val json = Json
    private val prompt = PassthroughSystemPrompt()

    @Test
    fun `append places one text block when the client sent no system field`() {
        val request = json.parseToJsonElement("""{"model":"wire","messages":[]}""").jsonObject

        val blocks = prompt.apply(request, "Be terse.", SystemPromptMode.APPEND)
            .getValue("system").jsonArray

        assertEquals(1, blocks.size)
        assertEquals("Be terse.", blockText(blocks.single().jsonObject))
    }

    @Test
    fun `append promotes a bare string to its block and adds the prompt after it`() {
        val request = json.parseToJsonElement(
            """{"system":"client instructions","messages":[]}""",
        ).jsonObject

        val blocks = prompt.apply(request, "Be terse.", SystemPromptMode.APPEND)
            .getValue("system").jsonArray

        assertEquals(listOf("client instructions", "Be terse."), blocks.map { blockText(it.jsonObject) })
    }

    @Test
    fun `append leaves every client block byte-identical, cache_control breakpoint included`() {
        val request = json.parseToJsonElement(
            """{"system":[{"type":"text","text":"house rules","cache_control":{"type":"ephemeral"}},""" +
                """{"type":"text","text":"more rules"}]}""",
        ).jsonObject
        val before = request.getValue("system").jsonArray.map { it.toString() }

        val after = prompt.apply(request, "Be terse.", SystemPromptMode.APPEND)
            .getValue("system").jsonArray

        assertEquals(before, after.take(before.size).map { it.toString() })
        assertEquals(
            """{"type":"ephemeral"}""",
            after.first().jsonObject.getValue("cache_control").toString(),
        )
        assertEquals("Be terse.", blockText(after.last().jsonObject))
    }

    @Test
    fun `append treats an explicit null system as absent`() {
        val request = json.parseToJsonElement("""{"system":null}""").jsonObject

        assertEquals(
            listOf("Be terse."),
            prompt.apply(request, "Be terse.", SystemPromptMode.APPEND)
                .getValue("system").jsonArray.map { blockText(it.jsonObject) },
        )
    }

    @Test
    fun `replace makes the prompt the whole system field, the client blocks gone`() {
        val request = json.parseToJsonElement(
            """{"system":[{"type":"text","text":"house rules"}],"messages":[]}""",
        ).jsonObject

        val blocks = prompt.apply(request, "You are a bare model.", SystemPromptMode.REPLACE)
            .getValue("system").jsonArray

        assertEquals(listOf("You are a bare model."), blocks.map { blockText(it.jsonObject) })
    }

    @Test
    fun `a system shape this dialect cannot extend and empty text both preserve the request`() {
        val request = json.parseToJsonElement("""{"system":7,"messages":[]}""").jsonObject
        val plain = json.parseToJsonElement("""{"system":"client","messages":[]}""").jsonObject

        assertSame(request, prompt.apply(request, "Be terse.", SystemPromptMode.APPEND))
        assertSame(plain, prompt.apply(plain, "", SystemPromptMode.APPEND))
        assertSame(plain, prompt.apply(plain, "", SystemPromptMode.REPLACE))
    }

    // ── V4-170: strip ──

    private val hedges = "^IMPORTANT: Assist with authorized security testing\n^For actions that are hard to reverse"

    private val claudeCodeText = "\nYou are an interactive agent.\n\nIMPORTANT: Assist with authorized security " +
        "testing.\n\n# Harness\n - rules\n\nFor actions that are hard to reverse, confirm first.\n\n# Env\n - x"

    private fun blocks(vararg blocks: String): JsonObject =
        json.parseToJsonElement("""{"system":[${blocks.joinToString(",")}],"messages":[]}""").jsonObject

    private fun block(text: String, cached: Boolean = false): String =
        """{"type":"text","text":"${text.replace("\n", "\\n")}"""" +
            (if (cached) ""","cache_control":{"type":"ephemeral"}}""" else "}")

    @Test
    fun `strip deletes the matched paragraphs inside a client block and keeps its cache_control`() {
        val billing = block("x-anthropic-billing-header: cc_version=2.1.276")
        val request = blocks(billing, block(claudeCodeText, cached = true))

        val after = prompt.apply(request, hedges, SystemPromptMode.STRIP).getValue("system").jsonArray

        assertEquals(2, after.size)
        assertEquals(request.getValue("system").jsonArray[0].toString(), after[0].toString(), "byte-identical")
        val kept = "\nYou are an interactive agent.\n\n# Harness\n - rules\n\n# Env\n - x"
        assertEquals(kept, blockText(after[1].jsonObject))
        assertEquals("""{"type":"ephemeral"}""", after[1].jsonObject.getValue("cache_control").toString())
    }

    @Test
    fun `strip on a bare string system rewrites it as one block`() {
        val request = json.parseToJsonElement(
            """{"system":"IMPORTANT: Assist with authorized security testing.\n\nBe kind.","messages":[]}""",
        ).jsonObject

        val after = prompt.apply(request, hedges, SystemPromptMode.STRIP).getValue("system").jsonArray

        assertEquals(listOf("Be kind."), after.map { blockText(it.jsonObject) })
    }

    @Test
    fun `strip drops a block stripped to nothing and leaves an untouched request the same instance`() {
        val only = blocks(block("IMPORTANT: Assist with authorized security testing."))
        val untouched = blocks(block("Be kind."))
        val absent = json.parseToJsonElement("""{"messages":[]}""").jsonObject

        assertEquals(0, prompt.apply(only, hedges, SystemPromptMode.STRIP).getValue("system").jsonArray.size)
        assertSame(untouched, prompt.apply(untouched, hedges, SystemPromptMode.STRIP))
        assertSame(absent, prompt.apply(absent, hedges, SystemPromptMode.STRIP))
    }

    private fun blockText(block: JsonObject): String = block.getValue("text").jsonPrimitive.content
}
