// NEW: the TOML reader the ported laws' own DECLARATION FILES are read with (restructure PR 6, §4.3).
//
// WHY THIS EXISTS RATHER THAN A DEPENDENCY. A law's declaration file — role-registry.toml's written
// dispositions — is a test INPUT of :quality-architecture, and this module's test classpath carries
// Konsist and JUnit and nothing else. Adding ktoml here to read four value shapes would put a
// parser between a law and the text a reviewer reads in a diff, for no boundary it does not already
// have. So the reader is exactly the subset those files use and refuses everything else BY NAME:
// tables, arrays of tables, basic strings, multiline basic strings (escapes and the line-ending
// `\` fold included), arrays of strings and booleans. A value shape nobody declared is a
// [ParseError], never a silently-dropped key — a declaration file that half-parses is the same
// failure as one nobody reads.
//
// NOT SUPPORTED, and the reader says so rather than guessing: literal (single-quoted) strings,
// integers, floats, dates, inline tables, nested arrays and dotted keys. RoleRegistryLawTest proves
// the reader against the real file by its known counts, which is what keeps this from being a
// second opinion about the bytes.
package splice.quality

internal object MiniToml {
    private const val TRIPLE = "\"\"\""
    private const val HEX = 16
    private const val SHORT_UNICODE = 4
    private const val LONG_UNICODE = 8
    private const val FOLD_CHARS = " \t\r\n"

    private val SIMPLE_ESCAPES = mapOf(
        '"' to '"',
        '\\' to '\\',
        'b' to '\b',
        't' to '\t',
        'n' to '\n',
        'f' to '\u000C',
        'r' to '\r',
    )

    /** Every value shape the declaration files use. */
    sealed interface Value

    data class Text(val value: String) : Value

    data class Flag(val value: Boolean) : Value

    data class Items(val value: List<Value>) : Value

    /** One `[table]`, or one element of an `[[array of tables]]`. */
    data class Table(val keys: Map<String, Value>) {
        /** The key's string value, or null when it is absent OR is not a string. */
        fun text(key: String): String? = (keys[key] as? Text)?.value

        /** The key's array, or null when it is absent OR is not an array. */
        private fun items(key: String): List<Value>? = (keys[key] as? Items)?.value

        /** The key's boolean, or null when it is absent OR is not a boolean. */
        fun flag(key: String): Boolean? = (keys[key] as? Flag)?.value

        /** The key's array READ AS STRINGS, or null when any element is not one. */
        fun strings(key: String): List<String>? {
            val listed = items(key) ?: return null
            val texts = listed.filterIsInstance<Text>()
            return if (texts.size == listed.size) texts.map { it.value } else null
        }
    }

    data class Document(val tables: Map<String, Table>, val arrays: Map<String, List<Table>>) {
        /** The `[[name]]` elements, in file order; empty when the file declares none. */
        fun array(name: String): List<Table> = arrays[name].orEmpty()
    }

    /** A shape this reader does not accept. Never swallowed: the caller names it. */
    class ParseError(message: String) : IllegalArgumentException(message)

    private data class Header(val name: String, val ofArray: Boolean)

    private data class Scan(val value: Value, val next: Int)

    fun parse(text: String): Document {
        val tables = linkedMapOf<String, Table>()
        val arrays = linkedMapOf<String, MutableList<Table>>()
        val lines = text.split("\n")
        var current = mutableMapOf<String, Value>()
        var i = 0
        while (i < lines.size) {
            val line = lines[i].trim()
            val header = headerOf(line)
            val skip = line.isEmpty() || line.startsWith("#")
            i = when {
                skip -> i + 1
                header != null -> {
                    current = open(header, tables, arrays)
                    i + 1
                }
                else -> readPair(lines, i, current)
            }
        }
        return Document(tables, arrays)
    }

    private fun headerOf(line: String): Header? {
        val ofArray = line.startsWith("[[") && line.endsWith("]]")
        val ofTable = line.startsWith("[") && line.endsWith("]")
        return when {
            ofArray -> Header(line.substring(2, line.length - 2).trim(), ofArray = true)
            ofTable -> Header(line.substring(1, line.length - 1).trim(), ofArray = false)
            else -> null
        }
    }

