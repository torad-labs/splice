// NEW: DR-140 — the DR-65 wall: no raw throwable text in a credential/state path
// (ported from checks/config/safe-failure-render.ts, restructure PR 6).
//
// THE CLASS. DR-65's law is "all failure rendering in credential/state paths goes through
// SafeFailureText.render". Nothing enforced it, so DR-73 swept the sinks BY HAND and its
// denominator was FILES rather than SINKS: UsageRingFile's read half was sealed and its write half
// kept its raw message for another eight days (DR-139). A hand sweep closes the instance; only a
// law closes the class. A credential path written tomorrow is in scope with no edit to this file.
//
// SCOPE IS CAUSAL, never a vocabulary guess. DR-65's hazard is an exception whose text quotes the
// bytes of the file that produced it, which is only reachable if the code TOUCHED FILES. So a
// source is in scope when it does filesystem I/O (SCOPE_IO) or names credential/state vocabulary
// (SCOPE_VOCAB, which covers a file that delegates its I/O to a collaborator). The first draft
// scoped on the vocabulary list ALONE and two reviews mutation-proved the hole within the hour:
// CodexAuthFile.kt and KimiOAuth.kt name no marker at all, so a raw render planted in either did
// not move the site count. Causality is checkable; a vocabulary list is a memory test.
//
// DENOMINATOR, FROM THE SOURCE (§24). The production universe is the BUILD's project map, walked
// through KotlinText.kotlinFiles and filtered by the module HOMES the checker's SOURCES globs
// name. Every site in an in-scope file is rolled, COMPLIANT ones included: scoring only the raw
// form would let the denominator SHRINK by one every time a site was fixed, so a tree could reach
// "0 undispositioned" by having no sites left to count — the disappearing denominator this wall
// exists to stop. routed + exempt + bad is the whole population every run.
//
// PARSE. Two views of every line, from ONE lexer with a STATE STACK, because every hole in this
// scanner traced back to reading structure off text that still held non-syntax: a URL's slashes
// inside a string ate a one-line failure lambda's closing brace; a brace inside a block comment or
// a char literal popped the depth early and a genuine render below went unseen; Kotlin block
// comments NEST, so a boolean flag exits at the inner closer; a string TEMPLATE hole is CODE, and
// blanking it hid a whole failure lambda; an escaped dollar is a literal, not an interpolation.
// `code` — strings, char literals and comments blanked, template holes KEPT — drives brace depth
// and decides which combinator governs a brace. `text` — comments blanked, string content KEPT —
// drives interpolation matching. Every branch emits as many characters as it consumes, so a column
// means the same thing in both views and in the raw line. On top of them sit two planes: FAILURE
// SPANS (per column, is a SHORT name bound to the throwable here) and THROWABLE BINDINGS (per
// column, which names mean a throwable here, innermost declaration winning).
//
// VIOLATIONS. Every site is COMPLIANT (routed through SafeFailureText.render) or EXEMPT (a dated,
// reasoned SAFE-RENDER-EXEMPT marker within 8 lines above). Absence is not a disposition: an
// undispositioned site fails BY NAME, and a blank, placeholder or under-30-character reason is an
// absence wearing a label and fails the same way.
//
// CLAIMED, anywhere in a scope file: an interpolation of an UNAMBIGUOUS throwable name, the
// non-interpolated spellings of the same renders on a receiver the named tier already trusts, the
// three members that exist only on Throwable, and any val bound from exceptionOrNull() at every
// column that binding is in scope for. CLAIMED conditionally: a SHORT name (it, e, t, ex, err)
// inside a failure lambda, at the column where it is still bound to that frame. NOT CLAIMED, and
// deliberately: getOrElse (overloaded), either half of a POSITIONAL fold (nothing lexical
// separates onSuccess from onFailure), a plain function parameter, a var (no flow typing), a local
// bound from .cause, and an IMPLICIT `it` inside a nested arrowless lambda — declaring it
// everywhere would green one residual and open a false positive on every forEach in a failure
// lambda, which is errors that HIDE renders instead of over-reporting them. Use a NAMED throwable
// and the wall sees it wherever it is.
package splice.quality

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

internal object SafeFailureRender {
    private val SCOPE_VOCAB = listOf(
        "SafeFailureText", "KeyStore", "KeyStorePath", "StatePaths", "SecureFile",
        "TopologyLoader", "CredentialJson", "Credentials", "MgmtKey", "LoginOutcomeFile",
        "JsonlSink",
    )
    private val SCOPE_VOCAB_TOKENS = SCOPE_VOCAB.map { it to Regex("\\b" + Regex.escape(it) + "\\b") }
    private val SCOPE_IO = listOf(
        Regex("java\\.nio\\.file"),
        Regex("\\bFiles\\."),
        Regex("\\bPath\\b"),
        Regex("FileChannel"),
        Regex("writeAtomic"),
        Regex("readString"),
    )

    // The checker's SOURCES globs, translated to the module HOMES they name: a literal directory,
    // or `<parent>/*` for a home whose modules are its direct children. The homes are matched
    // against the BUILD's project map, so a module is graded because the build declares it rather
    // than because a glob happened to reach its directory — and the file set is the same one the
    // globs yielded (744 files either way, diffed at the port).
    private val SOURCE_HOMES = listOf(
        "gateway/*", "client", "core", "upstream", "dialects/*",
        "providers/*", "daemon/*", "app", "quality/*",
    )

    // Identifiers that name a throwable, in TWO tiers, because `it` is Kotlin's UNIVERSAL lambda
    // parameter: treating it as a throwable everywhere flagged "Bearer $it" and nine more where it
    // is a String, and a wall that cries wolf gets its exemptions rubber-stamped.
    private const val THROWABLE_NAMED = "(?:cause|failure|throwable|\\w+(?:Failure|Error|Exception|Cause))"
    private const val THROWABLE_SHORT = "(?:it|e|t|ex|err)"
    private const val ESC_DOLLAR = "\\\$"

    // DR-157: `.fold(` came OUT — Result.fold takes TWO lambdas and the list is consulted only for
    // "was a failure combinator seen before this brace", so the FIRST (success) lambda matched.
    // DR-160: getOrElse came out too — it does not imply a Throwable receiver. exceptionOrNull
    // STAYED: its false positive was a control block opened as a lambda, fixed at the brace
    // decision, and deleting it would have cost `exceptionOrNull()?.let { … }`.
    private val FAILURE_CONTEXT = Regex("onFailure|exceptionOrNull|recoverCatching|recover\\b|\\bcatch\\s*\\(")

    // DR-160: a DECLARATION is not a call. `fun Result<Event>.onFailure(…)` matched the combinator
    // name and made a whole method body a failure span, so the pattern runs from `fun` to that
    // declaration's opening paren, covering receivers, generics and qualified names alike.
    private val FUN_DECL = Regex("\\bfun\\b[^(\\n]*\\(")

    // DR-159: a `{` that opens a CONTROL BLOCK versus one that opens a LAMBDA. A nested lambda
    // REBINDS `it`; a nested control block does not. A segment ending in `->` is a when-branch
    // arrow, which is a block; a lambda's parameter arrow appears AFTER its own brace.
    private val CONTROL_HEAD = Regex(
        "(?:\\belse|\\btry|\\bfinally|\\bdo|\\binit|\\bwhen|->)\\s*\$" +
            "|\\b(?:if|while|for|when|catch)\\s*\\(.*\\)\\s*\$",
    )

    // A throwable rendered INTO TEXT. Both interpolation forms, because the BARE one is strictly
    // WORSE: `$failure` calls toString(), the class name PLUS the same message.
    private val RENDERED = Regex(
        ESC_DOLLAR + "\\{[^}]*\\.message[^}]*\\}" +
            "|" + ESC_DOLLAR + "\\{\\s*" + THROWABLE_NAMED + "\\s*\\}" +
            "|" + ESC_DOLLAR + THROWABLE_NAMED + "\\b",
    )
    private val RENDERED_SHORT = Regex(
        ESC_DOLLAR + "\\{\\s*" + THROWABLE_SHORT + "\\s*\\}" + "|" + ESC_DOLLAR + THROWABLE_SHORT + "\\b",
    )

    // DR-170: the three members that exist ONLY on Throwable, so unlike `.message` the receiver's
    // type is not in doubt. Unconditional, and matched inside a string or not: a bare
    // printStackTrace() reaches the same sink with the same bytes as the interpolated form.
    private val THROWABLE_TEXT_MEMBER =
        Regex("\\.(?:stackTraceToString\\s*\\(|printStackTrace\\s*\\(|localizedMessage\\b)")

