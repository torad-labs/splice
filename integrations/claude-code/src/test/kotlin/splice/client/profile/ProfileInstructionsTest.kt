// Terminal launch profiles (issue #791, comment §4): the marker forms `drop_blocks` takes out of CLAUDE.md, as the
// profile's file reads after a launch.
package splice.client.profile

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ProfileInstructionsTest {
    private fun stripped(text: String, vararg markers: String) = ProfileInstructions(markers.toList()).stripped(text)

    @Test
    fun `comment and hash forms close across one another for the same name`() {
        val text = "a\n  <!--  BEGIN tool  -->\nx\n<!-- /tool -->\nb\n<!-- tool -->\ny\n# END tool\n" +
            "c\n# BEGIN tool\nz\n<!-- END tool -->\nd"
        assertEquals("a\nb\nc\nd", stripped(text, "tool").text)
    }

    @Test
    fun `a marker inside a line, another name, or no markers asked for leaves the text as it is`() {
        val text = "see <!-- tool --> here\n<!-- toolbox -->\nkept\n<!-- /toolbox -->\n"
        assertEquals(text, stripped(text, "tool").text)
        assertEquals(text, stripped(text).text)
    }

    @Test
    fun `a name with regex characters is read as written`() {
        assertEquals("a\n", stripped("a\n<!-- a.b+ -->\nx\n<!-- /a.b+ -->\n", "a.b+").text)
        assertEquals("a\n<!-- axb -->\nx\n<!-- /axb -->\n", stripped("a\n<!-- axb -->\nx\n<!-- /axb -->\n", "a.b").text)
    }

    @Test
    fun `a block never closed is kept and named, and the blocks before it still go`() {
        val result = stripped("<!-- t -->\nx\n<!-- /t -->\nkeep\n<!-- t -->\nrest\n", "t")
        assertEquals("keep\n<!-- t -->\nrest\n", result.text)
        assertEquals(listOf("t"), result.unclosed)
    }

    @Test
    fun `XML blocks are dropped with or without quoted attributes`() {
        val text = """
            before
              <tool>
            gone
            </tool  >
            between
            <tool version="1" label='a > b' >
            gone too
            </tool>
            after
        """.trimIndent()
        assertEquals(ProfileInstructions.Stripped("before\nbetween\nafter", emptyList()), stripped(text, "tool"))
    }

    @Test
    fun `self closing tags drop only their own lines`() {
        val text = "before\n<tool/>\nbetween\n<tool version='1' />\nafter"
        assertEquals(ProfileInstructions.Stripped("before\nbetween\nafter", emptyList()), stripped(text, "tool"))
    }

    @Test
    fun `XML markers must match the whole tag name on its own line`() {
        val text = """
            see <foo> here
            <foo>inline</foo>
            <foobar>
            </foobar>
            <foo-bar>
            </foo-bar>
            <foo_bar>
            </foo_bar>
            <foo:x>
            </foo:x>
        """.trimIndent()
        assertEquals(ProfileInstructions.Stripped(text, emptyList()), stripped(text, "foo"))
    }

    @Test
    fun `an XML block with its outer tag still open is kept whole and named`() {
        val text = "before\r\n  <tool>\r\n<tool>\r\ninside\r\n</tool>\r\n  tail"
        assertEquals(ProfileInstructions.Stripped(text, listOf("tool")), stripped(text, "tool"))
    }

    @Test
    fun `XML and comment forms never close one another`() {
        val text = """
            before
            <tool>
            <!-- /tool -->
            <!-- END tool -->
            # END tool
            still inside XML
            </tool>
            between
            <!-- tool -->
            </tool>
            still inside comment
            # END tool
            after
        """.trimIndent()
        assertEquals(ProfileInstructions.Stripped("before\nbetween\nafter", emptyList()), stripped(text, "tool"))
    }

    @Test
    fun `kept LF and CRLF lines retain their endings even before a final dropped block`() {
        for (ending in listOf("\n", "\r\n")) {
            val text = listOf("  keep  ", " <tool>", "gone", " </tool > ").joinToString(ending)
            assertEquals("  keep  $ending", stripped(text, "tool").text)
        }
    }

    @Test
    fun `a nested same name XML block ends at the outer close`() {
        val text = """
            before
            <tool>
            <tool mode='nested'>
            <tool/>
            </tool>
            still inside outer block
            </tool>
            after
        """.trimIndent()
        assertEquals(ProfileInstructions.Stripped("before\nafter", emptyList()), stripped(text, "tool"))
    }

    @Test
    fun `one file can contain several dropped blocks in all three forms`() {
        val text = """
            first
            <!-- notes -->
            drop
            <!-- /notes -->
            second
            <org:block>
            drop
            </org:block>
            # BEGIN notes
            drop
            # END notes
            third
            <org:block />
            <!-- my notes -->
            drop
            <!-- /my notes -->
            last
        """.trimIndent()
        val result = stripped(text, "notes", "org:block", "my notes")
        assertEquals(ProfileInstructions.Stripped("first\nsecond\nthird\nlast", emptyList()), result)
    }
}