    private fun open(
        header: Header,
        tables: MutableMap<String, Table>,
        arrays: MutableMap<String, MutableList<Table>>,
    ): MutableMap<String, Value> {
        val keys = mutableMapOf<String, Value>()
        if (header.ofArray) {
            arrays.getOrPut(header.name) { mutableListOf() } += Table(keys)
        } else {
            tables[header.name] = Table(keys)
        }
        return keys
    }

    private fun readPair(lines: List<String>, at: Int, into: MutableMap<String, Value>): Int {
        val line = lines[at]
        val equals = line.indexOf('=')
        if (equals < 0) throw ParseError("line ${at + 1}: `${line.trim()}` is neither a header nor a key/value pair")
        val scan = readValue(lines, at, line.substring(equals + 1).trim())
        into[line.substring(0, equals).trim()] = scan.value
        return scan.next
    }

    private fun readValue(lines: List<String>, at: Int, raw: String): Scan = when {
        raw.startsWith(TRIPLE) -> readMultiline(lines, at, raw)
        raw.startsWith("[") -> readArray(lines, at, raw)
        raw.startsWith("\"") -> Scan(Text(decode(quotedBody(raw, at), multiline = false)), at + 1)
        raw.startsWith("true") -> Scan(Flag(true), at + 1)
        raw.startsWith("false") -> Scan(Flag(false), at + 1)
        else -> throw ParseError("line ${at + 1}: `$raw` is not a shape this reader accepts")
    }

    /** The body of the basic string starting at index 0 of [raw], escapes still in place.
     *
     *  THE REMAINDER IS CHECKED, and that is the whole of PR 6 review F5. Taking the first closing
     *  quote and dropping whatever followed turns a malformed edit into a GREEN: a name added to an
     *  array without its comma —
     *      "ShutdownHookRemove"
     *      "SelftestVanishedRole",
     *  splits into ONE item, the second name vanishes from the denominator, and the law that grades
     *  against it passes over a file it half-read. A real TOML parser threw there. Anything after
     *  the closing quote but a `#` comment is that shape, and it fails BY NAME. */
    private fun quotedBody(raw: String, at: Int): String {
        val end = quotedEnd(raw, 0)
        if (end < 0) throw ParseError("line ${at + 1}: unterminated string `$raw`")
        val rest = raw.substring(end + 1).trim()
        if (rest.isNotEmpty() && !rest.startsWith("#")) {
            throw ParseError(
                "line ${at + 1}: `$rest` follows a closing quote — a missing comma or an unread value, " +
                    "never a silently dropped one",
            )
        }
        return raw.substring(1, end)
    }

    /** The index of the quote closing the one at [from], or -1. */
    private fun quotedEnd(raw: String, from: Int): Int {
        var i = from + 1
        while (i < raw.length) {
            val ch = raw[i]
            val closes = ch == '"'
            i += if (ch == '\\') 2 else 1
            if (closes) return i - 1
        }
        return -1
    }

    private fun readMultiline(lines: List<String>, at: Int, raw: String): Scan {
        val first = raw.substring(TRIPLE.length)
        val sameLine = first.indexOf(TRIPLE)
        if (sameLine >= 0) return Scan(Text(decode(first.substring(0, sameLine), multiline = true)), at + 1)
        val parts = mutableListOf<String>()
        if (first.isNotEmpty()) parts += first
        var i = at + 1
        while (i < lines.size) {
            val close = lines[i].indexOf(TRIPLE)
            val done = close >= 0
            parts += if (done) lines[i].substring(0, close) else lines[i]
            i += 1
            if (done) return Scan(Text(decode(parts.joinToString("\n"), multiline = true)), i)
        }
        throw ParseError("line ${at + 1}: unterminated multiline string")
    }

    private fun readArray(lines: List<String>, at: Int, raw: String): Scan {
        val rest = (listOf(raw) + lines.subList(minOf(at + 1, lines.size), lines.size)).joinToString("\n")
        val code = codePositions(rest)
        val end = arrayEnd(rest, code)
        if (end < 0) throw ParseError("line ${at + 1}: unterminated array")
        val consumed = rest.substring(0, end).count { it == '\n' }
        val items = splitItems(rest.substring(1, end), code, 1).map { itemValue(it, at) }
        return Scan(Items(items), at + consumed + 1)
    }

