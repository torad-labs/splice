// NEW: V4-91 — every config key an operator can write must be ACTED ON somewhere, by name
// (ported from checks/schema-keys-consumed.ts, restructure PR 6).
//
// WHY THIS EXISTS. A key that parses is not a key that works. `[daemon] state_dir` sat in the
// schema, deserialized cleanly, was echoed back by `doctor --json`, and NOTHING read it: an
// operator who set it got silence — no error, no effect, and a doctor report showing the value they
// asked for. quirks-keys-documented proves a key is DOCUMENTED, which is the other half; a
// documented dead key is worse than an undocumented one, because the documentation is a promise.
//
// CLASS. A text-level law over production Kotlin, not a compiler question: it grades what is
// WRITTEN, one hop on the schema plane and two on the knob plane.
//
// SCOPE. Every `.kt` under `<module>/src/main` of every module the BUILD declares
// ([KotlinText.kotlinFiles]). That is the build-derived denominator the checker's
// `gateway/*/src/main, client/src/main, …` glob list stood in for; both name the same 744 files on
// this tree. Test sources are not scanned at all: a key wired only into a test is not wired.
//
// DENOMINATOR, FROM THE SOURCE (§24), on both planes:
//   · SCHEMA — the primary constructor of each of the five operator-facing config types, parsed on
//     disk: Topology, DaemonConfig, HeadConfig, ProviderConfig, QuirksConfig. Each parameter yields
//     its TOML key (@SerialName when present, else the property name). The classes are LOCATED by
//     searching the tree for their declaration rather than by a recorded path, so moving one
//     between files does not drop it.
//   · KNOBS — every enum constant of `Knob`, with its key string.
// A key added tomorrow is in scope with no edit here. FOUR GUARDS refuse a vacuous pass: a missing
// class, a parse yielding no keys on either plane, a CLASS whose attributed @SerialName count
// disagrees with its own constructor body (a parameter the comma split dropped), and a Knob enum
// yielding no constants — each FAILS rather than passes.
//
// PARSE. The class head is matched at a line start, its primary constructor is walked
// comment- and string-aware ([KotlinText.balancedSpan]), and the body is split on TOP-LEVEL commas
// with angle brackets tracked SEPARATELY from brackets — counting `<`/`>` as depth makes `->` and a
// `>` comparison decrement it, and every parameter after a lambda default falls out of the list.
//
// VIOLATIONS.
//   A SCHEMA KEY is consumed when its property is READ — not merely declared, not merely written
//   back. IN ITS OWN FILE by something other than its declaration (the schema file is where this
//   tree PROJECTS its TOML types into its domain types, and a rule demanding a foreign reader would
//   red the correct design), OR IN ANOTHER production main file. Bare `.prop` is enough when no
//   other class in the tree declares a property of that name; when one does, the read must be
//   RECEIVER-QUALIFIED. That qualification is the wall: `stateDir` is declared twice, and a
//   name-only rule finds `statePaths.stateDir` in seven files and reports the dead key as green.
//   The receiver spellings are DERIVED from the schema (the decapitalised class name, the class
//   name, and every schema property whose declared type mentions the class, singularised for a
//   Map/List), never hand-listed.
//
//   A KNOB KEY is consumed when `Knob.<CONST>` is read outside its declaration AND outside the
//   ACCESSOR FACADE, or — one hop — when the facade accessor that reads it is itself read
//   elsewhere. The hop is what makes this plane non-vacuous: every knob is read by SpliceConfig.kt,
//   so counting the facade as a consumer would make the whole plane green with no wiring anywhere.
//
// TWO SURFACES ARE NOT CONSUMPTION ([SchemaKeysConsumed.NON_CONSUMPTION]), each dated, each checked
// for staleness: THE ECHO SURFACE (a doctor file that puts the value back out under its own key
// name — the value is SHOWN, not used) and THE ACCESSOR FACADE (followed one hop instead of
// trusted). THE ALLOWLIST is for a key deliberately parsed and deliberately not acted on; undated
// fails, blank-reasoned fails, and an entry naming a key that IS consumed fails as stale.
//
// NOT CAUGHT: a key read through a rename (`val d = topology.daemon` then `d.stateDir`) reads as
// unconsumed — a false RED, the safe direction; a key whose only reader is a test; and semantic
// deadness one level down (a key read into a variable nothing uses).
package splice.quality

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

internal object SchemaKeysConsumed {
    /** The five operator-facing config types. Named, not path-pinned: a move is a move and a
     *  disappearance is a hard error. */
    val SCHEMA_CLASSES = listOf("Topology", "DaemonConfig", "HeadConfig", "ProviderConfig", "QuirksConfig")
    const val KNOB_CLASS = "Knob"

    /** The scope a violation names — the build's own module map, not a glob. */
    const val SCOPE = "every module's src/main"

    /** Not consumption. (relative path, dated reason). A stale entry — the file is gone — is a hard
     *  error: a dead exclusion is an un-graded surface one rename later. */
    val NON_CONSUMPTION: List<Pair<String, String>> = listOf(
        "app/src/main/kotlin/splice/app/cli/doctor/DoctorReportShape.kt" to
            "2026-09-17: THE ECHO SURFACE. It puts every topology key back out under its own key " +
            "name — the value is being SHOWN, not used. Counting it would make this wall green " +
            "over exactly the population it exists to name.",
        "core/src/main/kotlin/splice/core/config/SpliceConfig.kt" to
            "2026-09-17: THE ACCESSOR FACADE over the knob map. Every knob is read here, so " +
            "treating it as a consumer would make the knob plane pass with no wiring anywhere. A " +
            "read here is a PROJECTION, and it is followed one hop instead.",
    )

