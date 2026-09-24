// V4-68, GRADLE PORT (restructure plan §6.3, row "tests are discovered") — a @Test method JUnit
// never DISCOVERED is a green suite with a hole in it. This file is the checker itself as pure
// Kotlin: no Gradle types anywhere below, so every rule here is provable against a fixture string
// or a @TempDir file (TestDiscoveryTest) rather than only against a build that happens to be
// configured correctly. splice.test-discovery.gradle.kts is the other half — it resolves the
// Gradle project model into the File/String arguments these functions take, and wires the result
// into a task.
//
// WHY THIS EXISTS. A @Test method whose body returns a non-Unit value is not discovered by JUnit:
// no failure, no skip, no warning, no line in any report. The suite is green, the XML is
// complete-looking, and the test has never run once in its life. Measured 2026-09-16, against the
// original bun checker this file replaces: daemon/head/src/test/kotlin/splice/head/
// HeadServerCapacityTest.kt declared four @Test methods and its own XML reported tests=3 — the
// fourth ends in held.await() inside `= runBlocking { ... }`, so the method returns a String, and
// it had never executed. Every gate leg reads what ran; nothing compared that against what was
// DECLARED until this wall.
//
// THE INSTRUMENT TRAP, measured while confirming the above, and the reason this wall anchors on
// the XML and never on the shape of the source. A naive scan for an expression-bodied @Test flags
// all FOUR methods in that class, because three of them end in a Unit-valued expression while the
// fourth does not — and kotlinx's TestResult is a typealias for Unit on the JVM, so
// `= runTest { }` is discovered and fine. A checker reasoning from syntax is wrong in BOTH
// directions: it accuses the innocent and can be fooled by the guilty. So the source here supplies
// only the DENOMINATOR (what was declared) and the XML supplies the OBSERVATION (what ran). The
// comparison is the whole check.
//
// DENOMINATOR, FROM KOTLIN TEST SOURCES. For every .kt file under a subproject's own
// src/test/kotlin (handed in by splice.test-discovery.gradle.kts, one directory per subproject —
// see the module-key note on MODULE_DISPOSITIONS below), each class is found by a string- and
// comment-aware scan (mask/bodyRange/classesIn), and its test methods are counted at MEMBER depth
// only (memberItems): a nested class's tests are its own, and a local function inside a test body
// is nobody's. That is what makes the count comparable to a per-class XML row.
//
// OBSERVATION, FROM JUNIT XML VIA THE JDK'S OWN PARSER. The original read
// gateway/<module>/build/test-results/test/*.xml with a hand-rolled attribute scanner because bun
// ships no XML parser; the JVM does, so this port reads each producer's DECLARED output
// (test.reports.junitXml.outputLocation, never a shared glob — splice.test-discovery.gradle.kts
// explains why the results directory is a mutually-destructive observation) with
// javax.xml.parsers.DocumentBuilderFactory instead. What survives the port UNCHANGED is the
// die-loudly contract: a document whose root lacks `name` or `tests` throws, naming the file,
// rather than returning an empty row that would read as "JUnit ran nothing" for a file that is
// merely unfamiliar (see parseJUnitXml).
//
// SHAPES. XML count LOWER than the denominator fails BY NAME, naming the missing methods. THREE
// shapes may legitimately report a HIGHER count. Two need a written reason in DISPOSITIONS below —
// never a bare allowlist entry: a @ParameterizedTest expands one method into N cases, and a class
// may INHERIT test methods from a base class. The third is @TestFactory (decided here, restructure
// PR 6, against ReleaseReadinessLawTest: one factory method, 47 DynamicTest children — 2 @Test + 47
// = 49), and it does NOT go in DISPOSITIONS: unlike @ParameterizedTest, its expansion factor is not
// a fixed number anyone could write down and re-earn — the factory returns one DynamicTest per
// mutation in a list that is EXPECTED to grow, and a disposition pinned to today's count would red
// the day after someone adds a mutation, for the healthiest possible reason (a disposition list
// that goes stale on every healthy addition is worse than no disposition at all). So the scanner
// COUNTS @TestFactory as a declared method — it is a JUnit annotation on a declaration, exactly as
// countable as @Test — and the comparison (audit) treats a class holding one as exempt from the
// higher-count disposition rule: any observed count at or above declared is the expected shape for
// that class, while observed BELOW declared still reds unconditionally — the factory method itself
// never running, or its expansion collapsing to nothing, is exactly the hazard this wall exists to
// catch, and teaching the denominator about the annotation leaves that hazard undiminished. A class
// with no XML at all also needs a written reason (a test task that is disabled by configuration is
// a decision, not an accident — but it is a decision someone must WRITE DOWN) — see
// MODULE_DISPOSITIONS.
//
// WHAT IT CANNOT SEE. A class that never compiles is not in any XML and not in this scan's
// dispositions unless its module is dispositioned. A test source set outside a subproject's own
// src/test/kotlin is not scanned. And a test that runs but asserts nothing is discovered, counted,
// and useless — this wall counts executions, it cannot judge them. The reverse drift — an XML row
// for a class that no longer exists in source — is reported by [census] and never failed: it is
// stale build output, not a hole in the suite.
package splice.discovery

