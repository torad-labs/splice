// NEW: V4-95 — every main-source class that declares itself AutoCloseable is CLOSED from a main
// source (ported from checks/autocloseable-closed.ts, restructure PR 6).
//
// THE LAW. Implementing AutoCloseable is a promise that the type owns something the JVM will not
// reclaim on its own: a process, a file lock, a channel, a thread. The promise is only kept if some
// PRODUCTION path actually calls `close()`. A closeable whose only `close()` callers live in
// `src/test` has the shape of a managed resource and the behaviour of a leak — and the test suite
// is green, because the tests are precisely the code that closes it.
//
// THE SCAR (ARCH-AUDIT 2026-09-17, audit D row 6). `JvmCodeModeRuntime` implements
// `CodeModeRuntime`, which extends `AutoCloseable`, and its `close()` shuts down the worker pool.
// It was constructed in production and nothing in any main source ever closed it; the only
// `.close()`/`.use {}` callers were tests.
//
// THE DENOMINATOR, FROM THE SOURCE, AND TRANSITIVELY (§24). Every `class`/`interface`/`object`
// declaration under every module's src/main is parsed off disk with its supertype list, and the
// closeable set is the TRANSITIVE closure of {AutoCloseable, Closeable} over that graph. A
// direct-mention denominator would have reported the audit's own finding as absent.
//
// DISPOSITION. Every concrete closeable gets exactly one, and absence is not one: closed
// (main-source evidence), allowlisted (a DATED entry with a written reason), or RED by name with
// its declaration site. Interfaces are excluded by design: `CodeModeRuntime : AutoCloseable`
// declares the contract, it does not own a resource.
//
// EVIDENCE that a type is closed, in main sources only: construct-and-use (`T(…).use { }`), a
// handle name (`val lock = DaemonLock(path)` … `lock.close()`), a method reference (`lease::close`),
// or a close through a closeable INTERFACE-typed handle (`config.runtime.close()` closes the single
// concrete implementer). NOT CAUGHT: whether close is reached on every PATH, a handle closed through
// a helper's differently-typed parameter, and anonymous closeables (`AutoCloseable { … }`).
package splice.quality

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

internal object AutoCloseableClosed {
    private val SEEDS = setOf("AutoCloseable", "Closeable", "java.lang.AutoCloseable", "java.io.Closeable")

    /** Concrete closeables that may go unclosed in main: name -> "YYYY-MM-DD: <reason>". EMPTY
     *  today, and that is a finding rather than an oversight. An undated or reasonless entry is
     *  itself a failure, because an exemption with no written reason is an absence wearing a label. */
    val ALLOWLIST: Map<String, String> = emptyMap()

    private val DECL = Regex(
        "^[ \\t]*(?:(?:public|internal|private|protected|abstract|open|final|sealed|data|value|inner|fun|enum|" +
            "annotation|expect|actual|companion)\\s+)*(class|interface|object)\\s+([A-Za-z_]\\w*)",
        RegexOption.MULTILINE,
    )

    // The independent second reading used as the parser-drift guard: a supertype list mentioning a seed.
    private val DIRECT_MENTION = Regex("[:,]\\s*(?:java\\.lang\\.|java\\.io\\.)?(?:AutoCloseable|Closeable)\\b")
    private val SUPERTYPE_NAME = Regex("^\\s*([A-Za-z_][\\w.]*)")
    private val DATED_REASON = Regex("^\\d{4}-\\d{2}-\\d{2}: \\S")

    data class Decl(val kind: String, val name: String, val supers: List<String>, val path: String, val line: Int)

    private fun nesting(c: Char): Int = when (c) {
        in "(<[" -> 1
        in ")>]" -> -1
        else -> 0
    }

    /** The index of the depth-0 ':' before the body opens, or -1. */
    private fun supertypeColon(tail: String): Int {
        var depth = 0
        for (i in tail.indices) {
            val c = tail[i]
            depth += nesting(c)
            val atTop = depth == 0
            if (atTop && c == ':') return i
            if (atTop && c == '{') break
        }
        return -1
    }

