package splice.core.capability

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** A mode's manifest read whole, as the modes catalog reads it. */
class TomlTreeTest {
    @Test
    fun `a manifest reads as JSON, its tables as objects and its array of tables as a list`() {
        val text = """
            # a comment
            name = "visual"  # trailing
            tools = ["show", "read_me"]
            on = false

            [heads]
            only = []

            [[resources]]
            uri = "ui://visual/app.html"
            _meta = { ui = { csp = { connectDomains = [] } } }

            [[resources]]
            uri = "ui://visual/two.html"
        """.trimIndent()
        val expected = Json.parseToJsonElement(
            """{"name":"visual","tools":["show","read_me"],"on":false,"heads":{"only":[]},
               "resources":[{"uri":"ui://visual/app.html","_meta":{"ui":{"csp":{"connectDomains":[]}}}},
                            {"uri":"ui://visual/two.html"}]}""",
        )
        assertEquals(expected, TomlTree.read(text))
    }

    @Test
    fun `text that is not TOML, a value it cannot read, and a key given twice read as null`() {
        assertNull(TomlTree.read("this is not toml\n"))
        assertNull(TomlTree.read("name = \"open\n"))
        assertNull(TomlTree.read("name = \"a\"\nname = \"b\"\n"))
        assertNull(TomlTree.read("[heads\nonly = []\n"))
    }
}
