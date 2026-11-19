// NEW: door-default (docs/specs/door-default.md, decision 5). `/login` in a session splice launches with Claude Code's
// own /login off: a command that submits a sentinel, and a UserPromptSubmit hook that catches the sentinel or the
// typed `/login` before the model sees it, blocks the turn and answers. On the door and on every head splice holds the
// credential for, the answer is splice's accounts page, opened in the person's browser by `splice console` and signed
// in by the single-use page it writes; signing in to a provider is done there. A command another machine serves
// (LAN share) answers with where its logins live instead, since nothing here holds them.
//
// The hook reads only the DECODED top-level prompt (LoginHookJson): `/login`, `/splice:login` or the sentinel, alone
// or followed by whitespace and arguments, which /login ignores. An input that mentions /login yet carries no prompt
// this scan can read is answered with a refusal, never acted on. No jq and no interpreter: the hooks' own rule.
package splice.client.login

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import splice.client.login.HookScriptFiles.shellSingleQuote

/** `/login` for one session: its command submits [LOGIN_SENTINEL], [elsewhere] is where the logins of a command
 *  another machine serves live, said in place of opening the accounts page (null for every session here), and
 *  [receipt] a head's login receipt (LoginOutcomeFile), told to the session on its next prompt; empty for none. */
internal class LoginScript(
    private val elsewhere: String? = null,
    private val receipt: String = "",
) {
    /** The command file, `commands/login.md`: its menu line, the sentinel it submits, and what it is for, which is what
     *  a session with no hook to catch it reads. The model never runs it of its own accord. */
    fun command(): String =
        """
        |---
        |description: ${if (elsewhere == null) ACCOUNTS_DESCRIPTION else ELSEWHERE_DESCRIPTION}
        |disable-model-invocation: true
        |---
        |$LOGIN_SENTINEL ${'$'}ARGUMENTS
        |
        |$UNCAUGHT
        """.trimMargin() + "\n"

    /** The UserPromptSubmit hook that answers `/login`. */
    fun hook(): String = buildString {
        val d = "$"
        val quoted = shellSingleQuote(LOGIN_SENTINEL)
        val verb = "(/login|/splice:login|$LOGIN_SENTINEL)"
        appendLine("#!/usr/bin/env bash")
        appendLine("# NEW (splice): /login answered by splice before it reaches the model; the turn is blocked.")
        appendLine("input=\"$d(cat)\"")
        if (receipt.isNotEmpty()) append(received())
        // `/splice:login` holds no `/login`, so its own spelling is looked for too.
        appendLine("[[ ${d}input == *\"/login\"* || ${d}input == *\":login\"* || ${d}input == *$quoted* ]] || exit 0")
        // A /login command line is a few hundred bytes; a large paste that merely mentions /login is an ordinary
        // prompt and is never scanned, which keeps the hook well inside its budget.
        appendLine("[ \"$d{#input}\" -le $MAX_SCAN_BYTES ] || exit 0")
        append(LoginHookJson.scanner())
        appendLine("if ! json_prompt; then")
        appendLine("  printf '%s' ${shellSingleQuote(block(UNPARSED))}")
        appendLine("  exit 0")
        appendLine("fi")
        appendLine("re=${shellSingleQuote("^$verb([[:space:]]|\$)")}")
        appendLine("[[ ${d}prompt =~ ${d}re ]] || exit 0")
        if (elsewhere == null) append(accounts()) else appendLine("printf '%s' ${shellSingleQuote(block(elsewhere))}")
        appendLine("exit 0")
    }

    /** A sign-in started in a terminal (`<head> login`) runs apart from the session, so its outcome is told here, on
     *  every prompt, once: the receipt is read and taken away. One older than its freshness window is taken away
     *  unsaid, judged through a link as every other reader judges it. */
    private fun received(): String = buildString {
        val d = "$"
        appendLine("receipt=${shellSingleQuote(receipt)}")
        appendLine("if [ -f \"${d}receipt\" ]; then")
        val window = LoginOutcomeFile.FRESH_WINDOW_MINUTES
        appendLine("  stale=\"$d(find -L \"${d}receipt\" -mmin +$window -print 2>/dev/null)\"")
        appendLine("  said=\"$d(cat \"${d}receipt\" 2>/dev/null)\"")
        appendLine("  rm -f \"${d}receipt\"")
        appendLine("  [ -n \"${d}stale\" ] && said=''")
        append(escaped("  "))
        appendLine("  if [ -n \"${d}said\" ]; then")
        appendLine("    ${spliced(context("splice login: $SLOT"))}")
        appendLine("    exit 0")
        appendLine("  fi")
        appendLine("fi")
    }

    /** `splice console` run where the session runs, and its outcome said: the page opened, or its own words. */
    private fun accounts(): String = buildString {
        val d = "$"
        appendLine("if command -v splice >/dev/null 2>&1; then")
        appendLine("  said=\"$d(splice console 2>&1)\"")
        appendLine("else")
        appendLine("  said='splice is not on the PATH of this session'")
        appendLine("fi")
        appendLine("case \"${d}said\" in")
        appendLine("  *'$OPENED'*) printf '%s' ${shellSingleQuote(block(ACCOUNTS_OPENED))} ;;")
        appendLine("  *)")
        append(escaped("    "))
        appendLine("    ${spliced(block("$NOT_OPENED$SLOT$RUN_IT"))} ;;")
        appendLine("esac")
    }

    /** `$said`, the one value decided at run time, escaped in pure bash for the JSON string it lands in: backslash
     *  first or it would double the escapes added after it; raw newlines, returns and tabs are illegal there, so they
     *  fold to spaces. */
    private fun escaped(indent: String): String = buildString {
        val d = "$"
        appendLine(indent + "said=\"$d{said//\\\\/\\\\\\\\}\"")
        appendLine(indent + "said=\"$d{said//\\\"/\\\\\\\"}\"")
        appendLine(indent + "said=\"$d{said//$d'\\n'/ }\"")
        appendLine(indent + "said=\"$d{said//$d'\\r'/ }\"")
        appendLine(indent + "said=\"$d{said//$d'\\t'/ }\"")
    }

    /** A printf of [json], encoded whole, with `$said` in place of [SLOT], so the run-time text rides between two
     *  single-quoted words and cannot break either the shell or the JSON. */
    private fun spliced(json: String): String {
        val parts = json.split(SLOT, limit = 2)
        return "printf '%s' ${shellSingleQuote(parts[0])}\"\$said\"${shellSingleQuote(parts.getOrElse(1) { "" })}"
    }

    /** What the hook adds to the session's context, as real JSON. */
    private fun context(text: String): String = Json.encodeToString(
        JsonObject.serializer(),
        buildJsonObject {
            putJsonObject("hookSpecificOutput") {
                put("hookEventName", "UserPromptSubmit")
                put("additionalContext", text)
            }
        },
    )

    /** The hook's decision as real JSON: the turn blocked, and [reason] shown to the person. */
    private fun block(reason: String): String = Json.encodeToString(
        JsonObject.serializer(),
        buildJsonObject {
            put("decision", "block")
            put("reason", reason)
        },
    )
}

