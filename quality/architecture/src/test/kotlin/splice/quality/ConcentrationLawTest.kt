// NEW: the responsibility-concentration RATCHET — the decomposition campaign's oracle, as a law
// (ported from checks/concentration.ts, restructure PR 6). The gate leg the ladder ran was
// `bun checks/concentration.ts --ratchet --max-ratio 1.8`, and this law is exactly that leg: the
// file-scale census, the package-scale census, and the two gated numbers.
//
// WHY THIS EXISTS. The style migration made the tree compliant (no top-level functions, no
// companion objects) WITHOUT making it decomposed. A per-class function ceiling pushed
// collaborators into existence inside the files that were already too big, so concentration moved
// sideways rather than down: one file declaring twelve types, another importing thirty-two
// subsystems. A per-class function count cannot see that. This can.
//
// CLASS. A text-level census over production Kotlin — line shapes and import lines, never types.
//
// SCOPE. Every `.kt` under `<module>/src/main` of every module the BUILD declares
// ([KotlinText.kotlinFiles]) — the build-derived denominator the checker's
// `gateway/*/src/main, client/src/main, …` glob list stood in for; both name the same 744 files on
// this tree. A source root a census stops walking is a denominator that shrinks in silence, and
// every baseline here is a COUNT, so the loss would read as an improvement.
//
// PARSE, per file (Python's line splitting, [KotlinText.splitLines]):
//
//     C = 0.5*logic_lines + 3*non_type_exports + 8*concerns
//
//   logic_lines       non-blank, non-comment, non-import, non-package
//   non_type_exports  top-level fun/val/var declarations. A top-level class/interface/object is
//                     NOT counted here, because `concerns` already counts it — ONE DECLARATION,
//                     ONE BILL. Subtracted PER LINE, never as `exports - types`: TYPE_DECL admits
//                     `private ` and EXPORT_DECL does not, so a per-set subtraction hands a file
//                     that hides a collaborator behind `private` a -3 CREDIT.
//   concerns          declared types in the file PLUS distinct splice.* subsystems it imports —
//                     the term that catches the failure above, because splitting one god class
//                     into six collaborators in the SAME file raises concerns rather than lowering
//                     it. Every spelling of a type counts (`fun interface`, `annotation class`) or
//                     the census is a dodge list. NESTED types are REPORTED and deliberately NOT
//                     billed: charging the repo's own mandated sealed-hierarchy idiom 8 points a
//                     variant re-scopes the campaign, which is a calibration call, not this law's.
//
// Then, per file, the gradient against its neighbours:
//
//     ratio = C / median(median C of each neighbouring PACKAGE)
//
//   neighbours  the packages this file imports from, plus the packages that import this file's
//               package. ONE VOTE PER PACKAGE, never one per file: a median over neighbour FILES
//               gives a package as many votes as it has files, so decomposing one importer into
//               twelve siblings drags the median down and inflates the ratio of files nobody
//               touched. The denominator is FLOORED at half the global median — a ratio taken
//               against a tiny neighbourhood is noise — and a file with NO neighbourhood at all is
//               graded against the global median outright (DR-117), because the old fallback was
//               the file's OWN C, which pinned its ratio to 1.0 and let a self-contained 800-line
//               god file pass in every band. The ratio divides the denominator AS REPORTED, so the
//               arithmetic reproduces from the law's own output.
//
//   BANDS: ratio < 1.8 low | 1.8-3.0 moderate | >= 3.0 HIGH (god object). A file under the global
//   median C is `low` whatever its ratio.
//
// VIOLATIONS — TWO GATED NUMBERS, both two-directional.
//   · BAND HIGH, against [Concentration.RATCHET_MAX_HIGH]. `--max-ratio 1.8` cannot be the gate
//     today (112 files sit above it) and the COUNT of those files is deliberately NOT the
//     criterion: the denominator is a file-scale order statistic, so ANY split moves files nobody
//     touched, and a pure relocation measured RED on that count while the worst row collapsed.
//     Over the campaign the count rose 7 times and fell 7 (43 -> 42) while HIGH went 22 -> 8
//     without ever rising. A RISE names the offending files exactly as the checker's GATED line
//     does; a FALL is also RED, with the number to record and where — a baseline held above the
//     measurement is unearned room for the next regression to hide in.
//   · THE WORST PACKAGE'S FILE COUNT, against [Concentration.PACKAGE_MAX_FILES]. The file plane
//     cannot see a package of fifty-one files, which is the same responsibility clump one
//     directory up. It is partition-stable in the direction that matters, it reads no threshold so
//     it cannot be satisfied by weakening, and an EMPTY census REFUSES rather than reporting a
//     clean tree. Summed C is REPORTED, not gated, for the same reason the debt count is.
//
//   CEILING EXCEPTIONS ([Concentration.CEILING_EXCEPTIONS], empty today) are a CEILING, not a
//   blanket: a file that rises above its own recorded number still fails, a ceiling recorded ABOVE
//   its file's measured ratio is a PADDED CEILING and fails too, a blank or undated justification
//   is a hard error, and a stale entry is a hard error.
//
// NOT CAUGHT / NOT PORTED, stated here rather than discovered later. `--since <ref>` (the
// diff-time instrument that attributed a move to `own` or `neighbourhood` by reading a git
// archive), `--file`, `--top` and `--json` were REPORT verbs the gate leg never invoked; they
// retired with checks/concentration.ts (restructure PR 6). A landing note that needs the
// attribution reads this law's census and `git log -p` for the file. This law runs no git and
// reads nothing outside the module source trees. Semantic concentration — a file whose
// twelve types are one cohesive idea — is not measurable from line shapes, which is why the debt
// count is printed and not gated.
package splice.quality

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.abs
import kotlin.math.max

