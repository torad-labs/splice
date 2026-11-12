package splice.provider.codex.stream.arguments

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import splice.provider.codex.diagnostics.CodeModeArgumentsProbe

class CodeModeSearchArgumentsTest {
    @Test
    fun `a query collection reports the source serde position before its opening byte`() {
        for ((value, kind) in listOf("{}" to "map", "[]" to "sequence")) {
            val observed = CodeModeArgumentsProbe.evaluate(
                buildJsonObject {
                    put("operation", "tool_search_arguments")
                    put("arguments", "{\"query\":$value}")
                },
            )
            assertEquals(
                buildJsonObject {
                    put(
                        "error",
                        "failed to parse function arguments: invalid type: $kind, expected a string at line 1 column 9",
                    )
                },
                observed,
            )
        }
    }
}