/** What `/login`'s command submits, for the hook to catch. */
internal const val LOGIN_SENTINEL = "SPLICE_CODEX_LOGIN"

/** What the command says where no hook caught it: a session splice did not start, which shares the person's folder
 *  and runs this file for its own `/login` row too (Claude Code 2.1.326), so it names both ways on. */
private const val UNCAUGHT = "On this machine /login is splice's. In a session splice starts, a hook answers it " +
    "before it reaches the model and opens splice's accounts page, where every account splice runs on is signed in. " +
    "This session has no such hook, so do nothing else: tell the person the two ways on, each run in a terminal. " +
    "`splice console` opens splice's accounts page. `claude auth login` is Claude Code's own sign-in to Anthropic."

private const val ACCOUNTS_DESCRIPTION = "Open splice's accounts page, where every account splice runs on is signed in"
private const val ELSEWHERE_DESCRIPTION = "Say where the accounts of this command live, on the machine that serves it"

// What `splice console` prints once the browser has the page (ConsoleCommand).
private const val OPENED = "opened the console"

private const val ACCOUNTS_OPENED = "Opened splice's accounts page in your browser, signed in. Sign in to an account " +
    "there, and a new session lists its models. Nothing was sent to the model."
private const val NOT_OPENED = "splice's accounts page did not open ("
private const val RUN_IT = "). Run `splice console` in a terminal to open it. Nothing was sent to the model."
private const val UNPARSED = "/login was seen, but this hook's input carries no prompt to read it from. Nothing was " +
    "done; run `splice console` in a terminal to open splice's accounts page."

// Where the run-time text is spliced into the JSON once it is encoded: characters JSON keeps as they are.
private const val SLOT = "@@SPLICE_SAID@@"

// why: a /login command line is a few hundred bytes; a prompt past this is an ordinary one, never scanned
private const val MAX_SCAN_BYTES = 16384