internal object Concentration {
    /** The `--max-ratio` the ladder's leg states at its call site. */
    const val GATE_RATIO = 1.8

    /** THE RATCHET BASELINE — the census this tree is held to, MEASURED, never estimated. Moving
     *  it is a deliberate, dated edit: UP records that the tree got worse, DOWN is the remedy this
     *  law itself prints when work lands. */
    const val RATCHET_RECORDED = "2026-09-21"
    const val RATCHET_MAX_HIGH = 13

    /** THE PACKAGE-SCALE BASELINE — the worst package's FILE COUNT. The package is named here so
     *  the diff reads without running anything, but the NAME is not gated: a different package
     *  becoming the worst at the same count is not a regression. splice.provider.codex, 29 of 744
     *  production files (next: splice.app.cli.doctor 27, splice.head.turn 26). */
    const val PACKAGE_RATCHET_RECORDED = "2026-09-21"
    const val PACKAGE_MAX_FILES = 29

    /** Where a shrunk baseline is re-recorded by hand. */
    const val THIS_LAW = "quality/architecture/src/test/kotlin/splice/quality/ConcentrationLawTest.kt"

    const val HIGH = "HIGH"
    const val MODERATE = "moderate"
    const val LOW = "low"
    private const val HIGH_BAND = 3.0
    private const val MODERATE_BAND = 1.8
    private const val FLOOR_SHARE = 0.5

    /** A file this tree provably cannot bring under the gate by refactoring: a CEILING that still
     *  fails when breached, a justification that is mechanically required, and an entry that is a
     *  hard error the moment it goes stale. Empty after HD-25, and a second entry added to make a
     *  red gate green is the laundering this list exists to prevent. */
    data class Ceiling(val file: String, val ratio: Double, val why: String)

    val CEILING_EXCEPTIONS: List<Ceiling> = emptyList()

    /** The numbers one run is graded against. The checker mutated its own module constants for
     *  its red proofs; a law is handed them, which is what lets an arm move ONE of them. */
    data class Baseline(val maxHigh: Int, val maxPackageFiles: Int, val ceilings: List<Ceiling>)

    val LIVE = Baseline(RATCHET_MAX_HIGH, PACKAGE_MAX_FILES, CEILING_EXCEPTIONS)

    /** Python's `str.strip()` whitespace, which is what the reference stripped with. */
    private const val PY_WS = " \t\n\r\u000B\u000C\u001C\u001D\u001E\u001F\u0085  " +
        "               　"

    val TYPE_DECL = Regex(
        "^(public |internal |private )?(sealed |data |abstract |open |value |enum |fun |annotation )*" +
            "(class|interface|object) ",
    )

