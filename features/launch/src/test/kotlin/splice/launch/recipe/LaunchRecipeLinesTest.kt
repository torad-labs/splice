// The line form the POSIX launch shim reads: one record per line, so an argv word with a newline in it, an empty word
// and a backslash all reach the shim as exactly one line each, and the shim undoes exactly the three escapes.
package splice.launch.recipe

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import splice.launch.LaunchRecipe

class LaunchRecipeLinesTest {
    @Test
    fun `a recipe is one record per line with newline, backslash and carriage return escaped`() {
        val recipe = LaunchRecipe(
            env = mapOf("FOO" to "a\nb\\c"),
            unset = listOf("ANTHROPIC_API_KEY"),
            argv = listOf("claude", "", "line one\nline two", "cr\rhere"),
            warning = "careful\nnow",
        )
        val want = "splice-launch 1\nU ANTHROPIC_API_KEY\nE FOO=a\\nb\\\\c\nA claude\nA \nA line one\\nline two\n" +
            "A cr\\rhere\nW careful\\nnow\nend\n"
        assertEquals(want, LaunchRecipeLines.render(recipe))
    }
}