    /** Keys deliberately parsed and deliberately not acted on. (key, "YYYY-MM-DD: why"). */
    val ALLOWLIST: List<Pair<String, String>> = emptyList()

    /** What each exclusion list is, for one run. The checker swapped module globals; a law is
     *  handed them, which is what lets the red proof mutate one list without touching the other. */
    data class Policy(val nonConsumption: List<Pair<String, String>>, val allowlist: List<Pair<String, String>>)

    val LIVE = Policy(NON_CONSUMPTION, ALLOWLIST)

    /** One config key an operator can write, and where it is declared. */
    data class Key(
        val plane: String,
        val owner: String,
        val prop: String,
        val key: String,
        val declaredIn: String,
        val line: Int,
    ) {
        fun locus(): String = "$declaredIn:$line"
    }

    /** One schema parameter: its property name, its TOML key, and its declared type. */
    data class Shape(val prop: String, val key: String, val declared: String)

    data class Schema(val keys: List<Key>, val shapes: Map<String, List<Shape>>, val problems: List<String>)

    data class Knobs(val keys: List<Key>, val knobFile: String, val problems: List<String>)

    data class Audit(val red: List<Pair<Key, String>>, val examined: Int, val problems: List<String>)

    /** JS `(?<![\w$])`: a read must not be the tail of a longer identifier. */
    private const val NOT_IDENTIFIER = "(?<![\\w\\\$])"
    private const val ANGLE_LEAD = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_>"

    private val DATED_REASON = Regex("^\\d{4}-\\d{2}-\\d{2}: \\S")
    private val SERIAL_NAME = Regex("@SerialName\\(\\s*\"([^\"]+)\"\\s*\\)")
    private val PARAM = Regex("\\b(?:val|var)\\s+(\\w+)\\s*:\\s*([^=]+?)(?:\\s*=\\s*[\\s\\S]*)?$")
    private val PROPERTY_DECL = Regex("\\b(?:val|var)\\s+(\\w+)\\s*:")
    private val ENUM_CONSTANT = Regex("^ {4}([A-Z][A-Z0-9_]*)\\s*\\(", RegexOption.MULTILINE)

    /** The key string is the enum constant's first argument, on its line or the next. */
    private val ENUM_KEY = Regex("^\\s*\"([A-Za-z0-9_.]+)\"", RegexOption.MULTILINE)
    private val FACADE_MEMBER =
        Regex("^ {4}(?:public |private |internal )?(?:val|fun)\\s+(\\w+)", RegexOption.MULTILINE)
    private val KNOB_CONSTANT_READ = Regex("Knob\\.([A-Z][A-Z0-9_]*)")

    private fun classDecl(name: String) = Regex(
        "^(?:public |internal |private )?(?:data |value |sealed )*class[ \\t]+${Regex.escape(name)}\\b[^\\n(]*\\(",
        RegexOption.MULTILINE,
    )

    private fun enumDecl(name: String) = Regex(
        "^(?:public |internal )?enum class[ \\t]+${Regex.escape(name)}\\b[^\\n(]*\\(",
        RegexOption.MULTILINE,
    )

    private data class Located(val rel: String, val match: MatchResult)

    private data class ClassSite(val name: String, val rel: String, val line: Int, val body: String)

    private class SchemaScan {
        val keys = mutableListOf<Key>()
        val shapes = linkedMapOf<String, List<Shape>>()
        val problems = mutableListOf<String>()
    }

    /** The first file, in path order, whose text matches [pattern]. */
    private fun locate(files: Map<String, String>, pattern: Regex): Located? {
        for (rel in files.keys.sorted()) {
            val match = pattern.find(files.getValue(rel))
            if (match != null) return Located(rel, match)
        }
        return null
    }

    /** Split a constructor body on TOP-LEVEL commas, dropping comments and respecting strings.
     *
     *  Angle brackets are tracked SEPARATELY from brackets and only open when immediately preceded
     *  by an identifier: counting `<`/`>` as depth makes `->` and a `>` comparison decrement it,
     *  and every parameter after a lambda default falls out of the list. */
    fun splitParams(body: String): List<String> {
        val split = ParamSplit(body)
        split.run()
        return split.parts.map { it.trim() }.filter { it.isNotEmpty() }
    }

    /** The one state machine [splitParams] runs, as a walk with its own cursor. */
    private class ParamSplit(private val body: String) {
        val parts = mutableListOf<String>()
        private val buf = StringBuilder()
        private var depth = 0
        private var angle = 0
        private var at = 0

        fun run() {
            while (at < body.length) step()
            if (buf.isNotEmpty()) parts += buf.toString()
        }

        private fun step() {
            val ch = body[at]
            when {
                ch == '"' || ch == '\'' -> literal(ch)
                body.startsWith("//", at) -> at = endOfLine()
                body.startsWith("/*", at) -> at = endOfBlockComment()
                else -> code(ch)
            }
        }

        private fun endOfLine(): Int {
            val newline = body.indexOf('\n', at)
            return if (newline < 0) body.length else newline
        }

        private fun endOfBlockComment(): Int {
            val close = body.indexOf("*/", at + 2)
            return if (close < 0) body.length else close + 2
        }

        private fun literal(quote: Char) {
            buf.append(quote)
            at += 1
            var escape = false
            while (at < body.length) {
                val ch = body[at]
                buf.append(ch)
                at += 1
                if (escape) {
                    escape = false
                } else if (ch == '\\') {
                    escape = true
                } else if (ch == quote) {
                    break
                }
            }
        }

