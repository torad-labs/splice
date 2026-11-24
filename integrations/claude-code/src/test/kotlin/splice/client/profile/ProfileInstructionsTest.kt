// Terminal launch profiles (issue #791, comment §4): the marker forms `drop_blocks` takes out of CLAUDE.md, as the
// profile's file reads after a launch.
package splice.client.profile

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ProfileInstructionsTest {
    private fun stripped(text: String, vararg markers: String) = ProfileInstructions(markers.toList()).stripped(text)

    @Test
    fun `every opening form closes with any closing form of the same name, markers alone on their line`() {
        val text = "a\n  <!--  BEGIN tool  -->\nx\n<!-- /tool -->\nb\n<!-- tool -->\ny\n<!-- END tool -->\n" +
            "c\n# BEGIN tool\nz\n# END tool\nd"
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
}
