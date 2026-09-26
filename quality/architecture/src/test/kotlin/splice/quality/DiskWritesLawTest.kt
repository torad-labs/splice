// NEW: V4-262 — every file splice writes has an entry in README.md's "What splice keeps on your disk"
// (Marlin's disk-writes audit, 2026-09-26, at 99244e63a).
//
// WHY THIS EXISTS. The site said splice saves no prompts or replies unless you turn that on, and the
// audit found six files holding content with nothing turned on. A section a reader can trust stays
// true only while nothing starts writing a file it does not list, and a new write is one line in any
// of 900 main source files. So the section is the disposition and this law is its ratchet.
//
// DENOMINATOR, FROM THE SOURCE (§24). Every Kotlin file under every module's src/main that the
// build's project map names (KotlinText.kotlinFiles), read on disk, so an untracked file counts. A
// file WRITES when its code, comments and strings blanked, calls a JDK or Kotlin primitive that
// creates, replaces, appends, copies, moves or links a file, opens a channel with any option but a
// bare READ, or redirects a process into a file; or when a string literal holds a shell redirect into
// a quoted, expanded path (`>> \"\$B\"`), which the shell the file spawns writes. A HELPER that writes
// whatever path its caller names is not a site: its callers are, found by the helper's call pattern.
//
// DISPOSITION. The section names each writing file by its file name (its module path when two writing
// files share a name). FAIL-CLOSED BOTH WAYS: UNLISTED, a writing file the section does not name, fails
// by path and line; STALE, a `.kt` name the section carries that writes nothing, fails too, so an entry
// cannot outlive its subject. A helper or a not-a-file call declared here that no longer matches is
// stale the same way.
//
// NOT CAUGHT, and written down. A write through a third-party library handed a path, a shell redirect
// to an unquoted or literal path, reflection, and files written by a program splice only starts (rig's
// installer, the JVM's own crash files). The section's prose names the ones it knows.
package splice.quality

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

internal object DiskWrites {
    const val README: String = "README.md"
    const val HEADING: String = "## What splice keeps on your disk"

    /** A call whose effect is a file's bytes on disk: created, replaced, appended, copied, moved or linked. */
    private val PRIMITIVE = Regex(
        """\bFiles\s*\.\s*(?:write|writeString|newOutputStream|newBufferedWriter|copy|move|createFile|""" +
            """createTempFile|createSymbolicLink|createLink)\s*\(|""" +
            """\b(?:FileOutputStream|FileWriter|RandomAccessFile|PrintWriter)\s*\(|""" +
            """\.\s*(?:writeText|writeBytes|appendText|appendBytes|outputStream|bufferedWriter|printWriter)\s*\(|""" +
            """\bRedirect\s*\.\s*(?:to|appendTo)\s*\(""",
    )

    /** A channel writes unless its only option is READ. */
    private val CHANNEL = Regex("""\b(?:FileChannel\s*\.\s*open|Files\s*\.\s*newByteChannel)\s*\(""")
    private val READ_OPTION = Regex("""\bREAD\b""")
    private val WRITE_OPTION = Regex("""\b(?:WRITE|APPEND|CREATE|CREATE_NEW|TRUNCATE_EXISTING|DELETE_ON_CLOSE)\b""")

    /** A shell redirect into a quoted, expanded path, as Kotlin spells it inside a string literal. */
    private val SHELL_REDIRECT = Regex("""(?<![0-9&])>>?\s*\\"\\\$""")

    private val KT_NAME = Regex("""[\w./-]*\b[A-Z]\w*\.kt\b""")
    private val SPACE = Regex("""\s+""")

    /** A file that writes whatever path its caller names, found at its callers by [call]. */
    data class Helper(val path: String, val call: Regex, val reason: String)

    /** A call the patterns match that writes no file, by the file it is in and the call's text. */
    data class NotAFile(val path: String, val call: String, val reason: String)

    data class Rules(val helpers: List<Helper>, val notAFile: List<NotAFile>)

