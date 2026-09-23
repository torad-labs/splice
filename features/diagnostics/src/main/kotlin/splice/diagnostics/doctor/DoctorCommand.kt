// NEW: `splice doctor` — the "why isn't this working" command. Five sections (prerequisites,
// installation, configuration, daemon, auth); every failed check carries the exact fix command.
// Checks are isolated (one crashing check reports itself, never kills the run) and secrets are
// reported by presence only, never by value. Exit 1 only on real failures — a stopped daemon or
// an unused-but-unauthed head is not a failure. Sections live one file each: DoctorProbes.kt
// (prerequisites), DoctorInstallProbes.kt (installation), DoctorConfigChecks.kt,
// DoctorDaemonChecks.kt (with DoctorHeadChecks.kt), DoctorAuth.kt and DoctorRuntime.kt. This file
// owns composition, isolation and the verdict.
package splice.diagnostics.doctor

import splice.accounts.pool.HeadAccountPoolView
import splice.core.config.RunningJar
import splice.core.terminal.CliPalette
import splice.core.terminal.ColorDepthProbe
import splice.core.terminal.TerminalOutput
import splice.core.util.Cancellables
import splice.core.util.EnvReader
import splice.core.util.SafeFailureText
import splice.daemonclient.DaemonProbe
import splice.daemonclient.DaemonSettings
import splice.diagnostics.doctor.report.DOCTOR_USAGE
import splice.diagnostics.doctor.report.DoctorJsonReport
import splice.diagnostics.doctor.report.DoctorReportOptions
import splice.diagnostics.doctor.report.DoctorRun
import splice.topology.TopologyLoader
import java.nio.file.Files
import java.nio.file.Path

/** The `doctor` verb as a cohesive unit of behavior (Kotlin style law, 2026-08-15: main sources
 *  carry no top-level functions): sections, rendering, and the verdict. The probe files it was
 *  already split across become constructed collaborators; every member keeps the old function's
 *  name so the diff at each call site is a receiver insertion. */
