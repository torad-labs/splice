// NEW: desktop-3p-parity (Nov 23, 2026). The person's own skills given to Claude Desktop on the gateway (`Claude-3p`),
// whose Chat and Cowork load skills from the profile's skills plugin
// (`local-agent-mode-sessions/skills-plugin/<org>/<account>/`, a `skills/<name>/` folder per skill and a
// `manifest.json` listing them). Offered: each skill in Claude Code's `~/.claude/skills/`, then each skill the
// person made in Claude Desktop (`creatorType` "user" in a skills plugin of the 1P profile); Anthropic's own skills
// are Desktop's to give in each mode. A skill is copied whole (DesktopTreeCopy) into each skills plugin Desktop made
// in the profile that has no skill of that name, and listed there as the person's (`creatorType` "user", not
// sync-managed), so Desktop keeps it and they can turn it off. Nothing already in a plugin is changed, and a plugin
// Desktop has not made yet is not made.
package splice.app.desktop.setup

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.util.JsonScalars
import java.nio.file.Path
import java.time.Clock
import java.time.Instant

/** One skill offered to the profile: its [name], the folder holding its `SKILL.md`, and what it says it is for. */
internal data class DesktopSkill(val name: String, val folder: Path, val description: String)

internal class DesktopSkillsSync(
    private val files: DesktopLibraryFiles,
    private val tree: DesktopTreeCopy,
    /** Claude Code's own folder, `~/.claude`. */
    private val claudeDir: Path,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val offers = DesktopSkillOffers(files)

    /** The person's skills given to each skills plugin of [targetAppData]; whether any was. */
    fun sync(sourceAppData: Path, targetAppData: Path): Boolean {
        val plugins = offers.plugins(targetAppData)
        if (plugins.isEmpty()) return false
        val sameRoot = sourceAppData.toAbsolutePath().normalize() == targetAppData.toAbsolutePath().normalize()
        val fromDesktop = if (sameRoot) emptyList() else offers.desktopSkills(sourceAppData)
        val offered = (offers.codeSkills(claudeDir.resolve(SKILLS_FOLDER)) + fromDesktop).distinctBy { it.name }
        if (offered.isEmpty()) return false
        return plugins.fold(false) { changed, plugin -> give(plugin, offered) || changed }
    }

    /** Each skill of [offered] that [plugin] lacks copied in and listed; whether any was. */
    private fun give(plugin: Path, offered: List<DesktopSkill>): Boolean {
        val manifestFile = plugin.resolve(SKILLS_MANIFEST)
        val manifest = files.read(manifestFile) ?: JsonObject(emptyMap())
        val listed = manifest[MANIFEST_SKILLS] as? JsonArray ?: return false
        val held = listed.mapNotNull { JsonScalars.str(it as? JsonObject, SKILL_NAME) }.toSet()
        val given = offered.filter { skill ->
            skill.name !in held && tree.copy(skill.folder, plugin.resolve(SKILLS_FOLDER).resolve(skill.name))
        }
        if (given.isEmpty()) return false
        val now: Instant = clock.instant()
        val entries = JsonArray(listed + given.map { entry(it, now.toString()) })
        val updated = JsonPrimitive(now.toEpochMilli())
        files.write(manifestFile, JsonObject(manifest + mapOf(MANIFEST_SKILLS to entries, MANIFEST_UPDATED to updated)))
        return true
    }

    /** [skill] as Desktop lists a skill the person made. */
    private fun entry(skill: DesktopSkill, updatedAt: String): JsonElement = buildJsonObject {
        put("skillId", skill.name)
        put(SKILL_NAME, skill.name)
        put("description", skill.description)
        put(SKILL_CREATOR, SKILL_CREATOR_USER)
        put("syncManaged", false)
        put("updatedAt", updatedAt)
        put("enabled", true)
    }
}

/** A skills plugin's list of its skills, and each entry's keys. */
internal const val SKILLS_MANIFEST: String = "manifest.json"
internal const val MANIFEST_SKILLS: String = "skills"
internal const val SKILL_NAME: String = "name"
internal const val SKILL_CREATOR: String = "creatorType"
internal const val SKILL_CREATOR_USER: String = "user"

/** The folder skills sit in, in Claude Code's folder and in a skills plugin alike. */
internal const val SKILLS_FOLDER: String = "skills"
private const val MANIFEST_UPDATED = "lastUpdated"
