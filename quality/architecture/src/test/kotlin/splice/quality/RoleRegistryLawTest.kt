// NEW: V4-89 — ONE ROLE, ONE INTERFACE: every shared single-abstract-method signature is
// dispositioned in writing (ported from checks/role-registry.ts, restructure PR 6).
//
// WHY THIS EXISTS. kt-no-lambda-seam made every seam a named port, and its header states the
// doctrine this law enforces the other half of: "one interface per role; a role with two spellings
// is one interface". It also names the reason a matcher cannot do it — the mapping from names to
// signatures is many-to-many in both directions. `() -> Boolean` is legitimately eleven roles
// (ClientGone and ClientFrameEmitted are read a few lines apart on the same retry path and drive
// OPPOSITE decisions; collapsing them into a shared BooleanSupplier would be strictly worse than
// the lambdas they replaced). Nothing in the compiler, and nothing in an ast-grep rule, can tell
// that situation apart from a real duplicate — so this law does not try to. It requires every
// shared signature to be DISPOSITIONED IN WRITING, and treats absence as the failure.
//
// DENOMINATOR, FROM THE SOURCE (§24). Every Kotlin file under every module's src/main that the
// BUILD'S PROJECT MAP names is parsed on disk and every `fun interface` in it enumerated — nested
// ones included, since a per-class seam is still a seam. The checker this replaces listed source
// globs by hand; the map is the same file set taken from the build, so a module that moves cannot
// drop out of the denominator in silence. THREE GUARDS refuse a vacuous pass: a parse yielding zero
// interfaces is a failure rather than a pass; every `fun interface` occurrence in the
// comment-blanked view must resolve to EXACTLY ONE outcome — a Role or a named problem — with the
// totals compared at the end, which catches the failure this parser is prone to (a branch that
// gives up on a declaration and moves the cursor on without recording anything, quietly shrinking
// the denominator); and a declaration whose body does not yield EXACTLY ONE abstract method is
// reported as UNTRUSTED rather than silently grouped, because a `fun interface` has one by language
// rule, so a different count means this parser, not the code, is wrong. The INDEPENDENT census is
// Konsist's — the Kotlin compiler frontend this module already depends on — because a regex cannot
// cross-check itself.
//
// SIGNATURE NORMALISATION, and why each part is load-bearing. Parameter types plus the return type,
// whitespace removed. `suspend` is PART of the signature: folding it in merged Ticker
// (`suspend (Long) -> Boolean`, the pacing seam whose false means "stop the loop") with PidAlive
// (`(Long) -> Boolean`, "is this pid alive"), and a suspend seam and a blocking one cannot be
// substituted for each other at all. TYPE PARAMETERS ARE POSITIONAL (`#1`, `#2`), never their
// declared spelling: `CoalescedWork<T>` and `MaterializedRequest<R>` ARE the same shape, and a
// normaliser that kept `T` and `R` would let a real duplicate through on a rename. The METHOD NAME
// is NOT part of the signature, deliberately — a duplicate role renamed from `invoke` to `run` is
// exactly the drift this law is for.
//
// DISPOSITION. quality/architecture/src/test/resources/role-registry.toml carries one `[[groups]]`
// entry per shared signature: the normalised signature verbatim, the date it was written, the names
// it accounts for, and a reason saying why these are DISTINCT ROLES. It is the law's own
// declaration data, declared as a test input by the build and read off the test classpath.
// FAIL-CLOSED IN BOTH DIRECTIONS, which is what makes it a ratchet rather than a list: GROWTH — a
// new name joining a dispositioned group is not in `names`, so it fails by name, and adding a seam
// that shares a shape costs one written sentence every time; STALENESS — a name in `names` that no
// longer exists, or an entry whose signature is no longer shared by 2+ names, fails as stale, so a
// disposition cannot outlive its subject; UNREASONED — a missing, blank or whitespace-only reason
// is an absence wearing a label and fails the same as no entry at all.
//
// NOT CAUGHT, and why it is written down rather than implied. TWO DECLARATIONS OF THE SAME NAME
// (SynthesizeExpiry exists once per vendor): those groups hold ONE distinct name, and the
// SEPARATION law makes a per-vendor fact one role per vendor — the census reports them so the count
// stays visible, and does not grade them. A DUPLICATE WITH DIFFERENT SIGNATURES: two names for one
// role whose methods take different parameter lists land in different groups; this law's claim is
// exactly "same shape, undeclared intent", never "same meaning". A ROLE-INAPPROPRIATE REASON: this
// law proves a reason EXISTS and is not blank; whether it is a good one is a reviewer's judgement,
// and the entries are checked-in text precisely so a reviewer sees them in a diff.
package splice.quality

import com.lemonappdev.konsist.api.Konsist
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

internal object RoleRegistry {
    /** The disposition file, as a reviewer edits it and as a violation names it. */
    const val DECLARATIONS = "quality/architecture/src/test/resources/role-registry.toml"

    private const val RESOURCE = "/role-registry.toml"
    private const val FUN = "fun"
    private const val RAW_QUOTE = "\"\"\""
    private const val BLOCK_CLOSE = "*/"
    private const val NAME_COLUMN = 32

    private val DECL = Regex("\\bfun\\s+interface\\s+(\\w+)")
    private val METHOD_HEAD = Regex("\\s*(\\w+)\\s*\\(")
    private val SUSPEND = Regex("\\bsuspend\\b")
    private val RETURN_COLON = Regex("^\\s*:\\s*")
    private val DEFAULTED = Regex("^\\s*[={]")
    private val WHITESPACE = Regex("\\s+")
    private val PARAM_MODIFIERS = Regex("^(vararg\\s+|noinline\\s+|crossinline\\s+)+")