        private fun code(ch: Char) {
            when {
                ch in "({[" -> depth += 1
                ch in ")}]" -> depth -= 1
                opensAngle(ch) -> angle += 1
                closesAngle(ch) -> angle -= 1
                separates(ch) -> {
                    parts += buf.toString()
                    buf.setLength(0)
                    at += 1
                    return
                }
            }
            buf.append(ch)
            at += 1
        }

        private fun separates(ch: Char): Boolean = ch == ',' && depth == 0 && angle == 0

        private fun opensAngle(ch: Char): Boolean {
            if (ch != '<' || at == 0) return false
            return body[at - 1] in ANGLE_LEAD && body.getOrNull(at + 1) != '='
        }

        private fun closesAngle(ch: Char): Boolean {
            if (ch != '>' || angle == 0) return false
            return body.getOrNull(at - 1) != '-' && body.getOrNull(at + 1) != '='
        }
    }

    /** (keys, class -> its parameter shapes, problems). */
    fun schemaKeys(files: Map<String, String>): Schema {
        val scan = SchemaScan()
        for (name in SCHEMA_CLASSES) classKeys(name, files, scan)
        return Schema(scan.keys, scan.shapes, scan.problems)
    }

    private fun classKeys(name: String, files: Map<String, String>, scan: SchemaScan) {
        val found = locate(files, classDecl(name))
        if (found == null) {
            scan.problems += "$name: no `class $name(` found under $SCOPE — it is part of the denominator, so its " +
                "absence cannot pass. If it was renamed, rename it in SCHEMA_CLASSES too."
            return
        }
        val text = files.getValue(found.rel)
        val span = KotlinText.balancedSpan(text, found.match.range.last, '(', ')')
        if (span == null) {
            scan.problems += "${found.rel}: $name's primary constructor could not be walked"
            return
        }
        val site =
            ClassSite(
                name,
                found.rel,
                KotlinText.lineOf(text, found.match.range.first),
                text.substring(span.bodyStart, span.closerAt),
            )
        val attributed = collectParams(site, scan)
        val drift = driftProblem(site, attributed)
        if (drift != null) scan.problems += drift
    }

    /** Every parameter of [site], appended to [scan]; returns how many carried a @SerialName. */
    private fun collectParams(site: ClassSite, scan: SchemaScan): Int {
        val shape = mutableListOf<Shape>()
        var attributed = 0
        for (raw in splitParams(site.body)) {
            val param = PARAM.find(raw) ?: continue
            val serial = SERIAL_NAME.find(raw)
            if (serial != null) attributed += 1
            val prop = param.groupValues[1]
            val key = serial?.groupValues?.get(1) ?: prop
            scan.keys += Key("schema", site.name, prop, key, site.rel, site.line)
            shape += Shape(prop, key, param.groupValues[2].trim())
        }
        scan.shapes[site.name] = shape
        return attributed
    }

    /** PARSER-DRIFT GUARD, per CLASS rather than per file: the @SerialName annotations this run
     *  ATTRIBUTED to parameters must equal the annotations inside the constructor BODY. A parameter
     *  the comma split loses takes its key with it, and a shorter key list is the one failure mode
     *  that would otherwise read as green. */
    private fun driftProblem(site: ClassSite, attributed: Int): String? {
        val inBody = SERIAL_NAME.findAll(KotlinText.stripComments(site.body)).count()
        if (attributed == inBody) return null
        return "${site.rel}: ${site.name} — attributed $attributed @SerialName key(s) but its constructor holds " +
            "$inBody; the parser dropped a parameter, so no key list from this run can be trusted"
    }

    /** (keys, the file declaring Knob, problems). */
    fun knobKeys(files: Map<String, String>): Knobs {
        val found = locate(files, enumDecl(KNOB_CLASS))
        val absent = "no `enum class $KNOB_CLASS(` found under $SCOPE — the knob plane's denominator is absent, " +
            "which cannot pass"
        return if (found == null) {
            Knobs(
                emptyList(),
                "",
                listOf(absent),
            )
        } else {
            knobConstants(found, files.getValue(found.rel))
        }
    }

    private fun knobConstants(found: Located, text: String): Knobs {
        val headEnd = found.match.range.last + 1
        val body = text.substring(headEnd)
        val constants = ENUM_CONSTANT.findAll(body).toList()
        val keys = mutableListOf<Key>()
        for ((index, constant) in constants.withIndex()) {
            val start = constant.range.first
            val end = if (index + 1 < constants.size) constants[index + 1].range.first else body.length
            val name = constant.groupValues[1]
            val keyMatch = ENUM_KEY.find(body.substring(start + constant.value.length, end))
                ?: return Knobs(keys, found.rel, listOf(unreadableKnobKey(found.rel, name)))
            val line = KotlinText.lineOf(text, headEnd + start)
            keys += Key("knob", KNOB_CLASS, name, keyMatch.groupValues[1], found.rel, line)
        }
        if (keys.isEmpty()) {
            return Knobs(
                emptyList(),
                found.rel,
                listOf("${found.rel}: parsed 0 $KNOB_CLASS constants — refusing to pass vacuously"),
            )
        }
        return Knobs(keys, found.rel, emptyList())
    }

    private fun unreadableKnobKey(rel: String, name: String) =
        "$rel: $name declares no readable key string — the knob denominator cannot be trusted"

    /** Every spelling a receiver of [name] can have, DERIVED from the schema shapes: the
     *  decapitalised class name, the class name itself, and the name of every schema property whose
     *  declared type mentions the class — plus the singular of a plural one, because
     *  `providers: Map<String, ProviderConfig>` is read as `provider.baseUrl` at the element. */
    fun receiversFor(name: String, shapes: Map<String, List<Shape>>): Set<String> {
        val out = linkedSetOf(name, name.replaceFirstChar { it.lowercaseChar() })
        val mentions = Regex("\\b${Regex.escape(name)}\\b")
        for (properties in shapes.values) {
            for (shape in properties) {
                if (!mentions.containsMatchIn(shape.declared)) continue
                out += shape.prop
                if (shape.prop.endsWith("s")) out += shape.prop.dropLast(1)
            }
        }
        return out
    }