import org.xml.sax.InputSource
import java.io.File
import java.io.StringReader
import java.util.regex.Pattern
import javax.xml.parsers.DocumentBuilderFactory

/** One test class found in source: the Gradle project path it lives under, its (possibly
 *  nested-qualified, `Outer$Inner`) name, the path it was read from, and the test methods declared
 *  at member depth. [count] is what the comparison calls the DENOMINATOR. [dynamicMethods] is the
 *  subset of [methods] declared `@TestFactory` — see THE THIRD SHAPE in the file header: unlike a
 *  plain @Test/@ParameterizedTest, an observed count above what it contributes here is the expected
 *  shape, not a hazard, so [audit] never asks it for a disposition. */
data class TestClass(
    val module: String,
    val name: String,
    val path: String,
    val methods: List<String>,
    val dynamicMethods: Set<String> = emptySet(),
) {
    val count: Int get() = methods.size
}

/** One class's row in a JUnit XML results directory: the root `tests` count and every `<testcase
 *  name>` — the comparison calls this the OBSERVATION. */
data class XmlRow(val count: Int, val names: Set<String>)

/** A disposition earned for a class whose XML count is legitimately HIGHER than its declared
 *  count: [reason] it is higher, and [expectedCount] — the observed count the disposition was
 *  earned for, so a re-expansion (or a shrink) reds instead of aging silently into a permanent
 *  waiver. */
data class Disposition(val reason: String, val expectedCount: Int)

// Classes whose XML count is legitimately HIGHER than the source denominator, carried over
// VERBATIM from the original — every entry was verified against the class's own annotations
// before it was written, and the count beside it is the observed XML count the disposition was
// earned for. Each is the same shape: @ParameterizedTest expands one method into N cases.
// (Inherited test methods would be the other shape; measured across all six, none of them is
// explained by inheritance — CodexCodeModeActiveInterruptionTest and
// CodexCodeModeInfrastructureTest do extend CodeModeBridgeTestSupport, but that base declares no
// tests, and their declared count matches their own annotations exactly.)
val DISPOSITIONS: Map<String, Disposition> = mapOf(
    "ResponsesWsRunnerTest" to
        Disposition("3 @ParameterizedTest methods expand to 9 cases (12 @Test + 9 = 21)", 21),
    "CodexCodeModeReanchorTest" to Disposition("1 @ParameterizedTest expands to 2 cases (1 @Test + 2 = 3)", 3),
    "CodexAuthAbsenceTest" to Disposition("1 @ParameterizedTest expands to 4 cases (2 @Test + 4 = 6)", 6),
    "CodexCodeModeActiveInterruptionTest" to
        Disposition("1 @ParameterizedTest expands to 4 cases; no plain @Test", 4),
    "CodexCodeModeInfrastructureTest" to Disposition("1 @ParameterizedTest expands to 2 cases; no plain @Test", 2),
    "SseReaderTest" to Disposition("1 @ParameterizedTest expands to 6 cases (11 @Test + 6 = 17)", 17),
    // v0.4.0 review (PR #195): the resume hook is run with no ANTHROPIC_AUTH_TOKEN and with the
    // operator's own one in it, and must send the turn key from its header file either way.
    "ResumeHookTest" to Disposition("1 @ParameterizedTest expands to 2 cases (5 @Test + 2 = 7)", 7),
)

// Modules whose test task is disabled BY CONFIGURATION, so no XML can exist. The reason is the
// disposition; cite where the decision lives. PORT NOTE: the original keyed this by the directory
// name under gateway/ (e.g. "gateway/silentmodule"); this port keys it by GRADLE PROJECT PATH
// (e.g. ":daemon-head") instead, since the denominator now comes from each subproject's own
// src/test/kotlin directory rather than a MODULE_HOMES glob cut apart by path segment. Empty
// today: every declared subproject with Kotlin tests runs them.
val MODULE_DISPOSITIONS: Map<String, String> = emptyMap()

