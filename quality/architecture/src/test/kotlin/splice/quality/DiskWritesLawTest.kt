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
// EVERY WRITE, NOT THE FIRST (V4-287). The section names files, so a second write in a file it already
// names passed: the scan kept each file's first write, and a not-a-file entry exempted every match of
// its call. [DiskWrites.Rules.sites] records each writing file's write calls and how many of each, as
// measured, and is a ratchet both ways: a new write call in a file (GROWN) or a new writing file
// (UNRECORDED) is red where it is, to be read against the file's row before the record is raised; a
// count above the measurement (SHRUNK) or a file that writes nothing (STALE SITES) is red until it is
// lowered. A not-a-file entry exempts the number of matches it declares, and one more is a write.
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

    /** A call the patterns match that writes no file, by the file it is in and the call's text. It
     *  exempts [matches] of them in that file, no more: one more of the same call is a write (V4-287). */
    data class NotAFile(val path: String, val call: String, val reason: String, val matches: Int = 1)

    /** [sites]: each writing file's write calls and how many of each, the V4-287 ratchet. */
    data class Rules(val helpers: List<Helper>, val notAFile: List<NotAFile>, val sites: Map<String, Map<String, Int>>)

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
        sites = SHIPPED_SITES,
    )

    /** One writing file: its path from the root, the first line and call that make it one, and every
     *  write call it makes with the lines it makes it on. */
    data class Site(val path: String, val line: Int, val call: String, val calls: Map<String, List<Int>>) {
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
        problems += ratchet(sites, rules.sites)
        return Audit(sites, problems)
    }

    /** V4-287: each writing file's write calls against [recorded], both ways. */
    private fun ratchet(sites: List<Site>, recorded: Map<String, Map<String, Int>>): List<String> {
        val stale = (recorded.keys - sites.map { it.path }.toSet()).sorted()
        return sites.flatMap { site -> recorded[site.path]?.let { measured(site, it) } ?: listOf(unrecorded(site)) } +
            stale.map { "STALE SITES: the ratchet records $it, which writes no file" }
    }

    private fun unrecorded(site: Site): String =
        "UNRECORDED: ${site.path} writes (${site.calls.keys.joinToString()}) and the ratchet records no write " +
            "there: read each against the file's row, then add ${entry(site)} to the sites"

    /** [site]'s write calls against [was], the counts the ratchet records for it. */
    private fun measured(site: Site, was: Map<String, Int>): List<String> {
        val grown = site.calls.filter { (call, lines) -> lines.size > (was[call] ?: 0) }.map { (call, lines) ->
            "GROWN: ${site.path} calls $call at line(s) ${lines.joinToString()} and the ratchet records " +
                "${was[call] ?: 0}: a new write in a file the section names; read it against the file's row, " +
                "then record ${entry(site)}"
        }
        val shrunk = was.filter { (call, count) -> (site.calls[call]?.size ?: 0) < count }.map { (call, count) ->
            "SHRUNK: ${site.path} calls $call ${site.calls[call]?.size ?: 0} time(s) and the ratchet records " +
                "$count: record ${entry(site)}, so no room is left for the next write"
        }
        return grown + shrunk
    }

    /** [site]'s ratchet entry as DiskWrites.SHIPPED's sites spell it. */
    private fun entry(site: Site): String = "\"${site.path}\" to mapOf(" +
        site.calls.entries.sortedBy { it.key }.joinToString { (call, lines) -> "\"$call\" to ${lines.size}" } + ")"

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

        /** How many matches each not-a-file entry met, the ones past its declared count included. */
        private val exempted = mutableMapOf<NotAFile, Int>()

        fun site(path: String, source: String): Site? {
            val helper = rules.helpers.firstOrNull { it.path == path }
            if (helper != null) {
                helperFiles += helper
                return null
            }
            val code = KotlinText.blankCommentsAndStrings(source)
            val hits = (primitives(path, code) + channels(code) + helperCalls(code) + shellRedirects(source))
                .sortedBy { it.first }
            val first = hits.firstOrNull() ?: return null
            val calls = hits.groupBy({ it.second }, { KotlinText.lineOf(source, it.first) })
            return Site(path, KotlinText.lineOf(source, first.first), first.second, calls)
        }

        fun stale(): MutableList<String> {
            val problems = mutableListOf<String>()
            rules.helpers.filter { it !in helperFiles }.forEach { problems += "STALE HELPER: ${it.path} is gone" }
            rules.helpers.filter { it !in called }.forEach { problems += "STALE HELPER: ${it.path} is called nowhere" }
            rules.notAFile.forEach { entry ->
                val met = exempted[entry] ?: 0
                if (met == 0) problems += "STALE NOT-A-FILE: ${entry.path} no longer calls ${entry.call}"
                if (met in 1 until entry.matches) {
                    problems += "STALE NOT-A-FILE: ${entry.path} calls ${entry.call} $met time(s) and the exemption " +
                        "covers ${entry.matches}: lower it to $met"
                }
            }
            return problems
        }

        private fun primitives(path: String, code: String): List<Pair<Int, String>> =
            PRIMITIVE.findAll(code).mapNotNull { match ->
                val call = match.value.replace(SPACE, "")
                val exempt = rules.notAFile.firstOrNull { it.path == path && it.call == call }
                val met = exempt?.let { exempted.merge(it, 1, Int::plus) } ?: 0
                if (exempt != null && met <= exempt.matches) null else match.range.first to call
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
                .map { it.range.first to "a shell redirect" }.toList()
        }
    }
}

