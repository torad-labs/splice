// NEW: the bash hook script TEXTS for the /login interception, api-key token capture, and
// key-missing advertiser (split from LoginInterception, detekt TooManyFunctions — that file
// keeps the wiring/merging, this one keeps the generated bash). No python dependency anywhere:
// scripts glob the raw hook JSON; the capture regex relies on the token charset being
// JSON-escape-free (word chars + dashes), so raw-JSON matching is exact.
package splice.core.launch

// The custom /login command body: submits the sentinel the hook catches. [signInLabel] names
// the head's provider so the UX reads right per head.
internal fun loginCommandMd(signInLabel: String, sentinel: String): String =
    """
    |---
    |description: Sign in to $signInLabel for this splice head
    |---
    |$sentinel
    """.trimMargin() + "\n"

internal fun loginHookScript(
    loginCommand: String,
    signInLabel: String,
    viaBrowser: Boolean,
    sentinel: String,
    /** True when this head can capture a bare token pasted into the prompt box. Decides the whole
     *  shape of /login for an api-key head — see [lead] below. */
    canCapturePaste: Boolean,
): String =
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
                viaBrowser ->
                    "Opening your browser to sign in to $signInLabel — finish there, then continue. " +
                        "If it did not open, run: $loginCommand"
                canCapturePaste ->
                    "Paste your $signInLabel API key as your next message. splice stores it to " +
                        "~/.config/splice/keys.toml (0600) and BLOCKS it before it reaches the model, " +
                        "so it is never sent upstream. Note: the session log on disk still records the " +
                        "pasted line — for a fully masked entry, run `$loginCommand` in a terminal " +
                        "instead. Then wait."
                else ->
                    "This head signs in with an API key. Run `$loginCommand` in a terminal — it asks " +
                        "for the key with a masked prompt. It cannot be asked for from inside this " +
                        "session."
            }
        appendLine("#!/usr/bin/env bash")
        appendLine("# NEW (splice): /login interception — route to this head's $signInLabel sign-in,")
        appendLine("# not Claude Code's disabled Anthropic login. Blocks the model turn.")
        appendLine("input=\"$d(cat)\"")
        appendLine("case \"${d}input\" in")
        appendLine("  *$sentinel*|*'\"prompt\":\"/login\"'*|*'\"prompt\": \"/login\"'*)")
        // Only the browser flow is spawned. A detached api-key login has no TTY and cannot prompt.
        if (viaBrowser) appendLine("    nohup $loginCommand >/dev/null 2>&1 &")
        append("    printf '%s' '{\"decision\":\"block\",\"reason\":")
        append("\"$lead\"}'")
        appendLine()
        appendLine("    ;;")
        appendLine("esac")
        appendLine("exit 0")
    }

// The capture regex is quote-anchored: the token must be the ENTIRE prompt string in the hook
// JSON, so a token quoted inside prose never matches (discussing a key is safe).
internal fun captureHookScript(spec: TokenCaptureSpec): String = buildString {
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
internal fun keySetupScript(spec: TokenCaptureSpec, loginCommand: String): String = buildString {
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