// ── the scanner: mask, bodyRange, memberItems, classesIn — ported faithfully from the original ──
/** The DENOMINATOR: what the Kotlin test sources DECLARE. A string- and comment-aware
 *  scan, and the I/O boundary that walks a module's test sources into it. */
object SourceScan {

    private val CLASS_DECL_PATTERN: Pattern = Pattern.compile("""\bclass\s+(\w+)""")

    // THE THIRD SHAPE (file header, SHAPES): TestFactory joins Test/ParameterizedTest as a countable
    // declaration — group(1) is the annotation NAME, so memberItems can tell a factory method from a
    // plain one without a second pass.
    private val MEMBER_ITEM_PATTERN: Pattern =
        Pattern.compile("""@(Test|ParameterizedTest|TestFactory)\b|\bfun\s+(`[^`]+`|\w+)\s*\(""")

    // The same shape read from the ORIGINAL text, where a backtick name is still spelled out.
    private val UNMASKED_FUN_PATTERN: Pattern = Pattern.compile("""\bfun\s+(`[^`\n]+`|\w+)\s*\(""")

    // The one annotation of the three whose expansion factor is no number anyone could write down.
    private const val FACTORY_ANNOTATION = "TestFactory"

    // Where a declaration ENDS without a body: a blank line, or a line at COLUMN 0 that starts another
    // declaration. Column 0 is load-bearing — an indented `val`/`var` is a constructor parameter of
    // the very class being scanned, and treating it as a boundary would hide that class and its tests
    // from the denominator entirely, which is a silent miss rather than a loud one. (A nested
    // body-less declaration followed by an INDENTED declaration is the residual hole; no such shape
    // exists in the tree today, measured against the original: the fix removed exactly the 8 false
    // spikes classes and no real one.)
    private val DECL_END_PATTERN: Pattern =
        Pattern.compile("""\n[ \t]*\n|\n(?:@|class|object|interface|fun|enum|val|var)\b""")

    // JUnit's own discovery rule, which is what this wall is really asserting: a @Test method must be
    // public and return void. Kotlin enforces neither, so the shape is a trap. Carried from the
    // original, where it is DEFINED AND NOT USED; the DISPOSITIONS table above is what earns the
    // inheritance case. Kept because a reader following that sentence will look here.
    @Suppress("unused")
    private val INHERIT_PATTERN: Pattern =
        Pattern.compile("""\bclass\s+\w+[^{]*?:\s*([A-Za-z_][\w.]*)\s*(?:\(|\{|${'$'})""")

    /** Blank every comment and string BODY, preserving length and newlines.
     *
     *  One scanner, not two: comments and strings both hide `class`/`fun`/braces from the structure
     *  scan, and a literal left intact is a false class — the original's first version reported
     *  classes named `Heads`, `per` and `name`, every one of them a phrase inside a triple-quoted JSON
     *  body, because Kotlin's TRIPLE-quoted strings were not recognised and `"""` was read as an empty
     *  string followed by another string. Length-preserving so offsets into the masked text still
     *  address the original. */
    internal fun mask(source: String): String {
        val out = StringBuilder(source.length)
        val n = source.length
        fun plain(ch: Char) = if (ch == '\n') '\n' else ' '
        fun xs(ch: Char) = if (ch == '\n') '\n' else 'x'
        var i = 0
        while (i < n) {
            val ch = source[i]
            if (ch == '`') {
                // Kotlin's backtick identifier — how the test names are spelled here. Its body is
                // masked, NOT scanned for quotes: an apostrophe inside a backtick name ("the
                // operator's servers...") read as a character literal would swallow the rest of the
                // line, braces and all, which is what made class bodies close early and counts read
                // as 1 in the original's own history.
                val end = source.indexOf('`', i + 1)
                val stop = if (end < 0) n else end + 1
                for (idx in i until stop) out.append(xs(source[idx]))
                i = stop
                continue
            }
            if (ch == '/' && i + 1 < n && source[i + 1] == '/') {
                val nl = source.indexOf('\n', i)
                if (nl < 0) break
                for (idx in i until nl) out.append(plain(source[idx]))
                i = nl
                continue
            }
            if (ch == '/' && i + 1 < n && source[i + 1] == '*') {
                val end = source.indexOf("*/", i + 2)
                val stop = if (end < 0) n else end + 2
                for (idx in i until stop) out.append(plain(source[idx]))
                i = stop
                continue
            }
            if (source.startsWith("\"\"\"", i)) {
                val end = source.indexOf("\"\"\"", i + 3)
                val stop = if (end < 0) n else end + 3
                for (idx in i until stop) out.append(xs(source[idx]))
                i = stop
                continue
            }
            if (ch == '"' || ch == '\'') {
                val stop = minOf(skipQuoted(source, i), n)
                for (idx in i until stop) out.append(xs(source[idx]))
                i = stop
                continue
            }
            out.append(ch)
            i += 1
        }
        return out.toString()
    }