    /** One named seam: where it is declared, the shape it normalises to, and the method that
     *  carries it (which is reported and deliberately NOT part of the signature). */
    data class Role(val name: String, val path: String, val line: Int, val signature: String, val method: String) {
        val at: String get() = "$path:$line"
    }

    /** The roles the tree yielded, the denominator they came from, and the untrusted-parse residue. */
    data class Census(val scope: String, val roles: List<Role>, val problems: List<String>)

    /** The dispositions, by signature, and everything that made them unreadable. */
    data class Config(val rel: String, val entries: Map<String, MiniToml.Table>, val problems: List<String>)

    /** What one interface body yielded: (method name, signature) pairs and any parse trouble. */
    data class Methods(val found: List<Pair<String, String>>, val problems: List<String>)

    private data class Head(val params: List<String>, val cursor: Int)

    private data class Method(
        val suspended: Boolean,
        val typeParams: List<String>,
        val name: String,
        val open: Int,
        val close: Int,
    )

    private data class Returned(val type: String, val tail: String)

    /** One shared signature under audit: its names, where each is declared, and the file that
     *  disposes it. */
    private data class Group(
        val signature: String,
        val names: List<String>,
        val where: Map<String, String>,
        val rel: String,
    ) {
        fun siblings(name: String): String = names.filter { it != name }.joinToString(", ")

        fun at(name: String): String = where[name].orEmpty()
    }

    // ── the comment- and string-blanked view ──────────────────────────────────────────────────