    /** NESTED types are REPORTED, not billed — see the header for the measured re-scoping radius. */
    val NESTED_TYPE_DECL = Regex(
        "^[ \\t]+(public |internal |private |protected )?" +
            "(sealed |data |abstract |open |value |enum |inner |fun |annotation )*" +
            "(class|interface|object)[ \\t]+[A-Za-z_]",
    )
    val EXPORT_DECL = Regex(
        "^(public |internal )?(sealed |data |abstract |open |value |enum |suspend |inline )*" +
            "(class|interface|object|fun|val|var) ",
    )
    val SPLICE_IMPORT = Regex("^import (splice\\.[A-Za-z0-9_.]+)\\.[A-Za-z0-9_]+")

    /** Every exemption starts with a date — an undated one is how the next exemption hides. */
    val EXCEPTION_JUSTIFICATION = Regex("^\\p{Nd}{4}-\\p{Nd}{2}-\\p{Nd}{2}: [^$PY_WS]")

    data class Row(
        val file: String,
        val pkg: String,
        val logic: Int,
        val exports: Int,
        val exportsNonType: Int,
        val types: Int,
        val nestedTypes: Int,
        val subsystems: List<String>,
        val concerns: Int,
        val c: Double,
        val neighbourPackages: List<String> = emptyList(),
        val neighbourMedianC: Double = 0.0,
        val denominator: Double = 0.0,
        val denominatorFloored: Boolean = false,
        val ratio: Double = 0.0,
        val band: String = "",
    )

    data class PackageRow(val pkg: String, val files: Int, val c: Double, val medianC: Double)

    /** `round(x, n)` on the EXACT binary value, half-to-even — the reference's arithmetic, not
     *  JavaScript's shortest-decimal half-up: 2.675 is 2.67499999… in binary. */
    fun pyRound(x: Double, digits: Int): Double =
        if (!x.isFinite()) x else BigDecimal(x).setScale(digits, RoundingMode.HALF_EVEN).toDouble()

    /** `sum()` over floats: CPython 3.12+ adds them with Neumaier compensation. */
    fun fsum(xs: List<Double>): Double {
        if (xs.isEmpty()) return 0.0
        var total = xs[0]
        var compensation = 0.0
        for (x in xs.subList(1, xs.size)) {
            val t = total + x
            compensation += if (abs(total) >= abs(x)) (total - t) + x else (x - t) + total
            total = t
        }
        return if (compensation != 0.0 && compensation.isFinite()) total + compensation else total
    }

    /** `statistics.median`. */
    fun median(data: List<Double>): Double {
        check(data.isNotEmpty()) { "no median for empty data" }
        val sorted = data.sorted()
        val half = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[half] else (sorted[half - 1] + sorted[half]) / 2
    }

    /** `repr()` of a float, for the numbers a violation quotes. */
    fun floatStr(x: Double): String = x.toString()

    private val COMMENT_LEAD = listOf("//", "*", "/*")
    private val NOT_CODE_LEAD = listOf("import ", "package ")

    private fun pyStrip(s: String): String = s.trim { it in PY_WS }

    private fun isLogic(line: String): Boolean {
        val stripped = pyStrip(line)
        if (stripped.isEmpty()) return false
        if (COMMENT_LEAD.any { stripped.startsWith(it) }) return false
        return NOT_CODE_LEAD.none { line.startsWith(it) }
    }

    /** One file's shape. The package is the directory path below `/kotlin/`, dotted. */
    fun measure(rel: String, text: String): Row {
        val lines = KotlinText.splitLines(text)
        val logic = lines.filter { isLogic(it) }
        val exports = lines.filter { EXPORT_DECL.containsMatchIn(it) }
        val types = lines.filter { TYPE_DECL.containsMatchIn(it) }
        val nonTypeExports = exports.filter { !TYPE_DECL.containsMatchIn(it) }
        // Matched over LOGIC lines, so `* class Foo does …` inside a doc comment is never a type.
        val nested = logic.filter { NESTED_TYPE_DECL.containsMatchIn(it) }
        val subsystems = linkedSetOf<String>()
        for (line in lines) SPLICE_IMPORT.find(line)?.let { subsystems += it.groupValues[1] }
        val concerns = types.size + subsystems.size
        return Row(
            file = rel,
            pkg = rel.split("/kotlin/").last().split("/").dropLast(1).joinToString("."),
            logic = logic.size,
            exports = exports.size,
            exportsNonType = nonTypeExports.size,
            types = types.size,
            nestedTypes = nested.size,
            subsystems = subsystems.sorted(),
            concerns = concerns,
            c = pyRound(FLOOR_SHARE * logic.size + 3 * nonTypeExports.size + 8 * concerns, 1),
        )
    }

