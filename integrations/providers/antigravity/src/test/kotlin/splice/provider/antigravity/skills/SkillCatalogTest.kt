package splice.provider.antigravity.skills

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.util.EnvReader
import splice.core.util.LogSink
import java.nio.file.Files
import java.nio.file.Path

internal class SkillCatalogTest {
    @TempDir
    lateinit var tmp: Path

    @Test
    fun `the person's skills win, then the nearest workspace's, and the gitfile stops ancestor discovery`() {
        val repo = tmp.resolve("parent/repo")
        val work = Files.createDirectories(repo.resolve("work"))
        Files.writeString(repo.resolve(".git"), "gitdir: elsewhere\n")
        writeSkill(work.resolve(".claude/skills/near"), "# Nearest Claude\n")
        writeSkill(work.resolve(".agents/skills/near"), "# Nearest agents\n")
        writeSkill(repo.resolve(".claude/skills/near"), "# Root Claude\n")
        writeSkill(work.resolve(".claude/skills/shared"), "# Project copy\n")
        writeSkill(repo.resolve(".agents/skills/root-agent"), "# Repository skill\n")
        writeSkill(tmp.resolve("parent/.agents/skills/too-far"), "# Outside repository\n")
        writeSkill(home().resolve(".claude/skills/shared"), "# Claude user skill\n")
        writeSkill(home().resolve(".agents/skills/home-agent"), "# Home agent skill\n")
        writeSkill(home().resolve(".agents/skills/shared"), "# Agents user skill\n")
        write(repo.resolve(".claude/commands/build.md"), "Build the app\n")
        write(work.resolve(".claude/commands/shared.md"), "Shadowed by a skill\n")

        val catalog = catalog(work)
        val result = catalog.load()
        assertEquals(listOf("shared", "home-agent", "near", "root-agent"), result.skillNames)
        assertEquals(listOf("shared", "home-agent", "near", "root-agent", "build"), result.commands.map { it.name })
        val menu = result.commands.associate { it.name to it.menuDescription }
        assertEquals("Claude user skill", menu["shared"])
        assertEquals("Nearest Claude", menu["near"])
        assertEquals("Build the app\n", SkillMarkdown().body(result.commands.last().file))
        assertNull(result.commands.last().folder)
        assertEquals(
            listOf(work.resolve(".agents/skills").toRealPath(), repo.resolve(".agents/skills").toRealPath()),
            catalog.nativeRoots(),
        )
    }

    @Test
    fun `desktop manifest omits disabled skills regardless of its identifier key`() {
        val plugin = tmp.resolve("plugin")
        for (name in listOf("by-id", "by-skill-id", "by-name", "visible", "unlisted")) {
            writeSkill(plugin.resolve("skills/$name"), "# $name\n")
        }
        write(
            plugin.resolve("manifest.json"),
            """
            {"lastUpdated":0,"skills":[
              {"id":"by-id","enabled":false},
              {"skillId":"by-skill-id","name":"A display name","enabled":false},
              {"name":"by-name","enabled":false},
              {"skillId":"visible","name":"A visible skill","enabled":true}
            ]}
            """.trimIndent(),
        )

        val result = catalog(work(), plugin).load()
        assertEquals(listOf("unlisted", "visible"), result.skillNames)
        assertEquals("# visible\n", SkillMarkdown().body(result.commands.last().file))
    }

    @Test
    fun `missing plugin manifest is optional and malformed manifest leaves skills enabled`() {
        val plugin = tmp.resolve("plugin")
        writeSkill(plugin.resolve("skills/visible"), "Fallback description\n")
        val logs = mutableListOf<String>()
        val catalog = catalog(work(), plugin, LogSink { logs += it })
        assertEquals(listOf("visible"), catalog.load().skillNames)
        assertTrue(logs.isEmpty())

        write(
            plugin.resolve("manifest.json"),
            """{"skills":[{"name":"visible","enabled":false},{"enabled":"invalid"}]}""",
        )
        val result = catalog.load()
        assertEquals(listOf("visible"), result.skillNames)
        assertEquals("Fallback description", result.commands.single().menuDescription)
        assertTrue(logs.any { "malformed" in it && "manifest" in it })
    }

    @Test
    fun `linked skill documents have canonical paths and aliases and loops do not add copies`() {
        val work = work()
        val roots = Files.createDirectories(work.resolve(".claude/skills"))
        val body = "Run the shared playbook.\n"
        val target = write(tmp.resolve("separate/playbook.md"), "---\n---\n$body")
        val folder = Files.createDirectory(roots.resolve("a-skill"))
        Files.createSymbolicLink(folder.resolve("SKILL.md"), target)
        Files.createSymbolicLink(roots.resolve("b-dir-alias"), folder)
        val fileAlias = Files.createDirectory(roots.resolve("c-file-alias"))
        Files.createSymbolicLink(fileAlias.resolve("SKILL.md"), target)
        val loop = Files.createDirectory(roots.resolve("d-loop")).resolve("SKILL.md")
        Files.createSymbolicLink(loop, loop.fileName)

        val item = catalog(work).load().commands.single()
        assertEquals("a-skill", item.name)
        assertEquals("Run the shared playbook.", item.menuDescription)
        assertEquals(body, SkillMarkdown().body(item.file))
        assertEquals(target.toRealPath(), item.file)
        assertEquals(folder.toRealPath(), item.folder)
    }