    // DR-187: every matcher above keys on a `$`, so `put("read_error", failure.message)` matched
    // NOTHING — and that is the DR-65 sink shape verbatim, since the compliant sites spell it
    // `put("read_error", render(failure))`. The RECEIVER carries the type evidence. The implicit
    // spellings are here too because String.plus and Appendable.append call toString(). The 2026-09-01
    // gap: the matcher was single-hop, so `failure.cause?.message` was invisible — zero or more
    // `receiver?.` hops may now precede the throwable-named segment.
    private const val TEXT_MEMBER = "(?:message\\b|toString\\s*\\()"
    private const val DOT = "\\s*\\??\\s*\\."
    private const val IMPLICIT = "(?:\"\\s*\\+\\s*|\\+\\s*\"|\\bappend\\s*\\(\\s*)"
    private const val HOPS = "(?:\\w+\\s*\\??\\s*\\.\\s*)*"
    private const val NOT_AFTER = "(?<![" + ESC_DOLLAR + "\\w.])"
    private val RENDERED_DIRECT = Regex(
        NOT_AFTER + HOPS + THROWABLE_NAMED + DOT + TEXT_MEMBER +
            "|" + IMPLICIT + THROWABLE_NAMED + "\\b(?![\\w.(])",
    )
    private val RENDERED_DIRECT_SHORT = Regex(
        NOT_AFTER + HOPS + THROWABLE_SHORT + DOT + TEXT_MEMBER +
            "|" + IMPLICIT + THROWABLE_SHORT + "\\b(?![\\w.(])",
    )
    private val UNCONDITIONAL = listOf(THROWABLE_TEXT_MEMBER, RENDERED, RENDERED_DIRECT)
    private val SHORT_FORMS = listOf(RENDERED_SHORT, RENDERED_DIRECT_SHORT)

    // DR-160 round 2: a segment must end at a STATEMENT boundary, not only at a brace. A newline
    // ends the statement unless the expression is still open. ROUND 3: these are tested against the
    // TEXT view, because the code view blanks string content and `val e = "event"` would end on `=`
    // and swallow the next line.
    private val CONTINUES = Regex("[.,=+\\-*/%&|?:<>(\\[{]\$|->\$|\\b(?:and|or)\$")
    private val CONTINUED_BY = Regex("^\\s*[.)\\]}]|^\\s*\\?\\.")

    // A throwable BOUND TO A LOCAL, which has no lambda and therefore no failure frame at all.
    // `.cause` and `var` are NOT sources: neither is typed, and without flow typing neither claim
    // can be honoured. EVERY declaration is recorded, throwable or not, because a shadow is just a
    // binding that is not a throwable — which is also how the language sees it.
    private val BINDING_HEAD = Regex("\\b(?:val|var)\\s+(\\w+)\\s*(?::[^=]+)?=\\s*(?![=])")
    private val THROWABLE_SOURCE = Regex("\\bexceptionOrNull\\s*\\(")
    private val FOR_PARAM = Regex("\\bfor\\s*\\(\\s*(?:val\\s+|var\\s+)?(\\w+)(?:\\s*:[^)]*?)?\\s+in\\b")
    private val CATCH_PARAM = Regex("\\bcatch\\s*\\(\\s*(\\w+)\\s*:")
    private val IDENTIFIER = Regex("\\w+")

    // `else ->` is a when-branch arrow, not a parameter list, and the one branch head that looks
    // exactly like a single bare name. `is Foo ->` is rejected by IDENTIFIER before this is read.
    private val NOT_A_PARAM = setOf("else", "is", "in", "when", "true", "false", "null", "this")

    // ...but a keyword list is not enough: `when (k) { e -> … }` COMPARES the subject to the value
    // e, it does not declare one. The two arrows are only separable by the block they sit in, so
    // the block remembers whether a `when` head opened it.
    private val WHEN_BLOCK = Regex("\\bwhen\\s*(?:\\([^)]*\\))?\\s*\$")

    // On restoring an interrupted statement the lambda body comes back with it, and a declaration
    // made INSIDE that body was already recorded at its own inner depth. The keywords are blanked
    // so the body can still be read for compliance evidence without re-declaring anything.
    private val INNER_DECL = Regex("\\b(?:val|var)\\b")

    /** A site that already obeys the law, enumerated DELIBERATELY even though it can never violate. */
    private val COMPLIANT = Regex("SafeFailureText\\.render\\(")

    /** Any interpolated identifier, so the binding tier can be asked "does THIS name mean a
     *  throwable at THIS column" in one pass rather than once per known name. */
    private val INTERPOLATED_NAME = Regex(
        ESC_DOLLAR + "\\{\\s*(\\w+)\\s*\\}" + "|" + ESC_DOLLAR + "(\\w+)\\b",
    )

    /** The disposition marker: dated so a review can age it, reasoned so it can be judged. */
    private val EXEMPT = Regex("SAFE-RENDER-EXEMPT\\[(\\d{4}-\\d{2}-\\d{2})\\]:[ \\t]*(.*)")
    private val PLACEHOLDER =
        Regex("^(todo|tbd|fixme|n/?a|none|safe|ok|fine|why|reason|\\.+|-+|\\?+)\\b", RegexOption.IGNORE_CASE)
    private val TRAILING_BLOCK_CLOSE = Regex("\\*/\$")

    /** How far above a site the marker may sit — a rendered string often spans a wrapped
     *  multi-line log call, so the comment explaining it is not always on the matched line. */
    private const val EXEMPT_LOOKBACK = 8
    private const val MIN_REASON_CHARS = 30
    private const val SINK_EXCERPT = 110

    const val COMPLIANT_VERDICT = "compliant"
    private const val EXEMPT_VERDICT = "exempt"
    private const val BAD_VERDICT = "bad"

    private const val BLOCK = "block"
    private const val STR = "str"
    private const val RAW = "raw"
    private const val CHAR = "char"
    private const val INTERP = "interp"
    private const val RAW_QUOTE = "\"\"\""
    private const val TEMPLATE_OPEN = "\${"

    /** The two views of one source. `code` sees structure and never string CONTENT; `text` sees
     *  content and never COMMENTS. Interpolation code appears in BOTH, because it is simultaneously
     *  real code and inside a string. */
    private data class Views(val code: List<String>, val text: List<String>)

    /** One frame of the lexer's stack: what it is, and its own nesting count (block comments NEST,
     *  and a template hole counts the braces inside it). */
    private data class Frame(val kind: String, val nesting: Int)

    /** The two views being built for one line. Every method appends as many characters to each as
     *  the caller consumed, which is what makes a column mean the same thing in both. */
    private class Painter(val code: StringBuilder, val text: StringBuilder) {
        fun blank(width: Int) = repeat(width) {
            code.append(' ')
            text.append(' ')
        }

        fun both(s: String) {
            code.append(s)
            text.append(s)
        }

        /** Blanked in the code view, kept verbatim in the text view. */
        fun reveal(raw: String, at: Int, width: Int) {
            repeat(width) { code.append(' ') }
            text.append(raw, at, at + width)
        }
    }

    /** ONE lexer with a STATE STACK, because Kotlin nests string-in-template-in-string arbitrarily
     *  and a flat in/out flag cannot express any of it. */
    private class Lexer {
        private val stack = mutableListOf<Frame>()

        fun views(lines: List<String>): Views {
            val code = mutableListOf<String>()
            val text = mutableListOf<String>()
            for (raw in lines) {
                val out = Painter(StringBuilder(), StringBuilder())
                var i = 0
                while (i < raw.length) i = step(raw, i, out)
                code += out.code.toString()
                text += out.text.toString()
            }
            return Views(code, text)
        }

        private fun step(raw: String, i: Int, out: Painter): Int = when (stack.lastOrNull()?.kind) {
            BLOCK -> inBlockComment(raw, i, out)
            STR, RAW, CHAR -> inLiteral(raw, i, out)
            else -> inCode(raw, i, out)
        }

        /** Kotlin block comments NEST: a boolean flag exits at the INNER closer and reads the rest
         *  of the outer comment as code, whose braces then pop the real depth. */
        private fun inBlockComment(raw: String, i: Int, out: Painter): Int {
            val nesting = stack.last().nesting
            val opens = raw.startsWith("/*", i)
            val closes = raw.startsWith("*/", i)
            if (opens) stack[stack.lastIndex] = Frame(BLOCK, nesting + 1)
            if (closes) sink(nesting - 1 != 0, Frame(BLOCK, nesting - 1))
            val width = if (opens || closes) 2 else 1
            out.blank(width)
            return i + width
        }

        /** Replaces the innermost frame with [inner] when [keep], and drops it otherwise. The two
         *  callers test DIFFERENT counters: a block comment opens at 1 and a template hole at 0. */
        private fun sink(keep: Boolean, inner: Frame) {
            if (keep) stack[stack.lastIndex] = inner else stack.removeAt(stack.lastIndex)
        }

        private fun closerOf(kind: String): String = when (kind) {
            RAW -> RAW_QUOTE
            CHAR -> "'"
            else -> "\""
        }

        private fun inLiteral(raw: String, i: Int, out: Painter): Int {
            val kind = stack.last().kind
            val closer = closerOf(kind)
            val escapable = kind != RAW && raw[i] == '\\'
            val escaping = escapable && i + 1 < raw.length
            return when {
                escaping -> escape(raw, i, out)
                raw.startsWith(TEMPLATE_OPEN, i) -> openTemplate(out, i)
                raw.startsWith(closer, i) -> closeLiteral(raw, i, out, closer)
                else -> {
                    out.reveal(raw, i, 1)
                    i + 1
                }
            }
        }

