// NEW: gpt-speed (docs/specs/gpt-speed.md, decision 1) — /fast and /ultrafast as skills in a head's own
// CLAUDE_CONFIG_DIR/skills. A skill named like a built-in command replaces it in Claude Code's / menu (a file in
// commands/ does not reliably: code.claude.com/docs/en/skills, "Resolve skills that share a name"), and its description
// is what the menu shows beside it.
//
// The head's skills dir is shared with the person's ~/.claude/skills by a whole-dir link on every other head, and a
// write through that link would land /fast in the person's own skills, offered in plain Claude Code and in every head.
// So a head that offers a speed holds a REAL skills dir: the person's skills re-linked entry by entry (GlobalEntryLinks,
// as commands/ already is for /login) and splice's own two beside them. The swap from a link is staged complete first,
// so a working skills dir is never seen missing.
//
// A head that offers no speed (not signed in with ChatGPT, or a model whose catalog entry lists no tier) gets neither
// skill, and a skill an earlier launch laid down is removed. Only a real dir whose SKILL.md opens with splice's sentinel
// is splice's: anything else at those names is the person's and stays.
package splice.client.speed

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import splice.client.wrap.GlobalEntryLinks
import splice.client.wrap.HeadRealDir
import splice.core.model.ServiceTier
import splice.core.speed.Speed
import splice.core.util.LogSink
import splice.core.util.SecureFile
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.isSymbolicLink

private const val SKILLS_DIR = "skills"
internal const val SKILL_FILE: String = "SKILL.md"

/** The speed skills of one head. */
internal class SpeedSkills(private val log: LogSink) {

    /** Lays down a skill for each of [tiers] (a tier splice has no speed for is skipped) in [configDir]'s skills, and
     *  removes splice's skill for any speed not among them. [globalSkills] is the person's ~/.claude/skills to re-link
     *  when the head shares them, or null. Throws on an I/O failure; the caller owns that leg's policy. */
    fun write(configDir: Path, tiers: List<ServiceTier>, globalSkills: Path?) {
        val offered = tiers.mapNotNull { tier -> tier.speed?.let { it to tier } }.distinctBy { it.first }.toMap()
        // No speed to offer and no real skills dir (a link to the person's, or none) holds nothing of splice's.
        if (offered.isEmpty() && !Files.isDirectory(configDir.resolve(SKILLS_DIR), NOFOLLOW_LINKS)) return
        // A whole-dir link becomes the real dir the skills live in, staged complete beside it first.
        HeadRealDir.write(configDir, SKILLS_DIR) { fill(it, offered, globalSkills) }
    }

    /** [skills] with splice's skill for each [offered] speed and none for any other, and the person's [globalSkills]
     *  linked in beside them, minus the names splice's own take. */
    private fun fill(skills: Path, offered: Map<Speed, ServiceTier>, globalSkills: Path?) {
        Speed.entries.filter { it != Speed.STANDARD && it !in offered }.forEach { removeOwn(skills, it) }
        globalSkills?.let { GlobalEntryLinks(offered.keys.map { it.word }.toSet()).linkInto(skills, it) }
        offered.forEach { (speed, tier) -> writeOne(skills, speed, tier) }
    }

    private fun writeOne(skills: Path, speed: Speed, tier: ServiceTier) {
        val dir = skills.resolve(speed.word)
        // A link at the name is the person's own skill of that name, re-linked from ~/.claude/skills: this head's /fast
        // is splice's, and removing the link leaves the person's skill where it is.
        if (dir.isSymbolicLink()) Files.delete(dir)
        if (dir.isDirectory(NOFOLLOW_LINKS) && !isOwn(dir)) {
            log("[speed] $dir is not splice's, so this head keeps it and offers no splice ${speed.title}\n")
            return
        }
        Files.createDirectories(dir)
        SecureFile.writeAtomic0600(dir.resolve(SKILL_FILE), skillText(speed, tier))
    }

    private fun removeOwn(skills: Path, speed: Speed) {
        val dir = skills.resolve(speed.word)
        if (!dir.isDirectory(NOFOLLOW_LINKS) || !isOwn(dir)) return
        Files.deleteIfExists(dir.resolve(SKILL_FILE))
        Files.deleteIfExists(dir)
    }

    fun isOwn(dir: Path): Boolean = try {
        bodyOf(Files.readString(dir.resolve(SKILL_FILE))).startsWith("$SPEED_SENTINEL ")
    } catch (_: NoSuchFileException) {
        false
    }

    private fun bodyOf(text: String): String = text.substringAfter("\n---\n", text).trimStart()

    /** The skill's frontmatter: the description the menu shows, the arguments it takes, and that only the person runs
     *  it, since a model that could switch it would spend the person's plan faster on its own say. Its body is the
     *  sentinel line the head's hook reads, should Claude Code hand the hook the expanded skill. */
    private fun skillText(speed: Speed, tier: ServiceTier): String {
        val description = "${SpeedCommands.costOf(speed, tier)} Sets this session's speed: bare toggles it, " +
            "on and off set it, off is standard speed."
        return buildString {
            appendLine("---")
            appendLine("description: ${Json.encodeToString(JsonPrimitive.serializer(), JsonPrimitive(description))}")
            appendLine("argument-hint: \"[on|off]\"")
            appendLine("disable-model-invocation: true")
            appendLine("---")
            appendLine("$SPEED_SENTINEL ${speed.word} ${'$'}ARGUMENTS")
        }
    }
}
