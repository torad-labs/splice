// NEW: the bash hook script TEXTS for the /login interception, api-key token capture, and
// key-missing advertiser (split from LoginInterception, detekt TooManyFunctions — that file
// keeps the wiring/merging, this one keeps the generated bash). No python dependency anywhere:
// scripts glob the raw hook JSON; the capture regex relies on the token charset being
// JSON-escape-free (word chars + dashes), so raw-JSON matching is exact.
// Named object since the 2026-08-16 style migration (HD-M8). The four generators did NOT move into
// LoginInterception: that split exists precisely to keep LoginInterception under the function
// ceiling, and folding them back would undo it. Same names, same bash bytes.
package splice.core.launch

/** Everything the /login hook needs for ONE head — a parameter object because these six always
 *  travel together and describe a single thing: how this head signs in. */
internal data class LoginHookSpec(
    val loginCommand: String,
    val signInLabel: String,
    val viaBrowser: Boolean,
    val sentinel: String,
    /** Absolute path of this head's login receipt — see LoginOutcomeFile. */
    val outcomeFile: String,
    /** True when this head can capture a bare token pasted into the prompt box. Decides the whole
     *  shape of /login for an api-key head — see [LoginHookScripts.loginHookScript]. */
    val canCapturePaste: Boolean,
)

internal object LoginHookScripts {

    // The custom /login command body: submits the sentinel the hook catches. [signInLabel] names
    // the head's provider so the UX reads right per head.
    fun loginCommandMd(signInLabel: String, sentinel: String): String =
        """
        |---
        |description: Sign in to $signInLabel for this splice head
        |---
        |$sentinel
        """.trimMargin() + "\n"

    fun loginHookScript(hook: LoginHookSpec): String =
        buildString {
            val d = "$" // keep the shell $ out of Kotlin interpolation
            // WHY THREE WORDINGS (2026-08-01): the api-key branch used to promise "a masked terminal
            // prompt is asking for your key" while spawning `<cmd> login` DETACHED with stdout to
            // /dev/null. Detached means no TTY, so System.console() is null, so the CLI printed its
            // pipe-instead hint into /dev/null and exited — the promised prompt could never appear and
            // the user was left waiting on nothing. Verified by running it. An api-key head that CAN
            // capture a paste is therefore told the path that actually works, and nothing is spawned.
            val lead =
                when {
                    hook.viaBrowser ->
                        "Opening your browser to sign in to ${hook.signInLabel} — finish there, then continue. " +
                            "If it did not open, run: ${hook.loginCommand}"
                    hook.canCapturePaste ->
                        "Paste your ${hook.signInLabel} API key as your next message. splice stores it to " +
                            "~/.config/splice/keys.toml (0600) and BLOCKS it before it reaches the model, " +
                            "so it is never sent upstream. Note: the session log on disk still records the " +
                            "pasted line — for a fully masked entry, run `${hook.loginCommand}` in a terminal " +
                            "instead. Then wait."
                    else ->
                        "This head signs in with an API key. Run `${hook.loginCommand}` in a terminal — it asks " +
                            "for the key with a masked prompt. It cannot be asked for from inside this " +
                            "session."
                }
            appendLine("#!/usr/bin/env bash")
            appendLine("# NEW (splice): /login interception — route to this head's ${hook.signInLabel} sign-in,")
            appendLine("# not Claude Code's disabled Anthropic login. Blocks the model turn.")
            appendLine("input=\"$d(cat)\"")
            // THE LOGIN RECEIPT (2026-08-01). The sign-in runs detached, so everything it prints is
            // lost; without this the session never learns whether the login worked. kimi CANNOT be
            // confirmed in a browser at all (device flow: no redirect target), so the confirmation has
            // to arrive here — the same in-client status surface opencode and Kilo Code settled on.
            // Checked on EVERY prompt, not just /login, because the user finishes in the browser and
            // then types something ordinary.
            appendLine("receipt=\"${hook.outcomeFile}\"")
            appendLine("if [ -f \"${d}receipt\" ]; then")
            appendLine("  msg=\"$d(cat \"${d}receipt\" 2>/dev/null)\"")
            appendLine("  rm -f \"${d}receipt\"")
            appendLine("  if [ -n \"${d}msg\" ]; then")
            append("    printf '%s' \"{\\\"hookSpecificOutput\\\":{\\\"hookEventName\\\":")
            append("\\\"UserPromptSubmit\\\",\\\"additionalContext\\\":\\\"splice ")
            append("${hook.signInLabel} login: ${d}msg\\\"}}\"")
            appendLine()
            appendLine("    exit 0")
            appendLine("  fi")
            appendLine("fi")
            appendLine("case \"${d}input\" in")
            appendLine("  *${hook.sentinel}*|*'\"prompt\":\"/login\"'*|*'\"prompt\": \"/login\"'*)")
            // Only the browser flow is spawned. A detached api-key login has no TTY and cannot prompt.
            if (hook.viaBrowser) appendLine("    nohup ${hook.loginCommand} >/dev/null 2>&1 &")
            append("    printf '%s' '{\"decision\":\"block\",\"reason\":")
            append("\"$lead\"}'")
            appendLine()
            appendLine("    ;;")
            appendLine("esac")
            appendLine("exit 0")
        }

