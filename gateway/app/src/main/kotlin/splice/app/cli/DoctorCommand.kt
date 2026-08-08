// NEW: `splice doctor` — the "why isn't this working" command. Five sections (prerequisites,
// installation, configuration, daemon, auth); every failed check carries the exact fix command.
// Checks are isolated (one crashing check reports itself, never kills the run) and secrets are
// reported by presence only, never by value. Exit 1 only on real failures — a stopped daemon or
// an unused-but-unauthed head is not a failure. Probes live in DoctorProbes.kt. :app: println ok.
package splice.app.cli

import splice.app.TopologyLoader
import splice.core.GATEWAY_VERSION
import splice.core.config.StatePaths
import splice.core.topology.AuthKind
import splice.core.topology.ProviderConfig
import splice.core.topology.Topology
import splice.core.topology.effectiveApiKeyEnv
import splice.core.topology.portCollisionMessage
import splice.core.util.runCatchingCancellable
import java.nio.file.Files
import java.nio.file.Path

private const val RESET = "\u001B[0m"
private const val DIM = "\u001B[2m"
private const val BOLD = "\u001B[1m"
private const val GREEN = "\u001B[32m"
private const val YELLOW = "\u001B[33m"
private const val RED = "\u001B[31m"
private const val CYAN = "\u001B[36m"

private const val CHECK_TOPOLOGY = "topology"
private const val CHECK_DAEMON = "daemon"
private const val FIX_RESTART = "splice restart"

internal enum class CheckStatus { OK, INFO, WARN, FAIL }

internal data class DoctorCheck(
    val name: String,
    val status: CheckStatus,
    val detail: String,
    val fix: String? = null,
)

/** Resolved control port + the version the listener there reports (null = nothing answering).
 *  Computed ONCE in doctor() and threaded into both the daemon and auth sections so the port is
 *  resolved a single time and /health is probed a single time (was: twice each). */
internal data class DaemonSnapshot(val port: Int, val health: ControlPlaneClient.HealthView?) {
    val healthVersion: String? get() = health?.version
    val running: Boolean get() = health != null
}

/** The topology as doctor sees it: not written yet, readable, or broken (with the parse error). */
internal sealed class DoctorTopology {
    data object Absent : DoctorTopology()
    data class Parsed(val topology: Topology) : DoctorTopology()
    data class Broken(val message: String) : DoctorTopology()
}

