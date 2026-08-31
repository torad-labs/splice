// NEW: load the topology TOML (~/.config/splice/splice.toml, XDG) into the :core schema, with
// jar-bundled defaults materialized on first run (mirrors how ensureMgmtKey lazily writes state).
// ktoml adopted per spike P0-TOML. Loaded ONCE at daemon start — adding a provider/head is an
// operator action that implies a restart (no hot topology).
package splice.app

import com.akuleshov7.ktoml.Toml
import kotlinx.serialization.decodeFromString
import splice.control.TopologyStale
import splice.core.GATEWAY_VERSION
import splice.core.SHIM_VERSION
import splice.core.topology.Topology
import splice.core.util.Cancellables
import splice.core.util.EnvReader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

public object TopologyLoader {

    private const val DEFAULT_TOML = """
[daemon]
control_port = 3096
# Reasoning display (edit + restart; env/PATCH still override):
show_reasoning = "text"
summary = "detailed"
replay_reasoning = false

# Supported starter route: create an OpenRouter API key, then EITHER export OPENROUTER_API_KEY
# or let `claude-openrouter login` store it to ~/.config/splice/keys.toml (0600 — survives restarts from
# any shell; inside a claude-openrouter session you can also paste it as a bare message and the
# token-capture hook stores it without it reaching the model).
# Experimental vendor-OAuth examples remain opt-in in config/splice.example.toml.
[providers.openrouter]
dialect = "openai-chat"
base_url = "https://openrouter.ai/api/v1"
auth = { kind = "api-key", env = "OPENROUTER_API_KEY" }

[[providers.openrouter.models]]
id = "anthropic/claude-haiku-4.5"
label = "Claude Haiku"
context_window = 200000

[heads.openrouter]
provider = "openrouter"
port = 3101
discovery_prefix = "claude-openrouter--"
pinned_model = "anthropic/claude-haiku-4.5"

[heads.openrouter.claude]
command = "claude-openrouter"
"""

    public fun configPath(env: EnvReader = EnvReader(System::getenv)): Path {
        val override = env("SPLICE_CONFIG")
        if (override != null) return Paths.get(expandHome(override))
        val xdg = env("XDG_CONFIG_HOME")
        val base = if (xdg != null) Paths.get(xdg) else Paths.get(System.getProperty("user.home"), ".config")
        return base.resolve("splice").resolve("splice.toml")
    }

    public fun loadOrMaterialize(path: Path): Topology = loadOrMaterializeWithDigest(path).topology

    /** JW-04: the parsed topology PLUS the sha-256 of the exact bytes it came from. The digest
     *  rides /health so shim/doctor/dashboard can tell "the file changed since boot" — topology
     *  stays deliberately non-hot-reloadable; this only makes the required restart visible. */
    public data class LoadedTopology(val topology: Topology, val digest: String)

    public fun loadOrMaterializeWithDigest(path: Path): LoadedTopology {
        if (!Files.exists(path)) {
            path.parent?.let(Files::createDirectories)
            Files.writeString(path, DEFAULT_TOML.trimIndent() + "\n")
        }
        val bytes = Files.readAllBytes(path)
        return LoadedTopology(parse(bytes.toString(Charsets.UTF_8)), sha256Hex(bytes))
    }

    /** Digest of the file as it is on disk RIGHT NOW; null when unreadable (fail open — an
     *  unreadable file must degrade the staleness signal, never break /health or a launch). */
    public fun currentDigest(path: Path): String? =
        Cancellables.runCatchingCancellable { sha256Hex(Files.readAllBytes(path)) }.getOrNull()

    /** JW-04: per-request staleness recompute, failing OPEN — an unreadable file degrades the
     *  signal, never /health. Lives here rather than beside its Daemon caller because the fact it
     *  computes is this loader's (Kotlin style law, 2026-08-15: it can no longer be a file-level
     *  helper, and [currentDigest] is the thing it wraps). */
    internal fun staleProbe(path: Path?, bootDigest: String): TopologyStale = TopologyStale {
        val now = path?.let { currentDigest(it) }
        now != null && bootDigest.isNotEmpty() && now != bootDigest
    }

    private fun sha256Hex(bytes: ByteArray): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    public fun parse(text: String): Topology {
        // Structural guards ktoml lacks (duplicate models keys, reopened tables, string rosters)
        // live in TomlStructurePreflight — extracted with its masker, 2026-08-31 concentration.
        TomlStructurePreflight.check(text)
        return Toml.decodeFromString(text)
    }

    public fun expandHome(raw: String): String =
        if (raw.startsWith("~/")) System.getProperty("user.home") + raw.substring(1) else raw

    // Version seams so CLI files can drop a splice.core import (median 1.0) without
    // taking the floor. Same pattern as DaemonHealth.cliVersion / ControlPayloads.gatewayVersion.
    public fun gatewayVersion(): String = GATEWAY_VERSION
    public fun shimVersion(): String = SHIM_VERSION
}
