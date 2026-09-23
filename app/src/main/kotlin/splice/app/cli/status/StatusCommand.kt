// NEW: `splice status` — the "is it working / am I signed in" view a user reaches for. Reads the
// topology + auth files + wrapper symlinks + daemon liveness, no daemon required. :app: println ok.

package splice.app.cli.status

import splice.app.cli.AdminSupport
import splice.app.cli.auth.CliSignIn
import splice.app.cli.doctor.HealthView
import splice.core.GATEWAY_VERSION
import splice.core.terminal.CliPalette
import splice.core.terminal.ColorDepthProbe
import splice.core.topology.AuthKind
import splice.core.topology.AuthKindRegistry
import splice.core.topology.ProviderConfig
import splice.core.util.EnvReader
import splice.daemonclient.DaemonHealth
import splice.topology.TopologyLoader

/** The `status` verb as a cohesive unit of behavior (Kotlin style law, 2026-08-15: main sources
 *  carry no top-level functions). Also the home of the two credential-presence predicates doctor
 *  reads (isClientAuth / authPresent) — it owns "is this head configured?", so DoctorCommand
 *  constructs one rather than re-deriving them. Every member keeps the old function's name.
 *  The printed table lives on StatusTable (extracted from LoginKimi, V4-21). */
/** The daemon's /health view for a control port, or null when nothing answers. */
internal fun interface HealthProbe {
    operator fun invoke(port: Int): HealthView?
}

internal class StatusCommand(
    private val healthProbe: HealthProbe = HealthProbe { port -> DaemonHealth().healthView(port) },
    private val accountPools: AccountPoolRead = JdkAccountPoolRead(),
) {

    private val signIn = CliSignIn()
    private val extras = StatusExtras(accountPools)

    internal fun status(envReader: EnvReader = EnvReader(System::getenv)) {
        val topology = TopologyLoader.loadOrMaterialize(TopologyLoader.configPath())
        val port = AdminSupport.controlPort()
        val health = healthProbe(port)
        val up = health?.version == GATEWAY_VERSION
        // ONE palette per call, from the env this call was handed — the same env every row reads,
        // so NO_COLOR set there reaches the output. It used to resolve from System::getenv at
        // construction, and a table whose tones answer to a different environment than its rows
        // cannot be tested for NO_COLOR at all.
        val palette = CliPalette(ColorDepthProbe(envReader).depth())
        val table = StatusTable(palette)

        // The daemon's state rides on the wordmark line rather than taking a labelled row of its
        // own: it is one fact, and a row per fact is what pushed the heads — the actual answer —
        // below the fold on a short terminal.
        val daemonLine = if (up) {
            palette.paint(palette.live, "daemon running on $port")
        } else {
            palette.paint(palette.strain, "daemon stopped") +
                palette.paint(palette.quiet, " (starts on first launch)")
        }
        println("  " + palette.paint(palette.strong, "splice $GATEWAY_VERSION") + "     " + daemonLine)
        clientVersionWarning(health)?.let { println("  " + palette.paint(palette.strain, "! $it")) }
        println()
        for (line in table.lines(topology, envReader)) println(line)
        if (up) extras.printAccounts(port, envReader)
        println()
        // Paths sink below the table: they are reference, not the answer, and an operator who wants
        // them knows they are here. Above the heads they read as though something were wrong.
        println("  " + palette.paint(palette.quiet, "config  ${TopologyLoader.configPath()}"))
        println("  " + palette.paint(palette.quiet, "jar     ${jarLine()}"))
        println()
        // The panel is the one affordance the rows cannot carry. The footer used to add "launch"
        // (the command column again) and "sign in" (each row's action again) — the same facts
        // re-derived by a second predicate, which had already drifted from the rows once, and which
        // on a ten-head topology was a single line of a hundred and seventy characters.
        println("  " + palette.paint(palette.quiet, "panel   ") + palette.paint(palette.signal, "splice dashboard"))
    }

    internal fun clientVersionWarning(health: HealthView?): String? = health?.clientVersionWarning

    /** DR-86 twin of doctor's jarCheck; lives on StatusExtras, kept here for the permanent arm's callers. */
    internal fun jarLine(): String = extras.jarLine()

    /** A head that DECLARES the caller's own credential rather than one splice holds.
     *
     *  DECLARED, not observed, and the distinction is load-bearing: the CLI reads the topology TOML
     *  and never the daemon's wired providers. Declaration and wiring agree on the
     *  anthropic-passthrough dialect — the one dispatch arm that builds a ClientAuthProvider — and
     *  on any other dialect the daemon falls through to an api-key provider and keeps enforcing the
     *  mgmt key. So this predicate answers "what does the head declare?", which is all this process
     *  can see; the daemon derives the actual bypass from the wired credential, never from here. */
    internal fun isClientAuth(provider: ProviderConfig): Boolean =
        AuthKindRegistry.from(provider.auth.kind) == AuthKind.Client

    internal fun authPresent(key: String, provider: ProviderConfig, envReader: EnvReader): Boolean =
        // A head that declares client auth has no splice-held credential to configure BY DESIGN, so
        // "is it configured?" is always yes. Without this it falls through to the api-key branch and
        // reads as permanently unconfigured, against a head that serves fine.
        isClientAuth(provider) || signIn.credentialConfigured(key, provider, envReader)

    internal fun wrapperInstalled(command: String, envReader: EnvReader): Boolean =
        signIn.wrapperInstalled(command, envReader)
}