    private fun itemValue(part: String, at: Int): Value {
        val token = part.trim()
        return when {
            token.startsWith("\"") -> Text(decode(quotedBody(token, at), multiline = false))
            token == "true" -> Flag(true)
            token == "false" -> Flag(false)
            else -> throw ParseError("line ${at + 1}: `$token` is not a string or boolean array element")
        }
    }

    /** True at every index that is NOT inside a basic string or a `#` comment. */
    private fun codePositions(text: String): BooleanArray {
        val code = BooleanArray(text.length) { true }
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            i = when {
                ch == '"' -> markString(text, i, code)
                ch == '#' -> markComment(text, i, code)
                else -> i + 1
            }
        }
        return code
    }

    private fun markString(text: String, at: Int, code: BooleanArray): Int {
        val end = quotedEnd(text, at)
        val last = if (end < 0) text.length - 1 else end
        for (k in at..last) code[k] = false
        return last + 1
    }

    private fun markComment(text: String, at: Int, code: BooleanArray): Int {
        var i = at
        while (i < text.length && text[i] != '\n') {
            code[i] = false
            i += 1
        }
        return i
    }

    /** The index of the `]` closing the `[` at index 0, or -1. */
    private fun arrayEnd(text: String, code: BooleanArray): Int {
        var depth = 0
        for (i in text.indices) {
            val opens = code[i] && text[i] == '['
            val closes = code[i] && text[i] == ']'
            if (opens) depth += 1
            if (closes) depth -= 1
            if (closes && depth == 0) return i
        }
        return -1
    }

    /** Split an array body on its top-level commas. [offset] is the body's index inside [code]. */
    private fun splitItems(body: String, code: BooleanArray, offset: Int): List<String> {
        val parts = mutableListOf<String>()
        val buf = StringBuilder()
        var depth = 0
        for (i in body.indices) {
            if (!code[i + offset]) {
                buf.append(body[i])
                continue
            }
            val ch = body[i]
            if (ch == '[') depth += 1
            if (ch == ']') depth -= 1
            val separates = ch == ',' && depth == 0
            if (separates) {
                parts += buf.toString()
                buf.setLength(0)
            } else {
                buf.append(ch)
            }
        }
        parts += buf.toString()
        return parts.filter { it.isNotBlank() }
    }

    /** Decode a basic string's escapes. Multiline strings also fold a line-ending `\`. */
    private fun decode(raw: String, multiline: Boolean): String {
        val out = StringBuilder(raw.length)
        var i = 0
        while (i < raw.length) {
            val ch = raw[i]
            i = if (ch == '\\') {
                escape(raw, i, out, multiline)
            } else {
                out.append(ch)
                i + 1
            }
        }
        return out.toString()
    }

    private fun escape(raw: String, at: Int, out: StringBuilder, multiline: Boolean): Int {
        val next = raw.getOrNull(at + 1)
        val foldable = next != null && next in FOLD_CHARS
        val simple = SIMPLE_ESCAPES[next]
        return when {
            next == null -> throw ParseError("a string ends on a lone backslash")
            multiline && foldable -> skipFold(raw, at + 1)
            simple != null -> {
                out.append(simple)
                at + 2
            }
            next == 'u' -> unicode(raw, at, out, SHORT_UNICODE)
            next == 'U' -> unicode(raw, at, out, LONG_UNICODE)
            else -> throw ParseError("`\\$next` is not a TOML basic-string escape")
        }
    }

    /** A line-ending `\` trims the newline and every leading blank on the lines that follow. */
    private fun skipFold(raw: String, from: Int): Int {
        var i = from
        while (i < raw.length && raw[i] in FOLD_CHARS) i += 1
        return i
    }

    private fun unicode(raw: String, at: Int, out: StringBuilder, width: Int): Int {
        val start = at + 2
        val hex = raw.substring(start, minOf(start + width, raw.length))
        val code = hex.toIntOrNull(HEX) ?: throw ParseError("`\\${raw[at + 1]}$hex` is not a hex escape")
        out.appendCodePoint(code)
        return start + width
    }
}
