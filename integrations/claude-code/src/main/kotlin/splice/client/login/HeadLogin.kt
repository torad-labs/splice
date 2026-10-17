// NEW: the /login interception of ONE head: what its sign-in is called and runs, and the staging of the
// commands/login.md plus the UserPromptSubmit hook that catches the sentinel. Split from LoginHookScripts.kt, where
// its fields were a parameter object with nothing to do.
package splice.client.login

import kotlinx.serialization.json.JsonObject
import splice.client.wrap.HeadCommandsDir
import java.nio.file.Path

internal const val LOGIN_HOOK_SH = "splice-login-hook.sh"
internal const val LOGIN_SENTINEL = "SPLICE_CODEX_LOGIN"

/** How one head signs in. [canCapturePaste] is true when the head also holds a [TokenCaptureSpec], which decides the
 *  whole shape of /login for an api-key head (see [LoginHookScripts.loginHookScript]); [outcomeFile] is its login
 *  receipt (LoginOutcomeFile); [headKey] lets a sign-in started as `splice login <key>` be found too. */
internal class HeadLogin(
    val loginCommand: String,
    val signInLabel: String,
    val viaBrowser: Boolean,
    val outcomeFile: String = "",
    val canCapturePaste: Boolean = false,
    val headKey: String = "",
) {
    /** False for a head with no login command: it gets no /login interception, and a stale one is reconciled away. */
    val offered: Boolean get() = loginCommand.isNotBlank()

    /** Writes the head's commands/login.md and its hook script, and answers the settings.json entry for the script.
     *  Throws on an I/O failure; the caller owns that leg's best-effort policy. */
    fun stage(configDir: Path, globalCommands: Path?, hooks: HookInstaller): JsonObject {
        HeadCommandsDir.write(configDir, signInLabel, globalCommands, LOGIN_SENTINEL)
        val script = HookScriptFiles.writeHookScript(
            configDir,
            LOGIN_HOOK_SH,
            LoginHookScripts.loginHookScript(this),
            hooks.chmod,
        )
        return HookScriptFiles.hookEntry(script, HookScriptFiles.HOOK_TIMEOUT_SECONDS)
    }
}
