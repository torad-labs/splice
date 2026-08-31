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
import splice.core.util.Cancellables
import splice.core.util.EnvReader
import java.nio.file.Files
import java.nio.file.Path

/** The `doctor` verb as a cohesive unit of behavior (Kotlin style law, 2026-08-15: main sources
 *  carry no top-level functions): sections, rendering, and the verdict. The probe files it was
 *  already split across become constructed collaborators; every member keeps the old function's
 *  name so the diff at each call site is a receiver insertion. */
internal class DoctorCommand {

    private val probes = DoctorProbes()

    // Install integrity is a separate section with separate inputs; it reads back into [probes] for
    // the one thing the two share, the malformed-PATH-entry parser.
    private val installProbes = DoctorInstallProbes(probes)
    private val doctorRuntime = DoctorRuntime()
    private val config = DoctorConfigChecks()
    private val auth = DoctorAuth()

    // ONE DoctorRuntime for the whole run: the daemon section's per-head rows and the runtime
    // section's own rows must read the same instrument, so the head checks receive the collaborator
    // this class already holds — the DoctorInstallProbes(probes) idiom.
    private val daemon = DoctorDaemonChecks(DoctorHeadChecks(doctorRuntime))

    internal fun doctor(envReader: EnvReader = EnvReader(System::getenv)): Boolean {
        val configPath = TopologyLoader.configPath(envReader)
        val topo = loadTopology(configPath)
        // Resolve the port and probe /health ONCE; both the daemon and auth sections read this snapshot
        // so a busy daemon is contacted a single time and the split-brain check can't silently self-skip.
        val topology = (topo as? DoctorTopology.Parsed)?.topology
        val port = AdminSupport.controlPort(topology, envReader)
        val snapshot = DaemonSnapshot(port, DaemonProbe.healthView(port))
        val sections = listOf(
            "prerequisites" to guarded { probes.prerequisiteChecks(envReader) },
            "installation" to guarded { installProbes.installationChecks(topo, envReader) },
            "configuration" to guarded { config.configurationChecks(topo, configPath) },
            CHECK_DAEMON to guarded { daemon.daemonChecks(snapshot, envReader, topology, configPath) },
            "auth" to guarded { auth.authChecks(topo, envReader, snapshot) },
            // JW-05: what actually HAPPENED — every section above reads configuration and presence;
            // this one reads the runtime instruments (health counters + perf outcome tail).
            "runtime" to guarded { doctorRuntime.runtimeChecks(snapshot, envReader) },
        )
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

    // One crashing check must not kill the report (nor masquerade as healthy).
    private fun guarded(block: DoctorProbe): List<DoctorCheck> =
        Cancellables.runCatchingCancellable(block::invoke).getOrElse { e ->
            listOf(DoctorCheck("doctor", CheckStatus.FAIL, "check crashed: ${e.message}"))
        }

    // DR-69: doctor's contract is exact diagnosis — only proven absence is Absent; an
    // unreadable (or dangling-linked) splice.toml is a PRESENT config with an access problem
    // and reports Broken, so the auth checks are not silently skipped as first-run.
    private fun loadTopology(configPath: Path): DoctorTopology = Cancellables
        .runCatchingCancellable { DoctorTopology.Parsed(TopologyLoader.parse(Files.readString(configPath))) }
        .getOrElse { e ->
            val genuinelyAbsent = e is java.nio.file.NoSuchFileException &&
                !Files.exists(configPath, java.nio.file.LinkOption.NOFOLLOW_LINKS)
            if (genuinelyAbsent) DoctorTopology.Absent else DoctorTopology.Broken(e.message ?: "unreadable")
        }

    private fun renderSection(title: String, checks: List<DoctorCheck>) {
        println()
        println("  $DIM$title$RESET")
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
