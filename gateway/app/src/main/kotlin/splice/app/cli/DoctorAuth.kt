// NEW: (split from DoctorCommand.kt, which sits at detekt's 14-function file budget) the doctor
// AUTH section — per-head credential presence, the honest severity rule, and the split-brain check
// that catches a key exported after the daemon booted. PHASED so the I/O and the verdict are
// separable: authChecks composes, probeHeads + headAuthOf do every read (env, keystore, topology),
// credentialVerdict + credentialLabel are pure, and splitBrainChecks is the daemon-side comparison.
package splice.app.cli

import splice.core.topology.AuthKindRegistry
import splice.core.topology.ProviderConfig
import splice.core.topology.Topology
import splice.core.util.EnvReader

/** The doctor auth section as a constructed collaborator (Kotlin style law, 2026-08-15: main
 *  sources carry no top-level functions). Every member keeps the old function's name so the diff at
 *  each call site is a receiver insertion. */
internal class DoctorAuth {

    // "Is this head's credential configured?" is StatusCommand's fact — doctor reads it rather
    // than re-deriving the client-auth / file / env / KeyStore precedence a second time.
    private val statusCommand = StatusCommand()

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
        val checks = credentialVerdict(heads, missingStatus)
        return checks + splitBrainChecks(heads, snapshot, envReader)
    }

    /** PHASE 1, all I/O: every configured head's credential state, read through StatusCommand.
     *  Heads whose provider does not resolve are dropped here exactly as they always were — the
     *  configuration section is what reports a dangling provider reference, not this one. */
    private fun probeHeads(topology: Topology, envReader: EnvReader): List<HeadAuth> =
        topology.heads.mapNotNull { (key, head) ->
            val provider = topology.providers[head.provider] ?: return@mapNotNull null
            headAuthOf(key, head.claude.command ?: key, provider, envReader)
        }

    /** PHASE 2, pure: the probed heads plus the honest severity become rows. Reads no environment,
     *  no filesystem and no daemon, so the severity rule is testable without any of the three. */
    private fun credentialVerdict(heads: List<HeadAuth>, missingStatus: CheckStatus): List<DoctorCheck> {
        return heads.map { auth ->
            when {
                auth.present -> DoctorCheck(auth.key, CheckStatus.OK, credentialLabel(auth))
                // Only genuine OAuth heads have a `<command> login` flow; api-key heads (env var known)
                // must never be sent to that dead end, so the guard is isOAuth, not envVar == null.
                auth.isOAuth -> DoctorCheck(auth.key, missingStatus, "not signed in", "${auth.command} login")
                else -> DoctorCheck(
                    auth.key,
                    missingStatus,
                    "${auth.envVar} is not set",
                    "export ${auth.envVar}=…   then: $FIX_RESTART",
                )
            }
        }
    }

    // api-key heads read the EFFECTIVE env var (explicit auth.env OR the derived <KEY>_API_KEY default
    // the daemon wires) so a derived-default head always gets an `export` fix, never the OAuth dead-end;
    // OAuth heads keep a null env var so they read as "signed in"/"login" and skip the split-brain probe.
    private fun headAuthOf(
        key: String,
        command: String,
        provider: ProviderConfig,
        envReader: EnvReader,
    ): HeadAuth {
        val isOAuth = AuthKindRegistry.isOAuth(provider.auth.kind)
        // A client-auth head keeps a NULL env var like an OAuth head: it has no api key, and the
        // derived default would be nonsense — `effectiveApiKeyEnv("claude-splice", …)` is
        // "CLAUDE-MAX_API_KEY", a name `export` cannot even accept, offered as the fix for a head
        // that works.
        val selfManaged = statusCommand.isClientAuth(provider)
        val envVar = when {
            isOAuth || selfManaged -> provider.auth.env
            else -> provider.auth.effectiveApiKeyEnv(key)
        }
        return HeadAuth(
            key,
            command,
            envVar,
            isOAuth,
            statusCommand.authPresent(key, provider, envReader),
            selfManaged,
        )
    }

    // The client-auth line reports what the head DECLARES, not what the daemon wired: doctor reads
    // the topology TOML and cannot see the providers. "splice holds no credential for this head"
    // was a claim about the running daemon that this process has no way to check — true whenever
    // declaration and wiring agree (anthropic-passthrough, the one arm that builds a
    // ClientAuthProvider), false on a dialect whose dispatch has no client arm and therefore keeps
    // an api-key provider plus the mgmt-key door. Naming the declaration is the honest form.
    private fun credentialLabel(auth: HeadAuth): String = when {
        auth.selfManaged -> "client-native — declared auth.kind = client, so there is no key to set"
        auth.envVar != null -> "${auth.envVar} is set"
        else -> "signed in"
    }

    // The daemon reads api-key env vars from ITS OWN environment. A key exported after the daemon
    // booted is present in this shell but invisible upstream — the single most confusing first-run
    // trap, so doctor names it explicitly. When the daemon is UP but the daemon-side comparison can't
    // run (no mgmt-key, or /api/auth unreachable), the flagship check would silently vanish exactly
    // when the daemon is busiest — so emit an explicit WARN instead of empty. A STOPPED daemon is a
    // plain skip (no noise): nothing to compare against.
    private fun splitBrainChecks(
        heads: List<HeadAuth>,
        snapshot: DaemonSnapshot,
        envReader: EnvReader,
    ): List<DoctorCheck> {
        if (!snapshot.running) return emptyList()
        val key = AdminSupport.mgmtKey(envReader)
        val daemonSees = key?.let { ControlPlaneClient.authPresence(snapshot.port, it) }
        if (daemonSees == null) {
            val reason = if (key == null) "no mgmt-key" else "daemon /api/auth unreachable"
            return listOf(DoctorCheck("daemon-auth", CheckStatus.WARN, "daemon-side auth check skipped: $reason"))
        }
        return heads.filter { it.present && it.envVar != null && daemonSees[it.key] == false }.map { auth ->
            DoctorCheck(
                auth.key,
                CheckStatus.FAIL,
                "${auth.envVar} is set in this shell but the daemon started without it",
                FIX_RESTART,
            )
        }
    }
}

private data class HeadAuth(
    val key: String,
    val command: String,
    val envVar: String?,
    val isOAuth: Boolean,
    val present: Boolean,
    /** The CALLER supplies the credential; splice holds none, so there is nothing to configure. */
    val selfManaged: Boolean = false,
)
