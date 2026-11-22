// NEW: head-catalog (gw-retire). What splice lays in a head's own Claude folder, named once from the materializer's own
// contract: the files it writes whole at the folder's top (settings.json, the memo of the hooks it merged, its record
// of the sessions it started, its hook scripts), the items it links to the person's own ~/.claude (sharedLinkItems),
// its entries in the folder's real commands and skills dirs (login.md, /fast and /ultrafast, and the person's own
// entries linked in one by one), and the keys it sets in the folder's .claude.json. HookSettings reads the hook
// scripts from here for the entries it owns, and retiring a head reads all of it for what to move out. Anything else
// in the folder is the person's or Claude Code's, and Claude Code's session data (transcripts, the peer registry,
// todos, history) is never splice's to move.
package splice.client

import splice.client.login.LOGIN_HOOK_SH
import splice.client.login.LoginInterception
import splice.client.resume.ForegroundHook
import splice.client.resume.ResumeHook
import splice.client.resume.SESSION_OWNERSHIP_FILE
import splice.client.speed.SKILL_FILE
import splice.client.speed.SpeedHook
import splice.client.speed.SpeedSkills
import splice.core.speed.Speed
import splice.core.util.LogSink
import splice.core.util.PathProbe
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.isSymbolicLink

/** How splice laid one entry: a file it wrote, a link it made, or a dir it made that holds nothing else. */
public enum class LaidKind { FILE, LINK, DIR }

/** One entry splice laid in a head's folder. */
public data class Laid(val path: Path, val kind: LaidKind)

/** A head folder as splice laid it: [laid] its entries, children before the dir that holds them; [state] the folder's
 *  .claude.json when it holds a key splice sets; [others] every entry that is not splice's and not session data. */
public data class HeadFolderContents(val laid: List<Laid>, val state: Path?, val others: List<Path>)

public object HeadFolderLayout {
    /** The hook scripts splice stages at the folder's top. */
    internal val hookScripts: List<String> = listOf(
        LOGIN_HOOK_SH,
        LoginInterception.CAPTURE_HOOK_SH,
        LoginInterception.KEYSETUP_HOOK_SH,
        ResumeHook.RESUME_HOOK_SH,
        ForegroundHook.SCRIPT,
        SpeedHook.SPEED_HOOK_SH,
    )

    /** The keys splice sets in the folder's .claude.json: the picker cache an earlier launch wrote, the custom-key
     *  approvals it keeps for its gateway key, and the onboarding it marks done. The rest is Claude Code's. */
    public val stateKeys: List<String> =
        listOf(ClaudeModelLists.STATE_CACHE, Keys.CUSTOM_API_KEY_RESPONSES, Keys.ONBOARDING)

    private val files: Set<String> = setOf(Keys.SETTINGS_FILE_NAME, HookSettings.ORIGINS, SESSION_OWNERSHIP_FILE) +
        hookScripts

    // Claude Code's session data: shared or kept by the folder, and never moved.
    private val sessionData: Set<String> = setOf(
        Keys.SESSIONS,
        Keys.PROJECTS,
        "todos",
        "history.jsonl",
        "shell-snapshots",
        "session-env",
        "file-history",
    )

    // The real dirs splice fills with entries of its own beside the person's, linked in one by one.
    private val ownDirs: Set<String> = setOf(Keys.COMMANDS, Keys.SKILLS)

    private val speedSkills = SpeedSkills(LogSink {})

    /** The hook commands splice owns in [configDir]'s settings, by the path each script is staged at. */
    internal fun hookCommands(configDir: Path): Set<String> =
        hookScripts.map { configDir.resolve(it).toString() }.toSet()

