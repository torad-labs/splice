// NEW: `splice status` — the "is it working / am I signed in" view a user reaches for. Reads the
// topology + auth files + wrapper symlinks + daemon liveness, no daemon required. :app: println ok.

package splice.app.cli

import splice.app.LoginIo
import splice.app.TopologyLoader
import splice.core.topology.AuthKind
import splice.core.topology.AuthKindRegistry
import splice.core.topology.ProviderConfig
import splice.core.util.EnvReader
import splice.core.util.SafeFailureText

/** The `status` verb as a cohesive unit of behavior (Kotlin style law, 2026-08-15: main sources
 *  carry no top-level functions). Also the home of the two credential-presence predicates doctor
 *  reads (isClientAuth / authPresent) — it owns "is this head configured?", so DoctorCommand
 *  constructs one rather than re-deriving them. Every member keeps the old function's name.
 *  The printed table lives on LoginKimi (existing-file extract, 2026-08-19). */
internal class StatusCommand {

    private val loginIo = LoginIo()
    private val table = LoginKimi()

    internal fun status(envReader: EnvReader = EnvReader(System::getenv)) {
        val topology = TopologyLoader.loadOrMaterialize(TopologyLoader.configPath())
        val port = AdminSupport.controlPort()
        val up = AdminSupport.daemonUp(port)

        println("${BOLD}splice$RESET $DIM— Claude Code, wrapped$RESET")
        println()
        val daemonLine = if (up) {
            "${GREEN}running$RESET $DIM· control :$port$RESET"
        } else {
            "${YELLOW}stopped$RESET $DIM(starts on first launch)$RESET"
        }
        println("  daemon    $daemonLine")
        println("  config    $DIM${TopologyLoader.configPath()}$RESET")
        println("  jar       $DIM${jarLine()}$RESET")
        println()
        println("  ${BOLD}HEAD          COMMAND        BACKEND                AUTH          WRAPPER$RESET")
        for ((key, head) in topology.heads) {
            val provider = topology.providers[head.provider] ?: continue
            println("  " + table.row(key, head, provider, envReader))
        }
        println()
        table.printNextSteps(topology, envReader)
    }

    /** DR-86: the status table is a reporter — a jar it cannot stat must say so, not render as
     *  installed (the doctor jarCheck twin). Internal for the permanent arm (codex redo). */
    internal fun jarLine(): String {
        val jar = AdminSupport.selfJar() ?: return "not installed — run: splice install"
        val failure = AdminSupport.jarAccessFailure(jar)
            ?: return jar.toString()
        return "$jar is unreadable (${SafeFailureText.render(failure)}) — fix access to it"
    }

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
        isClientAuth(provider) || loginIo.credentialConfigured(key, provider, envReader)

    internal fun wrapperInstalled(command: String, envReader: EnvReader): Boolean =
        loginIo.wrapperInstalled(command, envReader)
}
