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

/** Characters that mean something in a POSIX ERE (pgrep -f): escaped when a head name lands in one. */
private val ERE_META = Regex("[^A-Za-z0-9_-]")

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
    /** The head's topology key: a sign-in started as `splice login <key>` must be found too. */
    val headKey: String = "",
)

internal object LoginHookScripts {

    // ── generated-script safety (review 2026-08-28, PR 99) ────────────────────────────────────
    // Every value spliced below is OPERATOR-AUTHORED — signInLabel is AuthKind.signInLabel or the
    // ApiKeyProviderRegistry row label (else the provider id), loginCommand is
    // "${claude.command ?: key} login", envVar is auth.env — and it lands
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
        |description: Sign in to $signInLabel for this splice head (add --label NAME for another account)
        |argument-hint: "[--label NAME]"
        |---
        |$sentinel ${'$'}ARGUMENTS
        """.trimMargin() + "\n"

    // WHY THREE WORDINGS (2026-08-01): the api-key branch used to promise "a masked terminal
    // prompt is asking for your key" while spawning `<cmd> login` DETACHED with stdout to
    // /dev/null. Detached means no TTY, so System.console() is null, so the CLI printed its
    // pipe-instead hint into /dev/null and exited — the promised prompt could never appear and
    // the user was left waiting on nothing. Verified by running it. An api-key head that CAN
    // capture a paste is therefore told the path that actually works, and nothing is spawned.
    /** V4-13: the browser branch used to be one sentence whatever happened. A second /login while a
     *  sign-in was still waiting on its loopback callback (up to 300 s) spawned a login that died on
     *  the bind, unseen, while the hook kept promising a browser. Now the pending one is cancelled
     *  first and the reason says so, so /login always means "start over, in the browser". */
    private fun restartedText(hook: LoginHookSpec): String =
        "A previous ${hook.signInLabel} sign-in was still waiting and was cancelled. " + leadText(hook)

    private fun leadText(hook: LoginHookSpec): String =
        when {
            hook.viaBrowser ->
                "Opening your browser to sign in to ${hook.signInLabel} — finish there, then continue. " +
                    "If it did not open, run: ${hook.loginCommand}. To sign in another account: " +
                    "/login --label NAME"
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

    fun loginHookScript(hook: LoginHookSpec): String =
        buildString {
            val d = "$" // keep the shell $ out of Kotlin interpolation
            val lead = leadText(hook)
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
            // DR-103: this bash reader is the receipt's PRODUCTION consumer, so it must enforce
            // LoginOutcomeFile's freshness window itself — a stale failure receipt from days ago
            // must not announce "sign-in did not complete" on a fresh session after auth was fixed
            // another way. find -mmin truncates to whole minutes (a ≤59s skew vs the ms check);
            // the stale receipt is still consumed below so it cannot linger.
            //
            // DR-137: -L, because find defaults to -P and would judge a SYMLINK by its own mtime
            // while every other reader of this same receipt follows it — `[ -f ]` and `cat` two
            // lines below, and Files.isRegularFile / Files.getLastModifiedTime in
            // LoginOutcomeFile.consume. A link created now over a long-dead receipt read as fresh
            // and announced a stale failure. Same file, same verdict, whichever reader asks.
            appendLine(
                "  stale=\"$d(find -L \"${d}receipt\" -mmin +${LoginOutcomeFile.FRESH_WINDOW_MINUTES}" +
                    " -print 2>/dev/null)\"",
            )
            appendLine("  msg=\"$d(cat \"${d}receipt\" 2>/dev/null)\"")
            appendLine("  rm -f \"${d}receipt\"")
            appendLine("  [ -n \"${d}stale\" ] && msg=\"\"")
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
            appendLine(EXIT)
            appendLine("  fi")
            appendLine("fi")
            // /login exactly, /login followed by whitespace (a trailing space is a common keystroke)
            // and /login with arguments all mean /login; /loginx does not. The expanded command body
            // carries the sentinel plus the arguments, so both forms reach the same branch. The
            // arguments are read from the TOP-LEVEL prompt field only: the parse is anchored at the
            // object start over scalar pairs, so a nested object carrying its own prompt key is never
            // read. A readable top-level prompt that is not /login is an ordinary prompt, whatever
            // else the input carries. An input whose prompt this parse cannot read, yet carries the
            // sentinel or a /login prompt somewhere, is refused with a message, never treated as a
            // bare login (that would sign the primary in again).
            append(loginBranch(hook, lead))
        }

    /** The /login branch: decode the top-level prompt, then the browser block or the api-key text,
     *  then exit. The scan runs only when the raw input mentions /login or the sentinel at all. */
    private fun loginBranch(hook: LoginHookSpec, lead: String): String =
        buildString {
            val d = "$"
            val sentinel = shellSingleQuote(hook.sentinel)
            val verb = "(/login|${hook.sentinel.replace(ERE_META) { "\\" + it.value }})"
            // /login exactly, /login followed by whitespace (a trailing space is a common keystroke)
            // and /login with arguments all mean /login; /loginx does not. The expanded command body
            // carries the sentinel plus the arguments, so both forms reach the same branch. Only the
            // DECODED top-level prompt is read: a nested prompt key is data, field order and JSON
            // escapes do not matter, and a readable prompt that is not /login is an ordinary prompt.
            // An input that mentions /login or the sentinel yet has no top-level prompt string is
            // refused with a message, never treated as a bare login (that would sign the primary in).
            appendLine("[[ ${d}input == *\"/login\"* || ${d}input == *$sentinel* ]] || exit 0")
            // The scanner walks the input byte by byte in bash. A /login command line is a few hundred
            // bytes with the hook's own fields; a large paste that merely mentions /login is an
            // ordinary prompt and is never scanned (bounded latency under the 15 s hook budget).
            appendLine("[ \"$d{#input}\" -le $MAX_SCAN_BYTES ] || exit 0")
            append(LoginHookJson.scanner())
            appendLine("hit='' args='' prompt=''")
            appendLine("if json_prompt; then")
            appendLine("  if [ \"${d}prompt\" = /login ] || [ \"${d}prompt\" = $sentinel ]; then hit=1")
            appendLine("  elif [[ ${d}prompt =~ ^$verb[[:space:]] ]]; then")
            appendLine("    hit=1 args=\"$d{prompt:$d{#BASH_REMATCH[1]}}\"")
            appendLine("  fi")
            appendLine(ELSE_TOP)
            appendLine("  hit=unparsed")
            appendLine("fi")
            // Every head: an api-key head answered an unreadable input with its paste/terminal lead
            // text, contradicting the contract above (review 2026-09-14).
            appendLine("if [ \"${d}hit\" = unparsed ]; then")
            appendLine("  printf '%s' ${shellSingleQuote(blockDecision(refusalText(hook, Refusal.UNPARSED)))}")
            appendLine("  exit 0")
            appendLine("fi")
            appendLine("if [ -n \"${d}hit\" ]; then")
            if (hook.viaBrowser) {
                append(browserSpawn(hook))
            } else {
                appendLine("  printf '%s' ${shellSingleQuote(blockDecision(lead))}")
            }
            appendLine("fi")
            appendLine("exit 0")
        }

    private const val ELSE = "  else"
    private const val EXIT = "    exit 0"
    private const val MAX_SCAN_BYTES = 16384
    private const val ELSE_TOP = "else"
    private const val NO_ARGS = "^[[:space:]]*$"

    /** The arguments /login accepts, on the DECODED prompt: nothing, or --label NAME in the CLI's
     *  own label shape (AccountSelection: lowercase letters and digits, dot, dash, underscore, 48 at
     *  most). Anything else is refused HERE with the reason, and nothing is started: a bare login
     *  would sign the PRIMARY account in again and overwrite its credential. */
    private const val LABEL_ARGS = "^[[:space:]]+--label([[:space:]]+|=)([a-z0-9][a-z0-9._-]{0,47})[[:space:]]*$"
    private const val LABEL_GROUP = 2

    private enum class Refusal { BAD_ARGS, UNPARSED, STUCK, NO_COMMAND, DIED, FOREIGN }

    /** The ways the browser branch declines to start a login, each saying what to do instead. */
    private fun refusalText(hook: LoginHookSpec, why: Refusal): String =
        when (why) {
            Refusal.NO_COMMAND ->
                "The ${hook.signInLabel} login command (${hook.loginCommand.substringBefore(' ')}) is not on " +
                    "this session's PATH, so nothing was started and a sign-in still waiting was left " +
                    "alone. Run ${hook.loginCommand} from a terminal where it resolves."
            Refusal.DIED ->
                "${hook.loginCommand} exited as soon as it started, so no browser will open. Start it in a " +
                    "terminal to read why."
            Refusal.BAD_ARGS ->
                "/login takes no arguments other than --label NAME (lowercase letters and digits, dot, " +
                    "dash, underscore; 48 characters at most). Nothing was started; the " +
                    "${hook.signInLabel} sign-in is unchanged."
            Refusal.UNPARSED ->
                "/login was seen but this hook input carries no prompt string to read it from. Nothing " +
                    "was started; run ${hook.loginCommand} in a terminal."
            Refusal.STUCK ->
                "A previous ${hook.signInLabel} sign-in is still waiting and could not be cancelled. " +
                    "Finish it in the browser, or wait for it to time out, then /login again."
            Refusal.FOREIGN ->
                "A ${hook.signInLabel} sign-in started outside this session (${hook.loginCommand} in a " +
                    "terminal) is still waiting for its browser callback. Finish it there, or stop it, " +
                    "then /login again. Nothing was started."
        }

    /** The browser branch: an optional --label NAME rides through to the login command (checked
     *  here against the CLI's label shape; anything else is refused and nothing is spawned), a
     *  sign-in still waiting for its callback is cancelled first ([LoginHookPending]), and the
     *  reason names which of these happened. loginCommand is deliberately NOT quoted: it IS a
     *  command line ("claudex login"); its first word is the head the pending sign-in is matched by.
     *  Only a sign-in THIS hook started (marked ${LoginHookPending.ORIGIN_MARKER} in its environment)
     *  is ever cancelled: one the user started in a terminal is named and left alone (review
     *  2026-09-14: the hook killed a labeled terminal sign-in mid-consent and started the primary). */
    private fun browserSpawn(hook: LoginHookSpec): String {
        val d = "$"
        val stuck = "printf '%s' ${shellSingleQuote(blockDecision(refusalText(hook, Refusal.STUCK)))}"
        val foreign = "printf '%s' ${shellSingleQuote(blockDecision(refusalText(hook, Refusal.FOREIGN)))}"
        return buildString {
            appendLine("  label='' nre=${shellSingleQuote(NO_ARGS)} lre=${shellSingleQuote(LABEL_ARGS)}")
            appendLine("  if [[ ${d}args =~ ${d}nre ]]; then :")
            appendLine("  elif [[ ${d}args =~ ${d}lre ]]; then label=\"$d{BASH_REMATCH[$LABEL_GROUP]}\"")
            appendLine(ELSE)
            appendLine("    printf '%s' ${shellSingleQuote(blockDecision(refusalText(hook, Refusal.BAD_ARGS)))}")
            appendLine(EXIT)
            appendLine("  fi")
            // The replacement is proven resolvable BEFORE the pending sign-in is cancelled, and proven
            // to survive its first moment after: a hook that killed a working sign-in and then failed
            // to start another, silently, was worse than the bind failure it replaced (review 2026-09-14).
            val wrapper = shellSingleQuote(hook.loginCommand.substringBefore(' '))
            appendLine("  if ! command -v $wrapper >/dev/null 2>&1; then")
            appendLine("    printf '%s' ${shellSingleQuote(blockDecision(refusalText(hook, Refusal.NO_COMMAND)))}")
            appendLine(EXIT)
            appendLine("  fi")
            val words = listOf(hook.loginCommand.substringBefore(' '), hook.headKey)
            append(LoginHookPending.cancelBlock(words, stuck, foreign))
            val origin = LoginHookPending.ORIGIN_MARKER
            appendLine("  if [ -n \"${d}label\" ]; then")
            appendLine("    $origin nohup ${hook.loginCommand} --label \"${d}label\" >/dev/null 2>&1 &")
            appendLine(ELSE)
            appendLine("    $origin nohup ${hook.loginCommand} >/dev/null 2>&1 &")
            appendLine("  fi")
            appendLine("  spawned=$d!")
            appendLine("  sleep 0.3")
            appendLine("  if ! kill -0 \"${d}spawned\" 2>/dev/null && ! wait \"${d}spawned\"; then")
            appendLine("    printf '%s' ${shellSingleQuote(blockDecision(refusalText(hook, Refusal.DIED)))}")
            appendLine(EXIT)
            appendLine("  fi")
            appendLine("  if [ -n \"${d}restarted\" ]; then")
            appendLine("    printf '%s' ${shellSingleQuote(blockDecision(restartedText(hook)))}")
            appendLine(ELSE)
            appendLine("    printf '%s' ${shellSingleQuote(blockDecision(leadText(hook)))}")
            appendLine("  fi")
        }
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
