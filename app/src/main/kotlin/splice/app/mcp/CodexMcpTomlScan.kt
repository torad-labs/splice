// Low-level TOML line and token helpers for Codex `[mcp_servers.*]` reading and writing.
package splice.app.mcp

private val BARE_TOML_KEY = Regex("^[A-Za-z0-9_-]+$")
private val TOML_ESCAPES = mapOf('\\' to '\\', '"' to '"', 'n' to '\n', 'r' to '\r', 't' to '\t')

/** Pure TOML syntax helpers shared by [CodexMcpTomlCodec]. */
internal class CodexMcpTomlScan {
    fun joinMultilineValues(lines: List<String>): List<String> {
        val joined = mutableListOf<String>()
        val current = StringBuilder()
        var depth = 0
        lines.forEach { raw ->
            val clean = stripComment(raw)
            if (depth > 0) {
                current.append(' ').append(clean.trim())
                depth += bracketDelta(clean)
                if (depth <= 0) {
                    joined.add(current.toString())
                    current.clear()
                    depth = 0
                }
            } else {
                val delta = bracketDelta(clean)
                val opensMultiline = delta > 0 && '=' in clean && !clean.trimStart().startsWith("[")
                if (opensMultiline) {
                    current.append(clean.trim())
                    depth = delta
                } else {
                    joined.add(raw)
                }
            }
        }
        if (current.isNotEmpty()) joined.add(current.toString())
        return joined
    }

    fun stripComment(line: String): String {
        val state = QuoteState()
        for (i in line.indices) {
            val ch = line[i]
            if (state.advance(ch) && ch == '#') return line.substring(0, i)
        }
        return line
    }

    fun bracketDelta(line: String): Int {
        var delta = 0
        val state = QuoteState()
        for (ch in line) {
            if (!state.advance(ch)) continue
            delta += depthDelta(ch)
        }
        return delta
    }

    fun indexOfTopLevel(text: String, target: Char): Int {
        val state = QuoteState()
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
        val state = QuoteState()
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
        val state = QuoteState()
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

    /** Tracks basic/literal TOML quote state across characters. Returns true when [ch] is outside quotes. */
    private class QuoteState {
        private var quote: Char? = null
        private var escaped: Boolean = false
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
}