        /** An escape. `\$` in particular is a LITERAL dollar and must not read as an interpolation
         *  in the text view. */
        private fun escape(raw: String, i: Int, out: Painter): Int {
            out.code.append("  ")
            if (raw[i + 1] == '$') out.text.append("  ") else out.text.append(raw, i, i + 2)
            return i + 2
        }

        private fun openTemplate(out: Painter, i: Int): Int {
            stack += Frame(INTERP, 0)
            out.both(TEMPLATE_OPEN)
            return i + TEMPLATE_OPEN.length
        }

        private fun closeLiteral(raw: String, i: Int, out: Painter, closer: String): Int {
            stack.removeAt(stack.lastIndex)
            out.reveal(raw, i, closer.length)
            return i + closer.length
        }

        /** Top-level code, or the inside of a template hole — both are real code. */
        private fun inCode(raw: String, i: Int, out: Painter): Int {
            val opened = opener(raw, i, out)
            if (opened != null) return opened
            val brace = templateBrace(raw, i, out)
            if (brace != null) return brace
            out.both(raw[i].toString())
            return i + 1
        }

        private fun opener(raw: String, i: Int, out: Painter): Int? = when {
            raw.startsWith("//", i) -> {
                out.blank(raw.length - i)
                raw.length
            }
            raw.startsWith("/*", i) -> {
                stack += Frame(BLOCK, 1)
                out.blank(2)
                i + 2
            }
            raw.startsWith(RAW_QUOTE, i) -> {
                stack += Frame(RAW, 0)
                out.reveal(raw, i, RAW_QUOTE.length)
                i + RAW_QUOTE.length
            }
            raw[i] == '"' -> {
                stack += Frame(STR, 0)
                out.reveal(raw, i, 1)
                i + 1
            }
            raw[i] == '\'' -> {
                stack += Frame(CHAR, 0)
                out.reveal(raw, i, 1)
                i + 1
            }
            else -> null
        }

        /** A template hole's own braces, which close it only when its nesting has run out. */
        private fun templateBrace(raw: String, i: Int, out: Painter): Int? {
            val frame = stack.lastOrNull() ?: return null
            if (frame.kind != INTERP) return null
            return when (raw[i]) {
                '{' -> {
                    stack[stack.lastIndex] = Frame(INTERP, frame.nesting + 1)
                    out.both("{")
                    i + 1
                }
                '}' -> {
                    sink(frame.nesting != 0, Frame(INTERP, frame.nesting - 1))
                    out.both("}")
                    i + 1
                }
                else -> null
            }
        }
    }

    /** Per line: is the statement still open across the newline that follows it? */
    private fun continuations(textLines: List<String>): List<Boolean> = textLines.mapIndexed { i, txt ->
        val next = textLines.getOrElse(i + 1) { "" }
        CONTINUES.containsMatchIn(txt.trimEnd()) || CONTINUED_BY.containsMatchIn(next)
    }

    /** One open lambda: the brace depth it belongs to, and whether it binds a throwable. */
    private data class Span(val depth: Int, val failure: Boolean)

    /** Per line and COLUMN: may a SHORT name be read as the throwable here?
     *
     *  Structural, not a fixed lookback — a real onFailure whose nested cleanup pushes the render
     *  five lines below the opener stayed GREEN under a 3-line window, and widening the constant
     *  only moves the hole into the next nested block. DR-157 made the combinator/brace pairing
     *  POSITIONAL, and DR-160 replaced a single shadow depth with a FRAME STACK: the innermost
     *  binder wins, which is what the language's own scoping rule looks like. */
    private class Spans {
        private val frames = mutableListOf<Span>()
        private val segment = StringBuilder()
        private var depth = 0

        fun of(views: Views, cont: List<Boolean>): List<BooleanArray> {
            val out = mutableListOf<BooleanArray>()
            views.code.forEachIndexed { i, code ->
                val flags = BooleanArray(code.length)
                for (col in code.indices) {
                    advance(code[col])
                    flags[col] = frames.lastOrNull()?.failure == true
                }
                if (cont[i]) segment.append('\n') else segment.setLength(0)
                out += flags
            }
            return out
        }

        /** One character of the code view: `{` opens a frame, `}` pops every frame it encloses,
         *  `;` ends the statement, anything else extends it. */
        private fun advance(ch: Char) {
            when (ch) {
                '{' -> {
                    depth += 1
                    val kind = frameKind(segment.toString())
                    if (kind != null) frames += Span(depth, kind)
                    segment.setLength(0)
                }
                '}' -> {
                    while (frames.isNotEmpty() && frames.last().depth == depth) frames.removeAt(frames.lastIndex)
                    depth = maxOf(0, depth - 1)
                    segment.setLength(0)
                }
                ';' -> segment.setLength(0)
                else -> segment.append(ch)
            }
        }

        /** What a `{` opens: true a failure lambda, false a shadowing lambda, null a transparent
         *  control block. FAILURE is tested FIRST and that order is load-bearing: `catch (e: X) {`
         *  matches both lists, and when the control test won, catch could not open a failure frame
         *  at all — while the published coverage statement named it. */
        private fun frameKind(segment: String): Boolean? {
            if (FAILURE_CONTEXT.containsMatchIn(FUN_DECL.replace(segment, " "))) return true
            val rs = segment.trimEnd()
            return if (CONTROL_HEAD.containsMatchIn(rs.ifEmpty { " " })) null else false
        }
    }

    /** One declared local: its spelling, the brace depth it belongs to, and whether it means a
     *  throwable. A shadow is a binding that is not one, which is how the language sees it too. */
    private data class Local(val name: String, val depth: Int, val throwable: Boolean)

    /** A statement a `{` INTERRUPTED rather than ended: `val e = runCatching { … }
     *  .exceptionOrNull()` is ONE binding, and resetting at the brace lost its head. The head is
     *  stashed with the depth whose closing brace resumes it, carrying the block body with it so
     *  the compliance test can still see a sanitizer call inside the lambda. */
    private data class Interrupted(
        val head: String,
        val depth: Int,
        val outerDepth: Int,
        val body: StringBuilder,
        val whenHead: Boolean,
    )

    /** The names an explicit parameter list declares. PARSED rather than matched, because a type
     *  can contain both commas and brackets and no flat pattern splits that correctly. */
    private object ParamList {
        /** The names [text] declares, or null when it is not a parameter list at all — which
         *  REJECTS THE WHOLE LIST. That is the fail-closed direction: an undeclared name is
         *  reported, a wrongly declared one is hidden. */
        fun names(text: String): List<String>? {
            val found = mutableListOf<String>()
            for (raw in splitTop(text, ',')) {
                val item = splitTop(raw, ':')[0].trim()
                // a trailing comma declares nothing, and neither does an empty list
                if (item.isEmpty()) continue
                found += components(item) ?: return null
            }
            return found
        }

        /** One entry: a destructure's components, a bare identifier, or null when unreadable. */
        private fun components(item: String): List<String>? {
            val destructured = item.startsWith("(") && item.endsWith(")")
            return when {
                destructured -> names(item.substring(1, item.length - 1))
                IDENTIFIER.matches(item) -> listOf(item)
                else -> null
            }
        }

        /** [text] split on [sep] at bracket depth zero, counting `()` and `<>` alike: a type's own
         *  commas and colons belong to the type. */
        fun splitTop(text: String, sep: Char): List<String> {
            val parts = mutableListOf<String>()
            val item = StringBuilder()
            var depth = 0
            for (ch in text) {
                val opens = ch == '(' || ch == '<'
                val closes = ch == ')' || ch == '>'
                if (opens) depth += 1 else if (closes) depth = maxOf(0, depth - 1)
                val separates = ch == sep && depth == 0
                if (separates) {
                    parts += item.toString()
                    item.setLength(0)
                } else {
                    item.append(ch)
                }
            }
            parts += item.toString()
            return parts
        }
    }

    /** Per line, per COLUMN: the names that mean a throwable AT that column.
     *
     *  Scope is the language's, not the line's: declarations push, a closing brace pops everything
     *  it encloses, and a lookup takes the INNERMOST declaration of a name. Round 2 computed
     *  structure per column and REPORTED it per line, and that inversion cut both ways at once — a
     *  binding used on the line its block closes was invisible, and an inner `val e = "event"`
     *  beside its use could not hide an outer throwable `e`. A name's meaning is a property of a
     *  POSITION, not of a line. */
    private class Binder {
        private var live = mutableListOf<Local>()
        private val stash = mutableListOf<Interrupted>()
        private val segment = StringBuilder()
        private var depth = 0
        private var segDepth = 0
        private var visible: Set<String> = emptySet()
        private var before = 0

        fun walk(views: Views, cont: List<Boolean>): List<List<Set<String>>> {
            val out = mutableListOf<List<Set<String>>>()
            views.code.forEachIndexed { i, code ->
                val perCol = mutableListOf<Set<String>>()
                before = live.size
                for (ch in code) {
                    refresh()
                    perCol += visible
                    step(ch)
                }
                endOfLine(cont[i])
                if (live.size != before) visible = visibleOf(live)
                out += perCol
            }
            return out
        }