    /** Blank comments and string literals WITHOUT moving offsets or newlines.
     *
     *  One lexical pass, so a `//` inside a string cannot open a comment and a quote inside a
     *  comment cannot open a string. Offsets survive so every finding still names file:LINE, which
     *  is this law's contract. Raw strings are handled before ordinary ones: a KDoc example or a
     *  raw string containing the words `fun interface` must not become a phantom declaration, and
     *  the parser-drift guard counts occurrences in THIS view so the two halves cannot disagree.
     *  CHAR LITERALS are blanked too — `'"'` would otherwise open a string that never closes, which
     *  is why this is not [KotlinText.blankCommentsAndStrings]. */
    fun codeView(text: String): String {
        val out = StringBuilder(text)
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            val quoted = ch == '"' || ch == '\''
            i = when {
                text.startsWith("//", i) -> blank(out, i, endOfLine(text, i))
                text.startsWith("/*", i) -> blank(out, i, endOfBlock(text, i))
                text.startsWith(RAW_QUOTE, i) -> blank(out, i, endOfRaw(text, i))
                quoted -> blank(out, i, endOfQuoted(text, i))
                else -> i + 1
            }
        }
        return out.toString()
    }

    private fun blank(out: StringBuilder, from: Int, to: Int): Int {
        for (k in from until minOf(to, out.length)) {
            if (out[k] != '\n') out.setCharAt(k, ' ')
        }
        return to
    }

    private fun endOfLine(text: String, at: Int): Int {
        val end = text.indexOf('\n', at)
        return if (end < 0) text.length else end
    }

    private fun endOfBlock(text: String, at: Int): Int {
        val end = text.indexOf(BLOCK_CLOSE, at + 2)
        return if (end < 0) text.length else end + BLOCK_CLOSE.length
    }

    private fun endOfRaw(text: String, at: Int): Int {
        val end = text.indexOf(RAW_QUOTE, at + RAW_QUOTE.length)
        return if (end < 0) text.length else end + RAW_QUOTE.length
    }

    /** Past the closing quote, or AT the newline that cuts an unterminated literal short. */
    private fun endOfQuoted(text: String, at: Int): Int {
        val quote = text[at]
        var j = at + 1
        while (j < text.length) {
            val ch = text[j]
            val closes = ch == quote
            val cut = ch == '\n'
            j += if (ch == '\\') 2 else 1
            if (closes || cut) return if (closes) j else j - 1
        }
        return j
    }

    // ── bracket readers ───────────────────────────────────────────────────────────────────────

    /** Index of the bracket closing the one at [at], over the blanked view, or -1. */
    fun balancedAt(code: String, at: Int, open: Char, close: Char): Int {
        var depth = 0
        for (i in at until code.length) {
            if (code[i] == open) depth += 1
            if (code[i] == close) depth -= 1
            val done = code[i] == close && depth == 0
            if (done) return i
        }
        return -1
    }

    /** Index of the `>` closing the type-parameter list opened at [at], or -1. Stops at `{` or `;`
     *  so a `<` used as a comparison cannot run the scan off the end of the file. */
    fun angleEnd(code: String, at: Int): Int {
        var depth = 0
        for (i in at until code.length) {
            val ch = code[i]
            val stops = ch == '{' || ch == ';'
            if (stops) return -1
            if (ch == '<') depth += 1
            if (ch == '>') depth -= 1
            val done = ch == '>' && depth == 0
            if (done) return i
        }
        return -1
    }

    /** Split on top-level commas, honouring `()`, `[]`, `{}` and `<>`. */
    fun splitTop(text: String): List<String> {
        val parts = mutableListOf<String>()
        val buf = StringBuilder()
        var depth = 0
        for (ch in text) {
            if (ch in "([{<") depth += 1
            if (ch in ")]}>") depth -= 1
            val separates = ch == ',' && depth == 0
            if (separates) {
                parts += buf.toString()
                buf.setLength(0)
            } else {
                buf.append(ch)
            }
        }
        if (buf.toString().isNotBlank()) parts += buf.toString()
        return parts
    }

    /** `T`, `K`, `V` from a type-parameter list body, dropping bounds and variance. */
    fun typeParamNames(text: String): List<String> =
        splitTop(text).mapNotNull { part ->
            val head = part.trim().substringBefore(":").trim()
            if (head.isEmpty()) null else head.split(WHITESPACE).last()
        }

    /** Python's `str.isalnum()` plus `_`, ASCII-only, for the identifier-boundary checks. */
    private fun isWordChar(ch: Char?): Boolean {
        if (ch == null) return false
        val alpha = ch in 'A'..'Z' || ch in 'a'..'z'
        val digit = ch in '0'..'9' || ch == '_'
        return alpha || digit
    }

    // ── the abstract-method walk ──────────────────────────────────────────────────────────────

    /** TOP-LEVEL abstract methods only (bracket depth 0 within the body), so a nested enum's or
     *  data class's own functions are never mistaken for the seam — AccountCredentialIdentitySource
     *  has one abstract method beside two defaulted ones, a nested enum and a nested data class
     *  with an `init`, and a naive `fun` count reads it as seven. Abstract means NO BODY: the
     *  signature is followed by neither `=` nor `{`. One small function per state. */
    private class MethodScan(private val body: String, private val owner: List<String>) {
        private val found = mutableListOf<Pair<String, String>>()
        private val problems = mutableListOf<String>()
        private var i = 0
        private var depth = 0

        fun run(): Methods {
            while (i < body.length) i = step()
            return Methods(found, problems)
        }

        private fun step(): Int = when {
            body[i] in "{([" -> {
                depth += 1
                i + 1
            }
            body[i] in "})]" -> {
                depth -= 1
                i + 1
            }
            !atFun() -> i + 1
            else -> readMethod()
        }

        private fun atFun(): Boolean {
            val here = depth == 0 && body.startsWith(FUN, i)
            val leftFree = i == 0 || !isWordChar(body.getOrNull(i - 1))
            val rightFree = !isWordChar(body.getOrNull(i + FUN.length))
            val free = leftFree && rightFree
            return here && free
        }

        private fun readMethod(): Int {
            val suspended = SUSPEND.containsMatchIn(modifiers())
            val afterFun = skipBlanks(i + FUN.length)
            val head = typeParams(afterFun) ?: return afterFun + 1
            return signature(suspended, head)
        }

        /** Modifiers sit between the previous member boundary and this `fun`. */
        private fun modifiers(): String {
            var back = i - 1
            while (back >= 0 && body[back] !in "};\n") back -= 1
            return body.substring(back + 1, i)
        }

        private fun skipBlanks(from: Int): Int {
            var k = from
            while (k < body.length) {
                val blank = body[k] == ' ' || body[k] == '\t'
                if (!blank) break
                k += 1
            }
            return k
        }

        private fun typeParams(from: Int): Head? {
            val angled = from < body.length && body[from] == '<'
            if (!angled) return Head(emptyList(), from)
            val end = angleEnd(body, from)
            if (end < 0) {
                problems += "unterminated method type-parameter list"
                return null
            }
            return Head(typeParamNames(body.substring(from + 1, end)), end + 1)
        }

        private fun signature(suspended: Boolean, head: Head): Int {
            val name = METHOD_HEAD.find(body.substring(head.cursor)) ?: return i + FUN.length
            val open = head.cursor + name.value.length - 1
            val close = balancedAt(body, open, '(', ')')
            if (close < 0) {
                problems += "unbalanced parameter list on `${name.groupValues[1]}`"
                return open + 1
            }
            return emit(Method(suspended, head.params, name.groupValues[1], open, close))
        }

        private fun emit(method: Method): Int {
            val returned = readReturn(body.substring(method.close + 1))
            val defaulted = DEFAULTED.containsMatchIn(returned.tail)
            if (defaulted) return method.close + 1
            val slots = slots(method.typeParams)
            val types = paramTypes(body.substring(method.open + 1, method.close), slots)
            val prefix = if (method.suspended) "suspend " else ""
            found += method.name to "$prefix(${types.joinToString(",")})->${normalise(returned.type, slots)}"
            return method.close + 1
        }

        private fun readReturn(rest: String): Returned {
            val colon = RETURN_COLON.find(rest) ?: return Returned("Unit", rest)
            val typed = rest.substring(colon.value.length)
            val cut = returnCut(typed)
            return Returned(typed.substring(0, cut).trim().ifEmpty { "Unit" }, typed.substring(cut))
        }

        private fun returnCut(typed: String): Int {
            var depth = 0
            for (idx in typed.indices) {
                val ch = typed[idx]
                if (ch in "([<") depth += 1
                if (ch in ")]>") depth -= 1
                val ends = depth == 0 && ch in "={\n"
                if (ends) return idx
            }
            return typed.length
        }

        private fun slots(methodParams: List<String>): Map<String, String> =
            (owner + methodParams).distinct().withIndex().associate { (k, name) -> name to "#${k + 1}" }

        private fun normalise(kind: String, slots: Map<String, String>): String {
            var collapsed = WHITESPACE.replace(kind, "")
            for ((declared, slot) in slots) {
                collapsed = Regex("\\b${Regex.escape(declared)}\\b").replace(collapsed, slot)
            }
            return collapsed
        }

        private fun paramTypes(paramText: String, slots: Map<String, String>): List<String> =
            splitTop(paramText).map { it.trim() }.filter { it.isNotEmpty() }.map { param ->
                val bare = PARAM_MODIFIERS.replaceFirst(param, "")
                val kind = if (bare.contains(":")) bare.substringAfter(":") else bare
                normalise(kind.substringBefore("="), slots)
            }
    }

    fun abstractMethods(body: String, ownerParams: List<String>): Methods = MethodScan(body, ownerParams).run()

    // ── the census ────────────────────────────────────────────────────────────────────────────

    /** SELF-ACCOUNTING, not a second hand count: every occurrence must resolve to exactly one
     *  outcome, and the totals are compared at the end. */
    private class Collected {
        val roles = mutableListOf<Role>()
        val problems = mutableListOf<String>()
        var occurrences = 0
        var resolved = 0
    }

    fun collect(files: List<File>, root: File): Census {
        val into = Collected()
        for (file in files) {
            val rel = file.relativeTo(root).invariantSeparatorsPath
            val code = codeView(file.readText())
            for (match in DECL.findAll(code)) {
                into.occurrences += 1
                readDeclaration(code, match, rel, into)
            }
        }
        if (into.resolved != into.occurrences) {
            into.problems += "counted ${into.occurrences} `fun interface` occurrence(s) in the " +
                "comment-blanked sources but resolved only ${into.resolved} of them to a role or a " +
                "named problem — a declaration this parser silently dropped shrinks the denominator, " +
                "so no role list from this run can be trusted"
        }
        return Census("src/main of the ${files.size} file(s) the build's project map names", into.roles, into.problems)
    }

    private fun readDeclaration(code: String, match: MatchResult, rel: String, into: Collected) {
        val name = match.groupValues[1]
        val line = KotlinText.lineOf(code, match.range.first)
        val at = "$rel:$line $name"
        val owner = ownerParams(code, match.range.last + 1, at, into) ?: return
        val body = interfaceBody(code, owner.cursor, at, into) ?: return
        val methods = abstractMethods(body, owner.params)
        methods.problems.forEach { into.problems += "$at: $it" }
        into.resolved += 1
        if (methods.found.size != 1) {
            into.problems += "$at: parsed ${methods.found.size} abstract method(s); a `fun " +
                "interface` has exactly one by language rule, so this parser and the source " +
                "disagree and no signature from this run can be trusted"
            return
        }
        val (method, signature) = methods.found[0]
        into.roles += Role(name, rel, line, signature, method)
    }

    private fun ownerParams(code: String, from: Int, at: String, into: Collected): Head? {
        var cursor = from
        while (cursor < code.length) {
            val blank = code[cursor] == ' ' || code[cursor] == '\t'
            if (!blank) break
            cursor += 1
        }
        val angled = cursor < code.length && code[cursor] == '<'
        if (!angled) return Head(emptyList(), cursor)
        val end = angleEnd(code, cursor)
        if (end < 0) {
            into.problems += "$at: unterminated type-parameter list"
            into.resolved += 1
            return null
        }
        return Head(typeParamNames(code.substring(cursor + 1, end)), end + 1)
    }

    private fun interfaceBody(code: String, cursor: Int, at: String, into: Collected): String? {
        val brace = code.indexOf('{', cursor)
        if (brace < 0) {
            into.problems += "$at: no interface body — this parser and the " +
                "source disagree, so no role list from this run can be trusted"
            into.resolved += 1
            return null
        }
        val end = balancedAt(code, brace, '{', '}')
        if (end < 0) {
            into.problems += "$at: unbalanced interface body — this parser and " +
                "the source disagree, so no role list from this run can be trusted"
            into.resolved += 1
            return null
        }
        return code.substring(brace + 1, end)
    }

    /** signature -> roles, for every signature carried by 2+ DISTINCT names. */
    fun sharedGroups(roles: List<Role>): Map<String, List<Role>> {
        val bySignature = linkedMapOf<String, MutableList<Role>>()
        for (role in roles) bySignature.getOrPut(role.signature) { mutableListOf() } += role
        return bySignature.filterValues { members -> members.map { it.name }.distinct().size > 1 }
    }

    // ── the dispositions ──────────────────────────────────────────────────────────────────────

    /** The disposition file off the test classpath; null when this build shipped none. */
    fun declarations(): String? =
        javaClass.getResourceAsStream(RESOURCE)?.use { stream -> stream.readBytes().decodeToString() }

    /** signature -> entry, plus problems. A config that cannot be read is a failure, never a skip. */
    fun loadConfig(text: String?, rel: String): Config {
        if (text == null) {
            val missing = "$rel: the disposition file is missing — with no dispositions every shared " +
                "signature is an absence, so this cannot pass"
            return Config(rel, emptyMap(), listOf(missing))
        }
        val document = try {
            MiniToml.parse(text)
        } catch (error: MiniToml.ParseError) {
            val unreadable = "$rel: unparseable TOML (${error.message}) — a disposition nobody can read is not one"
            return Config(rel, emptyMap(), listOf(unreadable))
        }
        val entries = linkedMapOf<String, MiniToml.Table>()
        val problems = mutableListOf<String>()
        document.array("groups").forEachIndexed { index, entry -> register(entry, index, rel, entries, problems) }
        return Config(rel, entries, problems)
    }

    private fun register(
        entry: MiniToml.Table,
        index: Int,
        rel: String,
        entries: MutableMap<String, MiniToml.Table>,
        problems: MutableList<String>,
    ) {
        val signature = entry.text("signature")
        when {
            signature.isNullOrBlank() -> problems += "$rel: groups[$index] has no `signature`"
            entries.containsKey(signature) ->
                problems += "$rel: two entries dispose `$signature` — one " +
                    "signature, one disposition, or the second is dead text nobody reviews"
            else -> entries[signature] = entry
        }
    }

    // ── the audit ─────────────────────────────────────────────────────────────────────────────

    private fun undispositioned(group: Group, name: String): String =
        "NO DISPOSITION: $name (${group.at(name)}) shares the signature ${group.signature} with " +
            "${group.siblings(name)} and no entry in ${group.rel} says they are distinct roles. Either " +
            "reconcile the duplicate onto one interface, or add a [[groups]] entry with " +
            "signature = \"${group.signature}\" and a written reason."

    private fun unlisted(group: Group, name: String): String =
        "NO DISPOSITION: $name (${group.at(name)}) shares the signature ${group.signature} with " +
            "${group.siblings(name)}. The entry in ${group.rel} disposes that signature but does not " +
            "list $name: add it to `names` with the reason extended to cover it, or " +
            "reconcile it onto the interface that already holds this role."

    private fun stale(group: Group, name: String): String =
        "STALE DISPOSITION: ${group.rel} lists $name under ${group.signature}, but no " +
            "interface of that name carries that signature any more. A disposition may not " +
            "outlive its subject — drop the name (and the entry, if it is the last one)."

    private fun auditGroup(group: Group, entry: MiniToml.Table?, problems: MutableList<String>) {
        if (entry == null) {
            group.names.forEach { problems += undispositioned(group, it) }
            return
        }
        val reason = entry.text("reason")
        if (reason.isNullOrBlank()) {
            problems += "${group.rel}: the entry for ${group.signature} carries no reason — a disposition " +
                "without a written reason is an absence wearing a label, and accounts for " +
                "${group.names.joinToString(", ")} in name only"
            return
        }
        if (entry.text("dated").isNullOrBlank()) {
            problems += "${group.rel}: the entry for ${group.signature} carries no `dated` — an undated " +
                "disposition cannot be aged out or reviewed"
        }
        val listed = entry.strings("names")
        if (listed == null) {
            problems += "${group.rel}: the entry for ${group.signature} has no `names` list"
            return
        }
        group.names.filterNot { listed.contains(it) }.forEach { problems += unlisted(group, it) }
        listed.distinct().sorted().filterNot { group.names.contains(it) }.forEach { problems += stale(group, it) }
    }

    fun audit(census: Census, config: Config): List<String> {
        val problems = census.problems.toMutableList()
        if (census.roles.isEmpty()) {
            problems += "parsed 0 `fun interface` declarations under ${census.scope} — refusing to pass " +
                "vacuously, because a green over an empty denominator is what this wall exists to " +
                "prevent"
            return problems
        }
        problems += config.problems
        val groups = sharedGroups(census.roles)
        for (signature in groups.keys.sorted()) {
            val members = groups.getValue(signature)
            val names = members.map { it.name }.distinct().sorted()
            val group = Group(signature, names, members.associate { it.name to it.at }, config.rel)
            auditGroup(group, config.entries[signature], problems)
        }
        for (signature in config.entries.keys.sorted().filterNot { groups.containsKey(it) }) {
            problems += "STALE DISPOSITION: ${config.rel} disposes $signature, but that signature is no " +
                "longer shared by two or more names in the tree. Remove the entry — a list that " +
                "keeps entries nobody can reach is how an allowlist stops being reviewed."
        }
        return problems
    }

    // ── the census report (the checker's `report`, line for line) ─────────────────────────────

    private fun ordered(groups: Map<String, List<Role>>): List<Map.Entry<String, List<Role>>> =
        groups.entries.sortedWith(
            compareByDescending<Map.Entry<String, List<Role>>> { entry ->
                entry.value.map { it.name }.distinct().size
            }.thenBy { it.key },
        )

    private fun groupHeader(signature: String, members: List<Role>, entry: MiniToml.Table?): String {
        val names = members.map { it.name }.distinct().size
        val disposed = entry != null && !entry.text("reason").isNullOrBlank()
        return "  $signature   [$names names]  ${if (disposed) "DISPOSED" else "NO DISPOSITION"}"
    }

    private fun groupRows(members: List<Role>, entry: MiniToml.Table?): List<String> {
        val listed = entry?.strings("names").orEmpty()
        return members.sortedWith(compareBy({ it.name }, { it.path })).map { member ->
            val accounted = entry != null && listed.contains(member.name)
            "    ${if (accounted) " " else "!"} ${member.name.padEnd(NAME_COLUMN)} ${member.at}  (${member.method})"
        }
    }

    private fun repeatedNames(roles: List<Role>): List<String> {
        val byName = linkedMapOf<String, MutableList<Role>>()
        for (role in roles) byName.getOrPut(role.name) { mutableListOf() } += role
        val repeated = byName.filterValues { it.size > 1 }
        if (repeated.isEmpty()) return emptyList()
        val lines = mutableListOf(
            "  (not graded: ${repeated.size} name(s) declared more than once — the SEPARATION law " +
                "makes a per-vendor seam one role per vendor)",
        )
        for (name in repeated.keys.sorted()) {
            val bits = repeated.getValue(name).sortedBy { it.path }.joinToString(", ") { "${it.at} ${it.signature}" }
            lines += "    = $name: $bits"
        }
        return lines
    }

    fun census(census: Census, config: Config): List<String> {
        val groups = sharedGroups(census.roles)
        val signatures = census.roles.map { it.signature }.distinct().size
        val lines = mutableListOf(
            "role-registry: ${census.roles.size} `fun interface` declaration(s) over $signatures signature(s); " +
                "${groups.size} signature(s) shared by 2+ names; ${config.entries.size} disposition(s)",
        )
        census.problems.forEach { lines += "  UNTRUSTED: $it" }
        for ((signature, members) in ordered(groups)) {
            lines += groupHeader(signature, members, config.entries[signature])
            lines += groupRows(members, config.entries[signature])
        }
        lines += repeatedNames(census.roles)
        return lines
    }
}

