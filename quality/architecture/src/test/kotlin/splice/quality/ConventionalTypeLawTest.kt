// NEW: V4-30 — the conventional-type list lives in tools/gate/src/lib/conventional.ts, once.
// (Ported from checks/config/one-conventional-type-list.ts, restructure PR 6 §4.3; its oracle was
// checks/one-conventional-type-list-selftest.sh, which drove `check` against fixture trees — this
// law's red-proof plays the same fixtures, built at runtime from the tree's own vocabulary line.)
//
// CLASS. A second copy of the conventional-commit type vocabulary drifts. This repo once shipped a
// PR-title workflow allowing types the org gate rejects; that workflow is gone, the org gate is the
// authority, and tools/gate/src/lib/conventional.ts is the single mirror of it. Restating the list
// in prose is how a stale copy outlives a deletion, and how a contributor titles a PR with a type
// that cannot merge.
//
// SCOPE. Every text file in the tree except the SOURCE file itself, the one allowed copy. Docs,
// templates, agent files, checkers, tests — and this law's own file — are all in scope, which is
// why its fixtures build a run of three-or-more types at RUNTIME from the tree's real vocabulary
// line rather than typing one in Kotlin source: a hand-typed run here would make this law the
// second copy it exists to catch (the checker's own trick — see the "law's own file" case below).
//
// DENOMINATOR. Candidate paths come from `git ls-files` at the check root — tracked content plus
// the index, so a staged new file is in and untracked scratch cannot red the gate. Where there is
// no git repository the walk falls back to a full directory scan under the same filters, so a bare
// @TempDir fixture runs identically with no git init. The vocabulary itself is parsed from the
// TYPES export in tools/gate/src/lib/conventional.ts ([ConventionalType.vocabulary]); this law
// never restates it.
//
// PARSE. A run of three or more parsed types in sequence, separated only by whitespace, a middle
// dot, a comma, a pipe or a slash, word-boundary guarded so a longer word can never match on a
// prefix. Hit detection strips backticks first, so a fenced, middle-dot-separated list still reads
// as one run.
//
// VIOLATIONS, failed BY NAME. Any non-SOURCE file with a hit — including a copy that is byte-exact,
// because an exact second copy is still the divergence mechanism — the vocabulary source missing or
// too short to trust, and a scan that touches nothing (a denominator failure, never a silent pass).
//
// NOT CAUGHT, ported from the checker's own list. A short phrase naming only one or two types: that
// is a comparison, not a restated vocabulary, and a third real type turns it into one. Words the
// org gate does not recognize at all: those never enter the parsed alternation. `git log` history:
// `gate title` already warns against inferring the convention from it. Campaign ledgers under
// .dev/campaigns, which quote the defect being fixed rather than teach a title vocabulary. Research
// captures under .dev/research, where mutating a recording to please this wall would falsify
// evidence.
package splice.quality

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.TimeUnit

private const val SOURCE = "tools/gate/src/lib/conventional.ts"
private const val GIT_TIMEOUT_SECONDS = 30L
private const val MIN_TRACKED_TEXT_FILES = 100
private const val README = "README.md"
private const val DOCS = "docs.md"
private const val CONTRIBUTING = "CONTRIBUTING.md"
private const val NOTE = "note.md"
private const val SCRATCH = "scratch.md"
private const val THIS_LAW_FILE = "quality/architecture/src/test/kotlin/splice/quality/ConventionalTypeLawTest.kt"

/** Pure-ish functions ported from checks/config/one-conventional-type-list.ts (V4-30). The census
 *  (which candidate files exist) is kept separate from the verdict (which of them restate the
 *  vocabulary) — the same split [SilentConstants] uses. */
internal object ConventionalType {
    private const val MIN_TYPES = 3
    private val TYPES_LINE = Regex("""^export const TYPES = "([^"]+)";$""", RegexOption.MULTILINE)
    private val SKIP_DIRS = setOf(".git", "node_modules", "build", ".gradle", "dist", "out", "__pycache__", ".venv")
    private val SKIP_PREFIXES = listOf(".dev/campaigns/", ".dev/research/")
    private val SKIP_EXTENSIONS = setOf(
        "png", "jpg", "jpeg", "gif", "webp", "ico", "jar", "class", "so", "dylib",
        "zip", "gz", "pdf", "woff", "woff2", "ttf", "eot", "wasm",
    )

    /** Path-level rejectors backing [accept] — one predicate per [SKIP_DIRS]/[SKIP_EXTENSIONS]/
     *  [SKIP_PREFIXES] rule, so no single boolean expression has to chain all three. */
    private val REJECTORS: List<(String) -> Boolean> = listOf(
        { relPath: String -> relPath.split("/").any { it in SKIP_DIRS } },
        { relPath: String -> File(relPath).extension.lowercase() in SKIP_EXTENSIONS },
        { relPath: String -> SKIP_PREFIXES.any { relPath.startsWith(it) } },
    )