    /** The census's denominator: every production `.kt` the BUILD's module map claims, in path order. */
    fun collect(map: ProjectMap): List<Row> =
        KotlinText.kotlinFiles(map).map { measure(KotlinText.rel(map, it), it.readText()) }

    /** The gradient pass: each package votes once with its own median C. */
    fun scan(rows: List<Row>): List<Row> {
        val byPackage = rows.groupBy { it.pkg }
        val packageMedian = byPackage.mapValues { (_, files) -> median(files.map { it.c }) }
        val importers = linkedMapOf<String, MutableSet<String>>()
        for (row in rows) {
            for (pkg in row.subsystems) importers.getOrPut(pkg) { linkedSetOf() } += row.pkg
        }
        val globalMedian = if (packageMedian.isEmpty()) 0.0 else median(packageMedian.values.toList())
        val floor = globalMedian * FLOOR_SHARE
        return rows.map { row -> grade(row, packageMedian, importers[row.pkg].orEmpty(), Scale(globalMedian, floor)) }
            .sortedByDescending { it.ratio }
    }

    /** The two tree-wide numbers every row is graded against. */
    private data class Scale(val globalMedian: Double, val floor: Double)

    private fun grade(row: Row, packageMedian: Map<String, Double>, importers: Set<String>, scale: Scale): Row {
        val neighbours = linkedSetOf<String>()
        neighbours += row.subsystems.filter { packageMedian.containsKey(it) }
        neighbours += importers
        neighbours -= row.pkg
        val med = if (neighbours.isEmpty()) {
            scale.globalMedian
        } else {
            median(neighbours.map { packageMedian.getValue(it) })
        }
        // REPORT THE DIVISOR ACTUALLY USED, not just the raw median: when the floor bites the two
        // differ, and a gate whose arithmetic cannot be reproduced from its own output is not
        // auditable. The ratio then divides the denominator AS REPORTED, one rounding step down.
        val denominator = pyRound(max(med, scale.floor), 1)
        val ratio = if (denominator == 0.0) 0.0 else pyRound(row.c / denominator, 2)
        return row.copy(
            neighbourPackages = neighbours.sorted(),
            neighbourMedianC = pyRound(med, 1),
            denominator = denominator,
            denominatorFloored = med < scale.floor,
            ratio = ratio,
            band = bandOf(row.c, ratio, scale.globalMedian),
        )
    }

    private fun bandOf(c: Double, ratio: Double, globalMedian: Double): String = when {
        c < globalMedian -> LOW
        ratio >= HIGH_BAND -> HIGH
        ratio >= MODERATE_BAND -> MODERATE
        else -> LOW
    }

    /** One row per PACKAGE: files, summed C, median C — derived from the same rows the file plane
     *  measures, so the two can never disagree about what a file is or what its C is. */
    fun packageCensus(rows: List<Row>): List<PackageRow> =
        rows.groupBy { it.pkg }
            .map { (pkg, files) ->
                PackageRow(
                    pkg,
                    files.size,
                    pyRound(fsum(files.map { it.c }), 1),
                    pyRound(median(files.map { it.c }), 1),
                )
            }
            .sortedWith(compareByDescending<PackageRow> { it.files }.thenByDescending { it.c }.thenBy { it.pkg })

    /** The gated file census: band HIGH, EXCLUDING the ceiling-excepted files, which are graded
     *  against their own recorded ceilings by the same leg rather than counted twice. */
    fun gradedHigh(rows: List<Row>, baseline: Baseline): List<Row> {
        val capped = baseline.ceilings.map { it.file }.toSet()
        return rows.filter { it.file !in capped && it.band == HIGH }
    }

    /** The standing debt: files above the gate ratio. REPORTED, never gated — it moves on splits
     *  that touch nothing, so gating it penalises the decomposition this oracle exists to drive. */
    fun debt(rows: List<Row>, baseline: Baseline, maxRatio: Double): List<Row> {
        val capped = baseline.ceilings.map { it.file }.toSet()
        return rows.filter { it.file !in capped && it.ratio > maxRatio }
    }

    private fun baseName(file: String) = file.substring(file.lastIndexOf('/') + 1)