/** V4-287: each writing file's write calls and how many of each, measured at 3de98a1f8 (2026-09-26, 61
 *  writing files) and edited by hand when a write is added or removed; the ratchet prints the entry. */
private val SHIPPED_SITES: Map<String, Map<String, Int>> = mapOf(
    "app/src/main/kotlin/splice/app/DaemonBoundary.kt" to mapOf(
        "Files.move(" to 1,
        "Files.newBufferedWriter(" to 1,
        "Files.writeString(" to 1,
    ),
    "app/src/main/kotlin/splice/app/cli/status/DashboardCommand.kt" to mapOf("writeAtomic0600(" to 1),
    "app/src/main/kotlin/splice/app/daemon/DaemonLock.kt" to mapOf("FileChannel.open(" to 1),
    "app/src/main/kotlin/splice/app/head/HeadTraceStores.kt" to mapOf("ActivityDays(" to 1),
    "core/src/main/kotlin/splice/core/config/ConfigService.kt" to mapOf("writeAtomic0600(" to 1),
    "core/src/main/kotlin/splice/core/config/KeyStore.kt" to mapOf("FileChannel.open(" to 1, "writeAtomic0600(" to 1),
    "core/src/main/kotlin/splice/core/config/MgmtKey.kt" to mapOf("writeAtomic0600(" to 1),
    "core/src/main/kotlin/splice/core/config/TurnKey.kt" to mapOf("writeAtomic0600(" to 1),
    "core/src/main/kotlin/splice/core/model/ClientWindows.kt" to mapOf("Files.move(" to 1, "Files.writeString(" to 1),
    "core/src/main/kotlin/splice/core/topology/TopologyWriter.kt" to mapOf("writeAtomic0600(" to 2),
    "features/configuration/src/main/kotlin/splice/configuration/add/AddWrite.kt" to mapOf("writeAtomic0600(" to 1),
    "features/diagnostics/src/main/kotlin/splice/diagnostics/doctor/DoctorProbeWrite.kt" to mapOf(
        "Files.createTempFile(" to 1,
    ),
    "features/diagnostics/src/main/kotlin/splice/diagnostics/doctor/report/DoctorJsonReport.kt" to mapOf(
        "Files.writeString(" to 1,
    ),
    "features/diagnostics/src/main/kotlin/splice/diagnostics/doctor/report/DoctorReportFiles.kt" to mapOf(
        "Files.writeString(" to 1,
    ),
    "features/launch/src/main/kotlin/splice/launch/install/InstallLinker.kt" to mapOf("Files.createSymbolicLink(" to 1),
    "features/lifecycle/src/main/kotlin/splice/lifecycle/start/DaemonLaunch.kt" to mapOf("a shell redirect" to 2),
    "features/lifecycle/src/main/kotlin/splice/lifecycle/upgrade/SystemdUpgradeLauncher.kt" to mapOf(
        "Redirect.appendTo(" to 1,
        "a shell redirect" to 3,
    ),
    "features/lifecycle/src/main/kotlin/splice/lifecycle/upgrade/UpgradeActivation.kt" to mapOf("Files.copy(" to 1),
    "features/lifecycle/src/main/kotlin/splice/lifecycle/upgrade/UpgradeCommand.kt" to mapOf("Files.move(" to 1),
    "features/lifecycle/src/main/kotlin/splice/lifecycle/upgrade/UpgradeLayout.kt" to mapOf(
        "Files.copy(" to 2,
        "Files.createSymbolicLink(" to 1,
        "Files.move(" to 1,
    ),
    "features/lifecycle/src/main/kotlin/splice/lifecycle/upgrade/UpgradeLock.kt" to mapOf("FileChannel.open(" to 1),
    "features/lifecycle/src/main/kotlin/splice/lifecycle/upgrade/UpgradeRelease.kt" to mapOf("Files.write(" to 1),
    "features/lifecycle/src/main/kotlin/splice/lifecycle/upgrade/UpgradeRuns.kt" to mapOf("Files.writeString(" to 1),
    "features/lifecycle/src/main/kotlin/splice/lifecycle/upgrade/UpgradeWrapper.kt" to mapOf(
        "Files.copy(" to 2,
        "Files.move(" to 1,
    ),
    "features/models/src/main/kotlin/splice/models/discovery/RosterCache.kt" to mapOf("writeAtomic0600(" to 1),
    "features/sessions/src/main/kotlin/splice/sessions/activity/ActivityStore.kt" to mapOf("ActivityDays(" to 2),
    "features/sessions/src/main/kotlin/splice/sessions/teams/TeamStore.kt" to mapOf("writeAtomic0600(" to 2),
    "features/turns/src/main/kotlin/splice/head/compact/Compact.kt" to mapOf("JsonlSink.appendLine(" to 1),
    "features/turns/src/main/kotlin/splice/head/compaction/CompactionRecordings.kt" to mapOf("writeAtomic0600(" to 1),
    "features/turns/src/main/kotlin/splice/head/perf/PerfStats.kt" to mapOf(
        "Files.copy(" to 1,
        "JsonlSink.appendLine(" to 1,
    ),
    "features/turns/src/main/kotlin/splice/head/perf/SessionTotals.kt" to mapOf("writeAtomic0600(" to 1),
    "features/turns/src/main/kotlin/splice/head/usage/EconomicsStore.kt" to mapOf("writeAtomic0600(" to 1),
    "features/turns/src/main/kotlin/splice/head/usage/QuotaTracker.kt" to mapOf("writeAtomic0600(" to 1),
    "features/turns/src/main/kotlin/splice/head/usage/RateLimitFile.kt" to mapOf("writeAtomic0600(" to 1),
    "features/turns/src/main/kotlin/splice/head/usage/UsageRingFile.kt" to mapOf("writeAtomic0600(" to 1),
    "features/usage/src/main/kotlin/splice/usage/alerts/AlertStore.kt" to mapOf("writeAtomic0600(" to 2),
    "features/usage/src/main/kotlin/splice/usage/budgets/BudgetStore.kt" to mapOf("writeAtomic0600(" to 2),
    "integrations/claude-code/src/main/kotlin/splice/client/ClaudeConfigMaterializer.kt" to mapOf(
        "Files.createSymbolicLink(" to 1,
        "Files.move(" to 1,
        "writeAtomic0600(" to 2,
    ),
    "integrations/claude-code/src/main/kotlin/splice/client/ClaudeLoginFiles.kt" to mapOf("writeAtomic0600(" to 4),
    "integrations/claude-code/src/main/kotlin/splice/client/ClaudeLogins.kt" to mapOf("writeAtomic0600(" to 2),
    "integrations/claude-code/src/main/kotlin/splice/client/login/HookScriptFiles.kt" to mapOf(
        "Files.createTempFile(" to 2,
        "Files.move(" to 1,
        "Files.writeString(" to 2,
    ),
    "integrations/claude-code/src/main/kotlin/splice/client/login/LoginOutcomeFile.kt" to mapOf(
        "writeAtomic0600(" to 1,
    ),
    "integrations/claude-code/src/main/kotlin/splice/client/resume/ResumeAcrossHeads.kt" to mapOf("Files.copy(" to 3),
    "integrations/claude-code/src/main/kotlin/splice/client/resume/SessionOwnership.kt" to mapOf(
        "Files.createTempFile(" to 1,
        "Files.move(" to 1,
        "Files.writeString(" to 1,
    ),
    "integrations/claude-code/src/main/kotlin/splice/client/resume/SessionRegistryLink.kt" to mapOf(
        "Files.createSymbolicLink(" to 1,
        "Files.move(" to 1,
    ),
    "integrations/claude-code/src/main/kotlin/splice/client/resume/TranscriptModelRewrite.kt" to mapOf(
        "FileChannel.open(" to 1,
        "Files.createTempFile(" to 1,
        "Files.move(" to 1,
    ),
    "integrations/claude-code/src/main/kotlin/splice/client/wrap/HeadCommandsDir.kt" to mapOf(
        "Files.createSymbolicLink(" to 2,
        "Files.move(" to 2,
        "writeAtomic0600(" to 1,
    ),
    "integrations/claude-code/src/main/kotlin/splice/client/wrap/ProjectsLink.kt" to mapOf(
        "Files.createLink(" to 1,
        "Files.createSymbolicLink(" to 1,
        "Files.move(" to 1,
    ),
    "integrations/claude-code/src/main/kotlin/splice/client/wrap/WrappedHead.kt" to mapOf(
        "Files.copy(" to 1,
        "Files.createSymbolicLink(" to 1,
        "Files.move(" to 2,
        "writeAtomic0600(" to 1,
    ),
    "integrations/oauth/src/main/kotlin/splice/oauth/LoginIo.kt" to mapOf("writeAtomic0600(" to 1),
    "integrations/oauth/src/main/kotlin/splice/oauth/OAuthAccountFiles.kt" to mapOf("Files.move(" to 1),
    "integrations/oauth/src/main/kotlin/splice/oauth/OAuthAccountWrites.kt" to mapOf("writeAtomic0600(" to 1),
    "integrations/oauth/src/main/kotlin/splice/oauth/OAuthLoginReservation.kt" to mapOf("FileChannel.open(" to 1),
    "integrations/providers/codex/src/main/kotlin/splice/provider/codex/CodexAuthProvider.kt" to mapOf(
        "writeAtomic0600(" to 1,
    ),
    "integrations/providers/codex/src/main/kotlin/splice/provider/codex/CodexCodeModeStore.kt" to mapOf(
        "writeAtomic0600(" to 1,
    ),
    "integrations/providers/grok/src/main/kotlin/splice/provider/grok/GrokAuthProvider.kt" to mapOf(
        "writeAtomic0600(" to 1,
    ),
    "integrations/providers/kimi/src/main/kotlin/splice/provider/kimi/KimiAuthProvider.kt" to mapOf(
        "writeAtomic0600(" to 1,
    ),
    "integrations/providers/kimi/src/main/kotlin/splice/provider/kimi/KimiDeviceIdentity.kt" to mapOf(
        "writeAtomic0600(" to 1,
    ),
    "integrations/providers/muse/src/main/kotlin/splice/provider/muse/MuseMintPersistence.kt" to mapOf(
        "writeAtomic0600(" to 1,
    ),
    "integrations/topology/src/main/kotlin/splice/topology/TopologyLoader.kt" to mapOf("createNew0600(" to 1),
    "integrations/upstream/src/main/kotlin/splice/upstream/credentials/CredentialLock.kt" to mapOf(
        "FileChannel.open(" to 1,
    ),
)

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
    private class Tree(
        val root: File,
        val rules: DiskWrites.Rules = DiskWrites.Rules(emptyList(), emptyList(), STORED),
    ) {
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
        val helped = mapOf("$CORE/Stored.kt" to mapOf("writeAtomic0600(" to 1))
        with(Tree(root, DiskWrites.Rules(listOf(secure), listOf(stdin), helped))) {
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
        assertHit(grown, "UNRECORDED", "SelftestDump.kt") { "and its write is in no record (V4-287)" }
        assertEquals(2, grown.size, "exactly the mutant, unlisted and unrecorded: $grown")
        assertHit(live(readme = readme()?.replace(DiskWrites.HEADING, "## Elsewhere")).problems, "NO SECTION") {
            "the shipped README without its heading must be RED"
        }
    }

    @Test
    fun `a new write in a file the section names is red where it is, both ways - V4-287`(@TempDir root: File) {
        with(Tree(root)) {
            write("Store.kt" to STORE)
            assertEquals(emptyList<String>(), audit(readmeNaming("Store.kt")), "the recorded write: GREEN")
            write("Store.kt" to STORE + SECOND_WRITE)
            assertHit(audit(readmeNaming("Store.kt")), "GROWN", "Store.kt", "Files.writeString(", "line(s) 4, 6") {
                "a second write in a file the section names must be RED where it is"
            }
            write("Store.kt" to STORE, "Later.kt" to MUTANT)
            assertHit(audit(readmeNaming("Store.kt", "Later.kt")), "UNRECORDED", "Later.kt", "Files.write") {
                "a new writing file must be recorded, even once the section names it"
            }
            write("Quiet.kt" to QUIET)
            assertHit(audit(readmeNaming()), "STALE SITES", "Store.kt") { "a record for a file that writes nothing" }
        }
        val stdin = DiskWrites.NotAFile("$CORE/Pipe.kt", ".bufferedWriter(", "")
        val padded = mapOf("$CORE/Store.kt" to mapOf("Files.writeString(" to 2))
        with(Tree(root, DiskWrites.Rules(emptyList(), listOf(stdin), padded))) {
            write("Store.kt" to STORE, "Pipe.kt" to PIPE + FILE_WRITER)
            val problems = audit(readmeNaming("Store.kt"))
            assertHit(problems, "SHRUNK", "Store.kt", "1 time(s)", "records 2") { "a count above the measurement" }
            assertHit(problems, "UNLISTED", "Pipe.kt", ".bufferedWriter(") {
                "a not-a-file entry exempts the one match it declares, and the next is a write"
            }
        }
    }

    /** V4-287, the dispatch's mutation on the SHIPPED tree and README: one more write in
     *  CompactionRecordings.kt, a file the section names, is red where it is, and it is the only red. */
    @Test
    fun `a new write in a file the shipped section names is red - V4-287`(@TempDir root: File) {
        val files = KotlinText.kotlinFiles(map).map { source ->
            val rel = source.relativeTo(map.root).invariantSeparatorsPath
            File(root, rel).also { copy ->
                copy.parentFile.mkdirs()
                copy.writeText(source.readText() + if (rel.endsWith("/CompactionRecordings.kt")) SECOND_WRITE else "")
            }
        }
        val problems = DiskWrites.audit(files, root, readme()).problems
        assertHit(problems, "GROWN", "CompactionRecordings.kt", "Files.writeString(") {
            "a second write in a file the section names must be RED where it is"
        }
        assertEquals(1, problems.size, "exactly the mutant: $problems")
    }

    private companion object {
        const val CORE = "core/src/main/kotlin/splice/core"

        /** The synthetic tree's one recorded write: Store.kt's Files.writeString. */
        val STORED = mapOf("$CORE/Store.kt" to mapOf("Files.writeString(" to 1))

        /** One more write, appended to a source: a new site in a file that already writes. */
        const val SECOND_WRITE = "\n\nfun splicedExtraWrite(p: java.nio.file.Path) = " +
            "java.nio.file.Files.writeString(p, \"x\")\n"

        /** A second `.bufferedWriter(` in Pipe.kt, and this one opens a file. */
        const val FILE_WRITER = "\nfun log(f: java.io.File) = f.bufferedWriter().use { it.write(\"x\") }\n"

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