    /** TYPES parsed off SOURCE's own export line, or empty when the line is absent or carries fewer
     *  than [MIN_TYPES] entries — [audit] turns either into a named violation, never a silent skip. */
    fun vocabulary(source: String): List<String> {
        val raw = TYPES_LINE.find(source)?.groupValues?.get(1) ?: return emptyList()
        val types = raw.split("|").filter { it.isNotEmpty() }
        return if (types.size >= MIN_TYPES) types else emptyList()
    }

    /** A run of 3+ [types] in sequence, in the checker's own separator class, word-boundary guarded
     *  so a longer word can never match on a prefix. */
    fun runPattern(types: List<String>): Regex {
        val alternation = types.joinToString("|") { Regex.escape(it) }
        return Regex("(?<![A-Za-z])(?:$alternation)(?:[\\s·,|/]+(?:$alternation)){2,}(?![A-Za-z])")
    }

    /** Whether [text] carries a hit for [pattern] — backticks stripped first, so a fenced,
     *  middle-dot-separated list still reads as one run. A fresh [Regex.containsMatchIn] call per
     *  file, so unlike a stateful `g`-flag scan there is no lastIndex to carry between files. */
    fun hasHit(text: String, pattern: Regex): Boolean = pattern.containsMatchIn(text.replace("`", " "))

    private fun accept(relPath: String, file: File): Boolean = file.isFile && REJECTORS.none { it(relPath) }

    /** `git ls-files -z` at [root], or null when it answers nothing or fails — not a git repository,
     *  among other reasons — the checker's gitListed. */
    private fun gitListed(root: File): List<String>? {
        val process = ProcessBuilder("git", "-C", root.path, "ls-files", "-z").redirectErrorStream(true).start()
        val bytes = process.inputStream.readBytes()
        val finished = process.waitFor(GIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        if (!finished || process.exitValue() != 0) return null
        val names = bytes.decodeToString().split('\u0000').filter { it.isNotEmpty() }
        return names.ifEmpty { null }
    }

    /** Every file under [root], relative with forward slashes — the checker's tree-walk fallback,
     *  unpruned like the original (a directory named in [SKIP_DIRS] is still entered; [accept]
     *  filters its files out just the same, so the result is identical either way). */
    private fun walk(root: File): List<String> =
        root.walkTopDown().filter { it.isFile }.map { it.relativeTo(root).invariantSeparatorsPath }.toList()

    /** Every candidate text path under [root]: the tracked list when [root] is a git repository, a
     *  full walk otherwise (the checker's iterTextFiles/gitListed fallback), so a bare @TempDir
     *  fixture with no .git runs the same [accept] filters as a real checkout. SOURCE is included
     *  here; [secondCopies] and [audit] are what exclude it. */
    fun textFiles(root: File): List<String> {
        val candidates = gitListed(root) ?: walk(root)
        return candidates.filter { accept(it, File(root, it)) }
    }

    /** The paths among [files] (relative to [root]) that restate a run of 3+ [types] — SOURCE is
     *  excluded even when [files] carries it, because it necessarily holds the list this hunts for.
     *  An unreadable path is skipped, exactly as the checker's `try { readFileSync } catch { continue }`. */
    fun secondCopies(root: File, files: List<String>, types: List<String>): List<String> {
        val pattern = runPattern(types)
        return files.filter { it != SOURCE }.mapNotNull { rel ->
            val text = runCatching { File(root, rel).readText() }.getOrNull()
            val hit = text != null && hasHit(text, pattern)
            if (!hit) {
                null
            } else {
                "$rel restates 3+ conventional types; the list lives once in $SOURCE. " +
                    "Remedy: delete the copy and point readers at bun tools/gate title \"feat(scope): subject\""
            }
        }
    }

    /** The checker's `check`: a vocabulary SOURCE cannot supply, or a scan that touches nothing, is
     *  a violation, never a silent pass; otherwise every non-SOURCE hit, named. */
    fun audit(root: File): List<String> {
        val sourceFile = File(root, SOURCE)
        val types = if (sourceFile.isFile) vocabulary(sourceFile.readText()) else emptyList()
        if (types.isEmpty()) {
            val why = if (sourceFile.isFile) "has no usable TYPES assignment" else "missing"
            return listOf("$SOURCE $why — refusing to pass vacuously")
        }
        val files = textFiles(root)
        val scanned = files.count { it != SOURCE }
        return if (scanned == 0) {
            listOf("scanned 0 files — refusing to pass vacuously")
        } else {
            secondCopies(root, files, types)
        }
    }
}

/** One arm's fixture: [SOURCE] carrying the tree's real TYPES line, plus an unrelated file so the
 *  denominator is never zero — the role the checker's own selftest gives its own copy of the
 *  checker script. */
private class ConventionalTree(private val root: File, sourceLine: String) {
    init {
        File(root, SOURCE).apply { parentFile.mkdirs() }.writeText(sourceLine)
        File(root, README).writeText("Unrelated docs content, nothing restated here.\n")
    }

