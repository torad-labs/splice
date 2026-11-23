// Low-level TOML line and token helpers for Codex `[mcp_servers.*]` reading and writing.
package splice.app.mcp

private val BARE_TOML_KEY = Regex("^[A-Za-z0-9_-]+$")
private val TOML_ESCAPES = mapOf('\\' to '\\', '"' to '"', 'n' to '\n', 'r' to '\r', 't' to '\t')
private const val HEADER_FORBIDDEN_CHARS = "[],"

// why: TOML multiline string delimiters consist of three consecutive basic or literal quote characters.
private const val TRIPLE_QUOTE_LEN = 3

/** Parsed TOML table or array-of-tables header. */
internal data class TomlHeaderMatch(val path: String, val isArrayTable: Boolean)

/** Quote-aware TOML `[table]` / `[[array]]` parser; supports brackets inside quoted keys and rejects arrays. */
internal class TomlHeaderParser {
    fun parse(trimmed: String): TomlHeaderMatch? {
        if (!trimmed.startsWith("[") || !trimmed.endsWith("]")) return null
        val isArray = trimmed.startsWith("[[") && trimmed.endsWith("]]")
        val offset = if (isArray) 2 else 1
        if (trimmed.length <= offset * 2) return null
        val inner = trimmed.substring(offset, trimmed.length - offset).trim()
        return if (inner.isNotEmpty() && validInner(inner)) TomlHeaderMatch(inner, isArray) else null
    }

    private fun validInner(inner: String): Boolean {
        val state = TomlQuoteState()
        for (ch in inner) {
            if (state.advance(ch) && ch in HEADER_FORBIDDEN_CHARS) return false
        }
        return !state.inQuote
    }
}

/** Pure TOML syntax helpers shared by [CodexMcpTomlCodec]. */
internal class CodexMcpTomlScan {
    fun joinMultilineValues(lines: List<String>): List<String> {
        val joined = mutableListOf<String>()
        val current = StringBuilder()
        val tracker = TomlMultilineTracker()
        lines.forEach { raw ->
            if (tracker.active) {
                val clean = if (tracker.inTripleQuote) raw else stripComment(raw)
                current.append(' ').append(clean.trim())
                tracker.advanceLine(raw)
                if (!tracker.active) {
                    joined.add(current.toString())
                    current.clear()
                }
            } else {
                val clean = stripComment(raw)
                val trimmed = clean.trimStart()
                if ('=' in clean && !trimmed.startsWith("[")) tracker.advanceLine(raw)
                if (tracker.active) {
                    current.append(clean.trim())
                } else {
                    joined.add(raw)
                }
            }
        }
        if (current.isNotEmpty()) joined.add(current.toString())
        return joined
    }

    fun stripComment(line: String): String {
        val state = TomlQuoteState()
        for (i in line.indices) {
            val ch = line[i]
            if (state.advance(ch) && ch == '#') return line.substring(0, i)
        }
        return line
    }

    fun indexOfTopLevel(text: String, target: Char): Int {
        val state = TomlQuoteState()
        var depth = 0
        for (i in text.indices) {
            val ch = text[i]
            if (!state.advance(ch)) continue
            if (ch == target && depth == 0) return i
            depth += depthDelta(ch)
        }
        return -1
    }

    fun splitTopLevel(text: String, sep: Char): List<String> {
        val parts = mutableListOf<String>()
        val state = TomlQuoteState()
        var start = 0
        var depth = 0
        for (i in text.indices) {
            val ch = text[i]
            if (!state.advance(ch)) continue
            if (ch == sep && depth == 0) {
                parts.add(text.substring(start, i))
                start = i + 1
            } else {
                depth += depthDelta(ch)
            }
        }
        parts.add(text.substring(start))
        return parts
    }

    private fun depthDelta(ch: Char): Int = when (ch) {
        '[', '{' -> 1
        ']', '}' -> -1
        else -> 0
    }

    fun splitDottedPath(path: String): List<String> {
        val out = mutableListOf<String>()
        val buf = StringBuilder()
        val state = TomlQuoteState()
        for (ch in path) {
            val unquoted = state.advance(ch)
            when {
                unquoted && ch == '.' -> {
                    out.add(buf.toString().trim())
                    buf.clear()
                }
                !state.consumedQuote && !state.consumedEscape -> buf.append(ch)
            }
        }
        out.add(buf.toString().trim())
        return out
    }

    fun parseStringArray(raw: String): List<String> {
        val trimmed = raw.trim()
        val valid = trimmed.startsWith("[") && trimmed.endsWith("]")
        val inner = if (valid) trimmed.substring(1, trimmed.length - 1).trim() else ""
        return if (inner.isEmpty()) {
            emptyList()
        } else {
            splitTopLevel(inner, ',').mapNotNull { token -> parseScalarString(token.trim()) }
        }
    }

