// NEW: v0.4.0 FEATURES.md §6 — what `splice doctor` was asked for, what a run collected, and the
// one seam the JSON report reads that the text doctor already owns (the captured Claude Code
// version). Split from DoctorReport.kt so the emitter file carries the emission only.
package splice.app.cli

import splice.core.topology.Topology
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
    internal fun parse(args: List<String>): DoctorReportOptions {
        val outIndex = args.indexOf("--out")
        return DoctorReportOptions(
            json = "--json" in args,
            withLogs = "--with-logs" in args,
            out = args.getOrNull(outIndex + 1)?.takeIf { outIndex >= 0 }?.let(Paths::get),
            live = "--live" in args,
        )
    }
}

/** The collected doctor run: what the sections found, and the topology they read. */
internal data class DoctorRun(val topology: Topology?, val sections: List<Pair<String, List<DoctorCheck>>>)

/** The Claude Code version as the text doctor captured it (`claude --version`, once). */
internal fun interface ClaudeVersionRead {
    operator fun invoke(): String
}