        private fun refresh() {
            if (live.size != before) {
                visible = visibleOf(live)
                before = live.size
            }
        }

        private fun endOfLine(continues: Boolean) {
            if (continues) {
                segment.append('\n')
            } else {
                register(segment.toString(), segDepth)
                segment.setLength(0)
                segDepth = depth
            }
        }

        private fun step(ch: Char) {
            val arrow = ch == '>' && segment.endsWith("-")
            when {
                ch == '{' -> openBrace()
                ch == '}' -> closeBrace()
                ch == ';' -> endStatement()
                arrow -> lambdaArrow()
                else -> extend(ch)
            }
        }

        private fun openBrace() {
            val head = segment.toString()
            // for/catch parameters belong to the block the brace opens, not to the head.
            declareParams(head, depth + 1)
            register(head, segDepth)
            stash += Interrupted(head, segDepth, depth, StringBuilder(), WHEN_BLOCK.containsMatchIn(head.trimEnd()))
            depth += 1
            segment.setLength(0)
            segDepth = depth
        }

        private fun closeBrace() {
            // Register the block's own trailing statement BEFORE the depth drops, so it is recorded
            // at the inner depth and the prune below takes it back out again.
            register(segment.toString(), segDepth)
            depth = maxOf(0, depth - 1)
            live = live.filter { it.depth <= depth }.toMutableList()
            val resumed = stash.lastOrNull()?.takeIf { it.outerDepth == depth }
            segment.setLength(0)
            if (resumed == null) {
                segDepth = depth
            } else {
                stash.removeAt(stash.lastIndex)
                segment.append(resumed.head).append(" { ")
                    .append(INNER_DECL.replace(resumed.body.toString(), "   ")).append(" } ")
                segDepth = resumed.depth
            }
        }

        private fun endStatement() {
            register(segment.toString(), segDepth)
            segment.setLength(0)
            segDepth = depth
        }

        /** `{ e -> … }` declares e for this block; a when BRANCH arrow declares nothing, so the
         *  enclosing block decides which arrow this is. */
        private fun lambdaArrow() {
            val branch = stash.lastOrNull()?.whenHead == true
            if (!branch) declareLambda(segment.substring(0, segment.length - 1), depth)
            segment.setLength(0)
            segDepth = depth
        }

        private fun extend(ch: Char) {
            segment.append(ch)
            for (frame in stash) frame.body.append(ch)
        }

        /** Record every local declared by one completed statement, at [at]. A declaration whose
         *  right-hand side is an unsanitized exceptionOrNull() means a throwable; every other one
         *  is a SHADOW. The window is the binding's OWN whole right-hand side, because the
         *  sanitizer call sits past the combinator, inside the lambda. */
        private fun register(statement: String, at: Int) {
            val heads = BINDING_HEAD.findAll(statement).toList()
            heads.forEachIndexed { n, head ->
                val stop = if (n + 1 < heads.size) heads[n + 1].range.first else statement.length
                val rhs = statement.substring(head.range.last + 1, stop)
                // `var` can be reassigned to a String with no flow typing to catch it.
                val declared = head.value.trimStart().startsWith("val")
                val raw = THROWABLE_SOURCE.containsMatchIn(rhs) && !COMPLIANT.containsMatchIn(rhs)
                live += Local(head.groupValues[1], at, declared && raw)
            }
        }

        /** `for (e in xs)` and `catch (e: IOException)` declare e for the block their head
         *  precedes. A catch parameter IS a throwable, and saying so here is what lets it shadow
         *  correctly rather than merely being covered by the short-name tier. */
        private fun declareParams(head: String, at: Int) {
            for (found in FOR_PARAM.findAll(head)) live += Local(found.groupValues[1], at, false)
            for (found in CATCH_PARAM.findAll(head)) live += Local(found.groupValues[1], at, true)
        }

        /** An EXPLICIT parameter list only — the arrow is what makes it decidable. An arrowless
         *  lambda's implicit `it` never reaches here and is deliberately not declared. */
        private fun declareLambda(head: String, at: Int) {
            for (name in ParamList.names(head).orEmpty()) {
                if (name !in NOT_A_PARAM) live += Local(name, at, false)
            }
        }

        /** The names that mean a throwable, taking the INNERMOST declaration of each. */
        private fun visibleOf(locals: List<Local>): Set<String> {
            val innermost = linkedMapOf<String, Boolean>()
            for (local in locals) innermost[local.name] = local.throwable
            return innermost.filterValues { it }.keys
        }
    }

    /** Everything one file is judged through, computed once: its lines, the text view, the
     *  failure-span plane and the binding plane. The TypeScript threaded these as four optional
     *  parameters recomputed on demand; one holder is the same values with a clear contract. */
    private data class Lens(
        val lines: List<String>,
        val text: List<String>,
        val spans: List<BooleanArray>,
        val bindings: List<List<Set<String>>>,
    )

    private fun lens(source: String): Lens {
        val lines = KotlinText.splitLines(source)
        val views = Lexer().views(lines)
        val cont = continuations(views.text)
        return Lens(lines, views.text, Spans().of(views, cont), Binder().walk(views, cont))
    }

    /** Does line [idx] render a throwable into text?
     *
     *  Reads the COMMENT-BLANKED view, not the raw line: a trailing comment explaining the law is
     *  prose and cannot interpolate anything at runtime. String content is deliberately PRESERVED,
     *  because a real interpolation lives inside a string by definition. */
    private fun rendersThrowable(lens: Lens, idx: Int): Boolean {
        val line = lens.text[idx]
        if (UNCONDITIONAL.any { it.containsMatchIn(line) }) return true
        if (boundNameRendered(line, lens.bindings[idx])) return true
        return shortNameRendered(line, lens.spans[idx])
    }

    /** A bound name, at the MATCH's own column — a name can be declared, shadowed or closed out of
     *  scope partway along one line. */
    private fun boundNameRendered(line: String, perCol: List<Set<String>>): Boolean =
        INTERPOLATED_NAME.findAll(line).any { hit ->
            val name = hit.groupValues[1].ifEmpty { hit.groupValues[2] }
            val col = hit.range.first
            col < perCol.size && perCol[col].contains(name)
        }

    /** The short tier, frame-scoped, each spelling judged at its OWN column so a line carrying one
     *  of each cannot have the second silently decided by the first one's position. */
    private fun shortNameRendered(line: String, flags: BooleanArray): Boolean =
        SHORT_FORMS.mapNotNull { it.find(line) }.any { hit ->
            hit.range.first < flags.size && flags[hit.range.first]
        }

    /** COMPLIANT / EXEMPT / the failure reason for one site. */
    private data class Verdict(val verdict: String, val detail: String?)

    /** A raw render is judged raw even on a line that ALSO calls the sanitizer: one sanitized half
     *  never launders the other, so a mixed line still needs its own exemption. */
    private fun disposition(lens: Lens, idx: Int): Verdict {
        if (!rendersThrowable(lens, idx)) return Verdict(COMPLIANT_VERDICT, null)
        for (back in idx downTo maxOf(0, idx - EXEMPT_LOOKBACK)) {
            val found = EXEMPT.find(lens.lines[back]) ?: continue
            return exemption(found)
        }
        return Verdict(BAD_VERDICT, "renders a throwable raw with no SafeFailureText.render and no exemption")
    }

    private fun exemption(found: MatchResult): Verdict {
        val reason = TRAILING_BLOCK_CLOSE.replace(found.groupValues[2].trim(), "").trim()
        val placeholder = reason.isEmpty() || PLACEHOLDER.containsMatchIn(reason)
        return when {
            placeholder -> Verdict(
                BAD_VERDICT,
                "exemption reason is a placeholder, not a disposition: ${KotlinText.pyRepr(reason)}",
            )
            reason.length < MIN_REASON_CHARS -> Verdict(
                BAD_VERDICT,
                "exemption reason is ${reason.length} chars, under the $MIN_REASON_CHARS-char floor: " +
                    KotlinText.pyRepr(reason),
            )
            else -> Verdict(EXEMPT_VERDICT, found.groupValues[1])
        }
    }

    /** Why this file is in scope — credential vocabulary and/or file I/O — or empty if it is not. */
    private fun inScope(source: String): List<String> {
        val why = SCOPE_VOCAB_TOKENS.filter { it.second.containsMatchIn(source) }.map { it.first }
        return if (SCOPE_IO.any { it.containsMatchIn(source) }) why + "file-io" else why
    }

    /** One rendered-throwable — or already-routed — site with its disposition. */
    data class Site(
        val path: String,
        val line: Int,
        val text: String,
        val verdict: String,
        val detail: String?,
        val markers: List<String>,
    )

    /** Every site under a credential/state file, with its disposition, in the roll's path order. */
    fun sites(files: List<File>, root: File): List<Site> =
        files.flatMap { file -> fileSites(file.relativeTo(root).invariantSeparatorsPath, file.readText()) }

