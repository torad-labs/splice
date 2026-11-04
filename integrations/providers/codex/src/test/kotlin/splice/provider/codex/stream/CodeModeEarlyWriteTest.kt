package splice.provider.codex.stream

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.provider.codex.stream.CodeModeEarlyWrite.Event
import kotlin.random.Random

/**
 * Goal: when GPT's code-mode script starts with a Write, Claude Code shows Write(path) as soon as the path is written
 * and the file's content streams into it while GPT writes it. This reader is the part that sees the path and the
 * content in the script as it streams: whatever way the script is split into deltas, it names the path the script
 * gives, and the text it streams is the file the script asks for, or the start of it and never more.
 */
class CodeModeEarlyWriteTest {
    @Test
    fun `the Mac session's own script opens at its path and streams its css`() {
        val script = "text(await tools.Write({file_path:\"/Users/m/vend/site/styles.css\"," +
            "content:`@font-face{font-family:\"DM Sans\";src:url(a.woff2)}\nbody{margin:0}"
        val events = script.map { it.toString() }.flatMap(CodeModeEarlyWrite()::feed)

        assertEquals(Event.Opened("/Users/m/vend/site/styles.css"), events.first())
        assertEquals(
            "@font-face{font-family:\"DM Sans\";src:url(a.woff2)}\nbody{margin:0}",
            content(events),
        )
    }

    @Test
    fun `whatever way the script is split, the path and the whole file come out as the script wrote them`() {
        repeat(RUNS) { seed ->
            val random = Random(seed)
            val path = text(random, PATH_ALPHABET, 1, 60)
            val file = text(random, FILE_ALPHABET, 0, 400)
            val script = script(random, literal(path, quote(random), random), literal(file, quote(random), random))
            val events = split(script, random).flatMap(CodeModeEarlyWrite()::feed)

            assertEquals(Event.Opened(path), events.firstOrNull(), "seed $seed: $script")
            assertTrue(events.drop(1).all { it is Event.Content }, "seed $seed: one opening only")
            assertEquals(file, content(events), "seed $seed: $script")
            assertFalse(
                events.any { it is Event.Content && it.text.lastOrNull()?.isHighSurrogate() == true },
                "seed $seed: a character is never split across two deltas",
            )
        }
    }

    @Test
    fun `a template's interpolation stops the stream at the text before it`() {
        repeat(RUNS) { seed ->
            val random = Random(seed)
            val file = text(random, FILE_ALPHABET, 1, 300)
            val cut = random.nextInt(file.length + 1)
            val at = if (cut > 0 && file[cut - 1].isHighSurrogate()) cut - 1 else cut
            val before = literal(file.substring(0, at), '`', random).dropLast(1)
            val after = literal(file.substring(at), '`', random).drop(1)
            val script = script(random, "\"/w/page.html\"", "$before\${title}$after")
            val events = split(script, random).flatMap(CodeModeEarlyWrite()::feed)

            assertEquals(Event.Opened("/w/page.html"), events.firstOrNull(), "seed $seed: $script")
            assertEquals(file.substring(0, at), content(events), "seed $seed: $script")
        }
    }

    @Test
    fun `a script that runs anything before its Write streams nothing`() {
        val before = listOf("const x = 1;\n", "await tools.Read({});\n", "if (ok) ", "for (const f of files) ")
        repeat(RUNS) { seed ->
            val random = Random(seed)
            val write = script(random, "\"/w/a.txt\"", literal(text(random, FILE_ALPHABET, 0, 80), '"', random))
            val script = before[random.nextInt(before.size)] + write
            val events = split(script, random).flatMap(CodeModeEarlyWrite()::feed)

            assertEquals(emptyList<Event>(), events, "seed $seed: $script")
        }
    }

    @Test
    fun `a path or a content that is not a plain string literal streams nothing`() {
        listOf(
            "text(await tools.Write({file_path: dir + \"/a.css\", content: \"a\"}))",
            "text(await tools.Write({file_path: \"/w/a.css\", content: lines.join(\"\\n\")}))",
            "text(await tools.Write({file_path: `/w/\${name}.css`, content: \"a\"}))",
            "text(await tools.Read({file_path: \"/w/a.css\"}))",
        ).forEach { script ->
            assertEquals(emptyList<Event>(), split(script, Random(0)).flatMap(CodeModeEarlyWrite()::feed), script)
        }
    }

