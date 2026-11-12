package splice.upstream.codemode

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class CodeModeFunctionPrefixesTest {
    @Test
    fun `an empty namespace specification keeps its original description`() {
        val tool = CodeModeManual.NestedTool(
            "alpha",
            "Tool alpha",
            null,
            metadata = CodeModeManual.Metadata(namespace = ""),
        )
        val prefixes = CodeModeFunctionPrefixes(mapOf("alpha" to "Guidance"), listOf(tool))
        assertEquals(listOf(tool), prefixes.apply(listOf(tool)))
    }

    @Test
    fun `an available empty namespace still counts toward the source prefix budget`() {
        val tool = CodeModeManual.NestedTool(
            "alpha",
            "Tool alpha",
            null,
            metadata = CodeModeManual.Metadata(namespace = ""),
        )
        val error = assertThrows(IllegalArgumentException::class.java) {
            CodeModeFunctionPrefixes(mapOf("alpha" to "x".repeat(255)), listOf(tool))
        }
        assertEquals("Available functions' description prefixes exceed the 256-byte combined limit", error.message)
    }
}
