package splice.provider.codex

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import splice.core.turn.UsageField
import splice.provider.codex.stream.CodeModeSourceUsage

class CodeModeSourceUsageCompatibilityTest {
    @Test
    fun `legacy source usage retains its old complete numeric shape`() {
        val stored = """{"inputTokens":100,"outputTokens":7,"cachedTokens":20,""" +
            """"reasoningTokens":3,"cacheWriteTokens":0}"""
        val usage = Json.decodeFromString(CodeModeSourceUsage.serializer(), stored).value()
        assertEquals(UsageField.entries.toSet(), usage.reported)
        assertEquals(100L, usage.inputTokens)
        assertEquals(7L, usage.outputTokens)
    }

    @Test
    fun `new source usage can persist explicitly unreported zeros`() {
        val source = CodeModeSourceUsage(0, 0, 0, 0, 0, reported = emptySet())
        val encoded = Json.encodeToString(CodeModeSourceUsage.serializer(), source)
        val usage = Json.decodeFromString(CodeModeSourceUsage.serializer(), encoded).value()
        assertEquals(emptySet<UsageField>(), usage.reported)
    }
}