    fun named(rows: List<Row>) = rows.joinToString(" | ") { "${baseName(it.file)} ${floatStr(it.ratio)}" }

    /** Structural faults in the ceiling list, which fail whatever question the caller asked: a
     *  malformed list is a broken instrument, not a failing measurement. */
    fun exceptionErrors(rows: List<Row>, baseline: Baseline): List<String> {
        val known = rows.associateBy { it.file }
        val seen = mutableSetOf<String>()
        val errors = mutableListOf<String>()
        for (ceiling in baseline.ceilings) {
            if (!seen.add(ceiling.file)) {
                errors += "'${ceiling.file}' is listed twice — one ceiling per file, or the stricter entry is dead text"
            }
            if (!EXCEPTION_JUSTIFICATION.containsMatchIn(pyStrip(ceiling.why))) errors += undated(ceiling)
            val row = known[ceiling.file]
            if (row == null) errors += staleCeiling(ceiling) else paddedCeiling(ceiling, row)?.let { errors += it }
        }
        return errors
    }

    private fun undated(ceiling: Ceiling) =
        "'${ceiling.file}' has no dated justification — every exception starts 'YYYY-MM-DD: <why>'. A blank or " +
            "missing justification is a hard error, never a pass: an exemption nobody can evaluate is " +
            "indistinguishable from one nobody should have granted."

    private fun staleCeiling(ceiling: Ceiling) =
        "'${ceiling.file}' is not a production .kt file any more — delete the entry. A stale exemption is an " +
            "ungraded file one rename later, which is the failure it was written to prevent."

    /** THE CEILING MAY NOT SIT ABOVE THE FILE. Every other ceiling comparison here is
     *  `row.ratio > ceiling`, so a ceiling RECORDED ABOVE its file's real ratio failed NOTHING —
     *  the padding direction was unguarded in every mode, and this list once carried 3.35 points of
     *  room a file never earned. */
    private fun paddedCeiling(ceiling: Ceiling, row: Row): String? {
        val recorded = pyRound(ceiling.ratio, 2)
        if (recorded <= row.ratio) return null
        val room = floatStr(pyRound(pyRound(recorded - row.ratio, 2), 2))
        return "'${ceiling.file}' has a PADDED CEILING: recorded ${floatStr(ceiling.ratio)}, file measures " +
            "${floatStr(row.ratio)} (C=${floatStr(row.c)}, denominator=${floatStr(row.denominator)}). Record " +
            "${floatStr(row.ratio)}. A ceiling held above its file's measured ratio is $room points of unearned " +
            "room for the next regression to hide in, and on its own it fails nothing. A ceiling freezes a " +
            "MEASURED state; a number nobody re-measured is an exemption, which is the laundering this list " +
            "exists to prevent."
    }

    /** The ONE gated package number: the worst package's file count. Two-directional, and an empty
     *  census refuses rather than passing — a plane with no denominator cannot pass. */
    fun packageProblems(census: List<PackageRow>, baseline: Baseline): List<String> {
        if (census.isEmpty()) return listOf(EMPTY_CENSUS)
        val worst = census.first()
        if (worst.files == baseline.maxPackageFiles) return emptyList()
        val grew = worst.files > baseline.maxPackageFiles
        return listOf(if (grew) packageRegression(worst, baseline) else packageSlack(worst, baseline))
    }

    private const val EMPTY_CENSUS =
        "PACKAGE SCALE: the census is EMPTY — no production package was measured. A plane with no denominator " +
            "cannot pass; check the project map against the tree."

    private fun packageRegression(worst: PackageRow, baseline: Baseline) =
        "PACKAGE REGRESSION: the worst package holds ${worst.files} files, baseline ${baseline.maxPackageFiles} " +
            "(${worst.pkg}, sum C ${floatStr(worst.c)}). A package absorbed a file that nothing recorded — the " +
            "file plane cannot see this, which is why the package plane exists. Move the file out, or raise " +
            "PACKAGE_MAX_FILES in $THIS_LAW as a dated edit recording that the clump grew."

    private fun packageSlack(worst: PackageRow, baseline: Baseline) =
        "PACKAGE SLACK: the worst package holds ${worst.files} files (${worst.pkg}) and the baseline still claims " +
            "${baseline.maxPackageFiles}. Set PACKAGE_MAX_FILES = ${worst.files} and re-date " +
            "PACKAGE_RATCHET_RECORDED in $THIS_LAW. A baseline held above the measured count is unearned room for " +
            "the next regression to hide in — the same defect as a ceiling recorded above its file's measured ratio."

