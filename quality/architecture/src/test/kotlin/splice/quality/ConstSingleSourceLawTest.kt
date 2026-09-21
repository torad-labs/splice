// NEW: V4-88 — a constant that has ONE MEANING has one declaration site, and no comment is
// load-bearing for equality (ported from checks/const-single-source.ts, restructure PR 6).
//
// CLASS. TWO planes, because certainty differs per finding and a wall with 144 correct-looking
// errors gets suppressed and takes the five real ones with it.
//   STRICT — red always, no baseline, no allowlist, no escape. The shapes where the CODE ITSELF
//            says the values must agree: EQUAL-BY-COMMENT (a const whose adjacent comment asserts
//            an equality obligation AND names another const), KNOB-SHADOW (a const whose value
//            equals a Knob's declared default and whose name is within one qualifier token of that
//            Knob's), NAMED-SCAR (a duplicated name on the hand-authored list, each entry carrying
//            a written reason).
//   RATCHET — recorded in quality/architecture/src/test/resources/const-single-source-baseline.json
//            and then held: COPY (the same const NAME in 2+ files with the same NORMALISED value)
//            and COLLISION (the same NAME with DIFFERENT values — one name, two meanings). The
//            resource is lowered BY HAND; nothing here rewrites it, so every movement of the
//            ratchet is a line in a diff a human wrote.
//
// WHY IT EXISTS (ARCH-AUDIT 2026-09-17). Kotlin main sources in this tree carry no `companion`
// blocks (kt-no-companion-objects), so every constant is a file-scope `const val`. That style is
// right and it has one failure mode: a value needed in a second file gets a second `const val`
// instead of an import, and nothing afterwards keeps the two equal. The audit found the mature form
// — two 120_000L ceilings held equal by a PROSE COMMENT saying "The two must stay equal". A comment
// is not a wall: when one number moves, the gateway tells the client to come back at a time the
// cooldown has not finished, and every test stays green.
//
// WHY THE FIRST VERSION WAS WRONG, recorded because the correction IS the design. It reported all
// 144 duplicated names by name. Sampling killed the premise: FIELD_CONTENT = "content" in five
// dialect files, the CliStyle / MultiSelectPrompt escape codes, FIELD_ID = "id" in codemode versus
// responses — file-local names for DIFFERENT wires whose values coincide. Making one import the
// other would ADD cross-module coupling. Deriving the denominator from the source (§24) is
// necessary and not sufficient: the predicate over it still has to be the right one.
//
// SCOPE. Every `.kt` under `<module>/src/main` of every module the BUILD declares
// (KotlinText.kotlinFiles), never a glob. The checker named its module homes by hand; the map is
// the same 744 files, proven file-for-file at the port.
//
// DENOMINATOR, FROM THE SOURCE (§24). Every `const val` at any depth, 1352 today, plus the Knob
// plane parsed out of Knob.kt's enum entries. A file added tomorrow, or a Knob added tomorrow, is
// in scope with no edit to this file. Three guards refuse a vacuous pass: zero source files is a
// failure, zero parsed consts is a failure, and the parsed count must EQUAL the count of
// `const val` lines in the tree — a parser that has drifted off the source cannot be trusted to
// report an absence.
//
// PARSE, line-based and comment/string aware. VALUES ARE COMPARED NORMALISED, which is not
// cosmetic: measured on this tree a textual comparison mis-classed BOLD/RED/GREEN/DIM/CYAN/RESET
// (a raw ESC byte in one file, `\u001B` in the other — the SAME string), MILLIS_PER_SECOND
// (1000L vs 1_000L), MS_PER_S, BYTE_MASK (0xFF vs 0xff) and TTL_MS as COLLISIONs. Normalisation
// decodes \uXXXX escapes, drops digit separators and numeric type suffixes, lowercases hex digits
// and collapses whitespace, so two spellings of one value are one value. A declaration's value may
// continue onto the NEXT line and is read from there; an expression spanning three or more lines is
// normalised as its first two.
//
// VIOLATIONS. STRICT findings always. GROWTH — a COPY/COLLISION group not in the baseline, or a
// baselined group that has SPREAD to a new file. STALE — a group that has shrunk (red with the
// exact new file count and the resource to lower by hand), a recorded group the tree no longer
// duplicates, or a name recorded here AND on the strict list: the two planes may not launder each
// other.
//
// NOT CAUGHT, stated rather than implied. A duplicate with a DIFFERENT name and the same value —
// value-only matching over 619 numeric consts is mostly noise, so the HTTP status family gets its
// own structural wall (quality/rules/kotlin/kt-http-status-single-source.yml) and the general case
// stays open by choice. Non-`const` `val` declarations: a computed val is a different subject. A
// Knob shadow more than one qualifier away from its Knob's name. An equality comment whose
// counterpart is a SINGLE-token name, or is not a const at all.
package splice.quality

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

internal object ConstSingleSource {
    const val KNOB_IN_CORE = "splice/core/config/Knob.kt"
    const val BASELINE_RESOURCE = "const-single-source-baseline.json"
    const val BASELINE_PATH = "quality/architecture/src/test/resources/$BASELINE_RESOURCE"

    /** The name this law gives itself in a NAMED-SCAR finding. */
    private const val THIS_FILE = "ConstSingleSourceLawTest.kt"