    private fun fileSites(path: String, source: String): List<Site> {
        val markers = inScope(source)
        if (markers.isEmpty()) return emptyList()
        val lens = lens(source)
        return lens.lines.indices.mapNotNull { idx -> site(lens, idx, path, markers) }
    }

    private fun site(lens: Lens, idx: Int, path: String, markers: List<String>): Site? {
        val rendered = rendersThrowable(lens, idx)
        val routed = COMPLIANT.containsMatchIn(lens.text[idx])
        if (!rendered && !routed) return null
        val verdict = disposition(lens, idx)
        return Site(path, idx + 1, lens.lines[idx].trim(), verdict.verdict, verdict.detail, markers)
    }

    private fun isSourceHome(dir: String): Boolean = SOURCE_HOMES.any { home ->
        if (home.endsWith("/*")) dir.substringBeforeLast('/', "") == home.dropLast(2) else dir == home
    }

    /** Every production source the checker's SOURCES list names, read off the BUILD's map. */
    fun sources(map: ProjectMap): List<File> {
        val homes = map.modules.map { map.relativeDir(it) }.filter(::isSourceHome).map { "$it/" }
        return KotlinText.kotlinFiles(map, "src/main")
            .map { KotlinText.rel(map, it) to it }
            .filter { (rel, _) -> homes.any { rel.startsWith(it) } }
            .sortedBy { it.first }
            .map { it.second }
    }

    /** The undispositioned sites as the lines the checker's `check` verb prints: the blame, the
     *  sink text, and why the file is in scope. An undispositioned site fails BY NAME. */
    fun violations(found: List<Site>): List<String> = found.filter { it.verdict == BAD_VERDICT }.map { site ->
        "${site.path}:${site.line}: DR-65 violation — ${site.detail}\n" +
            "    ${site.text.take(SINK_EXCERPT)}\n" +
            "    in scope via: ${site.markers.joinToString(", ")}"
    }
}

/** One arm of checks/safe-failure-render-selftest.sh, ported verbatim: the label it fails under,
 *  the fixture file it writes, the verdict it claims and the body. [blamed] is [GREEN] for an `arm`
 *  expecting rc=0, [RED] for one expecting rc=1, and — for an `arm_at` — the line the blame must
 *  name, because an exit code alone cannot tell a correct verdict from a wrong one that happens to
 *  be non-zero: DR-157's fold fixture failed under BOTH the old and the new checker, the old one
 *  blaming the SUCCESS lambda it had misidentified. */
private data class Arm(val label: String, val file: String, val blamed: Int, val body: String)

private const val GREEN = -1
private const val RED = 0

class SafeFailureRenderLawTest {
    private val map = ProjectMap.fromSystemProperties()

    @Test
    fun `every throwable rendered into text in a credential or state source is dispositioned - DR-65`() {
        val files = SafeFailureRender.sources(map)
        assertTrue(files.size > VACUITY_FILES) {
            "the map yielded ${files.size} production file(s) — the walk is broken, and a law that reads no files " +
                "passes vacuously."
        }
        val roll = SafeFailureRender.sites(files, map.root)
        assertTrue(roll.size > VACUITY_SITES) {
            "the roll holds ${roll.size} site(s) over ${files.size} file(s) — a denominator that small means the " +
                "scan has lost the tree, and `0 undispositioned` over nothing is the green this wall exists to stop."
        }
        val problems = SafeFailureRender.violations(roll)
        assertTrue(problems.isEmpty()) {
            problems.joinToString(separator = "\n  - ", prefix = "SAFE FAILURE RENDER (DR-65) violated:\n  - ")
        }
    }

    /** The synthetic tree the red proofs write into. ONE fixture is on disk at a time, exactly as
     *  the selftest's `arm` writes and then removes one file per arm, so no arm can be decided by
     *  another's leftovers. `:console` is mapped and is NOT one of the checker's SOURCE homes,
     *  which is what makes the glob-to-map translation provable rather than asserted. */
    private class Tree(val root: File) {
        val map: ProjectMap = ProjectMap.parse(root, ":app=app;:console=console", setOf("build"))

        fun census(): List<SafeFailureRender.Site> = SafeFailureRender.sites(SafeFailureRender.sources(map), root)

        /** Writes one file into the tree and leaves it there. */
        fun place(rel: String, body: String) {
            val file = File(root, rel)
            file.parentFile.mkdirs()
            file.writeText(body)
        }

        /** The selftest's `arm`: write the fixture, grade the whole tree, remove it again. */
        fun blame(arm: Arm): List<String> {
            place("$PROBE_DIR/${arm.file}", arm.body + "\n")
            val hits = SafeFailureRender.violations(census())
            File(root, "$PROBE_DIR/${arm.file}").delete()
            return hits
        }

        fun prove(arms: List<Arm>) {
            for (arm in arms) verdict(arm, blame(arm))
        }

        private fun verdict(arm: Arm, hits: List<String>) = when (arm.blamed) {
            GREEN -> assertEquals(emptyList<String>(), hits, "${arm.label}: the compliant form must stay GREEN")
            RED -> assertTrue(hits.isNotEmpty()) { "${arm.label}: the violation must be RED" }
            else -> assertHit(hits, "${arm.file}:${arm.blamed}:", BLAME) {
                "${arm.label}: the blame must name line ${arm.blamed}"
            }
        }
    }

    /** THE BORING CASE — the smallest tree this wall can grade — and the two ways a file leaves the
     *  denominator. A routed site STAYS in the roll, because scoring only the raw form would let the
     *  count shrink by one every time a site was fixed. */
    @Test
    fun `the wall can actually fail - the denominator - DR-140`(@TempDir root: File) {
        with(Tree(root)) {
            place("$PROBE_DIR/Boring.kt", BORING_ROUTED)
            val routed = census()
            assertEquals(1, routed.size, "the smallest denominator is ONE routed site, got: $routed")
            assertEquals(SafeFailureRender.COMPLIANT_VERDICT, routed[0].verdict, routed[0].toString())
            assertEquals(emptyList<String>(), SafeFailureRender.violations(routed), "a routed site is not a violation")

            place("$PROBE_DIR/Boring.kt", BORING_RAW)
            val raw = census()
            assertEquals(1, raw.size, "the same site raw must still be ONE site, got: $raw")
            assertHit(SafeFailureRender.violations(raw), "Boring.kt:4:", BLAME) { "the one site must be RED by name" }

            // A file with neither file I/O nor credential vocabulary is not in the denominator, and
            // neither is a module the checker's SOURCES homes never named — the only thing the
            // glob-to-map translation can get wrong.
            place("$PROBE_DIR/Pure.kt", OUT_OF_SCOPE)
            place(OUTSIDE_HOME, BORING_RAW)
            assertTrue(File(root, OUTSIDE_HOME).isFile, "the out-of-home fixture must exist to prove anything")
            assertEquals(
                listOf("$PROBE_DIR/Boring.kt", "$PROBE_DIR/Pure.kt"),
                SafeFailureRender.sources(map).map { KotlinText.rel(map, it) },
                "the SOURCES homes name :app and not :console, so only app's sources are graded",
            )
            assertEquals(1, census().size, "an out-of-scope file and an out-of-home module add no sites")
        }
    }

    @Test
    fun `the wall can actually fail - scope, the first-draft holes and the marker - DR-140`(@TempDir root: File) {
        Tree(root).prove(ScopeArms.ALL)
    }

    @Test
    fun `the wall can actually fail - the failure-span plane - DR-140`(@TempDir root: File) {
        Tree(root).prove(SpanArms.ALL)
    }

    @Test
    fun `the wall can actually fail - comment prose and nested frames - DR-140`(@TempDir root: File) {
        Tree(root).prove(FrameArms.ALL)
    }

    @Test
    fun `the wall can actually fail - throwable bindings - DR-140`(@TempDir root: File) {
        Tree(root).prove(BindingArms.ALL)
    }

    @Test
    fun `the wall can actually fail - shadowing per column - DR-140`(@TempDir root: File) {
        Tree(root).prove(ShadowArms.ALL)
    }

    @Test
    fun `the wall can actually fail - the fail-closed wall and direct renders - DR-140`(@TempDir root: File) {
        Tree(root).prove(DirectArms.ALL)
    }

    private companion object {
        const val PROBE_DIR = "app/src/main/kotlin/splice/probe"
        const val OUTSIDE_HOME = "console/src/main/kotlin/splice/console/Outside.kt"
        const val BLAME = "renders a throwable raw"
        const val VACUITY_FILES = 100
        const val VACUITY_SITES = 20

        const val BORING_ROUTED = """package splice.probe
import java.nio.file.Files
// the smallest in-scope file there is: one throwable, one sink.
fun probe(failure: Throwable) = Files.exists(p).also { log("boom ${'$'}{SafeFailureText.render(failure)}") }
"""
        const val BORING_RAW = """package splice.probe
import java.nio.file.Files
// the smallest in-scope file there is: one throwable, one sink.
fun probe(failure: Throwable) = Files.exists(p).also { log("boom ${'$'}failure") }
"""
        const val OUT_OF_SCOPE = """package splice.probe
// neither file I/O nor credential vocabulary: causally out of reach of DR-65's hazard.
fun probe(failure: Throwable) = log("boom ${'$'}failure")
"""
    }
}

