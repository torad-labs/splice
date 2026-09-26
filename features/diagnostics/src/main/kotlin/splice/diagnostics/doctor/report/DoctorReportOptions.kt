// NEW: v0.4.0 FEATURES.md §6 — what `splice doctor` was asked for, what a run collected, and the
// one seam the JSON report reads that the text doctor already owns (the captured Claude Code
// version). Split from DoctorJsonReport.kt so the emitter file carries the emission only.
package splice.diagnostics.doctor.report

import splice.accounts.pool.HeadAccountPoolView
import splice.core.topology.Topology
import splice.diagnostics.doctor.DoctorCheck
import java.nio.file.Path
import java.nio.file.Paths

/** What the operator asked for on the command line. */
internal data class DoctorReportOptions(
    val json: Boolean,
    val withLogs: Boolean,
    val out: Path?,
    /** v0.4.0 (FEATURES.md §10): send each local runtime one tiny streamed request with one tool. */
    val live: Boolean = false,
) {
    /** Strict: only the four known flags, each at most once, --out with a value that is not a flag,
     *  and --with-logs / --out only beside --json. Anything else is null — the caller prints usage
     *  and writes nothing (a flag can never become a file name). */
    internal fun parse(args: List<String>): DoctorReportOptions? {
        val seen = mutableSetOf<String>()
        var out: Path? = null
        var valid = true
        var i = 0
        while (valid && i < args.size) {
            val flag = args[i]
            valid = flag in KNOWN_FLAGS && seen.add(flag)
            if (valid && flag == OUT_FLAG) {
                out = outValue(args.getOrNull(i + 1))
                valid = out != null
                i += 1
            }
            i += 1
        }
        return if (valid && consistent(seen, out)) {
            DoctorReportOptions("--json" in seen, "--with-logs" in seen, out, live = "--live" in seen)
        } else {
            null
        }
    }

    private fun outValue(value: String?): Path? = value?.takeIf { !it.startsWith("--") }?.let(Paths::get)

    /** --with-logs and --out describe the JSON report: without --json they are malformed. */
    private fun consistent(seen: Set<String>, out: Path?): Boolean =
        "--json" in seen || ("--with-logs" !in seen && out == null)
}

private const val OUT_FLAG = "--out"
private val KNOWN_FLAGS = setOf("--json", "--with-logs", "--live", OUT_FLAG)
internal const val DOCTOR_USAGE = "usage: splice doctor [--live] [--json [--with-logs] [--out FILE]]"

/** The collected doctor run: what the sections found, and the topology they read. */
internal data class DoctorRun(
    val topology: Topology?,
    val sections: List<Pair<String, List<DoctorCheck>>>,
    /** v0.4.0 (FEATURES.md §11): the account pools the running daemon reported, keyed by head. */
    val accountPools: Map<String, HeadAccountPoolView> = emptyMap(),
)

/** The Claude Code version as the text doctor captured it (`claude --version`, once). */
internal fun interface ClaudeVersionRead {
    operator fun invoke(): String
}