    /** THE NAMED SCARS — duplicated names the audit identified as ONE meaning, each with the reason
     *  it is one. Red regardless of the ratchet baseline; this list IS the fix row's checklist. It
     *  is the only hand-authored list here, so it is cross-checked: an entry that no longer names a
     *  real COPY/COLLISION fails as STALE, and a blank reason fails by name. V4-122 EMPTIED it, and
     *  that is the goal rather than an omission — every name it held had its duplication RESOLVED
     *  rather than baselined, so each entry became STALE by this law's own rule. An empty list is a
     *  valid state: the strict plane's teeth are the SOURCE-derived classes, which fire either way. */
    val NAMED_SCARS: Map<String, String> = emptyMap()

    private val DECL = Regex(
        "^[ \\t]*(?:(?:public|internal|private|protected)\\s+)?const\\s+val\\s+([A-Za-z_][A-Za-z0-9_]*)" +
            "\\s*(?::\\s*[^=]+?)?\\s*=[ \\t]*(.*)$",
    )
    private val CONST_VAL_LINE = Regex("\\bconst\\s+val\\b")

    /** A bare numeric token: decimal, hex or float, with optional digit separators and suffix.
     *  `matches` rather than `containsMatchIn`, because Java's `$` also matches before a trailing
     *  newline and the checker's `^…$` did not. */
    private val NUM_TOKEN = Regex("(?:0[xX][0-9a-fA-F_]+|[0-9][0-9_]*(?:\\.[0-9_]+)?(?:[eE][-+]?[0-9]+)?)[LlFfDdUu]*")

    /** An equality obligation written in prose — a sentence a human wrote instead of a wall. The
     *  detector needs one of these AND a named counterpart before it fires, and the finding quotes
     *  the PATTERN back, so re-wording one would change the message. */
    private val EQUALITY_PHRASES: List<Pair<String, Regex>> = listOf(
        "must\\s+(?:stay|remain|be\\s+kept)\\s+(?:equal|identical|the\\s+same|in\\s+sync)",
        "must\\s+match",
        "must\\s+(?:be\\s+)?the\\s+same\\s+as",
        "kept?\\s+in\\s+sync",
        "in\\s+sync\\s+with",
        "same\\s+value\\s+as",
        "mirror(?:s|ing|ed)?\\b",
    ).map { source -> source to Regex(source, RegexOption.IGNORE_CASE) }

    /** The counterpart an equality comment must NAME. MULTI-TOKEN is what separates an identifier
     *  from prose: every package-scope const here is SCREAMING_SNAKE, and these comments are written
     *  in English that capitalises words for emphasis ("the CLIENT-FACING deadline", "MUST stay
     *  equal"). Requiring an underscore drops CLIENT and KIND while keeping the real ones. */
    private val NAMED_CONST = Regex("\\b(?:Knob\\.)?([A-Z][A-Z0-9]*(?:_[A-Z0-9]+)+)\\b")
    private val KNOB_ENTRY = Regex("^ {4}([A-Z][A-Z0-9_]*)(?=\\()", RegexOption.MULTILINE)
    private val NAMED_ARG = Regex("^[A-Za-z_][A-Za-z0-9_]*\\s*=[^=]")
    private val LINE_COMMENT = Regex("//[^\\n]*")
    private val REQUIRED_KEYS = listOf("recorded", "total", "denominator", "groups")

    /** One `const val` declaration, its value already normalised. */
    data class Konst(val name: String, val rel: String, val line: Int, val value: String, val comment: String) {
        val where: String get() = "$rel:$line"
    }

    /** One duplicated NAME: its class, its declarations, and the files it spans. */
    data class Group(val kind: String, val name: String, val members: List<Konst>) {
        /** The baseline key: class + name. The sorted files are the VALUE, so a spread is visible. */
        val key: String get() = "$kind $name"
        val files: List<String> get() = members.map { it.rel }.distinct().sorted()
        val values: List<String> get() = members.map { it.value }.distinct().sorted()

        /** Ordered by (rel, line), so the message is stable. */
        val sites: String
            get() = members.sortedWith(compareBy({ it.rel }, { it.line })).joinToString(", ") { it.where }

        fun describe(): String = if (kind == "COPY") {
            "$name = ${values[0]} is declared in ${files.size} files ($sites)"
        } else {
            "$name is declared in ${files.size} files with ${values.size} different values " +
                "${values.joinToString(", ", "[", "]") { "'$it'" }} — one name, two meanings ($sites)"
        }
    }

    data class Parsed(val consts: List<Konst>, val rawCount: Int)
    data class Census(val consts: List<Konst>, val problems: List<String>)
    data class Knobs(val defaults: Map<String, String>, val problems: List<String>)
    data class EqualityHit(val konst: Konst, val phrase: String, val named: List<String>)
    data class ShadowHit(val konst: Konst, val knob: String, val value: String)
    data class Subject(val files: List<File>, val root: File, val knob: File, val knobRel: String)
    data class Baseline(
        val recorded: String,
        val denominator: Int,
        val total: Int,
        val groups: Map<String, List<String>>,
        val problems: List<String>,
    )

    /** The text-level readers. Each keeps the EXACT semantics of the checker's own copy — the
     *  comment handling differs between them on purpose (the value reader knows only `//`, the
     *  Knob walk knows `//` and strings, the comma split knows neither) and a "better" reader here
     *  is a different denominator. */
    private object Reader {
        private val ROLE_MARKERS = setOf("DEFAULT", "THE", "VAL")
        private val UNICODE_ESCAPE = Regex("\\\\[uU]([0-9a-fA-F]{4})")
        private val DIGIT_SEPARATOR = Regex("(?<=[0-9])_(?=[0-9])")
        private val NUMERIC_SUFFIX = Regex("(?<=[0-9])[LlFfDdUu]+\\b")
        private val HEX = Regex("0[xX]([0-9a-fA-F]+)")
        private val WHITESPACE = Regex("\\s+")
        private val COMMENT_MARKER = Regex("^/\\*+|^\\*+/?|^//|\\*/$")