    /** The pattern a read in ANOTHER file must match. Receiver-qualified when the property name is
     *  ambiguous, bare when only one class in the tree owns it. */
    fun readPattern(owner: String, prop: String, shapes: Map<String, List<Shape>>, ambiguous: Boolean): Regex {
        if (!ambiguous) return Regex("$NOT_IDENTIFIER${Regex.escape(prop)}\\b")
        val spellings = receiversFor(owner, shapes).sortedByDescending { it.length }
        val alternation = spellings.joinToString("|") { Regex.escape(it) }
        return Regex("(?:$alternation)\\s*\\??\\s*\\.\\s*${Regex.escape(prop)}\\b")
    }

    /** The file with [prop]'s own `val prop:` / `var prop:` declaration lines blanked. Line-based
     *  on purpose: the declaration is what must not count as its own read. */
    fun removeDeclaration(text: String, prop: String): String {
        val decl = Regex("\\b(?:val|var)\\s+${Regex.escape(prop)}\\s*:")
        return text.split("\n").joinToString("\n") { if (decl.containsMatchIn(it)) "" else it }
    }

    /** Knob constant -> the facade accessor names whose bodies read it. Split per DECLARATION
     *  rather than by a multi-line regex, so a knob is attributed to the accessor that names it. */
    fun knobAccessors(facade: String): Map<String, Set<String>> {
        val marks = FACADE_MEMBER.findAll(facade).toList()
        val out = linkedMapOf<String, MutableSet<String>>()
        for ((index, mark) in marks.withIndex()) {
            val end = if (index + 1 < marks.size) marks[index + 1].range.first else facade.length
            for (read in KNOB_CONSTANT_READ.findAll(facade.substring(mark.range.first, end))) {
                out.getOrPut(read.groupValues[1]) { linkedSetOf() } += mark.groupValues[1]
            }
        }
        return out
    }

    /** The shared state one grading run carries: the tree, the two exclusion lists derived from the
     *  policy, and the verdict as it accumulates. */
    private class Grader(val files: Map<String, String>, policy: Policy) {
        val excluded: Set<String> = policy.nonConsumption.map { it.first }.toSet()
        private val allowlisted: Set<String> = policy.allowlist.map { it.first }.toSet()
        val declaredBy: Map<String, Set<String>> = declaredByIndex(files)
        val facade: String = policy.nonConsumption.map { it.first }
            .firstOrNull { it.endsWith("SpliceConfig.kt") && files.containsKey(it) } ?: ""
        val accessors: Map<String, Set<String>> = knobAccessors(files[facade] ?: "")
        val red = mutableListOf<Pair<Key, String>>()
        val consumed = mutableSetOf<String>()

        fun grade(key: Key, why: String?) {
            if (why == null) {
                consumed += "${key.owner}.${key.prop}"
            } else if (key.key !in allowlisted) {
                red += key to why
            }
        }

        /** Some production file OTHER than the knob enum, the facade and the excluded surfaces
         *  matches [pattern]. */
        fun readsOutside(knobFile: String, pattern: Regex): Boolean {
            val skipped = excluded + knobFile + facade
            return files.any { (rel, text) -> rel !in skipped && pattern.containsMatchIn(text) }
        }
    }

    /** Which files declare a property of each name: the AMBIGUITY index, from the source. */
    private fun declaredByIndex(files: Map<String, String>): Map<String, Set<String>> {
        val out = linkedMapOf<String, MutableSet<String>>()
        for ((rel, text) in files) {
            for (decl in PROPERTY_DECL.findAll(text)) out.getOrPut(decl.groupValues[1]) { linkedSetOf() } += rel
        }
        return out
    }

    /** Null when the key is acted on; otherwise the sentence saying how it is dead. */
    private fun schemaUnconsumed(key: Key, shapes: Map<String, List<Shape>>, grader: Grader): String? {
        // (A) IN ITS OWN FILE, by something other than its own declaration. A bare `prop =` (a
        // named-argument WRITE) is not a read: putting the value back into a constructor call is
        // not acting on it.
        val read = Regex("$NOT_IDENTIFIER${Regex.escape(key.prop)}\\b(?!\\s*=(?!=))")
        if (read.containsMatchIn(removeDeclaration(grader.files.getValue(key.declaredIn), key.prop))) return null
        // (B) IN ANOTHER production main file, receiver-qualified when the name is ambiguous.
        val ambiguous = grader.declaredBy[key.prop].orEmpty().any { it != key.declaredIn }
        val pattern = readPattern(key.owner, key.prop, shapes, ambiguous)
        val skipped = grader.excluded + key.declaredIn
        if (grader.files.any { (rel, text) -> rel !in skipped && pattern.containsMatchIn(text) }) return null
        val echo = grader.excluded.sorted().filter { pattern.containsMatchIn(grader.files[it] ?: "") }
        return deadSchemaKey(key, echo)
    }

    private fun deadSchemaKey(key: Key, echo: List<String>): String {
        val tail = if (echo.isEmpty()) {
            " Nothing reads it anywhere, in its own file or outside it."
        } else {
            " Its only read in the tree is the echo surface (${echo.joinToString(", ")}), which shows the value " +
                "rather than using it."
        }
        return "${key.owner}.${key.prop} (TOML key `${key.key}`) is PARSED AND NEVER ACTED ON.$tail Thread it into " +
            "the behaviour it promises, or retire it with a dated ALLOWLIST entry in SchemaKeysConsumed saying why " +
            "it is deliberately inert."
    }