    @Test
    fun `an escape the reader cannot be sure of holds the stream where it stands`() {
        val script = "text(await tools.Write({file_path:\"/w/a.txt\",content:\"abc\\1def\"}))"
        val events = CodeModeEarlyWrite().feed(script)

        assertEquals(listOf(Event.Opened("/w/a.txt"), Event.Content("abc")), events)
    }

    private fun content(events: List<Event>): String =
        events.filterIsInstance<Event.Content>().joinToString("") { it.text }

    /** A Write call as GPT writes one, with the spacing and key quoting it varies. */
    private fun script(random: Random, path: String, content: String): String {
        val text = random.nextBoolean()
        fun ws() = listOf("", " ", "\n", "  ")[random.nextInt(4)]
        fun key(name: String) = listOf(name, "\"$name\"", "'$name'")[random.nextInt(3)]
        val head = (if (text) "text${ws()}(${ws()}" else "") +
            "await ${ws()}tools${ws()}.${ws()}Write${ws()}(${ws()}{${ws()}" +
            "${key("file_path")}${ws()}:${ws()}$path${ws()},${ws()}${key("content")}${ws()}:${ws()}"
        return head + content + "${ws()}})" + (if (text) ")" else "") + ";"
    }

    /** [text] as a JavaScript string literal in [quote], escaped the ways a model writes one, with a line
     *  continuation now and then. */
    private fun literal(text: String, quote: Char, random: Random): String {
        val out = StringBuilder().append(quote)
        var at = 0
        while (at < text.length) {
            if (random.nextInt(40) == 0) out.append("\\\n")
            at += encode(text, at, quote, random, out)
        }
        return out.append(quote).toString()
    }

    /** The character of [text] at [at] as a model escapes it in [quote]; returns how many characters it took. */
    private fun encode(text: String, at: Int, quote: Char, random: Random, out: StringBuilder): Int {
        val c = text[at]
        val plain = plainEscape(c, text.getOrNull(at + 1), quote, random)
        when {
            plain != null -> out.append(plain)
            c.isHighSurrogate() && random.nextBoolean() -> {
                out.append("\\u{").append(text.codePointAt(at).toString(HEX)).append('}')
                return 2
            }
            c.code > ASCII && !c.isSurrogate() && random.nextInt(3) == 0 ->
                out.append("\\u").append(c.code.toString(HEX).padStart(4, '0'))
            else -> out.append(c)
        }
        return 1
    }

    private fun plainEscape(c: Char, next: Char?, quote: Char, random: Random): String? = when (c) {
        quote, '\\' -> "\\$c"
        '\n', '\t' -> spacing(c, quote, random)
        '\r' -> "\\r"
        '\u0000' -> "\\u0000"
        '$' -> "\\$".takeIf { quote == '`' && next == '{' }
        else -> null
    }

    /** A line break or a tab: raw where the literal allows it, now and then, and escaped otherwise. */
    private fun spacing(c: Char, quote: Char, random: Random): String {
        val raw = random.nextBoolean() && (c == '\t' || quote == '`')
        return when {
            raw -> c.toString()
            c == '\n' -> "\\n"
            else -> "\\t"
        }
    }

    private fun quote(random: Random): Char = QUOTES[random.nextInt(QUOTES.length)]

    private fun text(random: Random, alphabet: List<String>, min: Int, max: Int): String =
        (0 until random.nextInt(min, max + 1)).joinToString("") { alphabet[random.nextInt(alphabet.size)] }

    /** The script cut into deltas the way a stream cuts it: anywhere, a character or many at a time. */
    private fun split(script: String, random: Random): List<String> {
        val widest = listOf(1, 3, 17, script.length.coerceAtLeast(1))[random.nextInt(4)]
        val parts = mutableListOf<String>()
        var at = 0
        while (at < script.length) {
            val end = minOf(script.length, at + random.nextInt(1, widest + 1))
            parts += script.substring(at, end)
            at = end
        }
        return parts
    }
}

private const val RUNS = 400
private const val HEX = 16
private const val ASCII = 0x7f
private const val QUOTES = "\"'`"
private val PATH_ALPHABET = "/abc-_.é ".map { it.toString() } + listOf("😀", "\"", "'", "\\", "中")
private val FILE_ALPHABET = "abcXYZ 019{}()\$;:,.-_=+*/<>#@!?\"'`\\\n\t\r".map { it.toString() } +
    listOf("é", "中", "😀", "\u0000", "\${")