    /** The supertype names of a declaration, given the text after its name: the ':' that opens the
     *  supertype list at nesting depth 0, then depth-0 commas until the class body opens. */
    fun supertypes(tail: String): List<String> {
        val colon = supertypeColon(tail)
        if (colon < 0) return emptyList()
        val names = mutableListOf<String>()
        val buf = StringBuilder()
        var depth = 0
        for (c in tail.substring(colon + 1)) {
            depth += nesting(c)
            val atTop = depth == 0
            if (atTop && c == '{') break
            if (atTop && c == ',') {
                names += buf.toString()
                buf.setLength(0)
            } else {
                buf.append(c)
            }
        }
        names += buf.toString()
        return names.mapNotNull { raw -> SUPERTYPE_NAME.find(raw)?.groupValues?.get(1) }
    }

    private fun declaration(m: MatchResult, src: String, path: String): Decl {
        val after = m.range.last + 1
        val tail = src.substring(after, minOf(after + 2000, src.length))
        val line = src.substring(0, m.range.first).count { it == '\n' } + 1
        return Decl(m.groupValues[1], m.groupValues[2], supertypes(tail), path, line)
    }

    /** Every declaration in [sources] (path -> comment-and-string-blanked text), in path order. */
    fun declarations(sources: Map<String, String>): List<Decl> =
        sources.keys.sorted().flatMap { path ->
            val src = sources.getValue(path)
            DECL.findAll(src).map { m -> declaration(m, src, path) }.toList()
        }

    /** Transitive closure of the seeds over the declaration graph, by SIMPLE name — an
     *  over-approximation, and the safe direction: it can only add types to the audit. */
    fun closeableClosure(decls: List<Decl>): Set<String> {
        val closeable = mutableSetOf<String>()
        var changed = true
        while (changed) {
            changed = false
            for (d in decls) {
                if (d.name in closeable) continue
                if (d.supers.any { s -> s in SEEDS || s.substringAfterLast('.') in closeable }) {
                    closeable += d.name
                    changed = true
                }
            }
        }
        return closeable
    }

    /** Identifiers bound to [typeName]: declared-type properties and parameters, and `val x = T(…)`. */
    fun handleNames(sources: Map<String, String>, typeName: String): Set<String> {
        val esc = Regex.escape(typeName)
        val typed = Regex("\\b(?:val|var)?\\s*([A-Za-z_]\\w*)\\s*:\\s*(?:[A-Za-z_][\\w.]*\\.)?$esc\\s*\\??")
        val initialised = Regex(
            "\\b(?:val|var)\\s+([A-Za-z_]\\w*)\\s*(?::[^=\\n]*)?=\\s*(?:[A-Za-z_][\\w.]*\\.)?$esc\\s*\\(",
        )
        val names = mutableSetOf<String>()
        for (src in sources.values) {
            typed.findAll(src).forEach { names += it.groupValues[1] }
            initialised.findAll(src).forEach { names += it.groupValues[1] }
        }
        return names
    }

    /** The index of the ')' closing the '(' at [open] — bare paren counting, as the checker did —
     *  or the text's length when it never closes. */
    private fun argumentsClose(src: String, open: Int): Int {
        var depth = 0
        for (i in open until src.length) {
            if (src[i] == '(') depth += 1
            if (src[i] == ')') depth -= 1
            if (src[i] == ')' && depth == 0) return i
        }
        return src.length
    }

    /** `T(…).use` / `T(…)?.use` / `T(…).close()` — the construction closed on the spot. */
    fun constructAndUse(sources: Map<String, String>, typeName: String): Boolean {
        val pattern = Regex("\\b(?:[A-Za-z_][\\w.]*\\.)?${Regex.escape(typeName)}\\s*\\(")
        val closer = Regex("^\\s*\\??\\.(use\\b|close\\s*\\()")
        return sources.values.any { src ->
            pattern.findAll(src).any { m ->
                val close = argumentsClose(src, m.range.last)
                closer.containsMatchIn(src.substring(minOf(close + 1, src.length), minOf(close + 40, src.length)))
            }
        }
    }