    val SHIPPED: Rules = Rules(
        helpers = listOf(
            Helper(
                "core/src/main/kotlin/splice/core/util/SecureFile.kt",
                Regex("""\b(writeAtomic0600|createNew0600)\s*\("""),
                "the owner-only writes: the atomic replace every credential and state file goes through, and the " +
                    "exclusive create of the first-run splice.toml",
            ),
            Helper(
                "core/src/main/kotlin/splice/core/util/JsonlSink.kt",
                Regex("""\bJsonlSink\s*\.\s*appendLine\s*\("""),
                "the locked JSONL append; it also keeps the file's `.lock` and one rolled `.1` beside it",
            ),
            Helper(
                "core/src/main/kotlin/splice/core/storage/ActivityDays.kt",
                Regex("""\bActivityDays\s*\("""),
                "one JSONL file per UTC day in a store's directory, through JsonlSink",
            ),
        ),
        notAFile = listOf(
            NotAFile(
                "integrations/mcp/src/main/kotlin/splice/control/mcp/HostedServer.kt",
                ".bufferedWriter(",
                "a hosted MCP server's stdin: the process's output stream, not a file",
            ),
            NotAFile(
                "integrations/mcp/src/main/kotlin/splice/control/mcp/McpContainment.kt",
                "Files.writeString(",
                "/proc/<pid>/oom_score_adj of a hosted MCP child: a kernel attribute that ends with the process",
            ),
        ),
    )

    /** One writing file: its path from the root, and the first line and call that make it one. */
    data class Site(val path: String, val line: Int, val call: String) {
        val name: String get() = path.substringAfterLast('/')
    }

    data class Audit(val sites: List<Site>, val problems: List<String>)

    fun audit(files: List<File>, root: File, readme: String?, rules: Rules = SHIPPED): Audit {
        val scan = Scan(rules)
        val sites = files.mapNotNull { file ->
            scan.site(file.relativeTo(root).invariantSeparatorsPath, file.readText())
        }
        val problems = scan.stale()
        val section = readme?.let(::section)
        if (section == null) {
            problems += "NO SECTION: $README has no line \"$HEADING\"; every file splice writes is listed there"
        } else {
            problems += unlisted(sites, section) + stale(sites, section)
        }
        return Audit(sites, problems)
    }

    /** The lines from [HEADING] up to the next `## ` heading, or null when the heading is missing. */
    fun section(readme: String): String? {
        val lines = readme.lines()
        val start = lines.indexOfFirst { it.trim() == HEADING }
        if (start < 0) return null
        val end = (start + 1 until lines.size).firstOrNull { lines[it].startsWith("## ") } ?: lines.size
        return lines.subList(start, end).joinToString("\n")
    }

    private fun unlisted(sites: List<Site>, section: String): List<String> {
        val named = KT_NAME.findAll(section).map { it.value }.toSet()
        val shared = sites.groupBy { it.name }.filterValues { it.size > 1 }.keys
        val listed = { site: Site ->
            named.any { it.endsWith(site.path) } || (site.name !in shared && site.name in named)
        }
        return sites.filterNot(listed).map { site ->
            val how = if (site.name in shared) "its module path (two writing files are ${site.name})" else site.name
            "UNLISTED: ${site.path}:${site.line} writes a file (${site.call}) and the section does not name $how"
        }
    }

    private fun stale(sites: List<Site>, section: String): List<String> =
        KT_NAME.findAll(section).map { it.value }.distinct().filter { name ->
            sites.none { site -> name == site.name || site.path.endsWith(name.trimStart('.', '/')) }
        }.map { "STALE: the section names $it, which writes no file in any module's src/main" }.toList()

    /** One pass over the files, remembering which declared helpers and exemptions were met. */
    private class Scan(private val rules: Rules) {
        private val helperFiles = mutableSetOf<Helper>()
        private val called = mutableSetOf<Helper>()
        private val exempted = mutableSetOf<NotAFile>()

        fun site(path: String, source: String): Site? {
            val helper = rules.helpers.firstOrNull { it.path == path }
            if (helper != null) {
                helperFiles += helper
                return null
            }
            val code = KotlinText.blankCommentsAndStrings(source)
            val hits = primitives(path, code) + channels(code) + helperCalls(code) + shellRedirects(source)
            val first = hits.minByOrNull { it.first } ?: return null
            return Site(path, KotlinText.lineOf(source, first.first), first.second)
        }

        fun stale(): MutableList<String> {
            val problems = mutableListOf<String>()
            rules.helpers.filter { it !in helperFiles }.forEach { problems += "STALE HELPER: ${it.path} is gone" }
            rules.helpers.filter { it !in called }.forEach { problems += "STALE HELPER: ${it.path} is called nowhere" }
            rules.notAFile.filter { it !in exempted }.forEach {
                problems += "STALE NOT-A-FILE: ${it.path} no longer calls ${it.call}"
            }
            return problems
        }