    /** What splice laid in [folder], a head's own Claude folder beside the person's [home]. Reads only. */
    public fun contents(folder: Path, home: Path): HeadFolderContents {
        val global = home.resolve(Keys.VANILLA_DIR)
        // The person's own ~/.claude is never a head's folder, as the materializer refuses to write there.
        if (folder.toAbsolutePath().normalize() == global.toAbsolutePath().normalize()) {
            return HeadFolderContents(emptyList(), null, emptyList())
        }
        val laid = mutableListOf<Laid>()
        val others = mutableListOf<Path>()
        val (state, rest) = entries(folder).filterNot { it.fileName.toString() in sessionData }
            .partition { it.fileName.toString() == Keys.CLAUDE_JSON }
        rest.forEach { top(it, global, laid, others) }
        return HeadFolderContents(laid, state.firstOrNull()?.takeIf { holdsStateKey(it) }, others)
    }

    /** One entry at the folder's top: a file splice writes whole, a link it made to the person's [global] item of
     *  the same name, one of the real dirs it fills, or the person's. */
    private fun top(entry: Path, global: Path, laid: MutableList<Laid>, others: MutableList<Path>) {
        val name = entry.fileName.toString()
        when {
            name in files && !entry.isDirectory(NOFOLLOW_LINKS) -> laid.add(Laid(entry, kindOf(entry)))
            name in sharedLinkItems && linksInto(entry, global) -> laid.add(Laid(entry, LaidKind.LINK))
            name in ownDirs && entry.isDirectory(NOFOLLOW_LINKS) -> ownDir(entry, global.resolve(name), laid, others)
            else -> others.add(entry)
        }
    }

    /** [dir]'s entries splice laid (links into the person's [global] dir of the same name, login.md, splice's speed
     *  skills), and the dir itself when nothing else is in it. */
    private fun ownDir(dir: Path, global: Path, laid: MutableList<Laid>, others: MutableList<Path>) {
        val before = others.size
        val inCommands = dir.fileName.toString() == Keys.COMMANDS
        for (entry in entries(dir)) {
            when {
                linksInto(entry, global) -> laid.add(Laid(entry, LaidKind.LINK))
                inCommands && isLogin(entry) -> laid.add(Laid(entry, LaidKind.FILE))
                !inCommands && isSpeedSkill(entry) -> speedSkill(entry, laid, others)
                else -> others.add(entry)
            }
        }
        if (others.size == before) laid.add(Laid(dir, LaidKind.DIR))
    }

    /** One of splice's speed skills: its SKILL.md, and the skill's dir when nothing else is in it. */
    private fun speedSkill(dir: Path, laid: MutableList<Laid>, others: MutableList<Path>) {
        val before = others.size
        for (entry in entries(dir)) {
            if (entry.fileName.toString() == SKILL_FILE && entry.isRegularFile(NOFOLLOW_LINKS)) {
                laid.add(Laid(entry, LaidKind.FILE))
            } else {
                others.add(entry)
            }
        }
        if (others.size == before) laid.add(Laid(dir, LaidKind.DIR))
    }

    private fun isLogin(entry: Path): Boolean =
        entry.fileName.toString() == Keys.LOGIN_MD && entry.isRegularFile(NOFOLLOW_LINKS)

    private fun isSpeedSkill(entry: Path): Boolean =
        entry.fileName.toString() in Speed.entries.map { it.word } && entry.isDirectory(NOFOLLOW_LINKS) &&
            speedSkills.isOwn(entry)

    private fun linksInto(entry: Path, global: Path): Boolean =
        entry.isSymbolicLink() && PathProbe.linkTarget(entry)?.normalize()?.startsWith(global.normalize()) == true

    private fun kindOf(entry: Path): LaidKind = if (entry.isSymbolicLink()) LaidKind.LINK else LaidKind.FILE

    private fun holdsStateKey(file: Path): Boolean =
        ClaudeModelLists.read(file)?.keys.orEmpty().any { it in stateKeys }

    private fun entries(dir: Path): List<Path> = if (!dir.isDirectory(NOFOLLOW_LINKS)) {
        emptyList()
    } else {
        Files.newDirectoryStream(dir).use { stream -> stream.sortedBy { it.fileName.toString() } }
    }
}