    private fun knobUnconsumed(key: Key, knobFile: String, grader: Grader): String? {
        if (grader.readsOutside(knobFile, Regex("Knob\\.${Regex.escape(key.prop)}\\b"))) return null
        val names = grader.accessors[key.prop].orEmpty().sorted()
        for (name in names) {
            if (grader.readsOutside(knobFile, Regex("\\.${Regex.escape(name)}\\b"))) return null
        }
        return deadKnobKey(key, names)
    }

    private fun deadKnobKey(key: Key, names: List<String>): String {
        if (names.isEmpty()) {
            return "Knob.${key.prop} (key `${key.key}`) is PARSED AND NEVER ACTED ON: no production file reads it " +
                "and the accessor facade does not expose it either. Wire it, or retire it with a dated ALLOWLIST entry."
        }
        return "Knob.${key.prop} (key `${key.key}`) is PARSED AND NEVER ACTED ON: its only reader is the accessor " +
            "facade (${names.joinToString(", ")}), and nothing reads that accessor either. An operator who sets " +
            "`${key.key}` gets silence. Wire it, or retire it with a dated ALLOWLIST entry saying why it is " +
            "deliberately inert."
    }

    private fun policyProblems(files: Map<String, String>, policy: Policy): List<String> {
        val problems = mutableListOf<String>()
        for ((rel, reason) in policy.nonConsumption) {
            if (!DATED_REASON.containsMatchIn(reason.trim())) {
                problems += "NON_CONSUMPTION entry for $rel has no dated reason — every exclusion starts " +
                    "'YYYY-MM-DD: <why>'. An exclusion nobody can evaluate is indistinguishable from one nobody " +
                    "should have granted."
            }
            if (rel !in files) {
                problems += "NON_CONSUMPTION names $rel, which is not a production main file any more — delete the " +
                    "entry. A stale exclusion is an un-graded surface one rename later."
            }
        }
        for ((key, reason) in policy.allowlist) {
            if (!DATED_REASON.containsMatchIn(reason.trim())) {
                problems += "ALLOWLIST entry ${KotlinText.pyRepr(key)} has no dated reason — a blank or undated " +
                    "reason is an absence wearing a label, not a disposition."
            }
        }
        return problems
    }

    private fun allowlistStale(policy: Policy, keys: List<Key>, consumed: Set<String>): List<String> {
        val problems = mutableListOf<String>()
        for ((key, _) in policy.allowlist) {
            val live = keys.filter { it.key == key }
            if (live.isEmpty()) {
                problems += "ALLOWLIST names ${KotlinText.pyRepr(key)}, which is not a config key any more — drop " +
                    "the entry; it currently exempts nothing."
            } else if (live.any { "${it.owner}.${it.prop}" in consumed }) {
                problems += "ALLOWLIST names ${KotlinText.pyRepr(key)}, which IS acted on now — drop the entry, so " +
                    "the list keeps meaning 'deliberately inert'."
            }
        }
        return problems
    }

    /** (dead keys with the reason each is red, keys examined, untrusted-parse problems). */
    fun unconsumed(files: Map<String, String>, policy: Policy): Audit {
        val problems = policyProblems(files, policy).toMutableList()
        val schema = schemaKeys(files)
        val knobs = knobKeys(files)
        problems += schema.problems
        problems += knobs.problems
        if (schema.keys.isEmpty() && knobs.keys.isEmpty()) {
            problems += "parsed 0 config keys under $SCOPE — refusing to pass vacuously, because a green over an " +
                "empty denominator is what this wall exists to prevent"
            return Audit(emptyList(), 0, problems)
        }
        val grader = Grader(files, policy)
        for (key in schema.keys) grader.grade(key, schemaUnconsumed(key, schema.shapes, grader))
        for (key in knobs.keys) grader.grade(key, knobUnconsumed(key, knobs.knobFile, grader))
        problems += allowlistStale(policy, schema.keys + knobs.keys, grader.consumed)
        return Audit(grader.red, schema.keys.size + knobs.keys.size, problems)
    }

    /** Everything the bare run would print as a failure: the untrusted-parse problems first, then
     *  one `<file>:<line>: <why>` line per dead key. */
    fun violations(files: Map<String, String>, policy: Policy = LIVE): List<String> {
        val audit = unconsumed(files, policy)
        return audit.problems + audit.red.map { (key, why) -> "${key.locus()}: $why" }
    }

    /** The census `--report` prints: one line per key, in schema-then-knob declaration order. */
    fun census(files: Map<String, String>, policy: Policy = LIVE): List<String> {
        val audit = unconsumed(files, policy)
        val dead = audit.red.map { "${it.first.owner}.${it.first.prop}" }.toSet()
        val head = "schema-keys-consumed: ${audit.examined} key(s), ${audit.red.size} parsed-and-never-acted-on"
        val keys = schemaKeys(files).keys + knobKeys(files).keys
        return listOf(head) + keys.map { key ->
            val state = if ("${key.owner}.${key.prop}" in dead) "DEAD" else "acted on"
            "  ${key.plane.padEnd(6)} ${key.owner}.${key.prop.padEnd(24)} key " +
                "${key.key.padEnd(26)} ${state.padEnd(9)} ${key.locus()}"
        }
    }

    /** The tree this law grades: relative path -> source text, in path order. */
    fun sources(map: ProjectMap): Map<String, String> {
        val out = linkedMapOf<String, String>()
        for (file in KotlinText.kotlinFiles(map).sortedBy { KotlinText.rel(map, it) }) {
            out[KotlinText.rel(map, file)] = file.readText()
        }
        return out
    }
}

