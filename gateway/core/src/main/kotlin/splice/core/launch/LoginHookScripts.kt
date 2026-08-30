// NEW: the bash hook script TEXTS for the /login interception, api-key token capture, and
// key-missing advertiser (split from LoginInterception, detekt TooManyFunctions — that file
// keeps the wiring/merging, this one keeps the generated bash). No python dependency anywhere:
// scripts glob the raw hook JSON; the capture regex relies on the token charset being
// JSON-escape-free (word chars + dashes), so raw-JSON matching is exact.
// Named object since the 2026-08-16 style migration (HD-M8). The four generators did NOT move into
// LoginInterception: that split exists precisely to keep LoginInterception under the function
// ceiling, and folding them back would undo it. Same names, same bash bytes.
package splice.core.launch

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

// The slot the runtime receipt text is spliced into, after the JSON is serialized. Only characters
// that JSON encodes verbatim, so the split lands where it was placed.
private const val RECEIPT_SLOT = "@@SPLICE_RECEIPT_MSG@@"

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

    // ── generated-script safety (review 2026-08-28, PR 99) ────────────────────────────────────
    // Every value spliced below is OPERATOR-AUTHORED — signInLabel is API_KEY_LABELS[provider] ?:
    // provider, loginCommand is "${claude.command ?: key} login", envVar is auth.env — and it lands
    // in a bash script LoginInterception chmods 0700 as a UserPromptSubmit hook, which bash parses
    // on every prompt for that head. Not a privilege boundary (the operator's daemon already runs as
    // their uid), but robustness in an artifact nobody ever opens: an apostrophe used to end the
    // single-quoted shell string early, and a quote or backslash used to corrupt the JSON payload
    // Claude Code parses as the hook's decision. Two layers, and neither may depend on the other's
    // characters being absent — so shell quoting and JSON encoding are both done properly, once.

    /** Bash has NO escape sequence inside `'...'`; close-escape-reopen is the only safe splice. */
    private fun shellSingleQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    /** For a value landing inside a `#` comment: a newline would end the comment and hand the rest
     *  of the line to bash as code. */
    private fun oneLine(value: String): String = value.replace('\n', ' ').replace('\r', ' ')

    /** The hook decision as REAL JSON rather than a hand-built literal. */
    private fun blockDecision(reason: String): String = Json.encodeToString(
        JsonObject.serializer(),
        buildJsonObject {
            put("decision", "block")
            put("reason", reason)
        },
    )

    /** The receipt announcement: the JSON is serialized with the label inside it (so the label
     *  cannot corrupt the object), then split at [RECEIPT_SLOT] so the runtime `$msg` rides between
     *  two single-quoted shell words. `printf '%s'` keeps every part an ARGUMENT, never a format, so
     *  a `%` or `\` in the label is not interpreted either. A label that itself contained the slot
     *  text would only misplace `$msg` — still valid shell, still valid JSON. */
    private fun receiptEcho(signInLabel: String, msgExpr: String): String {
        val payload = Json.encodeToString(
            JsonObject.serializer(),
            buildJsonObject {
                putJsonObject("hookSpecificOutput") {
                    put("hookEventName", "UserPromptSubmit")
                    put("additionalContext", "splice $signInLabel login: $RECEIPT_SLOT")
                }
            },
        )
        val parts = payload.split(RECEIPT_SLOT, limit = 2)
        return "printf '%s' ${shellSingleQuote(parts[0])}$msgExpr${shellSingleQuote(parts.getOrElse(1) { "" })}"
    }

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
            val label = oneLine(hook.signInLabel)
            appendLine("#!/usr/bin/env bash")
            appendLine("# NEW (splice): /login interception — route to this head's $label sign-in,")
            appendLine("# not Claude Code's disabled Anthropic login. Blocks the model turn.")
            appendLine("input=\"$d(cat)\"")
            // THE LOGIN RECEIPT (2026-08-01). The sign-in runs detached, so everything it prints is
            // lost; without this the session never learns whether the login worked. kimi CANNOT be
            // confirmed in a browser at all (device flow: no redirect target), so the confirmation has
            // to arrive here — the same in-client status surface opencode and Kilo Code settled on.
            // Checked on EVERY prompt, not just /login, because the user finishes in the browser and
            // then types something ordinary.
            appendLine("receipt=${shellSingleQuote(hook.outcomeFile)}")
            appendLine("if [ -f \"${d}receipt\" ]; then")
            appendLine("  msg=\"$d(cat \"${d}receipt\" 2>/dev/null)\"")
            appendLine("  rm -f \"${d}receipt\"")
            // The receipt is the ONE value here decided at RUNTIME, so it cannot be encoded by the
            // serializer with the rest of the payload — and it was landing raw inside a JSON string.
            // splice writes it (LoginOutcomeFile), but it relays provider text, and one `"` in that
            // made the whole hook answer unparseable: Claude Code then sees a broken hook, not a
            // login confirmation. Escaped in pure bash — no jq, no python, matching this file's own
            // no-dependency rule. Backslash FIRST or it would double the escapes added after it;
            // raw newline/CR/tab are illegal inside a JSON string, so they fold to spaces.
            appendLine("  msg=\"$d{msg//\\\\/\\\\\\\\}\"")
            appendLine("  msg=\"$d{msg//\\\"/\\\\\\\"}\"")
            appendLine("  msg=\"$d{msg//$d'\\n'/ }\"")
            appendLine("  msg=\"$d{msg//$d'\\r'/ }\"")
            appendLine("  msg=\"$d{msg//$d'\\t'/ }\"")
            appendLine("  if [ -n \"${d}msg\" ]; then")
            appendLine("    " + receiptEcho(hook.signInLabel, "\"${d}msg\""))
            appendLine("    exit 0")
            appendLine("  fi")
            appendLine("fi")
            appendLine("case \"${d}input\" in")
            appendLine("  *${hook.sentinel}*|*'\"prompt\":\"/login\"'*|*'\"prompt\": \"/login\"'*)")
            // Only the browser flow is spawned. A detached api-key login has no TTY and cannot prompt.
            // loginCommand is deliberately NOT quoted: it IS a command line ("claudex login"), and
            // quoting it into one word would break the spawn this branch exists for.
            if (hook.viaBrowser) appendLine("    nohup ${hook.loginCommand} >/dev/null 2>&1 &")
            appendLine("    printf '%s' ${shellSingleQuote(blockDecision(lead))}")
            appendLine("    ;;")
            appendLine("esac")
            appendLine("exit 0")
        }

    // The capture regex is quote-anchored: the token must be the ENTIRE prompt string in the hook
    // JSON, so a token quoted inside prose never matches (discussing a key is safe).
    fun captureHookScript(spec: TokenCaptureSpec): String = buildString {
        val d = "$" // keep the shell $ out of Kotlin interpolation
        appendLine("#!/usr/bin/env bash")
        appendLine("# NEW (splice): api-key capture — a BARE ${oneLine(spec.providerLabel)} token pasted as the whole")
        appendLine("# message is stored to keys.toml (0600) via `splice key set --stdin` and BLOCKED before")
        appendLine("# it reaches the model context, so it never travels upstream. The session transcript")
        appendLine("# still records the paste; the fully masked path is `<head> login`.")
        appendLine("input=\"$d(cat)\"")
        append("if [[ ${d}input =~ ")
        append("\\\"prompt\\\"[[:space:]]*:[[:space:]]*\\\"(${spec.tokenPattern})\\\" ]]; then")
        appendLine()
        appendLine("  token=\"$d{BASH_REMATCH[1]}\"")
        // A bare command word by necessity, and safe by construction: TokenCaptureSpec.init requires
        // envVar to match KeyStore's own ENV_NAME regex, so it cannot carry a shell metacharacter.
        appendLine("  if printf '%s' \"${d}token\" | splice key set ${spec.envVar} --stdin >/dev/null 2>&1; then")
        appendLine("    nohup splice restart >/dev/null 2>&1 &")
        val stored = "${spec.providerLabel} key received — stored to ~/.config/splice/keys.toml (0600) " +
            "and the daemon is restarting. It was NOT forwarded to the model. Note: this session log " +
            "still contains the pasted line; the fully masked path is <head> login."
        appendLine("    printf '%s' ${shellSingleQuote(blockDecision(stored))}")
        appendLine("  else")
        val failed = "${spec.providerLabel} key detected but storing it failed — " +
            "run <head> login in a terminal instead."
        appendLine("    printf '%s' ${shellSingleQuote(blockDecision(failed))}")
        appendLine("  fi")
        appendLine("fi")
        appendLine("exit 0")
    }

    // Installed only while the key is missing (the daemon re-materializes on every launch and
    // removes it once configured), so printing unconditionally is correct.
    fun keySetupScript(spec: TokenCaptureSpec, loginCommand: String): String = buildString {
        appendLine("#!/usr/bin/env bash")
        appendLine("# NEW (splice): key-missing advertiser for ${spec.envVar} — installed only while unconfigured.")
        val advert = "splice: ${spec.envVar} is not configured for this head. Tell the user ONCE, plainly: " +
            "paste your ${spec.providerLabel} API key as your next message and splice will store it " +
            "to keys.toml without it reaching the model, or run $loginCommand in a terminal for a " +
            "fully masked prompt. Then wait."
        appendLine("printf '%s' ${shellSingleQuote(advert)}")
        appendLine("exit 0")
    }
}
