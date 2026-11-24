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
        assertEquals("# Skill body", SkillMarkdown().body(item.file))
    }

    @Test
    fun `a UTF-8 BOM does not hide frontmatter`() {
        val markdown = "\uFEFF---\r\nname: selected\r\ndescription: From frontmatter\r\n---\r\n# Body\r\n"
        val item = skill(markdown)
        assertEquals("selected", item.name)
        assertEquals("From frontmatter", item.description)
        assertEquals("# Body\r\n", SkillMarkdown().body(item.file))
    }

    @Test
    fun `a missing closing fence leaves the entire document as body`() {
        val markdown = "---\nname: parsed\nuser-invocable: false\n\n# Body\n"
        val item = skill(markdown)
        assertEquals("from-folder", item.name)
        assertEquals("---", item.menuDescription)
        assertEquals(markdown, SkillMarkdown().body(item.file))
    }

    @Test
    fun `a closing fence beyond the line cap leaves the entire document as body`() {
        val markdown = "---\nname: parsed\n" + "  continuation\n".repeat(200) + "---\nBody\n"
        val item = skill(markdown)
        assertEquals("from-folder", item.name)
        assertEquals(markdown, SkillMarkdown().body(item.file))
    }

    @Test
    fun `the fallback skips bare heading markers and preserves hashtags`() {
        val item = skill("\n###\n  #hashtag in the body\n")
        assertEquals("#hashtag in the body", item.menuDescription)
    }

    @Test
    fun `a long fallback ends with an ellipsis without cutting a Unicode character`() {
        val line = "x".repeat(248) + "🦉 and more content"
        val item = skill("###### $line")
        assertEquals("x".repeat(248) + "🦉…", item.menuDescription)
        assertEquals("###### $line", SkillMarkdown().body(item.file))
    }

    @Test
    fun `a YAML comment ends a plain scalar while a hash inside a word or quotes is text`() {
        val item = skill(
            """
            ---
            name: commented # the folder name stays out of it
            description: Use C# here # note
            argument-hint: "<a # b>" # after the quote
            user-invocable: FALSE # model only
            disable-model-invocation: True # person only
            ---
            Body
            """.trimIndent(),
        )
        assertEquals("commented", item.name)
        assertEquals("Use C# here", item.description)
        assertEquals("<a # b>", item.hint)
        assertEquals(SkillUse(byUser = false, byModel = false), item.use)
    }

    @Test
    fun `a comment on a continuation line ends a plain scalar`() {
        val folded = skill("---\ndescription: first a#b\n  second # stop\n  third\n---\nBody line\n")
        assertEquals("first a#b second", folded.description)
    }

    @Test
    fun `a hash at the start of a value leaves it empty`() {
        val empty = skill("---\ndescription: # nothing here\nuser-invocable: true # still typeable\n---\n# Heading\n")
        assertEquals("Heading", empty.menuDescription, "an empty description falls back to the body")
        assertEquals(SkillUse(byUser = true, byModel = true), empty.use)
    }

    @Test
    fun `a value on the line below an empty key is read as quoted, block or plain where it starts`() {
        val item = skill(
            """
            ---
            name:
              moved # the folder name stays out of it
            description:  # the value follows
              "Use # here,
              then more" # after quote
            argument-hint:
              '<it''s>'
            user-invocable:
              "fAlSe" # model only
            disable-model-invocation:
              'TrUe'
            ---
            Body
            """.trimIndent(),
        )
        assertEquals("moved", item.name)
        assertEquals("Use # here, then more", item.description)
        assertEquals("<it's>", item.hint)
        assertEquals(SkillUse(byUser = false, byModel = false), item.use)
    }

    @Test
    fun `a comment line between a key and its value is skipped at any indent`() {
        val item = skill(
            """
            ---
            user-invocable:
              # Model only
              "fAlSe"
            disable-model-invocation:
            # Person only
              'TrUe'
            description: first
              # between continuation lines
              second
            ---
            Body
            """.trimIndent(),
        )
        assertEquals(SkillUse(byUser = false, byModel = false), item.use)
        assertEquals("first second", item.description)
    }

    @Test
    fun `a comment line inside a block or a multi-line quoted value is its text`() {
        val item = skill(
            """
            ---
            description: |
              line one
              # kept in the block
              line two
            argument-hint: "first
              # kept in the quotes
              last"
            ---
            Body
            """.trimIndent(),
        )
        assertEquals("line one # kept in the block line two", item.description)
        assertEquals("first # kept in the quotes last", item.hint)
    }

    @Test
    fun `only a space or tab before a hash starts a comment`() {
        val item = skill("---\ndescription: Keep #reference\targument-free\t# tab comment\n---\nBody\n")
        assertEquals("Keep #reference\targument-free", item.description)
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