    fun closedByName(sources: Map<String, String>, names: Set<String>): Boolean {
        for (name in names) {
            val closing = Regex("\\b${Regex.escape(name)}\\s*(?:\\?\\s*)?(?:\\.use\\b|\\.close\\s*\\(|::close\\b)")
            if (sources.values.any { closing.containsMatchIn(it) }) return true
        }
        return false
    }

    /** True when [superName] is a closeable INTERFACE that has main-source closing evidence:
     *  closing through the contract type closes whichever concrete implementation it holds. */
    private fun interfaceClosed(
        main: Map<String, String>,
        decls: List<Decl>,
        closeable: Set<String>,
        superName: String,
    ): Boolean {
        val base = superName.substringAfterLast('.')
        if (base !in closeable) return false
        if (decls.firstOrNull { it.name == base }?.kind != "interface") return false
        return constructAndUse(main, base) || closedByName(main, handleNames(main, base))
    }

    /** rel path -> blanked text of every .kt under `<module>/<sourceSet>`. */
    fun read(map: ProjectMap, vararg sourceSets: String): Map<String, String> {
        val out = linkedMapOf<String, String>()
        for (sourceSet in sourceSets) {
            for (file in KotlinText.kotlinFiles(map, sourceSet)) {
                out[KotlinText.rel(map, file)] = KotlinText.blankCommentsAndStrings(file.readText())
            }
        }
        return out
    }

    /** Parser-drift guard, computed a SECOND and independent way: a seed mentioned in a supertype
     *  list must have been attributed to at least one declaration. */
    private fun driftGuard(main: Map<String, String>, decls: List<Decl>): List<String> {
        val parsedDirect = decls.filter { d -> d.supers.any { it in SEEDS } }.map { it.name }.toSet()
        val mentioned = main.values.sumOf { DIRECT_MENTION.findAll(it).count() }
        if (mentioned == 0 || parsedDirect.isNotEmpty()) return emptyList()
        return listOf(
            "$mentioned supertype list(s) mention AutoCloseable/Closeable but the parser attributed NONE to a declaration — " +
                "supertypes() has drifted from the Kotlin it reads, so the closeable denominator is empty for a parser " +
                "reason, not a code reason.",
        )
    }

    private fun allowlistProblem(decl: Decl, reason: String): String? =
        if (DATED_REASON.containsMatchIn(reason)) {
            null
        } else {
            "${decl.path}:${decl.line} ${decl.name} — ALLOWLIST entry is not 'YYYY-MM-DD: <reason>'. An exemption with no " +
                "dated, written reason is an absence wearing a label."
        }

    /** The finding for a concrete closeable with no main-source closing evidence, or null. */
    private fun unclosedProblem(
        main: Map<String, String>,
        decls: List<Decl>,
        closeable: Set<String>,
        decl: Decl,
        joined: Lazy<String>,
    ): String? {
        if (constructAndUse(main, decl.name) || closedByName(main, handleNames(main, decl.name))) return null
        if (decl.supers.any { interfaceClosed(main, decls, closeable, it) }) return null
        val constructed = Regex(
            "\\b(?:[A-Za-z_][\\w.]*\\.)?${Regex.escape(decl.name)}\\s*\\(",
        ).containsMatchIn(joined.value)
        val why = if (constructed) {
            "constructed in a main source but never closed from one"
        } else {
            "never constructed in a main source either — dead in production, or its only construction moved to tests"
        }
        val via = decl.supers.joinToString(" -> ").ifEmpty { "AutoCloseable" }
        return "${decl.path}:${decl.line} ${decl.name} implements AutoCloseable/Closeable (via $via) and is $why. " +
            "Close it from production: `use { }`, a `close()` on a handle, or `handle::close` in a cleanup. A closeable " +
            "whose only close() callers are tests is a leak with a green suite."
    }