/** scope, the two first-draft holes, and every exemption-marker refusal. */
private object ScopeArms {
    val ALL = listOf(
        Arm(
            "routed sink passes",
            "A.kt",
            GREEN,
            """package p
import java.nio.file.Files
fun a(e: Throwable) = Files.exists(p).also { log("x (${'$'}{SafeFailureText.render(e)})") }""",
        ),
        Arm(
            "raw .message fails",
            "A.kt",
            RED,
            """package p
import java.nio.file.Files
fun a(e: Throwable) = Files.exists(p).also { log("x (${'$'}{e.message})") }""",
        ),
        Arm(
            "bare \$failure fails",
            "A.kt",
            RED,
            """package p
import java.nio.file.Files
fun a(failure: Throwable) = Files.exists(p).also { log("x (${'$'}failure)") }""",
        ),
        Arm(
            "file-io-only file is in scope",
            "NoVocab.kt",
            RED,
            """package p
fun a(failure: Throwable) = java.nio.file.Files.getLastModifiedTime(x).also { log("${'$'}failure") }""",
        ),
        Arm(
            "valid exemption passes",
            "A.kt",
            GREEN,
            """package p
import java.nio.file.Files
// SAFE-RENDER-EXEMPT[2026-08-31]: a bind failure names a port and an address, never file bytes
fun a(e: Throwable) = Files.exists(p).also { log("x (${'$'}{e.message})") }""",
        ),
        Arm(
            "blank reason fails",
            "A.kt",
            RED,
            """package p
import java.nio.file.Files
// SAFE-RENDER-EXEMPT[2026-08-31]:
fun a(e: Throwable) = Files.exists(p).also { log("x (${'$'}{e.message})") }""",
        ),
        Arm(
            "placeholder reason fails",
            "A.kt",
            RED,
            """package p
import java.nio.file.Files
// SAFE-RENDER-EXEMPT[2026-08-31]: TODO decide later
fun a(e: Throwable) = Files.exists(p).also { log("x (${'$'}{e.message})") }""",
        ),
        Arm(
            "short reason fails",
            "A.kt",
            RED,
            """package p
import java.nio.file.Files
// SAFE-RENDER-EXEMPT[2026-08-31]: fs only
fun a(e: Throwable) = Files.exists(p).also { log("x (${'$'}{e.message})") }""",
        ),
        Arm(
            "undated marker fails",
            "A.kt",
            RED,
            """package p
import java.nio.file.Files
// SAFE-RENDER-EXEMPT: a bind failure names a port and an address, never any file bytes
fun a(e: Throwable) = Files.exists(p).also { log("x (${'$'}{e.message})") }""",
        ),
        Arm(
            "out-of-scope file is not flagged",
            "Pure.kt",
            GREEN,
            """package p
fun a(failure: Throwable) = log("x (${'$'}failure)")""",
        ),
        Arm(
            "non-failure \$it is not flagged",
            "A.kt",
            GREEN,
            """package p
import java.nio.file.Files
fun a(bearer: String?) = Files.exists(p).also { bearer?.let { h("Bearer ${'$'}it") } }""",
        ),
    )
}

/** the failure-span plane: the lexer's non-syntax, fold, and which brace a combinator governs. */
private object SpanArms {
    val ALL = listOf(
        Arm(
            "\$it in onFailure fails",
            "A.kt",
            RED,
            """package p
import java.nio.file.Files
fun a() = Files.size(p).onFailure { log("stat failed: ${'$'}it — skipping") }""",
        ),
        Arm(
            "\$it deep inside a multi-line onFailure body fails",
            "Deep.kt",
            RED,
            """package p
import java.nio.file.Files
fun a() = write().onFailure {
    discard(
        runCatching { Files.deleteIfExists(tmp) },
        "cleanup is best-effort; the write failure rethrows",
    )
    throw java.io.IOException("secure write failed: ${'$'}it", it)
}""",
        ),
        Arm(
            "\$it after the lambda closes is not flagged",
            "After.kt",
            GREEN,
            """package p
import java.nio.file.Files
fun a() {
    write().onFailure {
        log("failed")
    }
    names.forEach { log("${'$'}it = stored") }
}""",
        ),
        Arm(
            "a URL in a one-line failure lambda does not leak the span",
            "Url.kt",
            GREEN,
            """package p
import java.nio.file.Files
fun a() {
    Files.size(p).onFailure { log("https://example.test/failure") }
    names.forEach { log("${'$'}it = stored") }
}""",
        ),
        Arm(
            "a brace inside a block comment does not close the span",
            "BlockComment.kt",
            RED,
            """package p
import java.nio.file.Files
fun a() = write().onFailure {
    /* nested cleanup closes with } after retries */
    log("failed: ${'$'}it")
}""",
        ),
        Arm(
            "a brace inside a char literal does not close the span",
            "CharLit.kt",
            RED,
            """package p
import java.nio.file.Files
fun a() = write().onFailure {
    val close = '}'
    log("failed: ${'$'}it, terminator ${'$'}close")
}""",
        ),
        Arm(
            "a brace inside a multi-line block comment does not close the span",
            "MultiComment.kt",
            RED,
            """package p
import java.nio.file.Files
fun a() = write().onFailure {
    /* the retry ladder
       closes with } here
       and continues */
    log("failed: ${'$'}it")
}""",
        ),
        Arm(
            "a nested block comment does not close the span",
            "Nested.kt",
            RED,
            """package p
import java.nio.file.Files
fun a() = write().onFailure {
    /* outer /* inner */ still outer } */
    log("failed: ${'$'}it")
}""",
        ),
        Arm(
            "a trailing exceptionOrNull does not claim an earlier brace",
            "Trailing.kt",
            GREEN,
            """package p
import java.nio.file.Files
fun a() {
    Files.size(p)
    runCatching { names.forEach { log("${'$'}it = stored") } }.exceptionOrNull()
}""",
        ),
        Arm(
            "exceptionOrNull feeding a let still opens the span",
            "FeedsLet.kt",
            RED,
            """package p
import java.nio.file.Files
fun a() {
    Files.size(p)
    outcome.exceptionOrNull()?.let { log("read_error: ${'$'}it") }
}""",
        ),
        Arm(
            "an unnamed positional fold is not itself a violation",
            "PosFold.kt",
            GREEN,
            """package p
import java.nio.file.Files
fun a() {
    Files.size(p)
    result.fold(
        { names.forEach { log("${'$'}it = stored") } },
        { log("failed: ${'$'}it") },
    )
}""",
        ),
        Arm(
            "Iterable.fold is not a failure combinator",
            "IterFold.kt",
            GREEN,
            """package p
import java.nio.file.Files
fun a() {
    Files.size(p)
    items.fold(0) { acc, x -> acc + x }
}""",
        ),
        Arm(
            "a named fold routed through the sanitizer passes",
            "NamedFold.kt",
            GREEN,
            """package p
import java.nio.file.Files
fun a() = Files.size(p).fold(
    onSuccess = { true },
    onFailure = { log("failed: ${'$'}{SafeFailureText.render(it)}"); false },
)""",
        ),
    )
}

