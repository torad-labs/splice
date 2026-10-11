package splice.dialect.anthropic

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import splice.core.prompt.SystemPromptMode

class PassthroughClientSystemMetadataTest {
    private val prompt = PassthroughSystemPrompt()
    private val billing = block(
        "x-anthropic-billing-header: cc_version=2.1.285.bc6; cc_entrypoint=sdk-cli;",
    )
    private val identity = block(
        "You are a Claude agent, built on Anthropic's Claude Agent SDK.",
        """{"type":"ephemeral","ttl":"1h"}""",
    )
    private val clientPrompt = block("Client prompt.", """{"type":"ephemeral","ttl":"1h"}""")
    private val replacement = block("Operator prompt.", """{"type":"ephemeral","ttl":"1h"}""")

    @Test
    fun `replace keeps the captured billing block byte equal and first before the cached operator prompt`() {
        val request = request(billing, identity, clientPrompt)

        val actual = prompt.apply(request, "Operator prompt.", SystemPromptMode.REPLACE)

        assertEquals(request(billing, replacement), actual)
        val kept = actual.getValue("system").jsonArray.first()
        assertSame(billing, kept)
        assertEquals(billing.toString(), kept.toString())
    }

    @Test
    fun `all exact prefix billing text blocks retain their fields and order`() {
        val second = Json.parseToJsonElement(
            """{"type":"text","text":"x-anthropic-billing-header: another-client-value;","cache_control":{"type":""" +
                """"ephemeral"},"client_field":{"original":true}}""",
        )
        val original = request(identity, second, clientPrompt, billing)

        val actual = prompt.apply(original, "Operator prompt.", SystemPromptMode.REPLACE)
            .getValue("system").jsonArray

        val expected = listOf(second, billing, replacement).map { it.toString() }
        assertEquals(expected, actual.map { it.toString() })
        assertSame(second, actual[0])
        assertSame(billing, actual[1])
    }

    @Test
    fun `requests without billing metadata retain the existing replacement bytes`() {
        val original = request(identity, clientPrompt)

        assertEquals(
            request(block("Operator prompt.")),
            prompt.apply(original, "Operator prompt.", SystemPromptMode.REPLACE),
        )
    }

    @Test
    fun `lookalike prefixes and non text blocks do not survive replace`() {
        val wrongType = Json.parseToJsonElement(
            """{"type":"other","text":"x-anthropic-billing-header: not-a-text-block"}""",
        )
        val original = request(
            block(" x-anthropic-billing-header: leading-space"),
            block("X-Anthropic-Billing-Header: different-case"),
            block("x-anthropic-billing-headerish: not-the-prefix"),
            block("Quoted x-anthropic-billing-header: inside-the-prompt"),
            wrongType,
        )

        assertEquals(
            request(block("Operator prompt.")),
            prompt.apply(original, "Operator prompt.", SystemPromptMode.REPLACE),
        )
    }

    @Test
    fun `replace inherits only the last removed text block cache policy without synthesizing one`() {
        val policy = """{"type":"ephemeral","ttl":"5m","client_extension":true}"""
        val original = request(billing, identity, block("Actual client prompt.", policy))

        assertEquals(
            request(billing, block("Operator prompt.", policy)),
            prompt.apply(original, "Operator prompt.", SystemPromptMode.REPLACE),
        )
        assertEquals(
            request(billing, block("Operator prompt.")),
            prompt.apply(
                request(billing, identity, block("Uncached prompt.")),
                "Operator prompt.",
                SystemPromptMode.REPLACE,
            ),
        )
        assertEquals(
            request(billing, block("Operator prompt.")),
            prompt.apply(request(billing), "Operator prompt.", SystemPromptMode.REPLACE),
        )
    }

    @Test
    fun `append still keeps all client blocks unchanged and adds an uncached trailing block`() {
        val original = request(billing, identity, clientPrompt)

        assertEquals(
            request(billing, identity, clientPrompt, block("Operator prompt.")),
            prompt.apply(original, "Operator prompt.", SystemPromptMode.APPEND),
        )
    }

    @Test
    fun `strip still edits prompt paragraphs in place without removing metadata or cache policy`() {
        val original = request(
            billing,
            identity,
            block("Remove this.\n\nKeep this.", """{"type":"ephemeral","ttl":"1h"}"""),
        )

        assertEquals(
            request(billing, identity, block("Keep this.", """{"type":"ephemeral","ttl":"1h"}""")),
            prompt.apply(original, "^Remove this", SystemPromptMode.STRIP),
        )
    }

    @Test
    fun `bare string replace remains a single operator block and never splits metadata out of prose`() {
        for (text in listOf("Client prompt.", "x-anthropic-billing-header: text\n\nClient prompt.")) {
            val original = buildJsonObject { put("system", text) }

            assertEquals(
                request(block("Operator prompt.")),
                prompt.apply(original, "Operator prompt.", SystemPromptMode.REPLACE),
            )
        }
    }

    private fun request(vararg blocks: kotlinx.serialization.json.JsonElement): JsonObject =
        buildJsonObject { put("system", JsonArray(blocks.toList())) }

    private fun block(text: String, cache: String? = null): JsonObject = buildJsonObject {
        put("type", "text")
        put("text", text)
        cache?.let { put("cache_control", Json.parseToJsonElement(it)) }
    }
}