    /** The index right after the `"`- or `'`-delimited literal starting at [start] (`source[start]` is
     *  the opening quote).
     *
     *  THE BUG THIS FIXES, measured 2026-09-21 against SharedQuirksLawTest (restructure PR 6):
     *  quality/architecture/src/test/kotlin/splice/quality/SharedQuirksLawTest.kt:163-165 reads
     *  `"found ${SharedQuirks.dialectModules(\n    map,\n).size} ..."` — legal Kotlin, since a `${}`
     *  template expression is parsed as ordinary code and a raw newline inside it is unremarkable. The
     *  ORIGINAL scan (stop at the first `"` OR the first `\n`, whichever comes first — carried from the
     *  bun checker this ported, and faithfully reproduced by the first version of this function) breaks
     *  at that embedded newline, resumes scanning as though OUTSIDE the string, and reads the `}` that
     *  closes the interpolation as a REAL closing brace — corrupting the depth count for every class
     *  after it in the file. The class itself still parses (its own `{`/`}` come before the corruption),
     *  but its member scan does not: three `@Test` methods declared, one counted (see
     *  TestDiscoveryTest's `THE MULTI-LINE INTERPOLATION BUG` arm for the red/green proof).
     *
     *  THE FIX tracks template-interpolation depth: once `${` is seen (only inside a `"`-string — a
     *  `'`-char literal never interpolates), `{`/`}` extend or close the interpolation instead of
     *  ending the scan, a raw newline inside it is ignored (this is exactly what makes it not end the
     *  string), and a nested `"`- or `'`-literal inside the interpolation recurses through this same
     *  function so a quote or brace INSIDE that nested literal is never mistaken for the interpolation's
     *  own boundary. Outside any interpolation the original's own newline-stops-the-scan safety net for
     *  a plain string is unchanged — a REAL unterminated string still stops at the next line. */
    private fun skipQuoted(source: String, start: Int): Int {
        val n = source.length
        val quote = source[start]
        var i = start + 1
        var templateDepth = 0
        while (i < n) {
            val ch = source[i]
            when {
                ch == '\\' -> i += 2
                templateDepth == 0 && ch == quote -> return i + 1
                templateDepth == 0 && ch == '\n' -> return i
                quote == '"' && templateDepth == 0 && ch == '$' && i + 1 < n && source[i + 1] == '{' -> {
                    templateDepth = 1
                    i += 2
                }
                templateDepth > 0 && ch == '{' -> {
                    templateDepth += 1
                    i += 1
                }
                templateDepth > 0 && ch == '}' -> {
                    templateDepth -= 1
                    i += 1
                }
                templateDepth > 0 && source.startsWith("\"\"\"", i) -> {
                    val end = source.indexOf("\"\"\"", i + 3)
                    i = if (end < 0) n else end + 3
                }
                templateDepth > 0 && (ch == '"' || ch == '\'') -> i = skipQuoted(source, i)
                else -> i += 1
            }
        }
        return n
    }

    /** (open_brace_index, close_brace_index) of the declaration whose text starts at [start], or
     *  null. The brace must appear before the declaration ENDS, which is what the boundary scan
     *  ([DECL_END_PATTERN]) is for: a declaration with NO body — `data class Topology(val daemon:
     *  DaemonConfig, ...)` — is followed by the next class's `{`, and taking that one would make a
     *  constructor-only data class look like a test class holding its neighbour's tests. */
    internal fun bodyRange(masked: String, start: Int): Pair<Int, Int>? {
        var limit = masked.length
        val boundaryMatcher = DECL_END_PATTERN.matcher(masked)
        if (boundaryMatcher.find(start)) limit = boundaryMatcher.start()
        val openAt = masked.indexOf('{', start)
        if (openAt < 0 || openAt > limit) return null
        var depth = 0
        var i = openAt
        while (i < masked.length) {
            when (masked[i]) {
                '{' -> depth += 1
                '}' -> {
                    depth -= 1
                    if (depth == 0) return openAt to i
                }
            }
            i += 1
        }
        return null
    }