/** comment prose, nested frames, the template hole, and the first bindings. */
private object FrameArms {
    val ALL = listOf(
        Arm(
            "a throwable named only in a trailing comment is not a render",
            "TrailingProse.kt",
            GREEN,
            """package p
import java.nio.file.Files
fun a() = Files.size(p).onFailure {
    val ignored = 1 // raw ${'$'}it would leak here
    log("failed: ${'$'}{SafeFailureText.render(it)}")
}""",
        ),
        Arm(
            "a real render on a line that also carries comment prose still fails",
            "ProseAndReal.kt",
            RED,
            """package p
import java.nio.file.Files
fun a() = Files.size(p).onFailure {
    log("failed: ${'$'}it") // the ${'$'}it above is the violation, this prose is not
}""",
        ),
        Arm(
            "a nested lambda inside a failure lambda rebinds it",
            "Shadow.kt",
            GREEN,
            """package p
import java.nio.file.Files
fun a() = Files.size(p).onFailure {
    names.forEach { log("${'$'}it = stored") }
}""",
        ),
        Arm(
            "a nested control block does not rebind it",
            "ControlBlock.kt",
            RED,
            """package p
import java.nio.file.Files
fun a() = Files.size(p).onFailure {
    if (x) {
        log("failed: ${'$'}it")
    }
}""",
        ),
        Arm(
            "shadowing on the same line as the failure lambda",
            "OneLine.kt",
            GREEN,
            """package p
import java.nio.file.Files
fun a() {
    Files.size(p)
    m.getOrElse(k) { names.forEach { log("${'$'}it") } }
}""",
        ),
        Arm(
            "a when branch does not rebind it",
            "WhenBranch.kt",
            RED,
            """package p
import java.nio.file.Files
fun a() = Files.size(p).onFailure {
    when (x) {
        is Foo -> {
            log("failed: ${'$'}it")
        }
    }
}""",
        ),
        Arm(
            "a named throwable inside a nested lambda still fails",
            "NamedInside.kt",
            RED,
            """package p
import java.nio.file.Files
fun a(failure: Throwable) = Files.size(p).onFailure {
    names.forEach { log("${'$'}failure") }
}""",
        ),
        Arm(
            "getOrElse does not bind a throwable",
            "GetOrElse.kt",
            GREEN,
            """package p
import java.nio.file.Files
fun a() {
    Files.size(p)
    list.getOrElse(0) { "missing ${'$'}it" }
}""",
        ),
        Arm(
            "an escaped dollar is not an interpolation",
            "Escaped.kt",
            GREEN,
            """package p
import java.nio.file.Files
fun a() = Files.size(p).onFailure {
    log("literal \${'$'}it stays literal")
}""",
        ),
        Arm(
            "a failure lambda inside a template hole is still seen",
            "Template.kt",
            RED,
            """package p
import java.nio.file.Files
fun a() {
    Files.size(p)
    val s = "outer ${'$'}{runCatching { x }.onFailure { "failed ${'$'}it" }} tail"
}""",
        ),
        Arm(
            "a bare exceptionOrNull statement does not poison a sibling lambda",
            "Poison.kt",
            GREEN,
            """package p
import java.nio.file.Files
fun a() {
    Files.size(p)
    names.forEach {
        outcome.exceptionOrNull()
        if (x) { log("${'$'}it") }
    }
}""",
        ),
        Arm(
            "a throwable bound to a short local is still a render",
            "Bound.kt",
            RED,
            """package p
import java.nio.file.Files
fun a() {
    Files.size(p)
    val e = outcome.exceptionOrNull()
    log("${'$'}e")
}""",
        ),
        Arm(
            "a sanitized binding is not a throwable",
            "BoundSafe.kt",
            GREEN,
            """package p
import java.nio.file.Files
fun a() {
    Files.size(p)
    val reason = outcome.exceptionOrNull()?.let { SafeFailureText.render(it) } ?: "unknown"
    log("failed (${'$'}reason)")
}""",
        ),
        Arm(
            "a fun named onFailure is not a failure lambda",
            "FunDecl.kt",
            GREEN,
            """package p
import java.nio.file.Files
fun a() {
    Files.size(p)
}
fun onFailure(e: Event) {
    log("${'$'}e")
}""",
        ),
        Arm(
            "a bare exceptionOrNull statement does not poison the next lambda",
            "NextLambda.kt",
            GREEN,
            """package p
import java.nio.file.Files
fun a() {
    Files.size(p)
    names.forEach {
        outcome.exceptionOrNull()
        values.forEach { log("${'$'}it") }
    }
}""",
        ),
    )
}

/** throwable bindings: their scope, their sources, and what never claims one. */
private object BindingArms {
    val ALL = listOf(
        Arm(
            "a failure lambda inside a shadowing lambda is still caught",
            "Restored.kt",
            5,
            """package p
import java.nio.file.Files
fun a() = Files.size(p).onFailure {
    names.forEach {
        runCatching { z() }.onFailure { log("${'$'}it") }
    }
}""",
        ),
        Arm(
            "a binding dies with the block that closes on its own line",
            "SameLine.kt",
            GREEN,
            """package p
import java.nio.file.Files
fun a() {
    Files.size(p)
    if (x) { val e = outcome.exceptionOrNull() }
    val e = "event"
    log("${'$'}e")
}""",
        ),
        Arm(
            "a binding survives a sibling block and stays live in a nested one",
            "NestedLive.kt",
            8,
            """package p
import java.nio.file.Files
fun a() {
    Files.size(p)
    val e = outcome.exceptionOrNull()
    if (x) { log("unrelated") }
    if (y) {
        log("${'$'}e")
    }
}""",
        ),
        Arm(
            "every binding in a statement is recorded, not just the first",
            "TwoBind.kt",
            6,
            """package p
import java.nio.file.Files
fun a() {
    Files.size(p)
    val e = first.exceptionOrNull(); val err = second.exceptionOrNull()
    log("${'$'}err")
}""",
        ),
        Arm(
            "a receiver-and-generic fun declaration is not a failure lambda",
            "FunRecv.kt",
            GREEN,
            """package p
import java.nio.file.Files
fun a() {
    Files.size(p)
}
fun Result<Event>.onFailure(e: Event) {
    log("${'$'}e")
}""",
        ),
        Arm(
            "an untyped .cause binding is not a throwable",
            "Cause.kt",
            GREEN,
            """package p
import java.nio.file.Files
fun a() {
    Files.size(p)
    val err = incident.cause
    log("${'$'}err")
}""",
        ),
        Arm(
            "catch binds the throwable, as the coverage note claims",
            "Catch.kt",
            4,
            """package p
import java.nio.file.Files
fun a() {
    try { Files.size(p) } catch (e: java.io.IOException) { log("${'$'}e") }
}""",
        ),
        Arm(
            "a sanitized call beside a raw binding does not launder it",
            "Launder.kt",
            6,
            """package p
import java.nio.file.Files
fun a() {
    Files.size(p)
    val e = outcome.exceptionOrNull(); log(SafeFailureText.render(other))
    log("${'$'}e")
}""",
        ),
        Arm(
            "a var binding is not claimed",
            "VarBind.kt",
            GREEN,
            """package p
import java.nio.file.Files
fun a() {
    Files.size(p)
    var e: Any? = outcome.exceptionOrNull()
    e = "plain"
    log("${'$'}e")
}""",
        ),
        Arm(
            "a binding wrapped onto the next line is seen",
            "Wrapped.kt",
            7,
            """package p
import java.nio.file.Files
fun a() {
    Files.size(p)
    val e =
        outcome.exceptionOrNull()
    log("${'$'}e")
}""",
        ),
        Arm(
            "a binding whose source runs through a lambda keeps its head",
            "ThroughLambda.kt",
            6,
            """package p
import java.nio.file.Files
fun a() {
    Files.size(p)
    val e = runCatching { z() }.exceptionOrNull()
    log("${'$'}e")
}""",
        ),
        Arm(
            "a string-valued binding does not swallow the next statement",
            "StringCont.kt",
            GREEN,
            """package p
import java.nio.file.Files
fun a() {
    Files.size(p)
    val e = "event"
    if (x) { outcome.exceptionOrNull() }
    log("${'$'}e")
}""",
        ),
        Arm(
            "a genuinely open statement still continues across the newline",
            "StillCont.kt",
            7,
            """package p
import java.nio.file.Files
fun a() {
    Files.size(p)
    val e =
        outcome.exceptionOrNull()
    log("${'$'}e")
}""",
        ),
        Arm(
            "a binding used on the same line it closes is still seen",
            "SameLineUse.kt",
            5,
            """package p
import java.nio.file.Files
fun a() {
    Files.size(p)
    if (x) { val e = outcome.exceptionOrNull(); log("${'$'}e") }
}""",
        ),
        Arm(
            "the same-line shape holds nested and inside a lambda",
            "SameLineNest.kt",
            5,
            """package p
import java.nio.file.Files
fun a() {
    Files.size(p)
    run { if (y) { val e = outcome.exceptionOrNull(); log("${'$'}e") } }
}""",
        ),
    )
}