class RoleRegistryLawTest {
    private val map = ProjectMap.fromSystemProperties()

    private fun live(): RoleRegistry.Census = RoleRegistry.collect(KotlinText.kotlinFiles(map), map.root)

    private fun shipped(): RoleRegistry.Config =
        RoleRegistry.loadConfig(RoleRegistry.declarations(), RoleRegistry.DECLARATIONS)

    @Test
    fun `every shared signature is accounted for in writing - V4-89`() {
        val census = live()
        assertTrue(census.roles.size > 100) {
            "the map yielded ${census.roles.size} `fun interface` declaration(s) — the walk is broken, " +
                "and a law that reads no seams passes vacuously."
        }
        assertTrue(RoleRegistry.sharedGroups(census.roles).isNotEmpty()) {
            "no signature is shared by 2+ names — this law would then grade nothing."
        }
        val config = shipped()
        val problems = RoleRegistry.audit(census, config)
        // THE CENSUS RUNS ON THE GREEN PATH. Below it rides on the failure text, which means that
        // on every green run the port executes nothing and nobody would learn it had rotted until
        // the day someone needed it to read a red. So it is computed here, and its own header is
        // checked against the denominator the audit just read: a census that disagrees with the
        // audit reds now instead of printing a wrong number into a failure someone is already
        // struggling with.
        val report = RoleRegistry.census(census, config)
        assertTrue(report.first().startsWith("role-registry: ${census.roles.size} `fun interface`")) {
            "the census header disagrees with the denominator the audit read " +
                "(${census.roles.size} roles):\n${report.first()}"
        }
        assertTrue(problems.isEmpty()) {
            // AND IT RIDES ON THE RED. The bun checker printed it from a second command
            // (`bun checks/role-registry.ts report .`) that the port deleted; a remedy naming a
            // command nobody can run is an absence wearing a label, and the reader of this failure
            // is exactly the person who needed that report. So it is the same output, same run.
            problems.joinToString(separator = "\n  - ", prefix = "ROLE REGISTRY (V4-89) violated:\n  - ") +
                report.joinToString("\n", prefix = "\n\n")
        }
    }