        private fun primitives(path: String, code: String): List<Pair<Int, String>> =
            PRIMITIVE.findAll(code).mapNotNull { match ->
                val call = match.value.replace(SPACE, "")
                val exempt = rules.notAFile.firstOrNull { it.path == path && it.call == call }
                exempt?.let { exempted += it }
                if (exempt == null) match.range.first to call else null
            }.toList()

        private fun channels(code: String): List<Pair<Int, String>> = CHANNEL.findAll(code).mapNotNull { match ->
            val close = KotlinText.closeParen(code, match.range.last) ?: code.length
            val args = code.substring(match.range.last, close)
            val readOnly = READ_OPTION.containsMatchIn(args) && !WRITE_OPTION.containsMatchIn(args)
            if (readOnly) null else match.range.first to match.value.replace(SPACE, "")
        }.toList()

        private fun helperCalls(code: String): List<Pair<Int, String>> = rules.helpers.flatMap { helper ->
            helper.call.findAll(code).map { match ->
                called += helper
                match.range.first to match.value.replace(SPACE, "")
            }.toList()
        }

        private fun shellRedirects(source: String): List<Pair<Int, String>> {
            val kinds = KotlinText.kinds(source)
            return SHELL_REDIRECT.findAll(source).filter { kinds[it.range.first] == KotlinText.STRING }
                .map { it.range.first to "a shell redirect ${it.value}" }.toList()
        }
    }
}

class DiskWritesLawTest {
    private val map = ProjectMap.fromSystemProperties()

    private fun readme(): String? = File(map.root, DiskWrites.README).takeIf { it.isFile }?.readText()

    private fun live(extra: List<File> = emptyList(), readme: String? = readme()): DiskWrites.Audit =
        DiskWrites.audit(KotlinText.kotlinFiles(map) + extra, map.root, readme)

    @Test
    fun `every file splice writes has an entry in the README's disk section - V4-262`() {
        val audit = live()
        assertTrue(audit.sites.size > 40) {
            "the walk found ${audit.sites.size} writing file(s): the census is broken, and a law that reads no " +
                "writes passes vacuously"
        }
        assertTrue(audit.problems.isEmpty()) {
            audit.problems.joinToString("\n  - ", prefix = "DISK WRITES (V4-262) violated:\n  - ") +
                "\n\nEvery writing file, as the census read them:\n" +
                audit.sites.joinToString("\n") { "  ${it.path}:${it.line}  ${it.call}" }
        }
    }

    /** One module whose sources each arm replaces, audited with [rules] and the README text the arm hands in. */
    private class Tree(val root: File, val rules: DiskWrites.Rules = DiskWrites.Rules(emptyList(), emptyList())) {
        private val synthetic = ProjectMap.parse(root, ":core=core", setOf("build"))

        fun write(vararg sources: Pair<String, String>) {
            val dir = File(root, "core/src/main/kotlin/splice/core")
            dir.mkdirs()
            dir.listFiles().orEmpty().forEach { it.delete() }
            sources.forEach { (name, text) -> File(dir, name).writeText(text) }
        }

        fun audit(readme: String?): List<String> =
            DiskWrites.audit(KotlinText.kotlinFiles(synthetic), root, readme, rules).problems
    }

    @Test
    fun `the law can actually fail - unlisted, stale and no section - V4-262`(@TempDir root: File) {
        with(Tree(root)) {
            write("Store.kt" to STORE, "Quiet.kt" to QUIET)
            val green = audit(readmeNaming("Store.kt"))
            assertEquals(emptyList<String>(), green, "a listed writer and a quiet file: GREEN")
            write("Store.kt" to STORE, "Quiet.kt" to QUIET, "Mutant.kt" to MUTANT)
            assertHit(audit(readmeNaming("Store.kt")), "UNLISTED", "Mutant.kt:4", "Files.write(") {
                "an added Files.write with no entry must be RED BY NAME and line"
            }
            write("Store.kt" to STORE)
            assertHit(audit(readmeNaming("Store.kt", "Gone.kt")), "STALE", "Gone.kt") {
                "a name that writes nothing is STALE"
            }
            assertHit(audit("# splice\n"), "NO SECTION") { "a README without the section is RED" }
            assertHit(audit(null), "NO SECTION") { "no README at all is RED" }
        }
    }