    private fun regression(high: List<Row>, baseline: Baseline, maxRatio: Double) =
        "REGRESSION: band HIGH rose ${baseline.maxHigh} -> ${high.size}. A god object appeared that nothing " +
            "recorded. The ${high.size} file(s) in band HIGH: ${named(high)}. Attribute each against the " +
            "${floatStr(maxRatio)} ceiling with `git log -p -- <file>`: cause `own` is code in this change, " +
            "cause `neighbourhood` is a denominator that moved under the file. Fix the file — " +
            "raising RATCHET_MAX_HIGH in $THIS_LAW is a dated edit recording that the tree got worse."

    private fun slack(measured: Int, baseline: Baseline) =
        "SLACK: band HIGH fell ${baseline.maxHigh} -> $measured, and the baseline still claims " +
            "${baseline.maxHigh}. Set RATCHET_MAX_HIGH = $measured and re-date RATCHET_RECORDED in $THIS_LAW. A " +
            "baseline held above the measured count is unearned room for the next regression to hide in — the " +
            "same defect as a ceiling recorded above its file's measured ratio."

    private fun breached(ceiling: Ceiling, row: Row) =
        "CEILING BREACHED: ${ceiling.file} ratio ${floatStr(row.ratio)} is above its recorded ceiling " +
            "${floatStr(ceiling.ratio)} (C=${floatStr(row.c)}). A ceiling freezes a known state; it does not stop " +
            "watching."

    /** The gate leg's verdict: the HIGH-band ratchet, the ceilings, and the package plane. */
    fun ratchetProblems(rows: List<Row>, maxRatio: Double, baseline: Baseline): List<String> {
        val problems = mutableListOf<String>()
        val high = gradedHigh(rows, baseline)
        if (high.size > baseline.maxHigh) problems += regression(high, baseline, maxRatio)
        if (high.size < baseline.maxHigh) problems += slack(high.size, baseline)
        val byFile = rows.associateBy { it.file }
        for (ceiling in baseline.ceilings) {
            val row = byFile[ceiling.file] ?: continue
            if (row.ratio > ceiling.ratio) problems += breached(ceiling, row)
        }
        problems += packageProblems(packageCensus(rows), baseline)
        return problems
    }

    /** Everything `--ratchet --max-ratio <r>` would print as a failure. An invalid ceiling list is
     *  terminal: a verdict taken over a list that cannot be trusted would be a green wearing the
     *  wrong number. */
    fun problems(rows: List<Row>, maxRatio: Double = GATE_RATIO, baseline: Baseline = LIVE): List<String> {
        val invalid = exceptionErrors(rows, baseline)
        if (invalid.isNotEmpty()) return invalid
        return ratchetProblems(rows, maxRatio, baseline)
    }
}

class ConcentrationLawTest {
    private val map = ProjectMap.fromSystemProperties()

    @Test
    fun `the concentration ratchet holds - band HIGH and the worst package are exactly their baselines`() {
        val rows = Concentration.scan(Concentration.collect(map))
        assertTrue(rows.size > 100) {
            "the map yielded ${rows.size} production file(s) — the walk is broken, and a census " +
                "that reads no files passes vacuously."
        }
        val problems = Concentration.problems(rows)
        // The standing debt rides on the failure text rather than on an assertion of its own. The
        // assertion that used to sit here compared the debt against band HIGH, which HIGH implies by
        // construction — a check proven unable to fail is not a check (PR 6 review, F7). What the
        // debt is FOR is naming the files a red should be attributed against, so that is where it is.
        assertTrue(problems.isEmpty()) {
            problems.joinToString(separator = "\n  - ", prefix = "CONCENTRATION RATCHET violated:\n  - ") +
                "\n  standing debt above ${Concentration.floatStr(Concentration.GATE_RATIO)}, reported and never " +
                "gated: " + Concentration.named(Concentration.debt(rows, Concentration.LIVE, Concentration.GATE_RATIO))
        }
    }

