// NEW: (split from DoctorCommand.kt, which sits at detekt's 14-function file budget) the doctor
// AUTH section — per-head credential presence, the honest severity rule, and the split-brain check
// that catches a key exported after the daemon booted. PHASED so the I/O and the verdict are
// separable: authChecks composes, probeHeads + headAuthOf do every read (env, keystore, topology),
// credentialVerdict + credentialLabel are pure, and splitBrainChecks is the daemon-side comparison.
package splice.app.cli

import splice.app.LoginIo
import splice.core.topology.AuthKind
import splice.core.topology.AuthKindRegistry
import splice.core.topology.ProviderConfig
import splice.core.topology.Topology
import splice.core.util.EnvReader

/** The doctor auth section as a constructed collaborator (Kotlin style law, 2026-08-15: main
 *  sources carry no top-level functions). Every member keeps the old function's name so the diff at
 *  each call site is a receiver insertion. */
internal class DoctorAuth {

    // Credential presence is LoginIo's fact (StatusCommand delegates to the same methods).
    // Reading LoginIo directly keeps splice.app as an honest neighbour vote.
    private val loginIo = LoginIo()
    private val verdict = DoctorAuthVerdict()
    private val restart = RestartCommand()

    internal fun authChecks(
        topo: DoctorTopology,
        envReader: EnvReader,
        snapshot: DaemonSnapshot,
    ): List<DoctorCheck> {
        val topology = (topo as? DoctorTopology.Parsed)?.topology
            ?: return listOf(DoctorCheck("auth", CheckStatus.INFO, "skipped (no readable topology)"))
        val heads = probeHeads(topology, envReader)
        if (heads.isEmpty()) return listOf(DoctorCheck("auth", CheckStatus.INFO, "no heads configured"))
        // Severity is honest to "can I use splice at all": with zero authed heads a missing credential
        // is THE blocker (FAIL); once any head works, the others are ignorable (WARN).
        val missingStatus = if (heads.none { it.present }) CheckStatus.FAIL else CheckStatus.WARN
        val checks = verdict.credentialVerdict(heads, missingStatus)
        return checks + restart.splitBrainChecks(heads, snapshot, envReader)
    }

    /** PHASE 1, all I/O: every configured head's credential state, read through StatusCommand.
     *  Heads whose provider does not resolve are dropped here exactly as they always were — the
     *  configuration section is what reports a dangling provider reference, not this one. */
    private fun probeHeads(topology: Topology, envReader: EnvReader): List<DoctorHeadAuth> =
        topology.heads.mapNotNull { (key, head) ->
            val provider = topology.providers[head.provider] ?: return@mapNotNull null
            headAuthOf(key, head.claude.command ?: key, provider, envReader)
        }

    // api-key heads read the EFFECTIVE env var (explicit auth.env OR the derived <KEY>_API_KEY default
    // the daemon wires) so a derived-default head always gets an `export` fix, never the OAuth dead-end;
    // OAuth heads keep a null env var so they read as "signed in"/"login" and skip the split-brain probe.
    private fun headAuthOf(
        key: String,
        command: String,
        provider: ProviderConfig,
        envReader: EnvReader,
    ): DoctorHeadAuth {
        val isOAuth = AuthKindRegistry.isOAuth(provider.auth.kind)
        // A client-auth head keeps a NULL env var like an OAuth head: it has no api key, and the
        // derived default would be nonsense — `effectiveApiKeyEnv("claude-splice", …)` is
        // "CLAUDE-MAX_API_KEY", a name `export` cannot even accept, offered as the fix for a head
        // that works.
        val selfManaged = AuthKindRegistry.from(provider.auth.kind) == AuthKind.Client
        val envVar = when {
            isOAuth || selfManaged -> provider.auth.env
            else -> provider.auth.effectiveApiKeyEnv(key)
        }
        return DoctorHeadAuth(
            key,
            command,
            envVar,
            isOAuth,
            selfManaged || loginIo.credentialConfigured(key, provider, envReader),
            selfManaged,
        )
    }
}