        /** Index after the literal opening at [at], quotes included, escapes honoured. */
        fun skipString(text: String, at: Int): Int {
            val quote = text[at]
            var i = at + 1
            var escape = false
            while (i < text.length) {
                val ch = text[i]
                i += 1
                when {
                    escape -> escape = false
                    ch == '\\' -> escape = true
                    ch == quote -> return i
                }
            }
            return i
        }

        /** Drop a trailing `//` comment, respecting string literals — a URL's `//` is not one. */
        fun stripLineComment(text: String): String {
            var i = 0
            while (i < text.length) {
                val ch = text[i]
                if (ch == '/' && text.startsWith("//", i)) return text.substring(0, i)
                i = if (ch == '"' || ch == '\'') skipString(text, i) else i + 1
            }
            return text
        }

        /** One value, one spelling. See the header's VALUES ARE COMPARED NORMALISED. */
        fun normalise(raw: String): String {
            var text = stripLineComment(raw).trim().trimEnd(',')
            text = UNICODE_ESCAPE.replace(text) { it.groupValues[1].toInt(16).toChar().toString() }
            text = DIGIT_SEPARATOR.replace(text, "")
            text = NUMERIC_SUFFIX.replace(text, "")
            text = HEX.replace(text) { "0x" + it.groupValues[1].lowercase() }
            return WHITESPACE.replace(text, " ").trim()
        }

        /** The contiguous comment block immediately above [index], plus that line's own trailing
         *  comment. Contiguity is the point: a blank line between a comment and a declaration means
         *  the comment belongs to whatever is above it. */
        fun commentAbove(lines: List<String>, index: Int): String {
            val parts = mutableListOf<String>()
            var j = index - 1
            while (j >= 0) {
                val stripped = lines[j].trim()
                val opens = stripped.startsWith("//") || stripped.startsWith("*")
                val block = stripped.startsWith("/*") || stripped.endsWith("*/")
                if (!opens && !block) break
                parts += stripped.replace(COMMENT_MARKER, "")
                j -= 1
            }
            parts.reverse()
            val trailing = lines[index]
            val cut = stripLineComment(trailing)
            if (cut.length < trailing.length) parts += trailing.substring(cut.length + 2)
            return parts.joinToString(" ") { it.trim() }.trim()
        }

        /** The text inside the parens opening at [start], string-aware, `//` comments skipped. */
        fun balanced(text: String, start: Int): String? {
            val parens = Parens()
            var i = start
            while (i < text.length) {
                val ch = text[i]
                val quoted = ch == '"' || ch == '\''
                val commented = ch == '/' && text.startsWith("//", i)
                if (quoted || commented) {
                    i = if (quoted) skipString(text, i) else skipLineComment(text, i)
                } else if (parens.closes(ch)) {
                    return text.substring(parens.bodyStart, i)
                } else {
                    parens.open(ch, i)
                    i += 1
                }
            }
            return null
        }

        /** Split on top-level commas, string-aware. Comments are NOT skipped, exactly as there. */
        fun splitTopLevel(body: String): List<String> {
            val parts = mutableListOf<String>()
            val buf = StringBuilder()
            var depth = 0
            var i = 0
            while (i < body.length) {
                val ch = body[i]
                if (ch == '"' || ch == '\'') {
                    val end = skipString(body, i)
                    buf.append(body, i, end)
                    i = end
                } else {
                    if (ch in "([{") depth += 1 else if (ch in ")]}") depth -= 1
                    val separates = ch == ',' && depth == 0
                    if (separates) {
                        parts += buf.toString()
                        buf.setLength(0)
                    } else {
                        buf.append(ch)
                    }
                    i += 1
                }
            }
            if (buf.isNotEmpty()) parts += buf.toString()
            return parts.map { it.trim() }
        }

        /** Significant name tokens: DEFAULT_ is a role marker and single letters carry no meaning. */
        fun tokens(name: String): Set<String> =
            name.split("_").filter { it.length > 1 && it !in ROLE_MARKERS }.toSet()

        private fun skipLineComment(text: String, at: Int): Int {
            val newline = text.indexOf('\n', at)
            return if (newline < 0) text.length else newline
        }
    }

    /** The baseline document's shape, read off JSON. Its own object so [loadBaseline] stays a
     *  sentence: parse or say why, then four fields with a written default for each. */
    private object Doc {
        fun parsed(text: String?): Pair<JsonObject?, String?> {
            if (text == null) {
                return null to "${ConstSingleSource.BASELINE_PATH}: missing — the ratchet has no recorded " +
                    "census to hold the tree to"
            }
            return try {
                Json.parseToJsonElement(text).jsonObject to null
            } catch (unreadable: IllegalArgumentException) {
                null to "${ConstSingleSource.BASELINE_PATH}: unreadable ($unreadable) — a ratchet that cannot " +
                    "read its baseline cannot gate"
            }
        }

        fun intAt(document: JsonObject, key: String): Int =
            (document[key] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0

        fun groups(document: JsonObject): Map<String, List<String>> =
            ((document["groups"] as? JsonObject) ?: JsonObject(emptyMap())).mapValues { (_, value) ->
                ((value as? JsonArray) ?: JsonArray(emptyList())).mapNotNull { (it as? JsonPrimitive)?.content }
            }
    }

    /** Paren depth for [Reader.balanced]: the body's first index once depth reaches one. */
    private class Parens {
        private var depth = 0
        var bodyStart = -1
            private set