class SchemaKeysConsumedLawTest {
    private val map = ProjectMap.fromSystemProperties()

    @Test
    fun `every schema and knob key an operator can write is acted on somewhere - V4-91`() {
        val files = SchemaKeysConsumed.sources(map)
        assertTrue(files.size > 100) {
            "the map yielded ${files.size} production file(s) — the walk is broken, and a law that " +
                "reads no files passes vacuously."
        }
        val audit = SchemaKeysConsumed.unconsumed(files, SchemaKeysConsumed.LIVE)
        assertTrue(audit.examined >= 50) {
            "the tree yielded ${audit.examined} config key(s) — this law is not pointed at the real schema."
        }
        val problems = SchemaKeysConsumed.violations(files)
        assertTrue(problems.isEmpty()) {
            problems.joinToString(separator = "\n  - ", prefix = "SCHEMA KEYS CONSUMED (V4-91) violated:\n  - ")
        }
    }

    /** The synthetic tree the red proof writes into — the shell selftest's fixture, module `:app`:
     *  the five schema classes, a consumer, an echo surface, an ambiguity twin, the Knob enum, the
     *  accessor facade and a knob consumer. Each arm re-writes it with different flags. */
    private class Tree(val root: File) {
        private val synthetic = ProjectMap.parse(root, ":app=app;:core=core", setOf("build"))
        val policy = SchemaKeysConsumed.Policy(
            listOf(
                "$PKG/Doctor.kt" to "2026-09-17: the fixture echo surface",
                "$PKG/SpliceConfig.kt" to "2026-09-17: the fixture accessor facade",
            ),
            emptyList(),
        )

        fun write(
            deadKey: Boolean = false,
            deadKnob: Boolean = false,
            rename: Boolean = false,
            orphan: Boolean = false,
        ) {
            val quirks = if (rename) "QuirksRenamed" else "QuirksConfig"
            val dead = if (deadKey) DEAD_KEY_LINE else ""
            val orphaned = if (orphan) ORPHAN_LINE else ""
            put("Schema.kt", SCHEMA.replace("%quirks%", quirks).replace("%extra%", dead + orphaned))
            put("Catalog.kt", CATALOG)
            put("Wiring.kt", WIRING.replace("%quirks%", quirks))
            put("Doctor.kt", DOCTOR.replace("%echo%", if (deadKey) DEAD_ECHO_LINE else ""))
            put("Paths.kt", PATHS)
            put("Knob.kt", KNOB.replace("%dead%", if (deadKnob) DEAD_KNOB_LINE else ""))
            put("SpliceConfig.kt", FACADE.replace("%dead%", if (deadKnob) DEAD_ACCESSOR_LINE else ""))
            put("KnobWiring.kt", KNOB_WIRING)
        }

        fun put(name: String, text: String) {
            File(root, "$PKG/$name").apply { parentFile.mkdirs() }.writeText(text)
        }

        fun files() = SchemaKeysConsumed.sources(synthetic)

        fun violations(with: SchemaKeysConsumed.Policy = policy) = SchemaKeysConsumed.violations(files(), with)

        fun census(with: SchemaKeysConsumed.Policy = policy) = SchemaKeysConsumed.census(files(), with)

        fun allow(vararg entries: Pair<String, String>) = policy.copy(allowlist = entries.toList())
    }

    /** THE CENSUS PORT HAD NO READER — not a test, not a task, nothing in the tree called it. A
     *  report nobody runs is one nobody can trust on the day it is needed, and that day is always
     *  a day something else is already red. So it runs here against the same fixture the law's own
     *  arms use, and its DEAD column is proved to MOVE: green has none, the planted dead key
     *  produces exactly one, and the header's count moves with it. */
    @Test
    fun `the census reads every key and marks the dead one - V4-91`(@TempDir root: File) {
        with(Tree(root)) {
            write()
            val green = census()
            assertTrue(green.isNotEmpty()) { "the census printed nothing at all" }
            val examined = SchemaKeysConsumed.unconsumed(files(), policy).examined
            assertTrue(green.first() == "schema-keys-consumed: $examined key(s), 0 parsed-and-never-acted-on") {
                "the census header disagrees with the audit it reports on:\n${green.first()}"
            }
            assertTrue(green.drop(1).size >= examined) {
                "fewer lines than keys examined — the census is not printing one line per key:\n" +
                    green.joinToString("\n")
            }
            assertTrue(green.none { "DEAD" in it }) {
                "the compliant fixture has no dead key:\n" + green.joinToString("\n")
            }

            write(deadKey = true)
            val red = census()
            val dead = red.filter { " DEAD " in it }
            assertEquals(1, dead.size, "exactly the planted key must read DEAD:\n" + red.joinToString("\n"))
            assertTrue("zz_dead_dir" in dead.single()) { "the DEAD line must name the key: ${dead.single()}" }
            assertTrue("1 parsed-and-never-acted-on" in red.first()) { red.first() }
        }
    }

    @Test
    fun `the law can actually fail - a key read only by the echo surface - V4-91`(@TempDir root: File) {
        with(Tree(root)) {
            write()
            assertEquals(emptyList<String>(), violations(), "the fully-wired fixture must be GREEN")
            val examined = SchemaKeysConsumed.unconsumed(files(), policy).examined
            assertTrue(examined >= 10) { "the fixture yielded $examined keys — every arm below would be unproven" }

            write(deadKey = true)
            val hits = violations()
            assertHit(hits, "zz_dead_dir") { "a schema key read only by the echo surface must be RED BY NAME" }
            assertHit(hits, "zz_dead_dir", "echo surface") {
                "the reason must NAME the echo surface rather than claim nothing reads it"
            }
        }
    }