/** shadowing per column: vals, for and catch heads, when arrows, typed parameter lists. */
private object ShadowArms {
    val ALL = listOf(
        Arm(
            "an inner nonthrowable val hides the outer throwable",
            "ShadowVal.kt",
            GREEN,
            """package p
import java.nio.file.Files
fun a() {
    Files.size(p)
    val e = outcome.exceptionOrNull()
    if (x) { val e = "event"; log("${'$'}e") }
}""",
        ),
        Arm(
            "the outer throwable comes back when the shadow block ends",
            "ShadowEnds.kt",
            7,
            """package p
import java.nio.file.Files
fun a() {
    Files.size(p)
    val e = outcome.exceptionOrNull()
    if (x) { val e = "event"; log("${'$'}e") }
    log("${'$'}e")
}""",
        ),
        Arm(
            "a for-loop parameter hides an outer throwable",
            "ShadowFor.kt",
            GREEN,
            """package p
import java.nio.file.Files
fun a() {
    Files.size(p)
    val e = outcome.exceptionOrNull()
    for (e in xs) { log("${'$'}e") }
}""",
        ),
        Arm(
            "a lambda parameter hides an outer throwable",
            "ShadowLambda.kt",
            GREEN,
            """package p
import java.nio.file.Files
fun a() {
    Files.size(p)
    val e = outcome.exceptionOrNull()
    items.forEach { e -> log("${'$'}e") }
}""",
        ),
        Arm(
            "a when-branch head is a comparison, not a parameter",
            "WhenArrow.kt",
            7,
            """package p
import java.nio.file.Files
fun a() {
    Files.size(p)
    val e = outcome.exceptionOrNull()
    when (k) {
        e -> log("${'$'}e")
    }
}""",
        ),
        Arm(
            "a subjectless when branch head is not a parameter either",
            "WhenBare.kt",
            7,
            """package p
import java.nio.file.Files
fun a() {
    Files.size(p)
    val e = outcome.exceptionOrNull()
    when {
        e -> log("${'$'}e")
    }
}""",
        ),
        Arm(
            "a lambda inside a when branch still shadows",
            "WhenLambda.kt",
            GREEN,
            """package p
import java.nio.file.Files
fun a() {
    Files.size(p)
    val e = outcome.exceptionOrNull()
    when (k) {
        else -> items.forEach { e -> log("${'$'}e") }
    }
}""",
        ),
        Arm(
            "a typed lambda parameter hides an outer throwable",
            "TypedParam.kt",
            GREEN,
            """package p
import java.nio.file.Files
fun a() {
    Files.size(p)
    val e = outcome.exceptionOrNull()
    items.forEach { e: Event -> log("${'$'}e") }
}""",
        ),
        Arm(
            "a parenthesised typed parameter hides it too",
            "TypedParen.kt",
            GREEN,
            """package p
import java.nio.file.Files
fun a() {
    Files.size(p)
    val e = outcome.exceptionOrNull()
    items.forEach { (e: Event) -> log("${'$'}e") }
}""",
        ),
        Arm(
            "two typed parameters both declare",
            "TypedTwo.kt",
            GREEN,
            """package p
import java.nio.file.Files
fun a() {
    Files.size(p)
    val e = outcome.exceptionOrNull()
    items.forEachIndexed { e: Event, i: Int -> log("${'$'}e") }
}""",
        ),
        Arm(
            "a typed destructure declares its components",
            "TypedDestructure.kt",
            GREEN,
            """package p
import java.nio.file.Files
fun a() {
    Files.size(p)
    val e = outcome.exceptionOrNull()
    pairs.forEach { (e: Event, n: Int) -> log("${'$'}e") }
}""",
        ),
        Arm(
            "a generic type carrying a comma is one parameter",
            "TypedGeneric.kt",
            GREEN,
            """package p
import java.nio.file.Files
fun a() {
    Files.size(p)
    val e = outcome.exceptionOrNull()
    groups.forEach { e: Map<String, Event> -> log("${'$'}e") }
}""",
        ),
        Arm(
            "a trailing comma in the parameter list is tolerated",
            "TrailingComma.kt",
            GREEN,
            """package p
import java.nio.file.Files
fun a() {
    Files.size(p)
    val e = outcome.exceptionOrNull()
    items.forEach { e, -> log("${'$'}e") }
}""",
        ),
        Arm(
            "a destructure typed on the outside still declares",
            "DestructureOuterType.kt",
            GREEN,
            """package p
import java.nio.file.Files
fun a() {
    Files.size(p)
    val e = outcome.exceptionOrNull()
    pairs.forEach { (e, n): Pair<Event, Int> -> log("${'$'}e") }
}""",
        ),
        Arm(
            "a typed parameter's shadow ends with its block",
            "TypedEnds.kt",
            7,
            """package p
import java.nio.file.Files
fun a() {
    Files.size(p)
    val e = outcome.exceptionOrNull()
    items.forEach { e: Event -> log("${'$'}e") }
    log("${'$'}e")
}""",
        ),
        Arm(
            "a typed parameter inside a when branch still shadows",
            "TypedWhenLambda.kt",
            GREEN,
            """package p
import java.nio.file.Files
fun a() {
    Files.size(p)
    val e = outcome.exceptionOrNull()
    when (k) {
        else -> items.forEach { e: Event -> log("${'$'}e") }
    }
}""",
        ),
    )
}

/** the fail-closed parameter wall, the documented residual, and the non-interpolated renders. */
private object DirectArms {
    val ALL = listOf(
        Arm(
            "an explicit zero-arg lambda declares nothing",
            "ZeroArg.kt",
            6,
            """package p
import java.nio.file.Files
fun a() {
    Files.size(p)
    val e = outcome.exceptionOrNull()
    items.forEach { -> log("${'$'}e") }
}""",
        ),
        Arm(
            "an underscore parameter does not clear an outer throwable",
            "Underscore.kt",
            6,
            """package p
import java.nio.file.Files
fun a() {
    Files.size(p)
    val e = outcome.exceptionOrNull()
    items.forEach { _ -> log("${'$'}e") }
}""",
        ),
        Arm(
            "run does not rebind a named throwable",
            "RunCapture.kt",
            6,
            """package p
import java.nio.file.Files
fun a() {
    Files.size(p)
    val e = outcome.exceptionOrNull()
    run { log("${'$'}e") }
}""",
        ),
        Arm(
            "apply does not rebind a named throwable",
            "ApplyCapture.kt",
            6,
            """package p
import java.nio.file.Files
fun a() {
    Files.size(p)
    val e = outcome.exceptionOrNull()
    cfg.apply { log("${'$'}e") }
}""",
        ),
        Arm(
            "an unreadable entry rejects the whole parameter list",
            "Backtick.kt",
            6,
            """package p
import java.nio.file.Files
fun a() {
    Files.size(p)
    val e = outcome.exceptionOrNull()
    items.forEach { `odd name`, e -> log("${'$'}e") }
}""",
        ),
        Arm(
            "the implicit-it residual: run inside a failure lambda is not reported",
            "ItResidual.kt",
            GREEN,
            """package p
import java.nio.file.Files
fun a() {
    Files.size(p)
    outcome.onFailure { run { log("${'$'}it") } }
}""",
        ),
        Arm(
            "a short name directly in a failure lambda is still reported",
            "ItDirect.kt",
            5,
            """package p
import java.nio.file.Files
fun a() {
    Files.size(p)
    outcome.onFailure { log("${'$'}it") }
}""",
        ),
        Arm(
            "comment mentioning \$failure is not flagged",
            "A.kt",
            GREEN,
            """package p
import java.nio.file.Files
// a bare `${'$'}failure` here would quote file bytes, which is why ${'$'}{e.message} is banned
fun a(e: Throwable) = Files.exists(p).also { log("x (${'$'}{SafeFailureText.render(e)})") }""",
        ),
        Arm(
            "an interpolated stackTraceToString is reported",
            "StackTrace.kt",
            5,
            """package p
import java.nio.file.Files
fun a(e: Throwable) {
    Files.size(p)
    log("boom ${'$'}{e.stackTraceToString()}")
}""",
        ),
        Arm(
            "a bare stackTraceToString call is reported",
            "StackTraceBare.kt",
            5,
            """package p
import java.nio.file.Files
fun a(e: Throwable) {
    Files.size(p)
    System.err.print(e.stackTraceToString())
}""",
        ),
        Arm(
            "localizedMessage is reported",
            "Localized.kt",
            5,
            """package p
import java.nio.file.Files
fun a(e: Throwable) {
    Files.size(p)
    log("boom ${'$'}{e.localizedMessage}")
}""",
        ),
        Arm(
            "iterating stackTrace frames beside a routed message is not flagged",
            "Frames.kt",
            GREEN,
            """package p
import java.nio.file.Files
fun a(e: Throwable) {
    Files.size(p)
    val frames = e.stackTrace.take(3).joinToString("") { frame -> "    at ${'$'}frame\n" }
    log("boom ${'$'}{SafeFailureText.render(e)}" + frames)
}""",
        ),
        Arm(
            "a non-interpolated .message is reported",
            "Direct.kt",
            5,
            """package p
import java.nio.file.Files
fun a(failure: Throwable) {
    Files.size(p)
    out.put("read_error", failure.message)
}""",
        ),
        Arm(
            "a multi-hop non-interpolated .message is reported",
            "Hops.kt",
            5,
            """package p
import java.nio.file.Files
fun a(failure: Throwable) {
    Files.size(p)
    out.put("read_error", failure.cause?.message)
}""",
        ),
        Arm(
            "a non-interpolated toString in a failure lambda is reported",
            "DirectShort.kt",
            5,
            """package p
import java.nio.file.Files
fun a() {
    Files.size(p)
    outcome.onFailure { log(it.toString()) }
}""",
        ),
        Arm(
            "an implicit toString by concatenation is reported",
            "Concat.kt",
            5,
            """package p
import java.nio.file.Files
fun a(failure: Throwable) {
    Files.size(p)
    log("read failed: " + failure)
}""",
        ),
        Arm(
            ".message on a non-throwable receiver is not flagged",
            "Response.kt",
            GREEN,
            """package p
import java.nio.file.Files
fun a(response: HttpResponse) {
    Files.size(p)
    log("status " + response.status)
    val m = response.message
}""",
        ),
        Arm(
            "a routed put() passes",
            "RoutedPut.kt",
            GREEN,
            """package p
import java.nio.file.Files
fun a(failure: Throwable) {
    Files.size(p)
    out.put("read_error", SafeFailureText.render(failure))
}""",
        ),
        Arm(
            "a short name outside a failure frame is not flagged",
            "ShortOut.kt",
            GREEN,
            """package p
import java.nio.file.Files
fun a(items: List<String>) {
    Files.size(p)
    items.forEach { log(it.toString()) }
}""",
        ),
    )
}