        fun open(ch: Char, at: Int) {
            if (ch == '(') {
                depth += 1
                if (depth == 1) bodyStart = at + 1
            }
        }

        /** True when [ch] closes the outermost paren; the depth is decremented either way. */
        fun closes(ch: Char): Boolean {
            if (ch != ')') return false
            depth -= 1
            return depth == 0 && bodyStart >= 0
        }
    }

    /** One file's declarations, and the raw `const val` line count the parser is graded against. */
    fun parseFile(rel: String, text: String): Parsed {
        val lines = KotlinText.splitLines(text)
        val found = mutableListOf<Konst>()
        var rawCount = 0
        for (i in lines.indices) {
            val line = lines[i]
            val stripped = line.trim()
            val commented = stripped.startsWith("//") || stripped.startsWith("*")
            if (commented) continue
            if (CONST_VAL_LINE.containsMatchIn(line)) rawCount += 1
            val match = DECL.find(line)
            if (match != null) {
                val inline = match.groupValues[2].trim()
                // continuation form: `const val X =` with the value on the next line
                val raw = if (inline.isNotEmpty()) inline else lines.getOrNull(i + 1)?.trim().orEmpty()
                found += Konst(match.groupValues[1], rel, i + 1, Reader.normalise(raw), Reader.commentAbove(lines, i))
            }
        }
        return Parsed(found, rawCount)
    }

    /** Every main-source declaration, plus the problems that make a report untrustworthy. */
    fun parseTree(files: List<File>, root: File): Census {
        if (files.isEmpty()) {
            return Census(
                emptyList(),
                listOf("no main sources under the project map's src/main trees — the denominator is absent"),
            )
        }
        val consts = mutableListOf<Konst>()
        var rawTotal = 0
        for (file in files) {
            val parsed = parseFile(file.relativeTo(root).invariantSeparatorsPath, file.readText())
            consts += parsed.consts
            rawTotal += parsed.rawCount
        }
        val problems = mutableListOf<String>()
        if (consts.isEmpty()) {
            problems += "parsed 0 const declarations from ${files.size} main source file(s) — refusing to pass " +
                "vacuously, because a green over an empty denominator is what this wall exists to prevent"
        }
        if (rawTotal != consts.size) {
            problems += "parsed ${consts.size} declarations but the tree holds $rawTotal `const val` lines — the " +
                "parser and the source disagree, so no finding or absence from this run can be trusted"
        }
        return Census(consts, problems)
    }

    /** Knob name -> normalised numeric default, parsed from the enum entries. */
    fun knobDefaults(knob: File, knobRel: String): Knobs {
        if (!knob.isFile) return Knobs(emptyMap(), emptyList())
        val text = knob.readText()
        val entries = KNOB_ENTRY.findAll(text).toList()
        if (entries.isEmpty()) {
            return Knobs(emptyMap(), listOf("$knobRel: parsed 0 Knob entries — the Knob plane cannot be checked"))
        }
        val defaults = linkedMapOf<String, String>()
        for (match in entries) {
            val body = Reader.balanced(text, match.range.last + 1)
            val positional = Reader.splitTopLevel(body.orEmpty()).filterNot { NAMED_ARG.containsMatchIn(it) }
            val readable = body != null && positional.size >= 4
            if (readable) {
                val fallback = Reader.normalise(LINE_COMMENT.replace(positional[3], ""))
                if (NUM_TOKEN.matches(fallback)) defaults[match.groupValues[1]] = fallback
            }
        }
        // KNOB-SHADOW compares against these DEFAULTS, so entries that all match while none yields a
        // default is the plane switched OFF, not a clean tree — and `entries.isEmpty()` cannot see it.
        val unreadable = "$knobRel: matched ${entries.size} Knob entry/entries but read 0 defaults — the " +
            "reader drops named arguments and takes the default from the FOURTH positional, so respelling " +
            "the entries leaves every entry matched and every default unread, with KNOB-SHADOW silently off"
        return if (defaults.isEmpty()) Knobs(emptyMap(), listOf(unreadable)) else Knobs(defaults, emptyList())
    }

    /** Every name declared in 2+ FILES, classed COPY (one normalised value) or COLLISION. */
    fun duplicates(consts: List<Konst>): List<Group> {
        val byName = consts.groupBy { it.name }
        return byName.keys.sorted().mapNotNull { name ->
            val members = byName.getValue(name)
            val kind = if (members.distinctBy { it.value }.size == 1) "COPY" else "COLLISION"
            if (members.distinctBy { it.rel }.size < 2) null else Group(kind, name, members)
        }
    }

    /** Declarations whose adjacent comment ASSERTS an equality with another const in the census. */
    fun equalityComments(consts: List<Konst>): List<EqualityHit> {
        val names = consts.map { it.name }.toSet()
        val hits = mutableListOf<EqualityHit>()
        for (konst in consts) {
            val phrase = EQUALITY_PHRASES.firstOrNull { it.second.containsMatchIn(konst.comment) }
            val named = NAMED_CONST.findAll(konst.comment)
                .map { it.groupValues[1] }
                .distinct()
                .filter { it != konst.name && it in names }
                .toList()
            if (phrase != null && named.isNotEmpty()) hits += EqualityHit(konst, phrase.first, named)
        }
        return hits
    }

    /** Declarations whose local literal default forks a Knob's operator-facing default. */
    fun knobShadows(consts: List<Konst>, knobs: Map<String, String>): List<ShadowHit> =
        consts.mapNotNull { konst -> shadow(konst, knobs) }

