// The skill index agy's system prompt gets: one line per skill agy cannot find itself, descriptions cut to one short
// line, and a large library capped with a count of what was left out.
package splice.provider.antigravity.skills

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path

internal class SkillIndexTest {
    @Test
    fun `commands and skills under agy's own folders stay out of the index`() {
        val native = Path.of("/work/.agents/skills")
        val catalog = catalogOf(
            skill("mine", "Mine", Path.of("/home/me/.claude/skills/mine")),
            skill("theirs", "Theirs", native.resolve("theirs")),
            command("cmd", "A command", Path.of("/home/me/.claude/commands")),
        )
        val index = checkNotNull(SkillIndex().of(catalog, listOf(native)))
        assertEquals(listOf(HEADER, "- mine: Mine (/home/me/.claude/skills/mine/SKILL.md)"), index.lines())
        assertNull(SkillIndex().of(catalogOf(skill("theirs", "Theirs", native.resolve("theirs"))), listOf(native)))
    }

    @Test
    fun `a long description is cut to one line and a large library ends with what was left out`() {
        val many = (1..120).map { skill("skill-$it", "word ".repeat(60), Path.of("/s/skill-$it")) }
        val index = checkNotNull(SkillIndex().of(catalogOf(*many.toTypedArray()), emptyList()))
        val lines = index.lines()
        assertTrue(index.length <= 12_000 + 40, "bounded, got ${index.length}")
        assertTrue(lines.drop(1).dropLast(1).all { it.startsWith("- skill-") && it.contains("... (/s/skill-") })
        val listed = lines.size - 2
        assertEquals("(${120 - listed} more skills omitted)", lines.last())
        assertTrue(listed in 1..100, "listed $listed")
    }

    private fun catalogOf(vararg items: LocalSkillItem) =
        LocalSkillCatalog(items.toList(), items.filter { it.isSkill }.map { it.name })

    private fun skill(name: String, description: String, folder: Path): LocalSkillItem {
        val file = folder.resolve("SKILL.md")
        return LocalSkillItem(name, description, "", "", SkillPlace(file, folder, folder.parent, file))
    }

    private fun command(name: String, description: String, root: Path) =
        LocalSkillItem(name, description, "", "", SkillPlace(root.resolve("$name.md"), null, root, null))
}

private const val HEADER =
    "Local skills available (read the SKILL.md at the path when a task matches; do not load them otherwise):"
