// PORT-OF: server/src/config.mjs statePaths/stateDir/logsDir @ pre-public-port-baseline — invariants: paths are an
// EXTERNAL CONTRACT (an out-of-repo HUD reads codex-usage.json / codex-ratelimit.json /
// claudex-compact-stats.jsonl byte-identically); CLAUDEX_STATE_DIR overrides for hermetic tests
// only; this file is the ONLY place the `.claude-codex` literal may appear (ast-grep wall).
package splice.core.config

import java.nio.file.Path
import java.nio.file.Paths

public class StatePaths(
    baseOverride: Path? = null,
    envReader: (String) -> String? = System::getenv,
) {
    public val stateDir: Path = baseOverride
        ?: envReader("CLAUDEX_STATE_DIR")?.let { Paths.get(it) }
        ?: Paths.get(System.getProperty("user.home"), ".claude-codex", "state")

    public val rootDir: Path = stateDir.parent ?: stateDir

    public val logsDir: Path = rootDir.resolve("logs")

    public val configFile: Path = stateDir.resolve("config.json")

    public val mgmtKeyFile: Path = stateDir.resolve("mgmt-key")

    public val daemonLockFile: Path = stateDir.resolve("daemon.lock")

    /** Per-head stat files. The codex/grok names are the frozen legacy contract (the header's
     *  own words — the out-of-repo HUD reads codex-usage.json / grok-usage.json byte-identically);
     *  new heads derive `<head>-usage.json` / `<head>-ratelimit.json`. The claudex/claude-grok
     *  topology keys MAP to the legacy names (audit 2026-07-18: key-derived names froze the HUD). */
    public fun usageFile(headKey: String): Path = stateDir.resolve("${legacyStatKey(headKey)}-usage.json")

    public fun ratelimitFile(headKey: String): Path = stateDir.resolve("${legacyStatKey(headKey)}-ratelimit.json")

    private fun legacyStatKey(headKey: String): String = when (headKey) {
        "codex", "claudex" -> "codex"
        "grok", "claude-grok" -> "grok"
        else -> headKey
    }

    /** Per-turn perf telemetry JSONL (bottleneck instrument) — additive, not a frozen HUD name. */
    public fun perfStatsFile(headKey: String): Path = stateDir.resolve("$headKey-perf.jsonl")

    /** Compact-stats JSONL lives in the ROOT dir (not state/) — HUD contract. The two legacy
     *  names are irregular on purpose (claudex-…, claude-grok-…); overridable per head. */
    public fun compactStatsFile(headKey: String, nameOverride: String? = null): Path {
        val name = nameOverride ?: when (headKey) {
            "codex", "claudex" -> "claudex-compact-stats.jsonl"
            "grok" -> "claude-grok-compact-stats.jsonl"
            else -> "$headKey-compact-stats.jsonl"
        }
        return rootDir.resolve(name)
    }
}
