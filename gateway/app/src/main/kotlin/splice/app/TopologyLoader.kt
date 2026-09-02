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

/** DR-66 redo: the first-run claim as a seam (the DR-67 WrapperClaim precedent) — the
 *  no-concurrent-clobber property (the claim LOSES to a creator that lands between the
 *  proven-absence read and the write, and the winner's bytes are read back) is only testable on
 *  the production path if a test can interleave that creator before the claim. */
internal fun interface StarterWrite {
    fun claim(path: java.nio.file.Path, starter: ByteArray)
}

/** The production claim: CREATE_NEW — exclusive by construction, never a truncate. */
internal object ExclusiveStarterWrite : StarterWrite {
    override fun claim(path: java.nio.file.Path, starter: ByteArray) {
        Files.write(path, starter, java.nio.file.StandardOpenOption.CREATE_NEW)
    }
}

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

    public fun loadOrMaterializeWithDigest(path: Path): LoadedTopology =
        loadOrMaterializeWithDigest(path, ExclusiveStarterWrite)

    internal fun loadOrMaterializeWithDigest(path: Path, write: StarterWrite): LoadedTopology {
        // DR-66: the read is the probe. Only proven absence (NoSuch + no NOFOLLOW entry) is a
        // first run; an unreadable existing file — or a dangling dotfiles symlink — aborts loud
        // instead of being clobbered with (or written through by) the starter.
        val bytes = Cancellables.runCatchingCancellable { Files.readAllBytes(path) }
            .getOrElse { failure ->
                val genuinelyAbsent = failure is java.nio.file.NoSuchFileException &&
                    !Files.exists(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                if (!genuinelyAbsent) throw failure
                materializeStarter(path, write)
            }
        return LoadedTopology(parse(bytes.toString(Charsets.UTF_8)), sha256Hex(bytes))
    }

    /** First-run creation NEVER truncates an unobserved path: the exclusive claim loses to any
     *  concurrent creator, whose file then wins and is read back instead. */
    private fun materializeStarter(path: Path, write: StarterWrite): ByteArray {
        val starter = (DEFAULT_TOML.trimIndent() + "\n").toByteArray(Charsets.UTF_8)
        path.parent?.let(Files::createDirectories)
        return Cancellables.runCatchingCancellable {
            write.claim(path, starter)
            starter
        }.getOrElse { failure ->
            if (failure is java.nio.file.FileAlreadyExistsException) Files.readAllBytes(path) else throw failure
        }
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