    /** §24: the text-level census cannot cross-check itself, so the denominator is taken a SECOND
     *  time from the Kotlin compiler frontend this module already depends on, and a vacuous
     *  agreement at zero is refused. */
    @Test
    fun `the text census equals Konsist's independent AST census - V4-89`() {
        val ast = map.modules
            .filter { module -> map.mainSources(module).isDirectory }
            .sumOf { module ->
                Konsist.scopeFromDirectory("${map.relativeDir(module)}/src/main/kotlin")
                    .interfaces(includeNested = true)
                    .count { it.hasFunModifier }
            }
        assertTrue(ast > 0) { "the AST census found ZERO fun interfaces — refusing a vacuous agreement" }
        assertEquals(
            ast,
            live().roles.size,
            "the text census and Konsist's AST census disagree — one of the two is wrong, and a " +
                "denominator nobody can reproduce is not a denominator",
        )
    }

    /** The declaration file is read by a reader written for EXACTLY the subset it uses
     *  ([MiniToml]), so the reader is proven against the real bytes by their known counts: a
     *  half-parse would otherwise show up as a disposition that silently stopped accounting for its
     *  names, which is a green this law exists to refuse. The escapes and the line-ending `\` fold
     *  are proven on the one entry that carries them. */
    @Test
    fun `the declaration file round-trips through the reader - V4-89`() {
        val text = RoleRegistry.declarations().orEmpty()
        val config = shipped()
        assertEquals(emptyList<String>(), config.problems, "the shipped dispositions must parse")
        assertEquals(27, config.entries.size, "one entry per shared signature")
        assertEquals(
            text.split("\n").count { it == "[[groups]]" },
            config.entries.size,
            "every [[groups]] header in the file yielded an entry — a dropped table is a dropped disposition",
        )
        val names = config.entries.values.map { it.strings("names") }
        assertTrue(names.none { it == null }) { "every entry must declare an array of strings under `names`" }
        assertEquals(107, names.sumOf { it.orEmpty().size }, "the names the file accounts for")
        assertTrue(config.entries.values.all { !it.text("reason").isNullOrBlank() }) { "every entry is reasoned" }
        assertTrue(config.entries.values.all { !it.text("dated").isNullOrBlank() }) { "every entry is dated" }

        val threads = config.entries.getValue("(Thread)->Unit")
        assertEquals(listOf("ShutdownHookAdd", "ShutdownHookRemove"), threads.strings("names"))
        assertTrue(threads.text("reason").orEmpty().contains("exact INVERSES")) { "the reason is read verbatim" }

        val escaped = config.entries.getValue("(String)->String?").text("reason").orEmpty()
        assertTrue(escaped.contains("\"the gateway")) { "a `\\\"` escape must decode to a quote" }
        assertTrue(!escaped.contains("\\") && !escaped.contains("\n")) {
            "a line-ending backslash folds the newline away: $escaped"
        }

        // The rest of the accepted subset, refused loudly rather than half-read.
        val extras = MiniToml.parse("[flags]\nquiet = true\nloud = false\n")
        assertEquals(true, extras.tables.getValue("flags").flag("quiet"))
        assertEquals(false, extras.tables.getValue("flags").flag("loud"))
        assertTrue(runCatching { MiniToml.parse("x = 1\n") }.exceptionOrNull() is MiniToml.ParseError) {
            "a value shape nobody declared is a ParseError, never a silently-dropped key"
        }
    }