    /** Subset AND within one qualifier. The bare subset test pairs any const built only of generic
     *  unit words with any knob that carries them: measured, it filed a `gh attestation verify`
     *  subprocess budget ({TIMEOUT, MS}) against Knob.FIRST_BYTE_TIMEOUT_MS — same number, unrelated
     *  subject. Two names for ONE value differ by at most one qualifier, so that is the bound. */
    private fun shadow(konst: Konst, knobs: Map<String, String>): ShadowHit? {
        val local = if (NUM_TOKEN.matches(konst.value)) Reader.tokens(konst.name) else emptySet()
        if (local.isEmpty()) return null
        val knob = knobs.keys.sorted().firstOrNull { name ->
            val knobTokens = Reader.tokens(name)
            val subset = local.all { token -> token in knobTokens }
            val near = knobTokens.count { token -> token !in local } <= 1
            konst.value == knobs.getValue(name) && subset && near
        }
        return if (knob == null) null else ShadowHit(konst, knob, knobs.getValue(knob))
    }

    /** EQUAL-BY-COMMENT, KNOB-SHADOW and NAMED-SCAR. No baseline reaches any of these. */
    fun strictProblems(
        consts: List<Konst>,
        knobs: Map<String, String>,
        groups: List<Group>,
        scars: Map<String, String>,
    ): List<String> {
        val problems = mutableListOf<String>()
        for (hit in equalityComments(consts)) {
            problems += "EQUAL-BY-COMMENT: ${hit.konst.where} ${hit.konst.name} = ${hit.konst.value} — its " +
                "comment asserts an equality (/${hit.phrase}/) with ${hit.named.joinToString(", ")}. A comment " +
                "is not a wall: make one of them the declaration and import it"
        }
        for (hit in knobShadows(consts, knobs)) {
            problems += "KNOB-SHADOW: ${hit.konst.where} ${hit.konst.name} = ${hit.value} duplicates " +
                "Knob.${hit.knob}'s default (${hit.value}) — the Knob is the operator-facing single source; " +
                "read it instead of re-declaring its default"
        }
        val byName = groups.associateBy { it.name }
        for (name in scars.keys.sorted()) {
            val group = byName[name]
            val reason = scars.getValue(name)
            problems += when {
                group == null ->
                    "NAMED-SCAR STALE: $name is on the NAMED_SCARS list in $THIS_FILE but is no " +
                        "longer declared in 2+ files — delete the entry. A named scar held past its fix is unearned " +
                        "room for the next duplicate to hide in"
                reason.isBlank() ->
                    "NAMED-SCAR: $name is listed with NO reason — a named scar without a " +
                        "written reason is an absence wearing a label; say why it is one meaning, or remove it"
                else -> "NAMED-SCAR (${group.kind}): ${group.describe()} — $reason. One meaning, so one " +
                    "declaration: red regardless of the ratchet baseline"
            }
        }
        return problems
    }

    /** The one problem [group] raises against the files [was] records, or null when it has not moved. */
    private fun moved(group: Group, was: List<String>?): String? {
        if (was == null) {
            return "GROWTH (${group.kind}): ${group.describe()} — not in the baseline. Give it one declaration " +
                "and an import, or record it in $BASELINE_PATH with the reason it is two independent values"
        }
        val now = group.files
        val spread = now.filterNot { it in was }
        return when {
            now == was -> null
            spread.isNotEmpty() ->
                "GROWTH (${group.kind}): ${group.name} has SPREAD to " +
                    "${spread.joinToString(", ")} — the baseline records ${was.size} file(s), the tree now has " +
                    "${now.size} (${group.sites})"
            else ->
                "STALE (${group.kind}): ${group.name} now spans ${now.size} file(s) but $BASELINE_PATH " +
                    "records ${was.size} — ${was.filterNot { it in now }.joinToString(", ")} no longer declares it; " +
                    "lower the entry BY HAND to the ${now.size} file(s) it has now (${now.joinToString(", ")}) to " +
                    "record the win. Nothing here rewrites the resource, so the ratchet stays visible in a diff"
        }
    }

    /** A recorded key the tree no longer carries, or one the strict plane already owns. */
    private fun stale(key: String, was: Int, measured: Boolean, scars: Map<String, String>): String? {
        val name = if (' ' in key) key.substringAfter(' ') else key
        return when {
            name in scars ->
                "STALE: $BASELINE_PATH records $key, but $name is on the NAMED_SCARS strict list " +
                    "— a name cannot be both baselined and strict; delete the baseline entry"
            !measured ->
                "STALE: $BASELINE_PATH records $key in $was file(s), but the tree no longer declares " +
                    "it in 2+ files — delete the entry BY HAND. A baseline held above the measurement is unearned " +
                    "room for the next duplicate to hide in"
            else -> null
        }
    }

    /** GROWTH and STALE over the COPY/COLLISION plane, keyed by (class, name) with sorted files. */
    fun ratchetProblems(groups: List<Group>, baseline: Baseline, scars: Map<String, String>): List<String> {
        val recorded = baseline.groups.mapValues { (_, files) -> files.sorted() }
        val measured = groups.associateBy { it.key }
        val problems = mutableListOf<String>()
        for (key in measured.keys.sorted()) {
            val group = measured.getValue(key)
            // a NAMED_SCARS name is handled by the strict plane; reporting it twice buries the reason
            val problem = if (group.name in scars) null else moved(group, recorded[key])
            if (problem != null) problems += problem
        }
        for (key in recorded.keys.sorted()) {
            val problem = stale(key, recorded.getValue(key).size, key in measured, scars)
            if (problem != null) problems += problem
        }
        return problems
    }

