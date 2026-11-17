// NEW: the /login interception of ONE head: whether it has one, and the staging of the commands/login.md plus the
// UserPromptSubmit hook that catches it. Split from LoginHookScripts.kt, where its fields were a parameter object with
// nothing to do.
//
// door-default (docs/specs/door-default.md, decision 5): on a head splice holds the credential for, /login opens
// splice's accounts page, where every account is signed in, in place of starting that head's own sign-in (LoginScript).
// A command another machine serves says where its logins live.
package splice.client.login

import kotlinx.serialization.json.JsonObject
import splice.client.wrap.HeadCommandsDir
import java.nio.file.Path

internal const val LOGIN_HOOK_SH = "splice-login-hook.sh"

/** How one head signs in. [loginCommand] is its terminal sign-in, empty for a head with none; [outcomeFile] its login
 *  receipt (LoginOutcomeFile); [signsInOn] the machine holding the logins of a command another machine serves (LAN
 *  share), empty for a head here, named [headKey]. */
internal class HeadLogin(
    val loginCommand: String,
    val outcomeFile: String = "",
    val headKey: String = "",
    val signsInOn: String = "",
) {
    /** False for a head with no login command: it gets no /login interception, and a stale one is reconciled away. */
    val offered: Boolean get() = loginCommand.isNotBlank()

    private val script: LoginScript
        get() {
            val elsewhere = signsInOn.takeIf { it.isNotBlank() }?.let {
                "$headKey signs in on $it: its logins live there, and this machine holds none. To sign in or add a " +
                    "login, run `$loginCommand` on $it, or use the Accounts page of its console."
            }
            return LoginScript(elsewhere, if (elsewhere == null) outcomeFile else "")
        }

    /** Writes the head's commands/login.md and its hook script, and answers the settings.json entry for the script.
     *  Throws on an I/O failure; the caller owns that leg's best-effort policy. */
    fun stage(configDir: Path, globalCommands: Path?, hooks: HookInstaller): JsonObject {
        val login = script
        HeadCommandsDir.write(configDir, login.command(), globalCommands)
        val written = HookScriptFiles.writeHookScript(configDir, LOGIN_HOOK_SH, login.hook(), hooks.chmod)
        return HookScriptFiles.hookEntry(written, LOGIN_TIMEOUT_SECONDS)
    }
}

/** `/login` runs `splice console`, a JVM start and a call to the daemon, inside the hook: well inside this. */
internal const val LOGIN_TIMEOUT_SECONDS = 30