    /** One test method declared at member depth: its [name], and whether the annotation that made
     *  it count was `@TestFactory` — THE THIRD SHAPE in the file header, decided where it is read
     *  rather than re-derived later from the same source a second time. */
    data class Member(val name: String, val dynamic: Boolean)

    /** The names of the test methods declared at MEMBER depth of a class body.
     *
     *  A method counts when a @Test/@ParameterizedTest annotation precedes it at member depth: the
     *  fun that follows a pending annotation is the annotated one, so helper functions and local
     *  functions are not mistaken for tests. Nested classes are skipped wholesale — their members
     *  belong to them, and the XML reports them under their own qualified name.
     *
     *  Names are read from [original]: a backtick name is masked to x's in [masked], and the XML
     *  quotes the name, so the masked copy cannot supply it.
     *
     *  STICKY MATCHING, THE JVM WAY. The original anchors each probe at the current offset with a
     *  sticky (`y`) regex. `Matcher.region(i, end)` + `lookingAt()` is the JVM equivalent, but the
     *  default OPAQUE region bounds make `\b` see the region's start as if it were the start of the
     *  whole input — which would let `\bfun` match the "fun" inside an identifier like "myFunction"
     *  once the scan walks past its first letter. `useTransparentBounds(true)` restores the real
     *  surrounding characters for that check, matching what the sticky regex sees natively. */
    internal fun memberItems(original: String, masked: String): List<Member> {
        val items = mutableListOf<Member>()
        var depth = 0
        var i = 0
        var pending = 0
        var pendingFactory = false
        val memberMatcher = MEMBER_ITEM_PATTERN.matcher(masked).apply { useTransparentBounds(true) }
        val unmaskedMatcher = UNMASKED_FUN_PATTERN.matcher(original).apply { useTransparentBounds(true) }
        while (i < masked.length) {
            val ch = masked[i]
            if (ch == '{') {
                depth += 1
                i += 1
                continue
            }
            if (ch == '}') {
                depth -= 1
                i += 1
                continue
            }
            if (depth > 0) {
                // inside a nested class or a function body — its members are its own, and a local
                // fun in a test body is nobody's. Only brace depth is tracked here.
                i += 1
                continue
            }
            memberMatcher.region(i, masked.length)
            if (!memberMatcher.lookingAt()) {
                i += 1
                continue
            }
            if (memberMatcher.group(1) != null) {
                pending += 1
                // group(1) is the annotation NAME, which is the whole reason the pattern captures
                // it: the factory answer is read here, not by a second pass over the same text.
                if (memberMatcher.group(1) == FACTORY_ANNOTATION) pendingFactory = true
            } else {
                unmaskedMatcher.region(i, original.length)
                // Both alternations of MEMBER_ITEM_PATTERN carry the name in a group and group(1) was
                // null above, so group(2) participated: the elvis is the guard the compiler can carry,
                // and it fails loudly rather than skipping — a nameless match here would mean the
                // pattern changed under this scan, which is a missing denominator, not a missing name.
                val raw = (if (unmaskedMatcher.lookingAt()) unmaskedMatcher.group(1) else memberMatcher.group(2))
                    ?: error("member scan matched a declaration with no name at offset $i")
                val name = raw.trim('`')
                if (pending > 0) {
                    items.add(Member(name, pendingFactory))
                    pending = 0
                    pendingFactory = false
                }
            }
            i = memberMatcher.end()
        }
        return items
    }

    /** Every class in [source] that declares test methods, NESTED classes included. [module] and
     *  [path] only label the result ([TestClass.module], [TestClass.path]); neither is read back, so
     *  a fixture string exercises the identical code path a real file does (see TestDiscoveryTest).
     *
     *  A nested class gets its outer name as a qualifier (`Outer$Inner`), because that is how JUnit
     *  writes the row it produces — measured against app's `inner class Heads` inside
     *  SetupCommandTest, which ran as SetupCommandTest$Heads. */
    fun classesIn(source: String, module: String, path: String): List<TestClass> {
        val masked = mask(source)
        val spans = mutableListOf<Triple<String, Int, Int>>() // (class name, open brace, close brace)
        val classMatcher = CLASS_DECL_PATTERN.matcher(masked)
        while (classMatcher.find()) {
            val (openAt, closeAt) = bodyRange(masked, classMatcher.end()) ?: continue
            spans.add(Triple(classMatcher.group(1), openAt, closeAt))
        }
        val found = mutableListOf<TestClass>()
        for (span in spans) {
            val (name, openAt, closeAt) = span
            var outer: String? = null
            for (other in spans) {
                if (other === span) continue
                val (otherName, otherOpen, otherClose) = other
                if (otherOpen < openAt && closeAt < otherClose) outer = otherName
            }
            val qualified = if (outer != null) "$outer\$$name" else name
            val members = memberItems(source.substring(openAt + 1, closeAt), masked.substring(openAt + 1, closeAt))
            if (members.isNotEmpty()) {
                found.add(
                    TestClass(
                        module = module,
                        name = qualified,
                        path = path,
                        methods = members.map { it.name },
                        dynamicMethods = members.filter { it.dynamic }.map { it.name }.toSet(),
                    ),
                )
            }
        }
        return found
    }