    fun loadBaseline(text: String?): Baseline {
        val (document, unreadable) = Doc.parsed(text)
        val missing = REQUIRED_KEYS.firstOrNull { document != null && it !in document }
        val problem = unreadable ?: missing?.let { "$BASELINE_PATH: missing required key '$it'" }
        if (document == null || problem != null) return Baseline("", 0, 0, emptyMap(), listOfNotNull(problem))
        return Baseline(
            (document["recorded"] as? JsonPrimitive)?.content.orEmpty(),
            Doc.intAt(document, "denominator"),
            Doc.intAt(document, "total"),
            Doc.groups(document),
            emptyList(),
        )
    }

    /** The GATE: an untrustworthy instrument first, then the strict findings and the ratchet moves. */
    fun audit(subject: Subject, baseline: String?, scars: Map<String, String> = NAMED_SCARS): List<String> {
        val census = parseTree(subject.files, subject.root)
        val recorded = loadBaseline(baseline)
        if (census.problems.isNotEmpty()) return census.problems + recorded.problems
        val knobs = knobDefaults(subject.knob, subject.knobRel)
        val untrusted = knobs.problems + recorded.problems
        if (untrusted.isNotEmpty()) return untrusted
        val groups = duplicates(census.consts)
        return strictProblems(census.consts, knobs.defaults, groups, scars) +
            ratchetProblems(groups, recorded, scars)
    }

    /** The shipped baseline, read off the test classpath. */
    fun baselineText(): String? =
        ConstSingleSource::class.java.getResourceAsStream(
            "/$BASELINE_RESOURCE",
        )?.use { it.readBytes().decodeToString() }
}

class ConstSingleSourceLawTest {
    private val map = ProjectMap.fromSystemProperties()

    @Test
    fun `every duplicated const name is strict-free and at or under its recorded ratchet - V4-88`() {
        val files = KotlinText.kotlinFiles(map)
        assertTrue(files.size > 10) {
            "the map yielded ${files.size} production file(s) — the walk is broken, and a law that reads no " +
                "files passes vacuously."
        }
        val knob = File(map.mainSources(":core"), ConstSingleSource.KNOB_IN_CORE)
        val subject = ConstSingleSource.Subject(files, map.root, knob, KotlinText.rel(map, knob))
        assertTrue(ConstSingleSource.parseTree(files, map.root).consts.size > 500) {
            "the parse has lost the tree — a census this small cannot hold a denominator."
        }
        val problems = ConstSingleSource.audit(subject, ConstSingleSource.baselineText())
        assertTrue(problems.isEmpty()) {
            problems.joinToString(separator = "\n  - ", prefix = "CONST SINGLE SOURCE (V4-88) violated:\n  - ")
        }
    }

    /** The synthetic tree the red proofs write into: three module homes, the Knob enum, and the
     *  baseline handed in as text so every shape is provable without touching the real resource. */
    private class Tree(val root: File) {
        private val synthetic = ProjectMap.parse(root, MODULES, setOf("build"))
        private val knob = File(root, KNOB_REL)

        fun write(vararg sources: Pair<String, String>) {
            for (rel in listOf(A_KT, B_KT, C_KT, KNOB_REL)) File(root, rel).delete()
            for ((rel, text) in sources) {
                File(root, rel).apply { parentFile.mkdirs() }.writeText(text)
            }
            knob.apply { parentFile.mkdirs() }.writeText(KNOB_SOURCE)
        }

        fun audit(baseline: String?, scars: Map<String, String> = emptyMap()): List<String> =
            ConstSingleSource.audit(
                ConstSingleSource.Subject(KotlinText.kotlinFiles(synthetic), root, knob, KNOB_REL),
                baseline,
                scars,
            )

        /** Replace the Knob source [write] stamped, for the arms that grade the Knob reader itself. */
        fun rewriteKnob(text: String) = knob.writeText(text)

        fun census() = ConstSingleSource.parseTree(KotlinText.kotlinFiles(synthetic), root)
    }

    @Test
    fun `the compliant tree and the boring cases are green - V4-88`(@TempDir root: File) {
        with(Tree(root)) {
            write(A_KT to COMPLIANT_A, B_KT to COMPLIANT_B)
            assertEquals(emptyList<String>(), audit(baseline()), "one declaration plus an import is the remedy")

            // THE BORING CASE: exactly one const in the tree. Nothing to pair with, and the wall
            // must say so with a count rather than going green on an empty denominator.
            write(A_KT to BORING)
            assertEquals(emptyList<String>(), audit(baseline()), "the one-const tree grades green")
            assertEquals(1, census().consts.size)

            // A comment that EXPLAINS a number without asserting an equality, and a value equal to a
            // Knob default that shares no name token: both GREEN, or the detectors are noise.
            write(A_KT to EXPLAINED_ONLY)
            assertEquals(emptyList<String>(), audit(baseline()), "an explanatory comment asserts nothing")
            write(A_KT to KNOB_UNRELATED)
            assertEquals(emptyList<String>(), audit(baseline()), "80 with no shared token is not a shadow")
        }
    }

    @Test
    fun `the law can actually fail - the strict plane - V4-88`(@TempDir root: File) {
        with(Tree(root)) {
            write(A_KT to EQUAL_COMMENT)
            assertHit(audit(baseline()), "EQUAL-BY-COMMENT", "MAX_CLIENT_HOLD_MS", "MAX_RATE_LIMIT_COOLDOWN_MS") {
                "a comment asserting the two must stay equal must be RED by name"
            }

            write(A_KT to KNOB_SHADOW_SRC)
            assertHit(audit(baseline()), "KNOB-SHADOW", "USAGE_WARN_PCT") {
                "a local default shadowing a Knob default must be RED by name"
            }
            // ...and no baseline reaches the strict plane: recording the shadow changes nothing.
            assertHit(audit(baseline("\"COPY DEFAULT_WARN_PCT\": [\"$A_KT\"]")), "KNOB-SHADOW") {
                "a strict finding is not baselineable"
            }
        }
    }