    // The capture regex is quote-anchored: the token must be the ENTIRE prompt string in the hook
    // JSON, so a token quoted inside prose never matches (discussing a key is safe).
    fun captureHookScript(spec: TokenCaptureSpec): String = buildString {
        val d = "$" // keep the shell $ out of Kotlin interpolation
        appendLine("#!/usr/bin/env bash")
        appendLine("# NEW (splice): api-key capture — a BARE ${spec.providerLabel} token pasted as the whole")
        appendLine("# message is stored to keys.toml (0600) via `splice key set --stdin` and BLOCKED before")
        appendLine("# it reaches the model context, so it never travels upstream. The session transcript")
        appendLine("# still records the paste; the fully masked path is `<head> login`.")
        appendLine("input=\"$d(cat)\"")
        append("if [[ ${d}input =~ ")
        append("\\\"prompt\\\"[[:space:]]*:[[:space:]]*\\\"(${spec.tokenPattern})\\\" ]]; then")
        appendLine()
        appendLine("  token=\"$d{BASH_REMATCH[1]}\"")
        appendLine("  if printf '%s' \"${d}token\" | splice key set ${spec.envVar} --stdin >/dev/null 2>&1; then")
        appendLine("    nohup splice restart >/dev/null 2>&1 &")
        append("    printf '%s' '{\"decision\":\"block\",\"reason\":")
        append("\"${spec.providerLabel} key received — stored to ~/.config/splice/keys.toml (0600) ")
        append("and the daemon is restarting. It was NOT forwarded to the model. Note: this session log ")
        append("still contains the pasted line; the fully masked path is <head> login.\"}'")
        appendLine()
        appendLine("  else")
        append("    printf '%s' '{\"decision\":\"block\",\"reason\":")
        append("\"${spec.providerLabel} key detected but storing it failed — ")
        append("run <head> login in a terminal instead.\"}'")
        appendLine()
        appendLine("  fi")
        appendLine("fi")
        appendLine("exit 0")
    }

    // Installed only while the key is missing (the daemon re-materializes on every launch and
    // removes it once configured), so printing unconditionally is correct.
    fun keySetupScript(spec: TokenCaptureSpec, loginCommand: String): String = buildString {
        appendLine("#!/usr/bin/env bash")
        appendLine("# NEW (splice): key-missing advertiser for ${spec.envVar} — installed only while unconfigured.")
        append("printf '%s' '")
        append("splice: ${spec.envVar} is not configured for this head. Tell the user ONCE, plainly: ")
        append("paste your ${spec.providerLabel} API key as your next message and splice will store it ")
        append("to keys.toml without it reaching the model, or run $loginCommand in a terminal for a ")
        append("fully masked prompt. Then wait.")
        appendLine("'")
        appendLine("exit 0")
    }
}