    @Test
    fun `the law can actually fail - a knob read only by an uncalled accessor - V4-91`(@TempDir root: File) {
        with(Tree(root)) {
            write(deadKnob = true)
            assertHit(violations(), "zzDeadKnob") {
                "a knob whose only reader is a facade accessor nobody calls must be RED BY NAME"
            }
            // The BORING case: one key per plane, both wired, and the count must come out at two.
            write()
            put("Schema.kt", BORING_SCHEMA)
            put("Catalog.kt", "")
            put("Wiring.kt", BORING_WIRING)
            put("Doctor.kt", "package splice.zzfix\n\ninternal class DoctorShape\n")
            put("Paths.kt", "package splice.zzfix\n\ninternal class LocalPaths\n")
            put("Knob.kt", BORING_KNOB)
            put("SpliceConfig.kt", BORING_FACADE)
            put("KnobWiring.kt", BORING_KNOB_WIRING)
            assertEquals(emptyList<String>(), violations(), "the one-key-per-plane tree must be GREEN")
            assertEquals(6, SchemaKeysConsumed.unconsumed(files(), policy).examined)
        }
    }

    @Test
    fun `the two mechanisms are load-bearing - the inverse arms - V4-91`(@TempDir root: File) {
        with(Tree(root)) {
            write(deadKey = true)
            // INVERSE 1: drop ONLY the echo-surface exclusion and the dead key reads as consumed.
            val withoutEcho = policy.copy(
                nonConsumption = policy.nonConsumption.filterNot { it.first.endsWith("Doctor.kt") },
            )
            assertTrue(violations(withoutEcho).none { it.contains("zz_dead_dir") }) {
                "with the echo surface counted, the dead key must read as CONSUMED — otherwise the " +
                    "exclusion is not what finds it"
            }
            // INVERSE 2: the fixture declares `zzDeadDir` on DaemonConfig AND on LocalPaths, and
            // Paths.kt reads `paths.zzDeadDir` — the live DaemonConfig.stateDir / StatePaths.stateDir
            // shape. A name-only rule matches the wrong owner and calls the dead key wired.
            val shapes = SchemaKeysConsumed.schemaKeys(files()).shapes
            val paths = files().getValue("$PKG/Paths.kt")
            assertTrue(
                SchemaKeysConsumed.readPattern(
                    "DaemonConfig",
                    "zzDeadDir",
                    shapes,
                    ambiguous = false,
                ).containsMatchIn(paths),
            ) {
                "a name-only read pattern must match LocalPaths.zzDeadDir — otherwise the qualification guards nothing"
            }
            assertFalse(
                SchemaKeysConsumed.readPattern(
                    "DaemonConfig",
                    "zzDeadDir",
                    shapes,
                    ambiguous = true,
                ).containsMatchIn(paths),
            ) {
                "the receiver-qualified pattern must NOT match LocalPaths.zzDeadDir"
            }
        }
    }

    @Test
    fun `the allowlist is a disposition, and only with a dated reason - V4-91`(@TempDir root: File) {
        with(Tree(root)) {
            write(deadKey = true, deadKnob = true)
            val dated = "2026-09-17: fixture — deliberately inert"
            assertEquals(
                emptyList<String>(),
                violations(allow("zz_dead_dir" to dated, "zzDeadKnob" to dated)),
                "two dated, reasoned allowlist entries must dispose of both dead keys",
            )
            assertHit(violations(allow("zz_dead_dir" to "  ", "zzDeadKnob" to dated)), "absence wearing a label") {
                "an allowlist entry with a blank reason must be a hard error"
            }
            val stale = allow("zz_dead_dir" to dated, "zzDeadKnob" to dated, "control_port" to dated)
            assertHit(violations(stale), "IS acted on") {
                "an allowlist entry naming a key that IS acted on must fail as stale"
            }
            assertHit(violations(allow("zz_gone" to dated)), "not a config key any more") {
                "an allowlist entry naming no key at all must fail as stale"
            }
        }
    }

    @Test
    fun `the law refuses an untrustworthy denominator - V4-91`(@TempDir root: File) {
        with(Tree(root)) {
            write()
            val gone = policy.copy(
                nonConsumption = policy.nonConsumption + ("$PKG/Gone.kt" to "2026-09-17: names nothing"),
            )
            assertHit(
                violations(gone),
                "stale exclusion",
            ) { "a NON_CONSUMPTION entry naming a file that is gone must be RED" }

            write(rename = true)
            assertHit(violations(), "part of the denominator") { "a schema class the search cannot find must be RED" }

            write(orphan = true)
            assertHit(violations(), "the parser dropped a parameter") {
                "a @SerialName belonging to no parameter must REFUSE rather than shorten the list"
            }

            // The BORING case (§24): a tree with no config keys at all must refuse, not pass.
            for (name in listOf(
                "Schema.kt",
                "Catalog.kt",
                "Wiring.kt",
                "Doctor.kt",
                "Paths.kt",
                "Knob.kt",
                "SpliceConfig.kt",
                "KnobWiring.kt",
            )) {
                File(root, "$PKG/$name").delete()
            }
            put("Inert.kt", "package splice.zzfix\n\ninternal class Inert\n")
            assertHit(violations(SchemaKeysConsumed.Policy(emptyList(), emptyList())), "vacuously") {
                "a tree with no config keys must REFUSE, not pass vacuously"
            }
        }
    }