    fun audit(main: Map<String, String>, allowlist: Map<String, String> = ALLOWLIST): List<String> {
        if (main.isEmpty()) {
            return listOf(
                "no Kotlin main sources under any module the build declares — refusing to pass vacuously; a checker that " +
                    "reads nothing vouches for nothing.",
            )
        }
        val decls = declarations(main)
        val closeable = closeableClosure(decls)
        val problems = driftGuard(main, decls).toMutableList()
        if (closeable.isEmpty()) {
            problems += "the closeable closure is EMPTY — refusing to pass vacuously. Either no type in this tree implements " +
                "AutoCloseable/Closeable (then this checker has nothing to guard and should say so out loud) or the parse " +
                "failed."
            return problems
        }
        val concrete = decls.filter { it.name in closeable && it.kind != "interface" }
            .sortedWith(compareBy({ it.path }, { it.line }))
        val joined = lazy { main.values.joinToString("\n") }
        for (decl in concrete) {
            val reason = allowlist[decl.name]
            val problem = if (reason != null) {
                allowlistProblem(decl, reason)
            } else {
                unclosedProblem(main, decls, closeable, decl, joined)
            }
            if (problem != null) problems += problem
        }
        return problems
    }

    /** Reporting aid: where the type IS closed in tests, when it is not closed in main. */
    fun testOnlyClosers(tests: Map<String, String>, typeName: String): List<String> {
        val names = handleNames(tests, typeName) + typeName
        val hits = mutableSetOf<String>()
        for ((path, src) in tests) {
            for (name in names) {
                val re = Regex(
                    "\\b${Regex.escape(
                        name,
                    )}\\s*(?:\\([^()]*\\))?\\s*(?:\\?\\s*)?(?:\\.use\\b|\\.close\\s*\\(|::close\\b)",
                )
                for (m in re.findAll(src)) hits += "$path:${src.substring(0, m.range.first).count { it == '\n' } + 1}"
            }
        }
        return hits.sorted()
    }

    /** The report: each problem, then where src/test closes the type — the pointer a fix starts from. */
    fun render(problems: List<String>, tests: Map<String, String>): String =
        problems.joinToString(
            separator = "\n  - ",
            prefix = "AUTOCLOSEABLE CLOSED (V4-95) violated:\n  - ",
        ) { problem ->
            val name = problem.split(Regex("\\s+")).getOrElse(1) { "" }
            problem + testOnlyClosers(tests, name).joinToString("") { "\n      closed only here: $it" }
        }

    fun concreteCloseables(main: Map<String, String>): List<Decl> {
        val decls = declarations(main)
        val closure = closeableClosure(decls)
        return decls.filter { it.name in closure && it.kind != "interface" }
    }
}

class AutoCloseableClosedLawTest {
    private val map = ProjectMap.fromSystemProperties()

    @Test
    fun `every concrete AutoCloseable in main sources is closed from a main source - V4-95`() {
        val main = AutoCloseableClosed.read(map, "src/main")
        assertTrue(main.size > 10) {
            "the map yielded ${main.size} production file(s) — the walk is broken, and a law that reads no files passes vacuously."
        }
        val problems = AutoCloseableClosed.audit(main)
        assertTrue(problems.isEmpty()) {
            AutoCloseableClosed.render(problems, AutoCloseableClosed.read(map, "src/test", "src/testFixtures"))
        }
        assertTrue(
            AutoCloseableClosed.concreteCloseables(main).isNotEmpty(),
            "the tree ships at least one concrete closeable, or this law guards nothing",
        )
    }