    /** Every test class under [testSourceDir] (recursively), tagged with [module] — the I/O boundary
     *  around [classesIn]. Replaces the original's MODULE_HOMES glob: splice.test-discovery.gradle.kts
     *  hands in each subproject's own src/test/kotlin directory directly, so the module tag is
     *  supplied rather than cut out of a matched path. A missing directory (:console ships no Kotlin;
     *  a module with no tests yet) scans as empty, never an error — the next module's commit is what
     *  grows the denominator. */
    fun scanModuleSources(testSourceDir: File, module: String): List<TestClass> {
        if (!testSourceDir.isDirectory) return emptyList()
        return testSourceDir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .sortedBy { it.path }
            .flatMap { file -> classesIn(file.readText(), module, file.path) }
            .toList()
    }
}

// ── the observation: JUnit XML via the JDK's own DOM parser ──
/** The OBSERVATION: what JUnit's own XML reports RAN, read with the JDK's parser. */
object JUnitXml {

    /** The root element's `name` and `tests`, and the `name` of every `<testcase>` descendant. */
    data class ParsedJUnitXml(val rootName: String, val rootTests: Int, val testcaseNames: List<String>)

    // Configured once: DOCTYPE declarations are refused so a JUnit XML report — build output this
    // same module just wrote — is never parsed with external entity resolution on by default, a bad
    // habit not worth forming even for a trusted file. Each call below asks the factory for its own
    // fresh DocumentBuilder, which is the documented, non-thread-shared way to use it.
    private val DOCUMENT_BUILDER_FACTORY: DocumentBuilderFactory = DocumentBuilderFactory.newInstance().apply {
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
    }

    /** [xml]'s root `name`/`tests` and every `<testcase name>`, read with the JDK's own parser rather
     *  than the original's hand-rolled attribute scanner — bun ships no XML parser and the JVM does,
     *  so the reason for a local reader disappears in this port. What does NOT disappear is the
     *  die-loudly contract: a root missing `name` or `tests` throws, naming [sourceName], rather than
     *  falling back to a stem or returning an empty row that would read as "JUnit ran nothing" for a
     *  document that is merely unfamiliar. A document the parser itself cannot read throws its own
     *  SAXException/IOException, which is the same contract by construction. */
    fun parseJUnitXml(xml: String, sourceName: String): ParsedJUnitXml {
        val root = DOCUMENT_BUILDER_FACTORY.newDocumentBuilder()
            .parse(InputSource(StringReader(xml)))
            .documentElement
        val name = root?.getAttribute("name")?.ifBlank { null }
        val testsRaw = root?.getAttribute("tests")?.ifBlank { null }
        if (root == null || name == null || testsRaw == null) {
            throw IllegalStateException(
                "$sourceName: JUnit XML root is missing name/tests — this reader dies loudly on a " +
                    "document it cannot trust rather than return an empty row for it",
            )
        }
        val tests = testsRaw.toIntOrNull()
            ?: throw IllegalStateException("$sourceName: JUnit XML root's tests=\"$testsRaw\" is not an integer")
        val testcaseNames = mutableListOf<String>()
        val nodes = root.getElementsByTagName("testcase")
        for (idx in 0 until nodes.length) {
            // Read the attribute through Node's own map rather than casting to Element:
            // getElementsByTagName yields elements BY CONTRACT, so casting the node could only ever be an
            // unchecked assertion of what the API already guarantees, and an absent `name` reads
            // as the same empty string either way.
            val caseName = nodes.item(idx).attributes?.getNamedItem("name")?.nodeValue.orEmpty()
            testcaseNames.add(caseName.replace("()", ""))
        }
        return ParsedJUnitXml(name, tests, testcaseNames)
    }

    /** [parseJUnitXml] over a real file — the I/O boundary. */
    fun parseJUnitXml(file: File): ParsedJUnitXml = parseJUnitXml(file.readText(), file.path)