    /** The synthetic tree the red proof writes into: one module, whose files each arm replaces. */
    private class Tree(val root: File) {
        private val synthetic = ProjectMap.parse(root, ":core=core", setOf("build"))

        fun write(vararg sources: Pair<String, String>) {
            val dir = File(root, MODULE)
            dir.mkdirs()
            dir.listFiles().orEmpty().forEach { it.delete() }
            sources.forEach { (name, text) -> File(dir, name).writeText(text) }
        }

        fun census(): RoleRegistry.Census = RoleRegistry.collect(KotlinText.kotlinFiles(synthetic), root)

        fun audit(config: String?): List<String> =
            RoleRegistry.audit(census(), RoleRegistry.loadConfig(config, RoleRegistry.DECLARATIONS))
    }

    @Test
    fun `the law can actually fail - growth and absence - V4-89`(@TempDir root: File) {
        with(Tree(root)) {
            write(PORTS to COMPLIANT_SOURCE)
            assertEquals(
                emptyList<String>(),
                audit(CONFIG_OK),
                "a dispositioned group with a suspend sibling and an arity sibling must be GREEN",
            )

            write(PORTS to COMPLIANT_SOURCE + SYNTHETIC_DUPLICATE)
            assertHit(audit(CONFIG_OK), "ClientHungUp") { "a third name joining the group must be RED BY NAME" }

            write(PORTS to COMPLIANT_SOURCE)
            assertHit(audit(""), "ClientGone") { "a shared signature with no entry must be RED BY NAME" }
            assertHit(audit(null), "missing") { "a config file that is absent must be RED" }
        }
    }

