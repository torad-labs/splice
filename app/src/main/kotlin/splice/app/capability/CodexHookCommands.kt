// NEW: parses, validates, and migrates Codex peer hook command strings (`splice peer codex attach|detach`),
// using quote-aware shell tokenization so binary paths containing `jq` or quoted arguments with spaces are
// preserved verbatim while genuine legacy `sh -c`/`jq` wrappers are cleanly unwrapped.
package splice.app.capability

/** Recognizes and migrates `splice peer codex attach|detach` hook commands without clobbering user customizations. */
internal object CodexHookCommands {
    private val shellVarToken = Regex("""^\$\{?[A-Za-z_][A-Za-z0-9_]*\}?$""")

    fun isSpliceCommand(command: String): Boolean =
        "splice peer codex" in command || "peer codex attach" in command || "peer codex detach" in command

    /** True when [command] invokes `splice peer codex <verb>` directly (any binary path or quoted flags). */
    fun isValidDirect(command: String, verb: String): Boolean {
        val trimmed = command.trim()
        if (trimmed.isEmpty() || hasShellSyntax(trimmed)) return false
        val tokens = scanTokens(trimmed)
        return isSplicePeerCall(tokens) && tokens[VERB_INDEX].value == verb
    }

    /** Preserves an existing valid direct command verbatim, or unwraps a legacy shell wrapper keeping binary & flags. */
    fun migrate(existing: String?, verb: String): String {
        val trimmed = existing?.trim().orEmpty()
        return when {
            trimmed.isEmpty() -> "splice peer codex $verb"
            isValidDirect(trimmed, verb) -> trimmed
            else -> unwrapLegacy(trimmed, verb)
        }
    }

    private fun unwrapLegacy(trimmed: String, verb: String): String {
        val outer = scanTokens(trimmed)
        val script = if (isShellWrapper(outer)) outer[SHELL_SCRIPT_INDEX].value else trimmed
        val stmt = splitStatements(script).firstOrNull { isSplicePeerCall(scanTokens(it)) }
            ?: return "splice peer codex $verb"
        val tokens = scanTokens(stmt).toMutableList()
        while (tokens.size > MIN_PEER_TOKENS && isLegacyIdArg(tokens.last().value)) {
            tokens.removeAt(tokens.lastIndex)
        }
        tokens[VERB_INDEX] = Token(verb, verb)
        return tokens.joinToString(" ") { it.raw }
    }

    private fun isShellWrapper(tokens: List<Token>): Boolean {
        if (tokens.size < MIN_SHELL_WRAPPER_TOKENS || tokens[1].value != "-c") return false
        val bin = tokens[0].value
        return bin == "sh" || bin == "bash" || bin.endsWith("/sh") || bin.endsWith("/bash")
    }

    private fun isSplicePeerCall(tokens: List<Token>): Boolean {
        if (tokens.size < MIN_PEER_TOKENS) return false
        val bin = tokens[0].value
        val isSplice = bin == "splice" || bin.endsWith("/splice")
        val verb = tokens[VERB_INDEX].value
        return isSplice && tokens[1].value == "peer" && tokens[2].value == "codex" &&
            (verb == "attach" || verb == "detach")
    }

    private fun isLegacyIdArg(value: String): Boolean =
        shellVarToken.matches(value) || value.startsWith("$(") || value.startsWith("`")

    /** Detects unquoted shell operators (`;`, `|`, `&`, newlines) or command substitution (`$(`, `` ` ``). */
    private fun hasShellSyntax(text: String): Boolean {
        var quote = SHELL_UNQUOTED
        var i = 0
        while (i < text.length) {
            val c = text[i]
            val toggled = toggleQuote(quote, c)
            when {
                isEscaped(quote, text, i) -> i += 2
                toggled != null -> {
                    quote = toggled
                    i++
                }
                isShellOperatorAt(quote, c, text.getOrNull(i + 1)) -> return true
                else -> i++
            }
        }
        return false
    }

    private fun isShellOperatorAt(quote: Char, c: Char, next: Char?): Boolean = when {
        quote == '\'' -> false
        c == '`' || (c == '$' && next == '(') -> true
        quote == SHELL_UNQUOTED && c in STATEMENT_BREAKS -> true
        else -> false
    }

    /** Splits a shell script on unquoted `;`, `|`, `&`, or newlines while keeping each statement's raw quotes intact. */
    private fun splitStatements(script: String): List<String> {
        val out = mutableListOf<String>()
        var quote = SHELL_UNQUOTED
        var start = 0
        var i = 0
        while (i < script.length) {
            val c = script[i]
            val toggled = toggleQuote(quote, c)
            when {
                isEscaped(quote, script, i) -> i += 2
                toggled != null -> {
                    quote = toggled
                    i++
                }
                quote == SHELL_UNQUOTED && c in STATEMENT_BREAKS -> {
                    script.substring(start, i).trim().takeIf(String::isNotEmpty)?.let(out::add)
                    start = i + 1
                    i++
                }
                else -> i++
            }
        }
        script.substring(start).trim().takeIf(String::isNotEmpty)?.let(out::add)
        return out
    }

    /** Quote-aware tokenizer returning both raw slice and unquoted value for each argv token. */
    private fun scanTokens(text: String): List<Token> {
        val tokens = mutableListOf<Token>()
        var i = 0
        while (i < text.length) {
            while (i < text.length && text[i].isWhitespace()) i++
            if (i >= text.length) break
            val token = readOneToken(text, i)
            tokens += token.first
            i = token.second
        }
        return tokens
    }

    private fun readOneToken(text: String, start: Int): Pair<Token, Int> {
        val value = StringBuilder()
        var quote = SHELL_UNQUOTED
        var i = start
        while (i < text.length) {
            val c = text[i]
            if (quote == SHELL_UNQUOTED && c.isWhitespace()) break
            if (isEscaped(quote, text, i)) {
                value.append(text[i + 1])
                i += 2
            } else {
                val toggled = toggleQuote(quote, c)
                if (toggled != null) quote = toggled else value.append(c)
                i++
            }
        }
        return Token(text.substring(start, i), value.toString()) to i
    }

    private fun isEscaped(quote: Char, text: String, i: Int): Boolean {
        if (text[i] != '\\' || i + 1 >= text.length) return false
        return quote == SHELL_UNQUOTED || (quote == '"' && text[i + 1] in DOUBLE_QUOTE_ESCAPES)
    }

    private fun toggleQuote(quote: Char, c: Char): Char? = when {
        quote == SHELL_UNQUOTED && (c == '\'' || c == '"') -> c
        quote != SHELL_UNQUOTED && c == quote -> SHELL_UNQUOTED
        else -> null
    }

    private data class Token(val raw: String, val value: String)
}

private const val SHELL_UNQUOTED: Char = '\u0000'
private const val DOUBLE_QUOTE_ESCAPES: String = "\"\\\$`"
private const val STATEMENT_BREAKS: String = ";|&\n"

// why: `splice peer codex <verb>` has 4 tokens with the verb at index 3.
private const val MIN_PEER_TOKENS: Int = 4

// why: index of `attach`/`detach` in `splice peer codex <verb>`.
private const val VERB_INDEX: Int = 3

// why: `sh -c <script>` has at least 3 tokens with the script body at index 2.
private const val MIN_SHELL_WRAPPER_TOKENS: Int = 3

// why: index of `<script>` in `sh -c <script>`.
private const val SHELL_SCRIPT_INDEX: Int = 2