    /** The synthetic two-module tree the red proof writes into, each arm replacing its files. */
    private class Tree(val root: File) {
        val synthetic = ProjectMap.parse(root, ":app=app;:upstream=upstream", setOf("build"))

        fun write(files: Map<String, String>) {
            listOf("app", "upstream").forEach { File(root, "$it/src").deleteRecursively() }
            for ((rel, body) in files) File(root, rel).apply { parentFile.mkdirs() }.writeText(body)
        }

        fun main() = AutoCloseableClosed.read(synthetic, "src/main")

        fun audit(allow: Map<String, String> = emptyMap()) = AutoCloseableClosed.audit(main(), allow)

        fun repr(hits: List<String>) = KotlinText.pyReprList(hits)
    }

    @Test
    fun `the law can actually fail - evidence forms and the two-hop leak - V4-95`(@TempDir root: File) {
        with(Tree(root)) {
            // 1. GREEN: every evidence form, plus the interface exclusion.
            write(mapOf(SPI to SPI_CONTRACT, APP to COMPLIANT_APP))
            assertEquals(emptyList<String>(), audit(), "1. the compliant tree must be GREEN")

            // 2. the BORING case: exactly one closeable, closed — and the same one NOT closed is RED, so
            //    case 2's green is a measurement of the evidence and not of an empty denominator.
            write(mapOf(APP to ONE_CLOSEABLE_APP))
            assertEquals(emptyList<String>(), audit(), "2. one-closeable tree, closed, must be GREEN")
            write(mapOf(APP to ONE_CLOSEABLE_APP.replace("        lock.close()\n", "")))
            assertHit(audit(), "DaemonLock") { "2b. the boring case must be able to FAIL" }

            // 3. RED BY NAME through a TWO-HOP interface chain, closed only from src/test — the scar.
            write(mapOf(SPI to SPI_CONTRACT, APP to LEAKY_APP, TEST to LEAKY_TEST))
            val hits = audit()
            assertHit(hits, "JvmCodeModeRuntime") { "3. a two-hop closeable closed only in tests must be RED BY NAME" }
            assertHit(hits, "never closed from one") { "3b. the RED must say it IS constructed in main" }
            assertTrue(
                hits.none {
                    it.split(Regex("\\s+")).getOrNull(1) == "CodeModeRuntime"
                },
            ) { "3c. the INTERFACE must not be reported, only the class, got: ${repr(hits)}" }
            assertEquals(
                1,
                hits.count {
                    it.contains("implements AutoCloseable")
                },
                "3e. exactly ONE finding is expected here, got: ${repr(hits)}",
            )
            val report = AutoCloseableClosed.render(hits, AutoCloseableClosed.read(synthetic, "src/test"))
            assertTrue("closed only here: $TEST:5" in report) {
                "3d. the report must point at the test-only closer by file and line, got:\n$report"
            }
        }
    }

