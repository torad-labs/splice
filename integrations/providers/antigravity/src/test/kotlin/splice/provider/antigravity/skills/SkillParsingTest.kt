package splice.provider.antigravity.skills

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.util.EnvReader
import splice.core.util.LogSink
import java.nio.file.Files
import java.nio.file.Path

internal class SkillParsingTest {
    @TempDir
    lateinit var tmp: Path

    @Test
    fun `quoted scalars fold continuation lines and only unescaped quotes close them`() {
        val item = skill(
            """
            ---
            description: 'Astro middleware,
              API routes. It''s
              SSR.'
            argument-hint: "Use \"first\"
              then C:\\work"
            ---
            # Skill body
            """.trimIndent(),
        )

        assertEquals("Astro middleware, API routes. It's SSR.", item.description)
        assertEquals("Use \"first\" then C:\\work", item.hint)
        assertEquals("# Skill body", item.body)
    }

    @Test
    fun `a UTF-8 BOM does not hide frontmatter`() {
        val markdown = "\uFEFF---\r\nname: selected\r\ndescription: From frontmatter\r\n---\r\n# Body\r\n"
        val item = skill(markdown)
        assertEquals("selected", item.name)
        assertEquals("From frontmatter", item.description)
        assertEquals("# Body\r\n", item.body)
    }

    @Test
    fun `a missing closing fence leaves the entire document as body`() {
        val markdown = "---\nname: parsed\nuser-invocable: false\n\n# Body\n"
        val item = skill(markdown)
        assertEquals("from-folder", item.name)
        assertEquals("---", item.description)
        assertEquals(markdown, item.body)
    }

    @Test
    fun `a closing fence beyond the line cap leaves the entire document as body`() {
        val markdown = "---\nname: parsed\n" + "  continuation\n".repeat(200) + "---\nBody\n"
        val item = skill(markdown)
        assertEquals("from-folder", item.name)
        assertEquals(markdown, item.body)
    }

    @Test
    fun `the fallback skips bare heading markers and preserves hashtags`() {
        val item = skill("\n###\n  #hashtag in the body\n")
        assertEquals("#hashtag in the body", item.description)
    }

    @Test
    fun `a long fallback ends with an ellipsis without cutting a Unicode character`() {
        val line = "x".repeat(248) + "🦉 and more content"
        val item = skill("###### $line")
        assertEquals("x".repeat(248) + "🦉…", item.description)
        assertEquals("###### $line", item.body)
    }

    private fun skill(markdown: String): LocalSkillItem {
        val project = Files.createDirectories(tmp.resolve("project"))
        Files.createDirectory(project.resolve(".git"))
        val folder = Files.createDirectories(project.resolve(".claude/skills/from-folder"))
        Files.writeString(folder.resolve("SKILL.md"), markdown)
        val env = mapOf("HOME" to tmp.resolve("home").toString())
        return ClaudeSkillCatalog(project, EnvReader(env::get), LogSink {}).load().commands.single()
    }
}