    @Test
    fun `every emitted ratio reproduces from its own row's C and denominator`() {
        val rows = Concentration.scan(Concentration.collect(map))
        val unreproducible = rows.filter {
            it.denominator != 0.0 && it.ratio != Concentration.pyRound(it.c / it.denominator, 2)
        }
        assertEquals(emptyList<String>(), unreproducible.map { it.file }) {
            "a row's ratio does not equal round(C / denominator, 2): the gate's arithmetic cannot " +
                "be reproduced from its own output"
        }
    }

    @Test
    fun `the census counts every spelling of a type and reports nested ones unbilled`() {
        val row = Concentration.measure("app/src/main/kotlin/splice/zzconc/Census.kt", CENSUS_FIXTURE)
        assertEquals(3, row.types, "fun interface and annotation class are TYPEs, or the census is a dodge list")
        assertEquals(2, row.nestedTypes, "a nested class and a nested annotation class must be REPORTED")
        assertEquals(0, row.exportsNonType, "ONE DECLARATION, ONE BILL — a top-level type is not also an export")
        assertEquals(27.0, row.c, "C bills all three top-level types at 8 and the nested ones at 0")
        assertEquals("splice.zzconc", row.pkg)
    }

    /** The synthetic tree the red proof writes into: five single-file packages, each a one-line
     *  class of C 8.5, so the global median is 8.5 and every row bands `low`. An arm plants one
     *  more file and asserts which plane moves. */
    private class Tree(val root: File) {
        private val synthetic = ProjectMap.parse(root, ":app=app;:core=core", setOf("build"))

        fun base() {
            clear()
            for (n in 0 until BASE_PACKAGES) put("p$n/B.kt", "package splice.zzconc.p$n\nclass B$n(val v: String)\n")
        }

        /** Empties the tree without removing the @TempDir itself. */
        fun clear() {
            root.listFiles().orEmpty().forEach { it.deleteRecursively() }
        }

        fun put(rel: String, text: String) {
            File(root, "$PKG/$rel").apply { parentFile.mkdirs() }.writeText(text)
        }

        /** A file of [classes] one-line types, optionally importing one real neighbour package. */
        fun god(rel: String, classes: Int, neighbour: String?) {
            val body = mutableListOf("package splice.zzconc.${rel.substringBefore('/')}")
            if (neighbour != null) body += "import splice.zzconc.$neighbour.B0"
            for (n in 1..classes) body += "class G$n(val v: String)"
            put(rel, body.joinToString("\n") + "\n")
        }

        fun rows() = Concentration.scan(Concentration.collect(synthetic))
    }

    @Test
    fun `the law can actually fail - a new god object rises off the baseline`(@TempDir root: File) {
        with(Tree(root)) {
            base()
            val control = Concentration.Baseline(0, 1, emptyList())
            assertEquals(
                emptyList<String>(),
                Concentration.problems(rows(), GATE, control),
                "the unmutated fixture must be GREEN",
            )

            god("god/God.kt", GOD_CLASSES, neighbour = "p0")
            val hits = Concentration.problems(rows(), GATE, control)
            assertHit(hits, "REGRESSION: band HIGH rose", "God.kt") {
                "a new band-HIGH file with the baseline unchanged must be RED, naming the file"
            }
            assertEquals(
                HIGH,
                rows().first { it.file.endsWith("God.kt") }.band,
                "the fixture must BE a god object first",
            )

            // The standing debt is what a red is attributed against, so it has to NAME the file
            // that just rose rather than count it (PR 6 review, F7 — the assertion this replaces
            // compared the debt against band HIGH, which band HIGH implies).
            val debt = Concentration.named(Concentration.debt(rows(), control, GATE))
            assertTrue("God.kt" in debt) { "the debt report must name the file above the gate ratio, got: $debt" }

            // SLACK: a baseline held above the measured count is unearned room, and is RED too.
            val padded = Concentration.Baseline(99, 1, emptyList())
            assertHit(Concentration.problems(rows(), GATE, padded), "SLACK: band HIGH fell", "RATCHET_MAX_HIGH = 1") {
                "a baseline above the measurement must be RED, naming the number to record"
            }
        }
    }

