// NEW: the bash hook script TEXTS for api-key token capture and the key-missing advertiser (split from
// LoginInterception, detekt TooManyFunctions — that file keeps the wiring/merging, this one keeps the generated
// bash). No interpreter dependency anywhere: scripts glob the raw hook JSON; the capture regex relies on the token
// charset being JSON-escape-free (word chars + dashes), so raw-JSON matching is exact. The /login hook is
// LoginScript's (docs/specs/door-default.md, decision 5).
package splice.client.login

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.client.login.HookScriptFiles.shellSingleQuote

internal object LoginHookScripts {

    // ── generated-script safety (review 2026-08-28, PR 99) ────────────────────────────────────
    // Every value spliced below is OPERATOR-AUTHORED — providerLabel is the ApiKeyProviderRegistry row label (else
    // the provider id), envVar is auth.env — and it lands in a bash script LoginInterception chmods 0700 as a
    // UserPromptSubmit hook, which bash parses on every prompt for that head. Not a privilege boundary (the
    // operator's daemon already runs as their uid), but robustness in an artifact nobody ever opens: an apostrophe
    // used to end the single-quoted shell string early, and a quote or backslash used to corrupt the JSON payload
    // Claude Code parses as the hook's decision. Two layers, and neither may depend on the other's characters being
    // absent — so shell quoting and JSON encoding are both done properly, once.

    // Shell quoting is HookScriptFiles.shellSingleQuote, the one copy every generated hook uses.

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

    // The capture regex is quote-anchored: the token must be the ENTIRE prompt string in the hook
    // JSON, so a token quoted inside prose never matches (discussing a key is safe).
    fun captureHookScript(spec: TokenCaptureSpec): String = buildString {
        val d = "$" // keep the shell $ out of Kotlin interpolation
        appendLine("#!/usr/bin/env bash")
        appendLine("# NEW (splice): api-key capture: a BARE ${oneLine(spec.providerLabel)} token pasted as the whole")
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
        // No restart (V4-227): the head reads keys.toml on every request, so the next turn already uses
        // the key, and the `splice restart` this ran dropped every head's turns in flight for nothing.
        val stored = "${spec.providerLabel} key received and stored to ~/.config/splice/keys.toml (0600); " +
            "the next request uses it, no restart. It was NOT forwarded to the model. Note: this session " +
            "log still contains the pasted line; the fully masked path is <head> login."
        appendLine("    printf '%s' ${shellSingleQuote(blockDecision(stored))}")
        appendLine("  else")
        val failed = "${spec.providerLabel} key detected but storing it failed; " +
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
        appendLine("# NEW (splice): key-missing advertiser for ${spec.envVar}, installed only while unconfigured.")
        val advert = "splice: ${spec.envVar} is not configured for this head. Tell the user ONCE, plainly: " +
            "paste your ${spec.providerLabel} API key as your next message and splice will store it " +
            "to keys.toml without it reaching the model, or run $loginCommand in a terminal for a " +
            "fully masked prompt. Then wait."
        appendLine("printf '%s' ${shellSingleQuote(advert)}")
        appendLine("exit 0")
    }
}