public class DoctorCommand(
    private val output: TerminalOutput,
    private val errors: TerminalOutput,
    jar: RunningJar,
    local: LocalRuntimeTransport,
    private val accountPools: AccountPoolRead = JdkAccountPoolRead(),
) {

    private val probes = DoctorProbes(jar)

    // Install integrity is a separate section with separate inputs; it reads back into [probes] for
    // the one thing the two share, the malformed-PATH-entry parser.
    private val installProbes = DoctorInstallProbes(probes, jar)
    private val doctorRuntime = DoctorRuntime()
    private val config = DoctorConfigChecks(DoctorLocalRuntime(local))
    private val auth = DoctorAuth(output)
    private val accountText = AccountPoolText()
    private val settings = DaemonSettings(errors)

    // ONE DoctorRuntime for the whole run: the daemon section's per-head rows and the runtime
    // section's own rows must read the same instrument, so the head checks receive the collaborator
    // this class already holds — the DoctorInstallProbes(probes) idiom.
    private val daemon = DoctorDaemonChecks(DoctorHeadChecks(doctorRuntime))

    /** `splice doctor [--live] [--json [--with-logs] [--out FILE]]`. The text report unless --json
     *  (v0.4.0, FEATURES.md §6), in which case DoctorJsonReport emits the allowlisted, redacted JSON
     *  instead. --live (FEATURES.md §10) is the only flag that sends a request anywhere: one tiny
     *  streamed tool call per local-runtime row, so a model's tool support is proven, not assumed. */
    internal fun doctor(envReader: EnvReader): Boolean = doctor(emptyList(), envReader)

    public fun doctor(args: List<String>, envReader: EnvReader): Boolean {
        val options = DoctorReportOptions(json = false, withLogs = false, out = null).parse(args)
        if (options == null) {
            errors.line("splice doctor: unknown or malformed arguments ${args.joinToString(" ")}\n$DOCTOR_USAGE")
            return false
        }
        val run = collect(envReader, options.live)
        if (options.json) {
            val report = DoctorJsonReport(envReader, claudeVersion = { installProbes.capturedVersion(CLAUDE_VERSION) })
            return report.emit(run, options, output)
        }
        // The SAME palette the other two surfaces resolve, for the same reason: NO_COLOR is a
        // contract, and an operator who sets it must not get escape bytes from the one command they
        // run when something is already wrong. Resolved from THIS call's env — the one every check
        // reads — once per run, because a palette that can change between two rows is not one.
        val palette = CliPalette(ColorDepthProbe(envReader).depth())
        val sections = run.sections
        val all = sections.flatMap { it.second }
        val failures = all.count { it.status == CheckStatus.FAIL }
        val warnings = all.count { it.status == CheckStatus.WARN }
        renderReport(sections, all, palette)
        output.line("")
        // Indented with the report it closes. At column 0 it read as the shell's next line rather
        // than the report's last one.
        when {
            failures > 0 ->
                output.line(
                    "  " + palette.paint(palette.dead, "$failures issue(s)") + " — fixes listed above. Re-run " +
                        palette.paint(palette.signal, "splice doctor") + " after.",
                )
            warnings > 0 ->
                output.line("  " + palette.paint(palette.live, "No blockers") + " ($warnings warning(s) above).")
            else -> output.line("  " + palette.paint(palette.live, "Everything checks out."))
        }
        return failures == 0
    }

    /**
     * Passes stay QUIET, problems get ROOM.
     *
     * The previous report printed all twenty-odd checks at equal weight under their section
     * headings, so the one row that needed acting on was camouflaged by the twenty that did not —
     * on a surface whose entire job is to surface that row. Now a pass is one dim line (its detail
     * kept: that is the evidence), a note with no fix is one dim line, and anything carrying a fix
     * gets a blank line, a glyph, its name in bold and the fix on its own line.
     */
    private fun renderReport(
        sections: List<Pair<String, List<DoctorCheck>>>,
        all: List<DoctorCheck>,
        palette: CliPalette,
    ) {
        output.line("")
        output.line(
            "  " + palette.paint(palette.strong, "splice doctor") + "  " +
                palette.paint(palette.quiet, "${all.size} checks"),
        )
        output.line("")
        // EVERY PASS KEEPS ITS LINE, dim, above the problems. An earlier cut collapsed them to a
        // roster ("18 passed  prerequisites installation ...") whenever anything was wrong, on the
        // reasoning that a broken row must not be camouflaged by twenty fine ones. Two scar tests
        // killed it, and they agree with each other: DoctorCommandTest pins `OPENROUTER_API_KEY is
        // set` and `probe timed out` in the output of machines that DO have failures. A passing
        // check's DETAIL is the evidence — did it see my key, did that probe hang — and `probe timed
        // out` is a check that passed without measuring anything, which a "18 passed" line would
        // have absorbed into its own denominator. The roster was a scan-length optimisation buying
        // itself with information; hierarchy here is carried by weight and space instead, which is
        // what the problems below already use and what survives a pipe.
        for (check in all.filter { it.status == CheckStatus.OK }) {
            output.line(
                "  " + palette.paint(palette.live, PASS_GLYPH) + "  ${check.name}  " +
                    palette.paint(palette.quiet, check.detail),
            )
        }
        // THE SPLIT IS "DOES IT CARRY A FIX", NOT THE STATUS. The first cut expanded WARN and FAIL
        // and collapsed every INFO to one dim line — which silently dropped the fix off an INFO that
        // had one, and `topology: no topology yet` is exactly that: INFO, because a machine with no
        // config yet is not broken, and it carries `splice init`, which is the single most important
        // string doctor prints on a fresh install. DoctorCommandTest caught it.
        //
        // So: a check with a fix gets room, whatever its status, because surfacing the fix is the
        // entire job of this surface. A note with no fix is a note.
        val notes = all.filter { it.status == CheckStatus.INFO && it.fix == null }
        val actionable = all.filter { it.status != CheckStatus.OK && (it.fix != null || it.status != CheckStatus.INFO) }
        // DR-173. Its first half — the old render took `checks.maxOf { ... }` to size a column and
        // threw on an empty section, which a running daemon with zero heads produces — cannot
        // return: nothing here measures a collection. Its second half: the old render gave an empty
        // section a heading and the words "nothing to report"; dropping section headings took away
        // the only place that could live, and an empty section silently vanishing is the same
        // silence the scar was written against: a live daemon with zero heads would show no runtime
        // line at all, which reads as "not checked" exactly when the operator is asking whether it
        // was. It keeps a note instead.
        for ((title, _) in sections.filter { it.second.isEmpty() }) {
            output.line("  " + palette.paint(palette.quiet, "$NOTE_GLYPH  $title  nothing to report"))
        }
        for (check in notes) {
            output.line("  " + palette.paint(palette.quiet, "$NOTE_GLYPH  ${check.name}  ${check.detail}"))
        }
        for (check in actionable) {
            renderProblem(check, palette)
        }
    }

    /** One problem, given room: what is wrong, then why it matters, then the command to run. */
    private fun renderProblem(check: DoctorCheck, palette: CliPalette) {
        val glyph = when (check.status) {
            CheckStatus.FAIL -> palette.paint(palette.dead, FAIL_GLYPH)
            CheckStatus.WARN -> palette.paint(palette.strain, WARN_GLYPH)
            // An INFO reaching here has a fix but is not a fault — a fresh machine with no topology
            // is not sick. It gets the room without the alarm.
            else -> palette.paint(palette.quiet, NOTE_GLYPH)
        }
        output.line("")
        output.line("  $glyph " + palette.paint(palette.strong, check.name))
        output.line("      " + palette.paint(palette.quiet, check.detail))
        check.fix?.let {
            output.line("      " + palette.paint(palette.quiet, "fix") + "   " + palette.paint(palette.signal, it))
        }
    }

    /** The `--json` report as TEXT, for a caller that SHIPS it rather than printing it — the
     *  console's /api/doctor. It shares the assembly AND the encoder with [doctor]'s own --json path
     *  (DoctorJsonReport.jsonText), so the console and `splice doctor --json` cannot disagree about one
     *  run; a second assembly here would have meant a second redaction path list.
     *
     *  [withLogs] defaults to false, which is what a bare `splice doctor --json` does: log lines
     *  leaving the machine are an explicit opt-in, and a console poll is not a person asking. */
    public fun reportJson(
        envReader: EnvReader,
        live: Boolean = false,
        withLogs: Boolean = false,
    ): String {
        val run = collect(envReader, live)
        val report = DoctorJsonReport(envReader, claudeVersion = { installProbes.capturedVersion(CLAUDE_VERSION) })
        return report.jsonText(report.build(run, withLogs))
    }

    /** Every section, collected once; both renderings read this. */
    internal fun collect(envReader: EnvReader, live: Boolean = false): DoctorRun {
        val configPath = TopologyLoader.configPath(envReader)
        val topo = loadTopology(configPath)
        // Resolve the port and probe /health ONCE; both the daemon and auth sections read this snapshot
        // so a busy daemon is contacted a single time and the split-brain check can't silently self-skip.
        val topology = (topo as? DoctorTopology.Parsed)?.topology
        val port = settings.controlPort(topology, envReader)
        val snapshot = DaemonSnapshot(port, DaemonProbe.healthView(port))
        val pools = if (snapshot.running) accountPools(port, envReader) else null
        val sections = listOf(
            "prerequisites" to guarded { probes.prerequisiteChecks(envReader) },
            "installation" to guarded { installProbes.installationChecks(topo, envReader) },
            "configuration" to guarded { config.configurationChecks(topo, configPath, live) },
            CHECK_DAEMON to guarded { daemon.daemonChecks(snapshot, envReader, topology, configPath) },
            "auth" to guarded { auth.authChecks(topo, envReader, snapshot) },
            // v0.4.0 (FEATURES.md §11): which account each pooled head is on, and when every one is out.
            "accounts" to guarded { accountChecks(snapshot, pools) },
            // JW-05: what actually HAPPENED — every section above reads configuration and presence;
            // this one reads the runtime instruments (health counters + perf outcome tail).
            "runtime" to guarded { doctorRuntime.runtimeChecks(snapshot, envReader) },
        )
        return DoctorRun(topology, sections, pools.orEmpty())
    }

    private fun accountChecks(snapshot: DaemonSnapshot, pools: Map<String, HeadAccountPoolView>?): List<DoctorCheck> =
        when {
            !snapshot.running -> listOf(DoctorCheck(ACCOUNTS_CHECK, CheckStatus.INFO, "skipped (daemon not running)"))
            pools == null -> listOf(
                DoctorCheck(ACCOUNTS_CHECK, CheckStatus.WARN, "the daemon's /api/auth could not be read (mgmt key?)"),
            )
            pools.isEmpty() -> listOf(DoctorCheck(ACCOUNTS_CHECK, CheckStatus.INFO, "one account per head"))
            else -> pools.map { (head, view) -> accountText.check(head, view) }
        }

    // One crashing check must not kill the report (nor masquerade as healthy).
    private fun guarded(block: DoctorProbe): List<DoctorCheck> =
        Cancellables.runCatchingBestEffort(block::invoke).getOrElse { e ->
            listOf(DoctorCheck("doctor", CheckStatus.FAIL, "check crashed: ${SafeFailureText.render(e)}"))
        }

    // DR-69: doctor's contract is exact diagnosis — only proven absence is Absent; an
    // unreadable (or dangling-linked) splice.toml is a PRESENT config with an access problem
    // and reports Broken, so the auth checks are not silently skipped as first-run.
    private fun loadTopology(configPath: Path): DoctorTopology = Cancellables
        .runCatchingCancellable { DoctorTopology.Parsed(TopologyLoader.parse(Files.readString(configPath))) }
        .getOrElse { e ->
            val genuinelyAbsent = e is java.nio.file.NoSuchFileException &&
                !Files.exists(configPath, java.nio.file.LinkOption.NOFOLLOW_LINKS)
            // DR-92: parse text can quote the offending value, and splice.toml legally carries
            // credential-like values (extra_headers Authorization on non-client topologies) —
            // render() keeps fs-failure diagnostics and withholds parser excerpts.
            if (genuinelyAbsent) DoctorTopology.Absent else DoctorTopology.Broken(SafeFailureText.render(e))
        }
}

// The glyphs. Colour is the SECOND carrier here, never the only one: at ColorDepth.NONE these
// four shapes are the entire difference between a pass, a note, a warning and a failure, and they
// stay distinguishable in a pipe, a CI log and a screen reader's line.
private const val PASS_GLYPH = "\u2713"
private const val NOTE_GLYPH = "\u2013"
private const val WARN_GLYPH = "!"
private const val FAIL_GLYPH = "\u2717"

private val CLAUDE_VERSION = listOf("claude", "--version")
private const val ACCOUNTS_CHECK = "accounts"