    @Test
    fun `the law can actually fail - a zero-neighbour god object still bands HIGH - DR-117`(@TempDir root: File) {
        with(Tree(root)) {
            base()
            // No import line and no importers, on purpose — the emptiness IS the arm. The old
            // fallback was the file's OWN C, which pinned the ratio to 1.0 and band low forever.
            god("lone/Lone.kt", GOD_CLASSES, neighbour = null)
            val lone = rows().first { it.file.endsWith("Lone.kt") }
            assertEquals(emptyList<String>(), lone.neighbourPackages, "the fixture must have NO neighbourhood")
            assertEquals(HIGH, lone.band, "a self-contained god file must not be graded against itself")
            assertHit(Concentration.problems(rows(), GATE, Concentration.Baseline(0, 1, emptyList())), "REGRESSION") {
                "the zero-neighbour god object must move the gated band"
            }
        }
    }

    @Test
    fun `the law can actually fail - the package plane the file plane cannot see`(@TempDir root: File) {
        with(Tree(root)) {
            base()
            put("p0/B2.kt", "package splice.zzconc.p0\nclass B2(val v: String)\n")
            val control = Concentration.Baseline(0, 1, emptyList())
            val hits = Concentration.problems(rows(), GATE, control)
            assertHit(hits, "PACKAGE REGRESSION", "splice.zzconc.p0") {
                "a package that absorbed a file must be RED even while the file census is green"
            }
            assertTrue(hits.none { it.contains("band HIGH") }) {
                "the FILE plane must not move, or this arm is perturbing the plane it is not testing: $hits"
            }
            assertHit(
                Concentration.problems(rows(), GATE, Concentration.Baseline(0, 99, emptyList())),
                "PACKAGE SLACK",
                "PACKAGE_MAX_FILES = 2",
            ) {
                "a package baseline above the measurement must be RED, naming the number to record"
            }
            // The census itself, arithmetically checkable: three one-class files of C 8.5.
            put("p0/B3.kt", "package splice.zzconc.p0\nclass B3(val v: String)\n")
            val p0 = Concentration.packageCensus(rows()).first { it.pkg == "splice.zzconc.p0" }
            assertEquals(Concentration.PackageRow("splice.zzconc.p0", 3, 25.5, 8.5), p0)
        }
    }

    @Test
    fun `the law can actually fail - the ceiling list and the boring empty tree`(@TempDir root: File) {
        with(Tree(root)) {
            base()
            god("god/God.kt", GOD_CLASSES, neighbour = "p0")
            val godFile = "$PKG/god/God.kt"
            val b0 = "$PKG/p0/B.kt"
            val dated = "2026-09-21: the fixture ceiling"
            assertHit(problems(rows(), Concentration.Ceiling(b0, 9.99, dated)), "PADDED CEILING", b0) {
                "a ceiling recorded ABOVE its file's measured ratio must be RED, whatever the caller asked"
            }
            assertHit(problems(rows(), Concentration.Ceiling(godFile, 1.0, dated)), "CEILING BREACHED") {
                "a file above its own recorded ceiling must still fail — a ceiling does not stop watching"
            }
            assertHit(problems(rows(), Concentration.Ceiling(b0, 1.0, "  ")), "no dated justification") {
                "a blank justification must be a hard error"
            }
            assertHit(
                problems(rows(), Concentration.Ceiling("$PKG/Gone.kt", 1.0, dated)),
                "not a production .kt file",
            ) {
                "a ceiling naming a file that is gone must be a hard error"
            }
            // The BORING case (§24): the file plane passes over an empty tree — HIGH equals the
            // baseline and there is no debt — so a lost source root would read as a clean repo.
            clear()
            assertHit(problems(rows()), "the census is EMPTY") {
                "an empty census must REFUSE rather than report a clean tree"
            }
        }
    }

    private fun problems(rows: List<Concentration.Row>, vararg ceilings: Concentration.Ceiling) =
        Concentration.problems(rows, GATE, Concentration.Baseline(0, 1, ceilings.toList()))

    private companion object {
        const val PKG = "app/src/main/kotlin/splice/zzconc"
        const val GATE = Concentration.GATE_RATIO
        const val HIGH = Concentration.HIGH
        const val BASE_PACKAGES = 5

        /** C = 8.5 * classes + 8 for a file with one import; twenty reaches seven times the 3.0
         *  HIGH threshold against a global median of 8.5, which is margin the arm can survive. */
        const val GOD_CLASSES = 20

        const val CENSUS_FIXTURE = """package splice.zzconc
fun interface CensusSeam { fun run(): Int }
annotation class CensusMarker
class CensusHost(val v: String) {
    annotation class NestedMarker
    class Nested(val x: Int)
}
"""
    }
}