    /** The simple class name and [XmlRow] one JUnit XML document describes. */
    fun xmlRowFrom(xml: String, sourceName: String): Pair<String, XmlRow> {
        val parsed = parseJUnitXml(xml, sourceName)
        return parsed.rootName.substringAfterLast('.') to XmlRow(parsed.rootTests, parsed.testcaseNames.toSet())
    }

    /** [xmlRowFrom] over a real file — the I/O boundary. */
    fun xmlRowFrom(file: File): Pair<String, XmlRow> = xmlRowFrom(file.readText(), file.path)

    /** Every module's XML rows, merged across every Test task's own output directory — a module can
     *  have more than one producer (:app's codeModePackagedTest reruns two classes against the
     *  packaged shadow jar, alongside the module's normal `test` task, each into its OWN
     *  junitXml.outputLocation). A class name observed by more than one producer keeps the LOWER
     *  observed count and the UNION of testcase names: a discovery regression unique to either
     *  producer still reds instead of being silently masked by the other producer's row for the same
     *  class. */
    fun scanModuleXml(xmlDirs: List<File>): Map<String, XmlRow> {
        val merged = mutableMapOf<String, XmlRow>()
        for (dir in xmlDirs) {
            for ((name, row) in scanXmlDirectory(dir)) {
                val existing = merged[name]
                merged[name] =
                    if (existing == null) row else XmlRow(minOf(existing.count, row.count), existing.names + row.names)
            }
        }
        return merged
    }

    /** Every per-class row under a single directory — one Test task's own junitXml.outputLocation,
     *  one document per class. */
    private fun scanXmlDirectory(xmlDir: File): Map<String, XmlRow> {
        val files = xmlDir.takeIf { it.isDirectory }
            ?.listFiles { candidate -> candidate.isFile && candidate.extension == "xml" }
            ?.sortedBy { it.path }
            ?: emptyList()
        return files.associate { xmlRowFrom(it) }
    }
}

// ── the comparison ──
/** The COMPARISON: the denominator against the observation, as problems, as a census, and
 *  as the one line the task prints when it is green. */
object TestDiscovery {

    /** Every problem between [classes] (the denominator) and [xmlByModule] (the observation, module ->
     *  simple class name -> row), by name; empty means green. [dispositions] and [moduleDispositions]
     *  default to the real [DISPOSITIONS]/[MODULE_DISPOSITIONS] tables above but are parameters —
     *  never module-level mutable state the way the original's `let DISPOSITIONS` was — so a test can
     *  supply its own table without mutating the real one (see TestDiscoveryTest). */
    fun audit(
        classes: List<TestClass>,
        xmlByModule: Map<String, Map<String, XmlRow>>,
        dispositions: Map<String, Disposition> = DISPOSITIONS,
        moduleDispositions: Map<String, String> = MODULE_DISPOSITIONS,
    ): List<String> {
        if (classes.isEmpty()) {
            return listOf(
                "no test class with a @Test method was parsed from any subproject's src/test/kotlin " +
                    "— refusing to pass vacuously, because a green over an empty denominator is the " +
                    "very signal this wall exists to distrust",
            )
        }
        if (xmlByModule.isEmpty()) {
            return listOf(
                "no JUnit XML found under any subproject's test-results directory — a checker " +
                    "reading an empty results directory is the bug it is hunting; verifyTestDiscovery " +
                    "depends on every Test task, so an empty read here means that dependency itself " +
                    "broke",
            )
        }

        val problems = mutableListOf<String>()
        for (testClass in classes) {
            val moduleRows = xmlByModule[testClass.module] ?: emptyMap()
            if (moduleRows.isEmpty()) {
                val reason = moduleDispositions[testClass.module]
                when {
                    reason == null -> problems.add(
                        "${testClass.module}: ${testClass.name} declares ${testClass.count} test " +
                            "method(s) but the module produced NO XML at all — either its tests " +
                            "never ran or its test task is disabled; disposition the module with a reason",
                    )
                    reason.isBlank() -> problems.add(
                        "${testClass.module}: module disposition carries NO reason — an " +
                            "undispositioned silence is exactly what this wall refuses",
                    )
                }
                continue
            }
            val row = moduleRows[testClass.name]
            if (row == null) {
                problems.add(
                    "${testClass.module}: ${testClass.name} declares ${testClass.count} test " +
                        "method(s) and produced NO XML row — JUnit never ran the class",
                )
                continue
            }
            val observed = row.count
            val missing = testClass.methods.filter { it !in row.names }
            when {
                observed < testClass.count -> problems.add(
                    "NOT DISCOVERED: ${testClass.module}:${testClass.name} declares " +
                        "${testClass.count} test method(s), the XML reports $observed" +
                        (if (missing.isNotEmpty()) "; never ran: ${missing.joinToString(", ")}" else "") +
                        " (${testClass.path})",
                )
                // THE THIRD SHAPE (file header, SHAPES): a class holding a @TestFactory expands by
                // a factor nobody can write down — the factory returns one DynamicTest per item in
                // a list that is EXPECTED to grow — so any count AT OR ABOVE declared is its
                // expected shape and it is never asked for a disposition. Measured 2026-09-21:
                // ReleaseReadinessLawTest ran 49 the day this was decided and 56 two days later,
                // for the healthiest possible reason. Observed BELOW declared still reds, in the
                // branch above and unconditionally: the factory method never running, or its
                // expansion collapsing to nothing, is exactly the hazard this wall exists to catch.
                observed > testClass.count && testClass.dynamicMethods.isEmpty() -> {
                    val entry = dispositions[testClass.name]
                    when {
                        entry == null -> problems.add(
                            "HIGHER COUNT, no disposition: ${testClass.module}:${testClass.name} " +
                                "declares ${testClass.count} test method(s) but ran $observed — if " +
                                "that expansion is legitimate, add it to DISPOSITIONS with a written reason",
                        )
                        entry.reason.isBlank() -> problems.add(
                            "${testClass.module}:${testClass.name} carries a disposition with NO " +
                                "reason — a blank reason is an absence wearing a label",
                        )
                        observed != entry.expectedCount -> problems.add(
                            "${testClass.module}:${testClass.name} ran $observed cases, not the " +
                                "${entry.expectedCount} its disposition was written for — the " +
                                "expansion moved, so the disposition is stale and must be re-earned",
                        )
                    }
                }
            }
        }
        return problems
    }