    @Test
    fun `the law can actually fail - growth on the ratchet plane - V4-88`(@TempDir root: File) {
        with(Tree(root)) {
            write(A_KT to dup("SEAM_WIDTH", "8"), B_KT to dup("SEAM_WIDTH", "8"))
            val recorded = baseline("\"COPY SEAM_WIDTH\": [\"$A_KT\", \"$B_KT\"]")
            assertHit(audit(baseline()), "GROWTH (COPY)", "SEAM_WIDTH", "not in the baseline") {
                "a synthetic COPY that is NOT in the baseline must be RED by name"
            }
            assertEquals(emptyList<String>(), audit(recorded), "the SAME COPY, recorded, is held")

            write(A_KT to dup("SEAM_WIDTH", "8"), B_KT to dup("SEAM_WIDTH", "8"), C_KT to dup("SEAM_WIDTH", "8"))
            assertHit(audit(recorded), "GROWTH (COPY)", "SPREAD", C_KT) { "a baselined COPY that spread is RED" }

            // Two spellings of ONE value are a COPY, not a COLLISION — the normaliser's own proof.
            write(A_KT to dup("MS_PER_S", "1000L"), B_KT to dup("MS_PER_S", "1_000"))
            assertHit(audit(baseline()), "GROWTH (COPY)", "MS_PER_S") { "1000L and 1_000 are one value" }

            // ...and one name with two values is a COLLISION, held only by its own entry.
            write(A_KT to dup("SEAM_BOUND", "10"), B_KT to dup("SEAM_BOUND", "200"))
            assertHit(audit(baseline()), "GROWTH (COLLISION)", "SEAM_BOUND") { "one name, two meanings" }
            assertEquals(
                emptyList<String>(),
                audit(baseline("\"COLLISION SEAM_BOUND\": [\"$A_KT\", \"$B_KT\"]")),
                "a recorded COLLISION is held",
            )
        }
    }

    @Test
    fun `the law can actually fail - a shrink on the ratchet plane - V4-88`(@TempDir root: File) {
        with(Tree(root)) {
            // The duplicate is GONE: the entry is above the measurement and must be deleted by hand.
            write(A_KT to dup("SEAM_WIDTH", "8"))
            assertHit(
                audit(baseline("\"COPY SEAM_WIDTH\": [\"$A_KT\", \"$B_KT\"]")),
                "STALE",
                "COPY SEAM_WIDTH",
                "no longer declares it in 2+ files",
                ConstSingleSource.BASELINE_PATH,
            ) { "a baseline entry the tree no longer carries must be RED" }

            // ...and a group that lost ONE of three files is the same failure with a number: the new
            // count, the file that dropped out, and the resource to lower by hand.
            write(A_KT to dup("SEAM_WIDTH", "8"), B_KT to dup("SEAM_WIDTH", "8"))
            assertHit(
                audit(baseline("\"COPY SEAM_WIDTH\": [\"$A_KT\", \"$B_KT\", \"$C_KT\"]")),
                "STALE (COPY)",
                "now spans 2 file(s)",
                "lower the entry BY HAND",
                C_KT,
            ) { "a partially-fixed entry must be RED with the exact new number" }
        }
    }

    @Test
    fun `a NAMED-SCAR outranks the baseline in both directions - V4-88`(@TempDir root: File) {
        with(Tree(root)) {
            val scar = mapOf("ERR_BODY_CAP" to "one error-body truncation width; fixture reason")
            val files = arrayOf(A_KT to dup("ERR_BODY_CAP", "8"), B_KT to dup("ERR_BODY_CAP", "8"))
            write(*files)
            assertHit(audit(baseline(), scar), "NAMED-SCAR (COPY)", "ERR_BODY_CAP", "regardless of the ratchet") {
                "a listed name is RED with an empty baseline"
            }
            val recorded = baseline("\"COPY ERR_BODY_CAP\": [\"$A_KT\", \"$B_KT\"]")
            assertHit(audit(recorded, scar), "NAMED-SCAR (COPY)", "ERR_BODY_CAP") {
                "baselining a listed name cannot suppress it"
            }
            assertHit(audit(recorded, scar), "cannot be both baselined and strict") {
                "recording a strict name in the ratchet baseline is itself an error"
            }
            // The other half of "the list is what makes it strict": the same copy, NOT listed, held.
            assertEquals(emptyList<String>(), audit(recorded), "the same copy, unlisted, is held by the baseline")

            assertHit(
                audit(baseline(), mapOf("NEVER_DUPLICATED" to "a reason for a duplicate that does not exist")),
                "NAMED-SCAR STALE",
                "NEVER_DUPLICATED",
            ) { "a listed name describing no real duplicate is STALE" }
            assertHit(audit(baseline(), mapOf("ERR_BODY_CAP" to "   ")), "NAMED-SCAR", "ERR_BODY_CAP", "NO reason") {
                "a listed name with a blank reason is RED by name"
            }
        }
    }