    @Test
    fun `the law can actually fail - unreasoned, undated and stale - V4-89`(@TempDir root: File) {
        with(Tree(root)) {
            write(PORTS to COMPLIANT_SOURCE)
            assertHit(audit(CONFIG_BLANK_REASON), "no reason") { "a whitespace reason must be RED" }
            assertHit(audit(CONFIG_NO_DATE), "no `dated`") { "an undated disposition must be RED" }
            assertHit(audit(CONFIG_STALE_NAME), "ClientVanished") { "a name no interface carries must be STALE" }
            assertHit(audit(CONFIG_STALE_ENTRY), "(Zork)->Zork") { "an entry nothing shares must be STALE" }
            assertHit(audit(CONFIG_OK + CONFIG_OK), "two entries dispose") { "one signature disposed twice is RED" }
        }
    }

    @Test
    fun `the law can actually fail - the vacuous and untrusted cases - V4-89`(@TempDir root: File) {
        with(Tree(root)) {
            write("Empty.kt" to "package splice.core\n\npublic class Nothing\n")
            assertHit(audit(CONFIG_OK), "refusing to pass vacuously") { "zero interfaces must not pass" }

            write(PORTS to DRIFT_SOURCE)
            assertHit(audit(CONFIG_OK), "disagree") { "a `fun interface` with no body must be UNTRUSTED" }

            // The BORING case: exactly one interface, no shared signature. Green on its own account,
            // and the shipped disposition for a group that is no longer there becomes STALE.
            write("One.kt" to BORING_SOURCE)
            assertHit(audit(CONFIG_OK), "STALE DISPOSITION") { "an entry for a vanished group must be STALE" }
            val census = census()
            assertEquals(emptyList<String>(), census.problems, "the one-interface parse must be trusted")
            assertEquals(1, census.roles.size)
            assertEquals(emptyMap<String, List<RoleRegistry.Role>>(), RoleRegistry.sharedGroups(census.roles))
            assertEquals(emptyList<String>(), audit(""), "one interface and an empty config must be GREEN")
        }
    }

    @Test
    fun `suspend and arity separate seams, and type parameters are positional - V4-89`(@TempDir root: File) {
        with(Tree(root)) {
            write(PORTS to COMPLIANT_SOURCE)
            val byName = census().roles.associate { it.name to it.signature }
            assertTrue(byName["Ticker"] != byName["PidAlive"]) {
                "suspend must separate Ticker from PidAlive; both normalised to ${byName["Ticker"]}"
            }
            assertEquals("()->String?", byName["IdentitySource"]) {
                "an interface with defaulted methods and nested types must yield its ONE abstract method"
            }

            // <T> and <R> are ONE group, so a rename cannot hide a duplicate.
            write("Generic.kt" to GENERIC_SOURCE)
            assertEquals(listOf("suspend ()->#1"), RoleRegistry.sharedGroups(census().roles).keys.toList())
            assertEquals(
                emptyList<String>(),
                audit(CONFIG_GENERIC),
                "the dispositioned generic twin must be GREEN",
            )
        }
    }

    /** The other half of the checker's proof: the SHIPPED tree and the SHIPPED dispositions,
     *  mutated. A fixture tree the law authored can drift away from the shape of the real files,
     *  and every arm above would stay green while the law stopped reading the tree. */
    @Test
    fun `the law can actually fail - against the shipped tree and dispositions - V4-89`(@TempDir root: File) {
        val config = RoleRegistry.declarations().orEmpty()
        assertTrue(config.contains(THREAD_SIGNATURE), "MUTATION NOT APPLIED: the (Thread)->Unit entry is gone")

        val seam = File(root, "zz-selftest/src/main/kotlin/splice/selftest/SelftestSeam.kt")
        seam.parentFile.mkdirs()
        seam.writeText(SYNTHETIC_SEAM)
        val grown = RoleRegistry.collect(KotlinText.kotlinFiles(map) + seam, map.root)
        val growth = RoleRegistry.audit(grown, shipped())
        assertHit(growth, "NO DISPOSITION: SelftestClientVanished", "()->Boolean") {
            "a new name joining a dispositioned group must be RED BY NAME, with its signature"
        }
        assertEquals(1, growth.count { it.contains("NO DISPOSITION") }, "exactly the synthetic name: $growth")

        val census = live()
        assertHit(audit(census, blankReason(config)), "carries no reason", THREAD_SIGNATURE) {
            "a shipped reason blanked to whitespace must be RED, naming its signature"
        }
        val absent = audit(census, dropEntry(config))
        assertHit(absent, "NO DISPOSITION: ShutdownHookAdd") { "a deleted entry must red every name under it" }
        assertHit(absent, "NO DISPOSITION: ShutdownHookRemove ") { "BOTH names, not just the first" }
        assertHit(audit(census, addStaleName(config)), "STALE DISPOSITION", "SelftestVanishedRole") {
            "a disposition naming an interface that no longer exists must be STALE BY NAME"
        }
    }

