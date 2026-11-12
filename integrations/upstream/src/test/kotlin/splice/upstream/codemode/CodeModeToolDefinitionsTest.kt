package splice.upstream.codemode

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class CodeModeToolDefinitionsTest {
    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `an explicit functions namespace keeps guidance and identity with flat callable names`(toolFirst: Boolean) {
        val spec = Json.parseToJsonElement(
            """{"type":"namespace","name":"functions","description":" Explicit functions ","tools":[
              {"type":"function","name":"alpha","description":"Alpha","parameters":{"type":"object"}},
              {"type":"custom","name":"raw","description":"Raw"}
            ]}""",
        )
        val tools = CodeModeToolDefinitions.collect(listOf(spec))
        assertEquals(listOf("alpha", "raw"), tools.map { it.codeModeName })
        assertEquals(listOf("functions", "functions"), tools.map { it.metadata.namespace })
        val prepared = CodeModeManual.prepare(tools, toolDescriptionFirst = toolFirst)
        for ((index, description) in listOf("Alpha", "Raw").withIndex()) {
            val prefix = if (toolFirst) "$description\n\nExplicit functions" else "Explicit functions\n\n$description"
            assertTrue(prepared[index].description.startsWith("$prefix\n\nexec tool declaration:"))
        }
        assertTrue(prepared[0].description.contains("alpha(args:"))
        assertTrue(prepared[1].description.contains("raw(input: string)"))
    }
}