    private companion object {
        const val PKG = "app/src/main/kotlin/splice/zzfix"
        const val DEAD_KEY_LINE = "    @SerialName(\"zz_dead_dir\") val zzDeadDir: String? = null,\n"
        const val ORPHAN_LINE = "    @SerialName(\"zz_orphan\")\n"
        const val DEAD_ECHO_LINE = "        \"zz_dead_dir\" to t.daemon.zzDeadDir,\n"
        const val DEAD_KNOB_LINE = "    ZZ_DEAD_KNOB(\"zzDeadKnob\", false),\n"
        const val DEAD_ACCESSOR_LINE =
            "    public val zzDeadKnob: Boolean get() = m[Knob.ZZ_DEAD_KNOB.key] == true\n"

        // extraWindows is read ONLY inside its own file (projected into a domain type), compactEffort
        // ONLY by a require() in its own init, and contextWindow is an AMBIGUOUS name (Catalog
        // declares one too) read receiver-qualified elsewhere — the three shapes a naive rule reds.
        const val SCHEMA = """package splice.zzfix

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
public data class Topology(
    val daemon: DaemonConfig = DaemonConfig(),
    val providers: Map<String, ProviderConfig> = emptyMap(),
    val heads: Map<String, HeadConfig> = emptyMap(),
)

@Serializable
public data class DaemonConfig(
    @SerialName("control_port") val controlPort: Int? = null,
    @SerialName("state_dir") val stateDir: String? = null,
%extra%)

@Serializable
public data class HeadConfig(
    val port: Int,
    /** A KDoc between parameters, with a comma and a ) in it. */
    @SerialName("context_window") val contextWindow: Long? = null,
)

@Serializable
public data class ProviderConfig(
    @SerialName("base_url") val baseUrl: String,
    val quirks: %quirks% = %quirks%(),
    @SerialName("extra_windows") val extraWindows: List<String> = emptyList(),
) {
    public fun toCatalog(): Catalog = Catalog(extraWindows = extraWindows)
}

@Serializable
public data class %quirks%(
    val store: Boolean = false,
    @SerialName("compact_effort") val compactEffort: String? = null,
) {
    init {
        require(compactEffort == null) { "compact_effort is retired" }
    }
}
"""

        const val CATALOG = """package splice.zzfix

public class Catalog(
    public val extraWindows: List<String> = emptyList(),
    public val contextWindow: Long = 0,
) {
    public fun widest(): String? = extraWindows.maxOrNull()
}
"""

        const val WIRING = """package splice.zzfix

internal class Wiring(private val topology: Topology, private val quirks: %quirks%) {
    fun bind(): Int = topology.daemon.controlPort ?: 0
    fun providerKeys(): Set<String> = topology.providers.keys
    fun headKeys(): Set<String> = topology.heads.keys
    fun window(head: HeadConfig): Long = head.contextWindow ?: 0
    fun port(head: HeadConfig): Int = head.port
    fun base(provider: ProviderConfig): String = provider.baseUrl
    fun quirksOf(provider: ProviderConfig): %quirks% = provider.quirks
    fun store(): Boolean = quirks.store
    fun dir(): String? = topology.daemon.stateDir
}
"""

        // The echo surface: it puts the dead key back out under its own key name and does nothing
        // else with it. It is excluded, which is what makes the dead key red.
        const val DOCTOR = """package splice.zzfix

internal class DoctorShape {
    fun shape(t: Topology): Map<String, Any?> = mapOf(
%echo%        "port" to t.daemon.controlPort,
    )
}
"""

        // The AMBIGUITY TWIN, in a file that is NOT excluded: a second class declaring the SAME
        // property name, read through a receiver that is not a spelling of DaemonConfig.
        const val PATHS = """package splice.zzfix

internal class LocalPaths {
    val zzDeadDir: String = "/var/lib/zzfix"
}

internal class PathUser(private val paths: LocalPaths) {
    fun dir(): String = paths.zzDeadDir
}
"""

        const val KNOB = """package splice.zzfix

public enum class Knob(
    public val key: String,
    public val default: Any?,
) {
    PORT("port", 3099L),
    WIRED_DIRECT("wiredDirect", "x"),
    WIRED_VIA_ACCESSOR("wiredViaAccessor", true),
%dead%}
"""

        const val FACADE = """package splice.zzfix

public class SpliceConfig internal constructor(private val m: Map<String, Any?>) {
    public val port: Int get() = (m[Knob.PORT.key] as? Int) ?: 0
    public val wiredViaAccessor: Boolean get() = m[Knob.WIRED_VIA_ACCESSOR.key] == true
%dead%}
"""

        const val KNOB_WIRING = """package splice.zzfix

internal class KnobWiring(private val cfg: SpliceConfig) {
    fun direct(): String = Knob.WIRED_DIRECT.key
    fun viaAccessor(): Boolean = cfg.wiredViaAccessor
    fun bind(): Int = cfg.port
}
"""

        const val BORING_SCHEMA = """package splice.zzfix

public data class Topology(val daemon: DaemonConfig = DaemonConfig())
public data class DaemonConfig(val only: Int = 0)
public data class HeadConfig(val port: Int)
public data class ProviderConfig(val baseUrl: String)
public data class QuirksConfig(val store: Boolean = false)
"""

        const val BORING_WIRING = """package splice.zzfix

internal class Wiring(private val t: Topology) {
    fun a(): Int = t.daemon.only
    fun b(h: HeadConfig): Int = h.port
    fun c(p: ProviderConfig): String = p.baseUrl
    fun d(q: QuirksConfig): Boolean = q.store
}
"""

        const val BORING_KNOB = """package splice.zzfix

public enum class Knob(public val key: String) {
    ONLY("only"),
}
"""

        const val BORING_FACADE = """package splice.zzfix

public class SpliceConfig {
    public val only: String get() = Knob.ONLY.key
}
"""

        const val BORING_KNOB_WIRING = """package splice.zzfix

internal class KnobWiring(private val c: SpliceConfig) { fun a() = c.only }
"""
    }
}