    private fun audit(census: RoleRegistry.Census, config: String): List<String> =
        RoleRegistry.audit(census, RoleRegistry.loadConfig(config, RoleRegistry.DECLARATIONS))

    /** The entry is still present, still lists every name, still dated. Only the words are gone. */
    private fun blankReason(config: String): String {
        val at = config.indexOf(THREAD_SIGNATURE)
        val start = config.indexOf(REASON_OPEN, at)
        val end = config.indexOf(TRIPLE, start + REASON_OPEN.length)
        return config.substring(0, start) + REASON_OPEN + "   " + TRIPLE + config.substring(end + TRIPLE.length)
    }

    private fun dropEntry(config: String): String {
        val at = config.indexOf(THREAD_SIGNATURE)
        val start = config.lastIndexOf(ENTRY_HEAD, at)
        val next = config.indexOf(ENTRY_HEAD, at)
        return config.substring(0, start) + config.substring(if (next < 0) config.length else next)
    }

    private fun addStaleName(config: String): String {
        val at = config.indexOf(THREAD_SIGNATURE)
        val close = config.indexOf(']', config.indexOf("names = [", at))
        return config.substring(0, close) + "    \"SelftestVanishedRole\",\n" + config.substring(close)
    }

    private companion object {
        const val MODULE = "core/src/main/kotlin/splice/core"
        const val PORTS = "Ports.kt"
        const val TRIPLE = "\"\"\""
        const val REASON_OPEN = "reason = \"\"\""
        const val ENTRY_HEAD = "[[groups]]"
        const val THREAD_SIGNATURE = "(Thread)->Unit"

        const val COMPLIANT_SOURCE = """package splice.core

/**
 * A KDoc that says `fun interface Decoy` in prose — the comment blanker must keep this out of the
 * denominator, and the parser-drift guard counts occurrences in the SAME blanked view.
 */
public fun interface ClientGone {
    public operator fun invoke(): Boolean
}

public fun interface ClientFrameEmitted {
    public operator fun invoke(): Boolean
}

/** A suspend seam of the "same" shape: proven NOT to join the group above. */
public fun interface Ticker {
    public suspend fun awaitTick(intervalMs: Long): Boolean
}

public fun interface PidAlive {
    public operator fun invoke(pid: Long): Boolean
}

/** One abstract method beside two DEFAULTED ones and two nested types — the shape that reads as
 *  seven `fun`s to a naive count (AccountCredentialIdentitySource). */
public fun interface IdentitySource {
    public fun identity(): String?

    public fun presence(): Presence = Presence.UNKNOWN

    public fun evidence(): Evidence {
        val id = identity()
        return Evidence(id)
    }

    public enum class Presence { PRESENT, UNKNOWN }

    public data class Evidence(public val id: String?) {
        public fun describe(): String = id ?: "none"
    }
}
"""

        const val GENERIC_SOURCE = """package splice.core

public fun interface CoalescedWork<T> {
    public suspend operator fun invoke(): T
}

public fun interface MaterializedRequest<R> {
    public suspend operator fun invoke(): R
}
"""

        const val BORING_SOURCE = """package splice.core

public fun interface Only {
    public operator fun invoke(): Boolean
}
"""

        const val SYNTHETIC_DUPLICATE = """
public fun interface ClientHungUp {
    public operator fun invoke(): Boolean
}
"""

        const val SYNTHETIC_SEAM = """package splice.selftest

/** A synthetic seam sharing () -> Boolean with the dispositioned names. */
public fun interface SelftestClientVanished {
    public operator fun invoke(): Boolean
}
"""

        // A `fun interface` the parser cannot account for: the drift guard must refuse the run.
        const val DRIFT_SOURCE = COMPLIANT_SOURCE + "\npublic fun interface Broken\n"

        const val CONFIG_OK = """[[groups]]
signature = "()->Boolean"
dated = "2026-09-17"
names = ["ClientFrameEmitted", "ClientGone"]
reason = "Read a few lines apart on the same retry path and driving OPPOSITE decisions."
"""

        const val CONFIG_GENERIC = """[[groups]]
signature = "suspend ()->#1"
dated = "2026-09-17"
names = ["CoalescedWork", "MaterializedRequest"]
reason = "One coalesces concurrent callers onto a single in-flight computation; the other materialises a request body once per turn."
"""

        val CONFIG_BLANK_REASON = CONFIG_OK.replace(
            "reason = \"Read a few lines apart on the same retry path and driving OPPOSITE decisions.\"",
            "reason = \"   \"",
        )
        val CONFIG_NO_DATE = CONFIG_OK.replace("dated = \"2026-09-17\"\n", "")
        val CONFIG_STALE_NAME = CONFIG_OK.replace(
            "names = [\"ClientFrameEmitted\", \"ClientGone\"]",
            "names = [\"ClientFrameEmitted\", \"ClientGone\", \"ClientVanished\"]",
        )
        const val CONFIG_STALE_ENTRY = CONFIG_OK + """
[[groups]]
signature = "(Zork)->Zork"
dated = "2026-09-17"
names = ["Gone", "Went"]
reason = "A signature no interface in the tree carries."
"""
    }
}