    @Test
    fun `the law reads writes, not words, and follows a helper to its callers - V4-262`(@TempDir root: File) {
        with(Tree(root)) {
            write("Quiet.kt" to QUIET)
            assertHit(audit(readmeNaming("Quiet.kt")), "STALE", "Quiet.kt") {
                "a READ channel, a comment and a string naming Files.write are no write"
            }
            write("Shell.kt" to SHELL)
            assertHit(audit(readmeNaming()), "UNLISTED", "Shell.kt", "shell redirect") {
                "a shell append into a quoted path writes"
            }
        }
        val call = Regex("""\bwriteAtomic0600\s*\(""")
        val secure = DiskWrites.Helper("core/src/main/kotlin/splice/core/SecureFile.kt", call, "")
        val stdin = DiskWrites.NotAFile("core/src/main/kotlin/splice/core/Pipe.kt", ".bufferedWriter(", "")
        with(Tree(root, DiskWrites.Rules(listOf(secure), listOf(stdin)))) {
            write("SecureFile.kt" to STORE, "Stored.kt" to HELPED, "Pipe.kt" to PIPE)
            assertEquals(emptyList<String>(), audit(readmeNaming("Stored.kt")), "the helper's caller is the site")
            write("SecureFile.kt" to STORE, "Pipe.kt" to QUIET)
            val stale = audit(readmeNaming())
            assertHit(stale, "STALE HELPER", "called nowhere") { "a helper no file calls is STALE" }
            assertHit(stale, "STALE NOT-A-FILE", "Pipe.kt") { "an exemption that matches nothing is STALE" }
        }
    }

    /** The dispatch's mutation, on the SHIPPED tree and README: one Files.write in a main source set
     *  with no entry reds by name, and it is the only red. */
    @Test
    fun `the law can actually fail - against the shipped tree - V4-262`(@TempDir root: File) {
        val mutant = File(root, "zz-selftest/src/main/kotlin/splice/selftest/SelftestDump.kt")
        mutant.parentFile.mkdirs()
        mutant.writeText(MUTANT)
        val grown = live(listOf(mutant)).problems
        assertHit(grown, "UNLISTED", "SelftestDump.kt:4", "Files.write(") {
            "a new write with no entry must be RED BY NAME"
        }
        assertEquals(1, grown.size, "exactly the mutant: $grown")
        assertHit(live(readme = readme()?.replace(DiskWrites.HEADING, "## Elsewhere")).problems, "NO SECTION") {
            "the shipped README without its heading must be RED"
        }
    }

    private companion object {
        fun readmeNaming(vararg names: String): String =
            "# splice\n\n${DiskWrites.HEADING}\n\n| File | Written by |\n| --- | --- |\n" +
                names.joinToString("") { "| state/x | `$it` |\n" } + "\n## License\n\nnot `Later.kt`\n"

        val STORE = """
            package splice.core
            import java.nio.file.Files
            import java.nio.file.Path
            fun keep(p: Path) = Files.writeString(p, "x")
        """.trimIndent()

        val MUTANT = """
            package splice.core
            import java.nio.file.Files
            import java.nio.file.Path
            fun dump(p: Path, b: ByteArray) = Files.write(p, b)
        """.trimIndent()

        val QUIET = """
            package splice.core
            import java.nio.channels.FileChannel
            import java.nio.file.Path
            import java.nio.file.StandardOpenOption
            // Files.write(p, b) in a comment writes nothing
            val said = "Files.writeString(p, x) in a string writes nothing, nor does a -> ${'$'}b"
            fun read(p: Path) = FileChannel.open(p, StandardOpenOption.READ).use { it.size() }
        """.trimIndent()

        val SHELL = """
            package splice.core
            const val RUN = "exec >> \"\${'$'}d/run.log\" 2>&1 < /dev/null"
        """.trimIndent()

        val HELPED = """
            package splice.core
            import java.nio.file.Path
            fun save(p: Path) = SecureFile.writeAtomic0600(p, "k")
        """.trimIndent()

        val PIPE = """
            package splice.core
            fun feed(p: Process) = p.outputStream.bufferedWriter().use { it.write("x") }
        """.trimIndent()
    }
}