    fun file(rel: String, text: String) {
        File(root, rel).apply { parentFile.mkdirs() }.writeText(text)
    }

    fun delete(rel: String) {
        File(root, rel).delete()
    }

    fun git(args: List<String>) {
        val process = ProcessBuilder(listOf("git") + args).directory(root).redirectErrorStream(true).start()
        val output = process.inputStream.readBytes().decodeToString()
        val label = "git ${args.joinToString(" ")}"
        check(process.waitFor(GIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) { "$label did not finish under $root" }
        check(process.exitValue() == 0) { "$label failed under $root: $output" }
    }

    fun audit(): List<String> = ConventionalType.audit(root)
}

class ConventionalTypeLawTest {
    private val map = ProjectMap.fromSystemProperties()
    private val liveTypes: List<String> = ConventionalType.vocabulary(File(map.root, SOURCE).readText())
    private val liveSourceLine: String = "export const TYPES = \"${liveTypes.joinToString("|")}\";\n"

    @Test
    fun `live - the checkout names the conventional-type vocabulary exactly once`() {
        val files = ConventionalType.textFiles(map.root)
        assertTrue(files.size > MIN_TRACKED_TEXT_FILES) {
            "textFiles answered ${files.size} candidate path(s) under ${map.root} — the census did not run"
        }
        val problems = ConventionalType.audit(map.root)
        assertTrue(problems.isEmpty()) {
            problems.joinToString(separator = "\n  - ", prefix = "ONE CONVENTIONAL TYPE LIST (V4-30) violated:\n  - ")
        }
    }

    @TestFactory
    fun `the law can actually fail - every selftest arm`(@TempDir root: File): List<DynamicTest> {
        val source = liveSourceLine
        val types = liveTypes
        val arms = listOf(
            "source file alone is green" to { dir: File -> sourceAloneIsGreen(dir, source) },
            "a correct second copy still reds, named" to { dir: File -> correctCopyStillReds(dir, source, types) },
            "stale extra types still red, named" to { dir: File -> staleExtraTypesStillRed(dir, source, types) },
            "two types in a row are green" to { dir: File -> twoTypesAreGreen(dir, source, types) },
            "missing source is a violation, never a skip" to { dir: File -> missingSourceIsRed(dir, source) },
            "the law's own file is not a second copy" to { dir: File -> lawFileIsNotASecondCopy(dir, source) },
            "untracked copy green, tracked copy reds, named" to { dir: File -> gitDenominatorArm(dir, source, types) },
        )
        return arms.mapIndexed { index, (name, run) ->
            DynamicTest.dynamicTest(name) { run(File(root, index.toString()).apply { mkdirs() }) }
        }
    }

    private fun sourceAloneIsGreen(dir: File, source: String) {
        assertEquals(emptyList<String>(), ConventionalTree(dir, source).audit())
    }

    private fun correctCopyStillReds(dir: File, source: String, types: List<String>) {
        val tree = ConventionalTree(dir, source)
        tree.file(DOCS, typesPhrase(types))
        assertHit(tree.audit(), DOCS) { "a byte-exact second copy must still be RED — the divergence mechanism" }
    }

    private fun staleExtraTypesStillRed(dir: File, source: String, types: List<String>) {
        val tree = ConventionalTree(dir, source)
        tree.file(CONTRIBUTING, "${types.joinToString(" ")} extra-one extra-two\n")
        assertHit(tree.audit(), CONTRIBUTING) { "org-rejected words tacked onto the real types must still be RED" }
    }

    private fun twoTypesAreGreen(dir: File, source: String, types: List<String>) {
        val tree = ConventionalTree(dir, source)
        tree.file(NOTE, "${types[0]} ${types[1]}\n")
        assertEquals(emptyList<String>(), tree.audit())
    }

    private fun missingSourceIsRed(dir: File, source: String) {
        val tree = ConventionalTree(dir, source)
        tree.delete(SOURCE)
        assertHit(tree.audit(), SOURCE) { "an absent vocabulary source must be RED, never a silent skip" }
    }

    private fun lawFileIsNotASecondCopy(dir: File, source: String) {
        val tree = ConventionalTree(dir, source)
        tree.file(THIS_LAW_FILE, File(map.root, THIS_LAW_FILE).readText())
        assertEquals(emptyList<String>(), tree.audit(), "this law's own source must not restate the list it hunts for")
    }

    private fun gitDenominatorArm(dir: File, source: String, types: List<String>) {
        val tree = ConventionalTree(dir, source)
        tree.git(listOf("init", "-q"))
        tree.git(listOf("add", "-A"))
        tree.file(SCRATCH, typesPhrase(types))
        assertEquals(emptyList<String>(), tree.audit(), "untracked scratch is not in the git denominator")
        tree.git(listOf("add", SCRATCH))
        assertHit(tree.audit(), SCRATCH) { "a copy has to be tracked to reach main; once staged it must be RED" }
    }

    private fun typesPhrase(types: List<String>): String = types.joinToString(" ") + "\n"
}
