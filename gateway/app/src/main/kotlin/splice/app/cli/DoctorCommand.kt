// NEW: `splice doctor` — the "why isn't this working" command. Five sections (prerequisites,
// installation, configuration, daemon, auth); every failed check carries the exact fix command.
// Checks are isolated (one crashing check reports itself, never kills the run) and secrets are
// reported by presence only, never by value. Exit 1 only on real failures — a stopped daemon or
// an unused-but-unauthed head is not a failure. Sections live one file each: DoctorProbes.kt
// (prerequisites), DoctorInstallProbes.kt (installation), DoctorConfigChecks.kt,
// DoctorDaemonChecks.kt (with DoctorHeadChecks.kt), DoctorAuth.kt and DoctorRuntime.kt. This file
// owns composition, isolation and the verdict. :app: println ok.
package splice.app.cli

import splice.app.DaemonProbe
import splice.app.TopologyLoader
import splice.control.HeadAccountPoolView
import splice.core.util.Cancellables
import splice.core.util.EnvReader
import splice.core.util.SafeFailureText
import java.nio.file.Files
import java.nio.file.Path

/** The `doctor` verb as a cohesive unit of behavior (Kotlin style law, 2026-08-15: main sources
 *  carry no top-level functions): sections, rendering, and the verdict. The probe files it was
 *  already split across become constructed collaborators; every member keeps the old function's
 *  name so the diff at each call site is a receiver insertion. */
internal class DoctorCommand(private val accountPools: AccountPoolRead = JdkAccountPoolRead()) {

    private val probes = DoctorProbes()

    // Install integrity is a separate section with separate inputs; it reads back into [probes] for
    // the one thing the two share, the malformed-PATH-entry parser.
    private val installProbes = DoctorInstallProbes(probes)
    private val doctorRuntime = DoctorRuntime()
    private val config = DoctorConfigChecks()
    private val auth = DoctorAuth()
    private val accountText = AccountPoolText()

    // ONE DoctorRuntime for the whole run: the daemon section's per-head rows and the runtime
    // section's own rows must read the same instrument, so the head checks receive the collaborator
    // this class already holds — the DoctorInstallProbes(probes) idiom.
    private val daemon = DoctorDaemonChecks(DoctorHeadChecks(doctorRuntime))

    /** `splice doctor [--live] [--json [--with-logs] [--out FILE]]`. The text report unless --json
     *  (v0.4.0, FEATURES.md §6), in which case DoctorReport emits the allowlisted, redacted JSON
     *  instead. --live (FEATURES.md §10) is the only flag that sends a request anywhere: one tiny
     *  streamed tool call per local-runtime row, so a model's tool support is proven, not assumed. */
    internal fun doctor(envReader: EnvReader = EnvReader(System::getenv)): Boolean = doctor(emptyList(), envReader)

    internal fun doctor(args: List<String>, envReader: EnvReader = EnvReader(System::getenv)): Boolean {
        val options = DoctorReportOptions(json = false, withLogs = false, out = null).parse(args)
        if (options == null) {
            System.err.println("splice doctor: unknown or malformed arguments ${args.joinToString(" ")}\n$DOCTOR_USAGE")
            return false
        }
        val run = collect(envReader, options.live)
        if (options.json) {
            val report = DoctorReport(envReader, claudeVersion = { installProbes.capturedVersion(CLAUDE_VERSION) })
            return report.emit(run, options)
        }
        val sections = run.sections
        println("${BOLD}splice doctor$RESET $DIM— every ✗ and ! comes with its fix$RESET")
        sections.forEach { (title, checks) -> renderSection(title, checks) }
        val all = sections.flatMap { it.second }
        val failures = all.count { it.status == CheckStatus.FAIL }
        val warnings = all.count { it.status == CheckStatus.WARN }
        println()
        when {
            failures > 0 ->
                println("$RED$failures issue(s)$RESET — fixes listed above. Re-run ${CYAN}splice doctor$RESET after.")
            warnings > 0 -> println("${GREEN}No blockers$RESET ($warnings warning(s) above).")
            else -> println("${GREEN}Everything checks out.$RESET")
        }
        return failures == 0
    }

    /** Every section, collected once; both renderings read this. */
    internal fun collect(envReader: EnvReader, live: Boolean = false): DoctorRun {
        val configPath = TopologyLoader.configPath(envReader)
        val topo = loadTopology(configPath)
        // Resolve the port and probe /health ONCE; both the daemon and auth sections read this snapshot
        // so a busy daemon is contacted a single time and the split-brain check can't silently self-skip.
        val topology = (topo as? DoctorTopology.Parsed)?.topology
        val port = AdminSupport.controlPort(topology, envReader)
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
        Cancellables.runCatchingCancellable(block::invoke).getOrElse { e ->
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

    private fun renderSection(title: String, checks: List<DoctorCheck>) {
        println()
        println("  $DIM$title$RESET")
        // DR-173: maxOf THROWS NoSuchElementException on an empty list, and this render loop sits
        // OUTSIDE guarded() — that wraps only the collectors, which have already run by the time
        // sections is built. An empty list is reachable without anything being wrong: a daemon that
        // is running, with a readable key and /api/heads answering, but configured with ZERO heads
        // returns one, because DaemonLock.headsRuntime reserves null for a failed request and hands
        // back an empty List for an empty array. So `splice doctor` died with a stack trace instead
        // of printing a report, on an install whose only sin was having no heads yet.
        //
        // Total by construction rather than guarded from outside: an empty section SAYS it is
        // empty. A bare heading with nothing under it is the silence doctor exists to replace, and
        // DoctorAuth already answers the same input with a one-line INFO.
        if (checks.isEmpty()) {
            println("  $DIM–  nothing to report$RESET")
            return
        }
        val width = checks.maxOf { it.name.length }
        checks.forEach { check ->
            val glyph = when (check.status) {
                CheckStatus.OK -> "$GREEN✓$RESET"
                CheckStatus.INFO -> "$DIM–$RESET"
                CheckStatus.WARN -> "$YELLOW!$RESET"
                CheckStatus.FAIL -> "$RED✗$RESET"
            }
            println("  $glyph ${check.name.padEnd(width)}  ${check.detail}")
            check.fix?.let { println("    ${" ".repeat(width)}  ${DIM}fix:$RESET $CYAN$it$RESET") }
        }
    }
}

private val CLAUDE_VERSION = listOf("claude", "--version")
private const val ACCOUNTS_CHECK = "accounts"