internal fun doctor(envReader: (String) -> String? = System::getenv): Boolean {
    val configPath = TopologyLoader.configPath(envReader)
    val topo = loadTopology(configPath)
    // Resolve the port and probe /health ONCE; both the daemon and auth sections read this snapshot
    // so a busy daemon is contacted a single time and the split-brain check can't silently self-skip.
    val topology = (topo as? DoctorTopology.Parsed)?.topology
    val port = AdminSupport.controlPort(topology, envReader)
    val snapshot = DaemonSnapshot(port, ControlPlaneClient.healthView(port))
    val sections = listOf(
        "prerequisites" to guarded { prerequisiteChecks(envReader) },
        "installation" to guarded { installationChecks(topo, envReader) },
        "configuration" to guarded { configurationChecks(topo, configPath) },
        CHECK_DAEMON to guarded { daemonChecks(snapshot, envReader, topology, configPath) },
        "auth" to guarded { authChecks(topo, envReader, snapshot) },
        // JW-05: what actually HAPPENED — every section above reads configuration and presence;
        // this one reads the runtime instruments (health counters + perf outcome tail).
        "runtime" to guarded { runtimeChecks(snapshot, envReader) },
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
private fun guarded(block: () -> List<DoctorCheck>): List<DoctorCheck> =
    runCatchingCancellable(block).getOrElse { e ->
        listOf(DoctorCheck("doctor", CheckStatus.FAIL, "check crashed: ${e.message}"))
    }

private fun loadTopology(configPath: Path): DoctorTopology {
    if (!Files.exists(configPath)) return DoctorTopology.Absent
    return runCatchingCancellable { DoctorTopology.Parsed(TopologyLoader.parse(Files.readString(configPath))) }
        .getOrElse { e -> DoctorTopology.Broken(e.message ?: "unreadable") }
}

private fun configurationChecks(topo: DoctorTopology, configPath: Path): List<DoctorCheck> = when (topo) {
    is DoctorTopology.Absent -> listOf(
        DoctorCheck(CHECK_TOPOLOGY, CheckStatus.INFO, "no topology yet at $configPath", "splice init"),
    )
    is DoctorTopology.Broken -> listOf(
        DoctorCheck(
            CHECK_TOPOLOGY,
            CheckStatus.FAIL,
            "$configPath does not parse: ${topo.message}",
            "fix the TOML (compare config/splice.example.toml), or delete it and run: splice init",
        ),
    )
    is DoctorTopology.Parsed -> {
        val topology = topo.topology
        val heads = topology.heads.entries.joinToString(", ") { (k, h) -> "$k → ${h.claude.command ?: k}" }
        val summary = DoctorCheck(
            CHECK_TOPOLOGY,
            CheckStatus.OK,
            "$configPath — ${topology.heads.size} head(s): $heads",
        )
        val brokenRefs = topology.heads.filterValues { it.provider !in topology.providers }.map { (key, head) ->
            DoctorCheck(
                CHECK_TOPOLOGY,
                CheckStatus.FAIL,
                "head '$key' references missing provider '${head.provider}'",
                "add [providers.${head.provider}] to $configPath or fix the head's provider",
            )
        }
        // JW-13: a duplicate port is a pre-flight FAIL naming both heads (mirrors the
        // wrapper-command collision install validates), not an opaque per-head bind error.
        val portDupes = topology.portCollisions().map { (port, keys) ->
            DoctorCheck(
                CHECK_TOPOLOGY,
                CheckStatus.FAIL,
                portCollisionMessage(port, keys),
                "change one head's port in $configPath",
            )
        }
        listOf(summary) + brokenRefs + portDupes
    }
}

private fun daemonChecks(
    snapshot: DaemonSnapshot,
    envReader: (String) -> String?,
    topology: Topology?,
    configPath: Path? = null,
): List<DoctorCheck> {
    val statePaths = StatePaths(envReader = envReader)
    val daemon = when (val running = snapshot.healthVersion) {
        null -> DoctorCheck(CHECK_DAEMON, CheckStatus.INFO, "stopped (starts on first launch)")
        GATEWAY_VERSION -> DoctorCheck(CHECK_DAEMON, CheckStatus.OK, "running $GATEWAY_VERSION on :${snapshot.port}")
        else -> DoctorCheck(
            CHECK_DAEMON,
            CheckStatus.WARN,
            "running $running but this CLI is $GATEWAY_VERSION",
            FIX_RESTART,
        )
    }
    // daemon.lock is a flock advisory gate whose FILE persists after the daemon exits, so its mere
    // presence proves nothing about liveness (DaemonLock.kt) — report the path only, never a
    // fabricated staleness WARN. The state dir path is the same kind of orientation detail.
    val stateInfo = listOf(
        DoctorCheck("state dir", CheckStatus.INFO, statePaths.stateDir.toString()),
        // JW-08: daemon.log lives in the SIBLING logs dir, not state/ — printing only the state
        // dir sent operators to a directory that does not contain the logs. Name the real path
        // and the verb that reaches it (works with the daemon stopped).
        DoctorCheck("logs", CheckStatus.INFO, "${statePaths.logsDir.resolve("daemon.log")}  (splice logs)"),
        DoctorCheck("daemon.lock", CheckStatus.INFO, statePaths.daemonLockFile.toString()),
    )
    return listOf(daemon) + headChecks(snapshot, topology) +
        listOfNotNull(topologyFreshness(snapshot, configPath), mgmtKeyCheck(statePaths, snapshot.running)) +
        stateInfo
}

/** JW-04: is the file on disk still the one the daemon booted from? Compared digest-to-digest
 *  (the doctor hashes the local file; the daemon published what it parsed), with the daemon's
 *  own topologyStale recompute as the belt. Fail-open: no health, no published digest, or an
 *  unreadable local file all mean no row — never a fabricated verdict. */
private fun topologyFreshness(snapshot: DaemonSnapshot, configPath: Path?): DoctorCheck? {
    val h = snapshot.health
    val booted = h?.topologyDigest?.takeIf { it.isNotEmpty() }
    val local = booted?.let { configPath?.let(TopologyLoader::currentDigest) } ?: return null
    return if (local == booted && h?.topologyStale != true) {
        DoctorCheck("topology", CheckStatus.OK, "running config matches the file on disk")
    } else {
        DoctorCheck(
            "topology",
            CheckStatus.WARN,
            "splice.toml changed since the daemon booted — the running topology is stale",
            "splice restart",
        )
    }
}

/** JW-02: the degraded-boot rows doctor was structurally blind to. /health has carried
 *  heads/readyHeads/failedHeads since the shim's converge-wait; a green "daemon running" over
 *  dead heads plus "Everything checks out." was the exact lie this command exists to prevent.
 *  Per-head TCP probes distinguish a bound-but-unassembled head from an unbound one; probed only
 *  while the daemon runs (a stopped daemon's closed ports are expected, not findings). */
private fun headChecks(snapshot: DaemonSnapshot, topology: Topology?): List<DoctorCheck> {
    val h = snapshot.health ?: return emptyList()
    val perHead = topology?.heads.orEmpty().map { (key, cfg) ->
        val listening = AdminSupport.controlPortBound(cfg.port)
        DoctorCheck(
            "head $key",
            if (listening) CheckStatus.INFO else CheckStatus.WARN,
            ":${cfg.port} ${if (listening) "listening" else "not listening"}",
        )
    }
    return listOfNotNull(headSummary(h)) + perHead
}

/** The heads/readyHeads/failedHeads verdict; null on a foreign/ancient listener without the
 *  counters (a real daemon always sends all three) — nothing honest to report then. */
private fun headSummary(h: ControlPlaneClient.HealthView): DoctorCheck? {
    val heads = h.heads
    val ready = h.readyHeads
    val failed = h.failedHeads
    val countersPresent = listOf(heads, ready, failed).none { it == null }
    if (!countersPresent) return null
    checkNotNull(heads)
    checkNotNull(ready)
    checkNotNull(failed)
    return when {
        failed > 0 -> DoctorCheck(
            "heads",
            CheckStatus.FAIL,
            "$failed of $heads head(s) FAILED to start",
            "splice restart (then: splice logs --head <key> --tail 50 to see why)",
        )
        ready + failed < heads ->
            DoctorCheck("heads", CheckStatus.WARN, "still converging: $ready ready + $failed failed of $heads")
        else -> DoctorCheck("heads", CheckStatus.OK, "$ready of $heads head(s) ready")
    }
}

// LOST-COVERAGE fix: a missing mgmt-key file 401s every bearer endpoint. Present → OK; absent while
// the daemon RUNS is a hard FAIL (the daemon holds its boot-minted key in memory and re-reads no
// file, and `splice restart` can't authenticate the shutdown without the file — so the honest fix
// is a manual kill, after which the next launch re-mints via MgmtKey.ensure()); absent while stopped
// is benign (minted on first launch).
private fun mgmtKeyCheck(statePaths: StatePaths, daemonRunning: Boolean): DoctorCheck {
    val keyFile = statePaths.mgmtKeyFile
    val present = runCatchingCancellable { Files.readString(keyFile).trim().isNotEmpty() }.getOrDefault(false)
    return when {
        present -> DoctorCheck("mgmt-key", CheckStatus.OK, keyFile.toString())
        daemonRunning -> DoctorCheck(
            "mgmt-key",
            CheckStatus.FAIL,
            "missing at $keyFile — admin endpoints will 401",
            "terminate the daemon process manually; the next launch re-mints the key",
        )
        else -> DoctorCheck("mgmt-key", CheckStatus.INFO, "minted on first launch")
    }
}

private data class HeadAuth(
    val key: String,
    val command: String,
    val envVar: String?,
    val isOAuth: Boolean,
    val present: Boolean,
)

private fun authChecks(
    topo: DoctorTopology,
    envReader: (String) -> String?,
    snapshot: DaemonSnapshot,
): List<DoctorCheck> {
    val topology = (topo as? DoctorTopology.Parsed)?.topology
        ?: return listOf(DoctorCheck("auth", CheckStatus.INFO, "skipped (no readable topology)"))
    val heads = topology.heads.mapNotNull { (key, head) ->
        val provider = topology.providers[head.provider] ?: return@mapNotNull null
        headAuthOf(key, head.claude.command ?: key, provider, envReader)
    }
    if (heads.isEmpty()) return listOf(DoctorCheck("auth", CheckStatus.INFO, "no heads configured"))
    // Severity is honest to "can I use splice at all": with zero authed heads a missing credential
    // is THE blocker (FAIL); once any head works, the others are ignorable (WARN).
    val missingStatus = if (heads.none { it.present }) CheckStatus.FAIL else CheckStatus.WARN
    val checks = heads.map { auth ->
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
    return checks + splitBrainChecks(heads, snapshot, envReader)
}

// api-key heads read the EFFECTIVE env var (explicit auth.env OR the derived <KEY>_API_KEY default
// the daemon wires) so a derived-default head always gets an `export` fix, never the OAuth dead-end;
// OAuth heads keep a null env var so they read as "signed in"/"login" and skip the split-brain probe.
private fun headAuthOf(
    key: String,
    command: String,
    provider: ProviderConfig,
    envReader: (String) -> String?,
): HeadAuth {
    val isOAuth = AuthKind.isOAuth(provider.auth.kind)
    val envVar = if (isOAuth) provider.auth.env else effectiveApiKeyEnv(key, provider.auth)
    return HeadAuth(key, command, envVar, isOAuth, authPresent(key, provider, envReader))
}

private fun credentialLabel(auth: HeadAuth): String =
    if (auth.envVar != null) "${auth.envVar} is set" else "signed in"

// The daemon reads api-key env vars from ITS OWN environment. A key exported after the daemon
// booted is present in this shell but invisible upstream — the single most confusing first-run
// trap, so doctor names it explicitly. When the daemon is UP but the daemon-side comparison can't
// run (no mgmt-key, or /api/auth unreachable), the flagship check would silently vanish exactly
// when the daemon is busiest — so emit an explicit WARN instead of empty. A STOPPED daemon is a
// plain skip (no noise): nothing to compare against.
private fun splitBrainChecks(
    heads: List<HeadAuth>,
    snapshot: DaemonSnapshot,
    envReader: (String) -> String?,
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
