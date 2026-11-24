// NEW: desktop-antigravity (ACP skills). The bounded skill index a new agy session gets through
// `_meta.antigravity.systemPrompt.append`: the name, a one-line description and the SKILL.md path of each local skill
// agy does not discover by itself, so agy reads a skill when a task calls for it and loads nothing otherwise. Skills
// agy serves are left out: one found in an `.agents/skills` folder of the workspace walk (even when its entry links
// elsewhere), one that is an alias of such a skill's folder, and one sharing such a skill's name. The index never
// carries Desktop's system prompt.
package splice.provider.antigravity.skills

import java.nio.file.Path

/** Builds the skill index for agy's system prompt. */
internal class SkillIndex {
    /** [catalog]'s skills the model may choose and agy does not serve itself, as an index; null when none qualify. A
     *  `disable-model-invocation: true` skill stays out (only the person may start it), while a `user-invocable:
     *  false` one stays in. agy serves a skill found in one of its [nativeRoots], one whose real folder is that of one
     *  of its [native] skills (an alias that links there), and one with the name of one of them. */
    fun of(catalog: LocalSkillCatalog, nativeRoots: List<Path>, native: List<LocalSkillItem> = emptyList()): String? {
        val skills = catalog.commands.filter { skill ->
            skill.use.byModel && containedSkill(skill) && !agyServes(skill, nativeRoots, native)
        }
        if (skills.isEmpty()) return null
        val lines = mutableListOf(INDEX_HEADER)
        var chars = INDEX_HEADER.length
        for (skill in skills) {
            val entry = line(skill)
            if (lines.size > MAX_INDEXED || chars + entry.length + 1 > MAX_INDEX_CHARS) break
            lines += entry
            chars += entry.length + 1
        }
        val omitted = skills.size - (lines.size - 1)
        if (omitted > 0) lines += "($omitted more skills omitted)"
        return lines.joinToString("\n")
    }

    /** Whether agy already serves [skill]: found in one of its folders whatever the entry links to, the same real
     *  folder as one of its skills, or the same name. */
    private fun agyServes(skill: LocalSkillItem, nativeRoots: List<Path>, native: List<LocalSkillItem>): Boolean =
        skill.root in nativeRoots || native.any { it.folder == skill.folder || it.name == skill.name }

    private fun containedSkill(skill: LocalSkillItem): Boolean =
        skill.folder?.startsWith(skill.root) == true && skill.file.startsWith(skill.root)

    private fun line(skill: LocalSkillItem): String {
        val summary = skill.description.replace(WHITESPACE, " ").trim()
        val cut = MAX_SUMMARY - ELLIPSIS.length
        val short = if (summary.length <= MAX_SUMMARY) summary else summary.take(cut) + ELLIPSIS
        val about = if (short.isEmpty()) "" else ": $short"
        return "- ${skill.name}$about (${skill.file})"
    }
}

private const val INDEX_HEADER =
    "Local skills available (read the SKILL.md at the path when a task matches; do not load them otherwise):"
private const val ELLIPSIS = "..."

// why: the index rides in every request's system prompt; a hundred entries lists a typical library whole.
private const val MAX_INDEXED = 100

// why: about 3k tokens at most, so a large skill library never crowds agy's context.
private const val MAX_INDEX_CHARS = 12_000

// why: one line per skill is enough for agy to tell when to read its SKILL.md.
private const val MAX_SUMMARY = 160
private val WHITESPACE = Regex("\\s+")
