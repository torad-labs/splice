// NEW: door-default (docs/specs/door-default.md, decision 5). splice's `/login` in a `claude` session on the door. It
// has to be the bare `/login`, and Claude Code 2.1.326 offers a bare command only from the session's own Claude folder
// (a plugin's only under its prefix, a folder handed by --add-dir only behind a trust dialog), so splice lays ONE file
// in the person's own Claude folder, `commands/login.md`, and nothing else. The hook that answers it lives in splice's
// state folder and rides each launch's `--settings` overlay, so a session splice did not start has no hook and reads
// the file's own words.
//
// Splice's file is told from the person's by the sentinel it submits (LoginScript), which no command of theirs holds:
// a `/login` they wrote, as a command or a skill, is never replaced and never taken out.
package splice.client.login

import kotlinx.serialization.json.JsonObject
import splice.client.Keys
import splice.core.util.SecureFile
import java.nio.file.Files
import java.nio.file.Path

/** splice's `/login` over the person's Claude folder, [claudeDir] (UserHome.claudeDir), answered by a hook laid in
 *  [hookDir]. */
public class DoorLogin(private val claudeDir: Path, private val hookDir: Path) {
    private val login = LoginScript()
    private val file: Path = claudeDir.resolve(Keys.COMMANDS).resolve(Keys.LOGIN_MD)

    /** splice's file, laid where no `/login` is there; one of splice's is written again, so it reads as this splice
     *  writes it. Returns the person's own `/login` when it is in the way, which is left as it is. Throws the disk's
     *  IOException. */
    public fun lay(): Path? {
        theirs()?.let { return it }
        val text = login.command()
        when (current()) {
            text -> Unit
            // Created only where nothing is there, so a /login the person writes meanwhile is never written over.
            null -> SecureFile.createNew0600(
                Files.createDirectories(file.parent).resolve(Keys.LOGIN_MD),
                text.toByteArray(),
            )
            else -> SecureFile.writeAtomic0600(file, text)
        }
        return null
    }

    /** The person's own `/login` in their Claude folder, a command or a skill; null when there is none. */
    public fun theirs(): Path? {
        val skill = claudeDir.resolve(Keys.SKILLS).resolve(LOGIN_SKILL).resolve(SKILL_MD)
        return when {
            Files.exists(file) && !splices(current()) -> file
            Files.exists(skill) -> skill
            else -> null
        }
    }

    /** splice's file taken out, and only splice's: true when one was there. Throws the disk's IOException. */
    public fun remove(): Boolean = splices(current()) && Files.deleteIfExists(file)

    /** The hook, laid; the `hooks` a launch's overlay carries for it, by event. Throws the disk's IOException. */
    public fun hooks(): Map<String, List<JsonObject>> {
        val dir = Files.createDirectories(hookDir)
        val script = HookScriptFiles.writeHookScript(dir, LOGIN_HOOK_SH, login.hook(), HookInstaller().chmod)
        return mapOf(
            LoginInterception.USER_PROMPT_SUBMIT to listOf(HookScriptFiles.hookEntry(script, LOGIN_TIMEOUT_SECONDS)),
        )
    }

    /** What [file] holds, or null when it is not there as a file. Throws the disk's IOException. */
    private fun current(): String? = if (Files.isRegularFile(file)) Files.readString(file) else null

    private fun splices(text: String?): Boolean = text != null && LOGIN_SENTINEL in text
}

// The folder a `/login` skill is, under `skills/`.
private const val LOGIN_SKILL = "login"
private const val SKILL_MD = "SKILL.md"