    @Test
    fun `plain continuation and quoted escapes reach metadata with the original body`() {
        val work = work()
        val folder = work.resolve(".claude/skills/quoted")
        val body = "\n## Body\nDo the work.\n"
        val front = """
            ---
            name: "renamed"
            description: First line
              second line
              third line
            argument-hint: "\"file\" \\ output"
            ---
        """.trimIndent()
        writeSkill(folder, "$front\n$body")
        writeSkill(
            work.resolve(".agents/skills/single"),
            "---\r\ndescription: 'It''s ready'\r\n---\r\nLine one\r\nLine two\r\n",
        )
        writeSkill(work.resolve(".agents/skills/blank"), "---\ndescription: \" \"\n---\n\n  ### From the body  \n")

        val items = catalog(work).load().commands.associateBy { it.name }
        val quoted = items.getValue("renamed")
        assertEquals("First line second line third line", quoted.description)
        assertEquals("\"file\" \\ output", quoted.hint)
        assertEquals(body, SkillMarkdown().body(quoted.file))
        assertEquals("It's ready", items.getValue("single").description)
        assertEquals("Line one\r\nLine two\r\n", SkillMarkdown().body(items.getValue("single").file))
        assertEquals("From the body", items.getValue("blank").menuDescription)
    }

    @Test
    fun `a body is truncated without breaking a UTF-8 character at the byte limit`() {
        val work = work()
        val front = "---\ndescription: Large document\n---\n"
        val bytesBeforeCharacter = 1024 * 1024 - 2
        val prefix = "a".repeat(bytesBeforeCharacter - front.length)
        val file = writeSkill(work.resolve(".agents/skills/large"), "$front$prefix🦉 not retained").toRealPath()
        val item = catalog(work).load().commands.single()
        val marker = "[truncated: SKILL.md exceeds 1 MiB; read the full file at $file]"
        assertEquals("Large document", item.description)
        assertEquals("$prefix\n\n$marker", SkillMarkdown().body(item.file))
    }

    @Test
    fun `a workspace without git stops before home and still uses user sources`() {
        val home = home()
        val project = Files.createDirectories(home.resolve("parent/work"))
        val config = tmp.resolve("alternate-config")
        writeSkill(project.resolve(".agents/skills/work"), "# Workspace\n")
        writeSkill(home.resolve("parent/.claude/skills/parent"), "# Below home\n")
        writeSkill(home.resolve(".claude/skills/home-claude"), "# Not the selected config\n")
        writeSkill(home.resolve(".agents/skills/home-agent"), "# User agent\n")
        writeSkill(config.resolve("skills/configured"), "# Selected config\n")
        writeSkill(tmp.resolve(".claude/skills/outside-home"), "# Beyond home\n")
        val env = mapOf("HOME" to home.toString(), "CLAUDE_CONFIG_DIR" to config.toString())

        val catalog = ClaudeSkillCatalog(project, EnvReader(env::get), LogSink {})
        val result = catalog.load()
        assertEquals(listOf("configured", "home-agent", "work", "parent"), result.skillNames)
        assertEquals(listOf(project.resolve(".agents/skills").toRealPath()), catalog.nativeRoots())
    }

    @Test
    fun `a capped root yields at most one thousand commands and logs once across reloads`() {
        val project = work()
        val root = Files.createDirectories(project.resolve(".claude/commands"))
        repeat(1_001) { index -> Files.writeString(root.resolve("command-$index.md"), "Command body\n") }
        val logs = mutableListOf<String>()
        val catalog = catalog(project, log = LogSink { logs += it })

        assertEquals(1_000, catalog.load().commands.size)
        assertEquals(1_000, catalog.load().commands.size)
        assertTrue(logs.single().contains("capped at 1000 entries"))
    }

    private fun work(): Path {
        val repo = Files.createDirectories(tmp.resolve("repo"))
        Files.createDirectory(repo.resolve(".git"))
        return Files.createDirectory(repo.resolve("work"))
    }

    private fun home(): Path = tmp.resolve("home")

    private fun catalog(work: Path, plugin: Path? = null, log: LogSink = LogSink {}): ClaudeSkillCatalog {
        val env = EnvReader { name ->
            when (name) {
                "HOME" -> home().toString()
                "CLAUDE_CODE_DESKTOP_SKILLS_PLUGIN_DIR" -> plugin?.toString()
                else -> null
            }
        }
        return ClaudeSkillCatalog(work, env, log)
    }

    private fun writeSkill(folder: Path, text: String): Path = write(folder.resolve("SKILL.md"), text)

    private fun write(file: Path, text: String): Path {
        Files.createDirectories(file.parent)
        return Files.writeString(file, text)
    }
}