    fun parseInlineTable(raw: String): Map<String, String> {
        val trimmed = raw.trim()
        val valid = trimmed.startsWith("{") && trimmed.endsWith("}")
        val inner = if (valid) trimmed.substring(1, trimmed.length - 1).trim() else ""
        val out = LinkedHashMap<String, String>()
        if (inner.isEmpty()) return out
        splitTopLevel(inner, ',').forEach { pair ->
            val eq = indexOfTopLevel(pair, '=')
            if (eq > 0) {
                val k = unquoteKey(pair.substring(0, eq).trim())
                val v = parseScalarString(pair.substring(eq + 1).trim())
                if (k.isNotEmpty() && v != null) out[k] = v
            }
        }
        return out
    }

    fun parseScalarString(raw: String): String? = when {
        raw.length < 2 -> null
        raw.startsWith("'") && raw.endsWith("'") -> raw.substring(1, raw.length - 1)
        raw.startsWith("\"") && raw.endsWith("\"") -> unescapeBasic(raw.substring(1, raw.length - 1))
        else -> null
    }

    fun unquoteKey(raw: String): String = parseScalarString(raw) ?: raw

    fun formatKey(key: String): String = if (BARE_TOML_KEY.matches(key)) key else quoteString(key)

    fun quoteString(value: String): String = buildString(value.length + 2) {
        append('"')
        value.forEach { ch ->
            when (ch) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(ch)
            }
        }
        append('"')
    }

    private fun unescapeBasic(inner: String): String {
        val sb = StringBuilder(inner.length)
        var i = 0
        while (i < inner.length) {
            val ch = inner[i]
            if (ch == '\\' && i + 1 < inner.length) {
                val next = inner[i + 1]
                sb.append(TOML_ESCAPES[next] ?: next)
                i += 2
            } else {
                sb.append(ch)
                i += 1
            }
        }
        return sb.toString()
    }
}

/** Tracks basic/literal TOML quote state across characters. Returns true when [ch] is outside quotes. */
internal class TomlQuoteState {
    private var quote: Char? = null
    private var escaped: Boolean = false
    val inQuote: Boolean get() = quote != null
    var consumedQuote: Boolean = false
        private set
    var consumedEscape: Boolean = false
        private set

    fun advance(ch: Char): Boolean {
        consumedQuote = false
        consumedEscape = false
        return when {
            escaped -> {
                escaped = false
                false
            }
            quote != null -> advanceInsideQuote(ch)
            ch == '"' || ch == '\'' -> {
                quote = ch
                consumedQuote = true
                false
            }
            else -> true
        }
    }

    private fun advanceInsideQuote(ch: Char): Boolean {
        when {
            ch == '\\' && quote == '"' -> {
                escaped = true
                consumedEscape = true
            }
            ch == quote -> {
                quote = null
                consumedQuote = true
            }
        }
        return false
    }
}

/** Tracks multiline TOML bracket/brace depth and triple-quoted strings across lines. */
internal class TomlMultilineTracker {
    private var bracketDepth = 0
    private var tripleQuote: Char? = null

    val inTripleQuote: Boolean get() = tripleQuote != null
    val active: Boolean get() = bracketDepth > 0 || tripleQuote != null

    fun advanceLine(line: String) {
        val state = TomlQuoteState()
        var i = 0
        while (i < line.length) {
            i = advanceAt(line, i, state)
        }
        if (bracketDepth < 0) bracketDepth = 0
    }

    private fun advanceAt(line: String, index: Int, state: TomlQuoteState): Int {
        val delim = tripleQuote
        return if (delim != null) advanceTriple(line, index, delim) else advanceNormal(line, index, state)
    }

    private fun advanceTriple(line: String, index: Int, delim: Char): Int = when {
        delim == '"' && line[index] == '\\' && index + 1 < line.length -> index + 2
        startsTriple(line, index, delim) -> {
            tripleQuote = null
            index + TRIPLE_QUOTE_LEN
        }
        else -> index + 1
    }

    private fun advanceNormal(line: String, index: Int, state: TomlQuoteState): Int {
        val ch = line[index]
        if (!state.inQuote && ch == '#') return line.length
        if (!state.inQuote && startsAnyTriple(line, index)) {
            tripleQuote = ch
            return index + TRIPLE_QUOTE_LEN
        }
        if (state.advance(ch)) {
            when (ch) {
                '[', '{' -> bracketDepth += 1
                ']', '}' -> bracketDepth -= 1
            }
        }
        return index + 1
    }

    private fun startsAnyTriple(line: String, index: Int): Boolean =
        startsTriple(line, index, '"') || startsTriple(line, index, '\'')

    private fun startsTriple(line: String, index: Int, ch: Char): Boolean {
        val token = if (ch == '"') "\"\"\"" else "'''"
        return line.regionMatches(index, token, 0, TRIPLE_QUOTE_LEN)
    }
}