    @Test
    fun `the instrument refuses rather than passing - V4-88`(@TempDir root: File) {
        with(Tree(root)) {
            write(A_KT to "package splice.nothing\n\ninternal fun f(): Int = 7\n")
            assertHit(audit(baseline()), "refusing to pass vacuously") { "a tree with no const at all must REFUSE" }

            // A `const val` line the parser cannot read: the raw count sees it and DECL does not, so
            // the run refuses rather than reporting an absence it cannot vouch for.
            write(A_KT to DRIFT)
            assertHit(audit(baseline()), "the parser and the source disagree") { "a parser/source drift must REFUSE" }

            write(A_KT to dup("SEAM_WIDTH", "8"), B_KT to dup("SEAM_WIDTH", "8"))
            assertHit(audit(null), "has no recorded census") { "a missing baseline cannot gate" }
            assertHit(audit("{"), "unreadable") { "an unparsable baseline cannot gate" }
            assertHit(audit("{\"recorded\": \"x\", \"total\": 0, \"groups\": {}}"), "missing required key") {
                "a baseline without its required keys cannot gate"
            }
            assertHit(
                ConstSingleSource.parseTree(emptyList(), root).problems,
                "the denominator is absent",
            ) { "a tree with no main sources at all must REFUSE" }

            // The Knob plane's teeth are the parsed DEFAULTS, and `entries.isEmpty()` cannot see a
            // Knob whose entries all still MATCH while none of them yields a default. Respelling the
            // entries is enough to do it, and before this arm that turned KNOB-SHADOW off for good
            // with every test green.
            write(A_KT to dup("SEAM_WIDTH", "8"), B_KT to dup("SEAM_WIDTH", "8"))
            rewriteKnob(KNOB_NAMED_ARGS)
            assertHit(audit(baseline()), "read 0 defaults") {
                "a Knob whose entries match but whose defaults do not parse must REFUSE"
            }
        }
    }

    private companion object {
        const val A_KT = "app/src/main/kotlin/splice/A.kt"
        const val B_KT = "core/src/main/kotlin/splice/B.kt"
        const val C_KT = "daemon/control/src/main/kotlin/splice/C.kt"
        const val MODULES = ":app=app;:core=core;:daemon-control=daemon/control"
        const val KNOB_REL = "core/src/main/kotlin/${ConstSingleSource.KNOB_IN_CORE}"

        /** A baseline document with [entries] spelled the way the resource spells them. */
        fun baseline(entries: String = ""): String =
            "{\n  \"recorded\": \"selftest\",\n  \"denominator\": 0,\n  \"total\": 0,\n  \"groups\": {$entries}\n}\n"

        fun dup(name: String, value: String): String = "package splice.dup\n\nprivate const val $name = $value\n"

        // The compliant tree: one declaration per value, the second file imports it, and a comment
        // that merely EXPLAINS a number is not a finding.
        const val COMPLIANT_A = """package splice.a

import splice.b.SHARED_CEILING_MS

// 120s starves a herd but lets a recovering account resume inside one client-retry cycle.
internal const val LOCAL_ONLY_MS = 45_000L

internal fun hold(): Long = SHARED_CEILING_MS
"""
        const val COMPLIANT_B = "package splice.b\n\ninternal const val SHARED_CEILING_MS = 120_000L\n"
        const val BORING = "package splice.only\n\nprivate const val ONE = 7\n"

        // The parser-drift fixture: an ANNOTATED const. The line holds `const val`, so the raw count
        // sees it, and DECL — which admits a visibility modifier and nothing else — does not.
        const val DRIFT = """package splice.drift

private const val OK = 1

@Suppress("UNCHECKED_CAST") private const val ANNOTATED = 3
"""

        const val EQUAL_COMMENT = """package splice.a

private const val MAX_RATE_LIMIT_COOLDOWN_MS = 120_000L

// Mirrors :upstream's MAX_RATE_LIMIT_COOLDOWN_MS, which is private to that module.
// The two must stay equal — the cooldown ceiling is when this gateway next lets a request through.
private const val MAX_CLIENT_HOLD_MS = 120_000L
"""

        const val EXPLAINED_ONLY = """package splice.a

// 4 attempts matches the surveyed harness floor; the old default of 2 still failed turns on blips.
private const val UPSTREAM_ATTEMPTS = 4
"""

        const val KNOB_SHADOW_SRC = "package splice.a\n\nprivate const val DEFAULT_WARN_PCT = 80\n"

        // Same value as a Knob default but sharing no name token: GREEN (see the header's NOT CAUGHT).
        const val KNOB_UNRELATED = "package splice.a\n\nprivate const val RETRY_SLOTS = 80\n"

        /** The same enum, respelled with named arguments: every entry still matches KNOB_ENTRY and
         *  not one of them yields a readable default. */
        const val KNOB_NAMED_ARGS = """package splice.core.config

public enum class Knob(
    public val key: String,
    public val kind: KnobKind,
    public val envNames: List<String>,
    public val default: Any?,
) {
    USAGE_WARN_PCT(
        key = "usageWarnPct",
        kind = KnobKind.NUMBER,
        envNames = listOf("SPLICE_USAGE_WARN_PCT"),
        default = 80L,
    ),
}
"""

        const val KNOB_SOURCE = """package splice.core.config

public enum class Knob(
    public val key: String,
    public val kind: KnobKind,
    public val envNames: List<String>,
    public val default: Any?,
    public val restartRequired: Boolean = false,
) {
    USAGE_WARN_PCT("usageWarnPct", KnobKind.NUMBER, listOf("SPLICE_USAGE_WARN_PCT"), 80L),
    FOLD_MAX_TIER(
        "foldMaxTier",
        KnobKind.NUMBER,
        listOf("CLAUDEX_FOLD_MAX_TIER"),
        // a comment between the args, which a naive positional split would count as one
        6L,
        restartRequired = true,
    ),
}
"""
    }
}