    // ── the census (the --report verb, ported as a function instead of a CLI arm) ──

    /** Every declared class against its observed XML count, ordered by module then name, followed by
     *  every XML row with no class in source (STALE-XML — reported, never failed; see WHAT IT CANNOT
     *  SEE in the file header). Returns the report as text instead of writing to stdout, so it stays a
     *  pure function the plugin task can print, log, or write to a file. */
    fun census(
        classes: List<TestClass>,
        xmlByModule: Map<String, Map<String, XmlRow>>,
        dispositions: Map<String, Disposition> = DISPOSITIONS,
    ): String {
        val out = StringBuilder()
        out.append("tests-are-discovered: ${classes.size} test class(es) parsed from source\n")
        for (testClass in classes.sortedWith(compareBy({ it.module }, { it.name }))) {
            val observed = xmlByModule[testClass.module]?.get(testClass.name)?.count
            val mark = when {
                observed == null -> "NO-XML"
                observed < testClass.count -> "SHORT"
                observed > testClass.count -> dispositions[testClass.name]?.reason
                    ?: if (testClass.dynamicMethods.isEmpty()) "HIGHER-NO-REASON" else "DYNAMIC"
                else -> "OK"
            }
            out.append(
                "  ${mark.padEnd(20)} ${testClass.module.padEnd(26)} ${testClass.name.padEnd(44)} " +
                    "declared=${testClass.count} xml=${observed?.toString() ?: "none"}\n",
            )
        }
        val known = classes.map { "${it.module} ${it.name}" }.toSet()
        val stale = xmlByModule.flatMap { (module, entries) -> entries.keys.map { module to it } }
            .filterNot { "${it.first} ${it.second}" in known }
            .sortedWith(compareBy({ it.first }, { it.second }))
        for ((module, name) in stale) {
            out.append("  STALE-XML            ${module.padEnd(26)} ${name.padEnd(44)} (no class in source)\n")
        }
        return out.toString()
    }

    /** The one-line census verifyTestDiscovery prints on success: total classes scanned, total
     *  @Test/@ParameterizedTest methods declared, and total testcases observed in XML (observed can
     *  exceed declared when a disposition covers a legitimate expansion — [audit] already proved
     *  that's fine before this line ever prints). */
    fun summaryLine(classes: List<TestClass>, xmlByModule: Map<String, Map<String, XmlRow>>): String {
        val declared = classes.sumOf { it.count }
        val observed = classes.sumOf { xmlByModule[it.module]?.get(it.name)?.count ?: 0 }
        return "tests-are-discovered: ${classes.size} class(es), $declared declared, $observed observed"
    }
}