    @Test
    fun `the law can actually fail - main closers, the allowlist and refusals - V4-95`(@TempDir root: File) {
        with(Tree(root)) {
            // 4. the same tree with the close moved into main goes GREEN — proves 3 failed for the
            //    stated reason (no main closer) and not for some incidental parse difference.
            write(
                mapOf(
                    SPI to SPI_CONTRACT,
                    APP to LEAKY_APP.replace("    fun build() = Wiring(runtime = JvmCodeModeRuntime())", "    fun build() {\n        val runtime = JvmCodeModeRuntime()\n        runtime.close()\n    }"),
                    TEST to LEAKY_TEST,
                ),
            )
            assertEquals(emptyList<String>(), audit(), "4. closing it from main must go GREEN")

            // 5. a dated allowlist entry is a disposition; an undated one is not.
            write(mapOf(SPI to SPI_CONTRACT, APP to LEAKY_APP, TEST to LEAKY_TEST))
            assertEquals(
                emptyList<String>(),
                audit(mapOf("JvmCodeModeRuntime" to "2026-09-17: selftest fixture, allowlisted on purpose.")),
                "5. a dated allowlist entry must be GREEN",
            )
            val hits = audit(mapOf("JvmCodeModeRuntime" to "because I said so"))
            assertHit(hits, "not 'YYYY-MM-DD") { "5b. an UNDATED allowlist entry must be RED" }

            // 6. refuse to pass vacuously: no closeables at all, and no sources at all.
            write(mapOf(APP to NO_CLOSEABLE_APP))
            assertHit(audit(), "refusing to pass vacuously") { "6. a tree with no closeable must REFUSE, not pass" }
            write(emptyMap())
            assertHit(audit(), "refusing to pass vacuously") { "6b. a tree with no main sources must REFUSE" }

            // 7. the interface-typed close is closing evidence; removing the close goes RED by name.
            write(mapOf(SPI to SPI_CONTRACT, APP to INTERFACE_CLOSED_APP))
            assertEquals(emptyList<String>(), audit(), "7. a close through the interface must be GREEN")
            write(
                mapOf(
                    SPI to SPI_CONTRACT,
                    APP to INTERFACE_CLOSED_APP.replace("    fun shutdown() { runtime.close() }", "    fun shutdown() = Unit"),
                ),
            )
            assertHit(audit(), "JvmCodeModeRuntime") { "7b. removing the interface-typed close must be RED by name" }
        }
    }

    private companion object {
        const val APP = "app/src/main/kotlin/splice/app/App.kt"
        const val SPI = "upstream/src/main/kotlin/splice/upstream/Spi.kt"
        const val TEST = "app/src/test/kotlin/RuntimeTest.kt"

        const val SPI_CONTRACT = """package splice.upstream

public interface CodeModeRuntime : AutoCloseable {
    public fun start(): Unit
}
"""

        // Each of the three evidence forms, once.
        const val COMPLIANT_APP = """package splice.app

internal class WorkerSession(private val start: WorkerStart) : AutoCloseable {
    override fun close() = Unit
}

public class DaemonLock(private val lockFile: Path) : AutoCloseable {
    override fun close() = Unit
}

internal class Lease(val label: String) : AutoCloseable {
    override fun close() = Unit
}

internal class Runner {
    fun one(start: WorkerStart) = WorkerSession(start).use { session -> session.toString() }

    fun two(path: Path) {
        val lock = DaemonLock(path)
        lock.close()
    }

    fun three(lease: Lease) {
        Cancellables.runCatchingCleanup(lease::close)
    }
}
"""

        // The scar: a two-hop closeable closed only from src/test.
        const val LEAKY_APP = """package splice.app

public class JvmCodeModeRuntime : CodeModeRuntime {
    override fun start() = Unit
    override fun close() = Unit
}

internal class Arm {
    fun build() = Wiring(runtime = JvmCodeModeRuntime())
}
"""
        const val LEAKY_TEST = """package splice.app

class CodeModeRuntimeTest {
    fun reclaims() {
        JvmCodeModeRuntime().use { runtime -> runtime.start() }
    }
}
"""

        // The interface-typed close: constructed in main, closed through the `CodeModeRuntime` port.
        const val INTERFACE_CLOSED_APP = """package splice.app

public class JvmCodeModeRuntime : CodeModeRuntime {
    override fun start() = Unit
    override fun close() = Unit
}

internal class Config(private val runtime: CodeModeRuntime) {
    fun shutdown() { runtime.close() }
}

internal class Arm {
    fun build() = Config(runtime = JvmCodeModeRuntime())
}
"""
        const val ONE_CLOSEABLE_APP = """package splice.app

public class DaemonLock(private val lockFile: Path) : AutoCloseable {
    override fun close() = Unit
}

internal class Runner {
    fun go(path: Path) {
        val lock = DaemonLock(path)
        lock.close()
    }
}
"""
        const val NO_CLOSEABLE_APP = """package splice.app

internal class Plain(val label: String) {
    fun go() = Unit
}
"""
    }
}
