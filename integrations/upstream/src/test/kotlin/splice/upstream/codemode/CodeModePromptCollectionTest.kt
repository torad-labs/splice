package splice.upstream.codemode

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.util.JsonScalars
import splice.upstream.codemode.diagnostics.CodeModeCoreProbe

class CodeModePromptCollectionTest {
    private val json = Json

    @Test
    fun `exec prompt collection sorts and deduplicates while direct rendering retains supplied order`() {
        val first = CodeModeManual.NestedTool("zeta", "First zeta", null)
        val alpha = CodeModeManual.NestedTool("alpha", "Alpha", null)
        val duplicate = first.copy(description = "Second zeta")
        val supplied = listOf(first, alpha, duplicate)
        assertEquals(listOf(alpha, first), CodeModeToolDefinitions.prompt(supplied))
        val direct = CodeModeManual.description(supplied, false)
        assertTrue(direct.indexOf("First zeta") < direct.indexOf("Alpha"))
        assertTrue(direct.indexOf("Alpha") < direct.indexOf("Second zeta"))
    }

    @Test
    fun `prefix API uses the real prompt collector before rendering its supplied definitions`() {
        val input = json.parseToJsonElement(
            """
            {"operation":"prefixes","tools":[
              {"name":"zeta","description":"Zeta"},
              {"name":"alpha","description":"Alpha"},
              {"name":"alpha","description":"Second alpha"}
            ]}
            """.trimIndent(),
        ).jsonObject
        val result: JsonObject = CodeModeCoreProbe.evaluate(input)
        val description = checkNotNull(JsonScalars.str(result, "prompt_description"))
        val expected = """
            ### `alpha`
            Alpha

            exec tool declaration:
            ```ts
            declare const tools: { alpha(args: { [key: string]: unknown; }): Promise<unknown>; };
            ```

            ### `zeta`
            Zeta

            exec tool declaration:
            ```ts
            declare const tools: { zeta(args: { [key: string]: unknown; }): Promise<unknown>; };
            ```
        """.trimIndent()
        assertTrue(description.endsWith(expected), description)
    }
}
