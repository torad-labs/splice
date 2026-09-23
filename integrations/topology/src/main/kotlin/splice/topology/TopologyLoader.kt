// NEW: load the topology TOML (~/.config/splice/splice.toml, XDG) into the :core schema, with
// jar-bundled defaults materialized on first run (mirrors how ensureMgmtKey lazily writes state).
// ktoml adopted per spike P0-TOML. Loaded ONCE at daemon start — adding a provider/head is an
// operator action that implies a restart (no hot topology). V4-162: the context windows are the
// exception, re-read by TopologyWindows while the daemon runs.
package splice.topology

import com.akuleshov7.ktoml.Toml
import kotlinx.serialization.decodeFromString
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
# Experimental vendor-OAuth examples remain opt-in in app/src/main/resources/splice.example.toml.
[providers.openrouter]
dialect = "openai-chat"
base_url = "https://openrouter.ai/api/v1"
auth = { kind = "api-key", env = "OPENROUTER_API_KEY" }

[[providers.openrouter.models]]
id = "anthropic/claude-sonnet-5"
label = "Claude Sonnet 5"
context_window = 1000000
[[providers.openrouter.models]]
id = "anthropic/claude-opus-5"
label = "Claude Opus 5"
context_window = 1000000
[[providers.openrouter.models]]
id = "z-ai/glm-5.3-flash"
label = "GLM 5.3 Flash"
context_window = 1310720
[[providers.openrouter.models]]
id = "openai/gpt-5.6-sol"
label = "GPT-5.6 Sol"
context_window = 1050000
[[providers.openrouter.models]]
id = "openai/gpt-5.6-luna"
label = "GPT-5.6 Luna"
context_window = 1050000
[[providers.openrouter.models]]
id = "google/gemini-3.8-flash"
label = "Gemini 3.8 Flash"
context_window = 1048576
[[providers.openrouter.models]]
id = "deepseek/deepseek-v4-flash-0731"
label = "DeepSeek V4 Flash 0731"
context_window = 1310720
[[providers.openrouter.models]]
id = "z-ai/glm-5.3"
label = "GLM 5.3"
context_window = 1310720
[[providers.openrouter.models]]
id = "meta-llama/llama-4-maverick"
label = "Llama 4 Maverick"
context_window = 1048576
[[providers.openrouter.models]]
id = "anthropic/claude-haiku-4.5"
label = "Claude Haiku 4.5"
context_window = 200000

[heads.openrouter]
provider = "openrouter"
port = 3101
discovery_prefix = "claude-openrouter--"
pinned_model = "anthropic/claude-sonnet-5"
models = [
  { id = "anthropic/claude-sonnet-5", slot = "sonnet" },
  { id = "anthropic/claude-opus-5", slot = "opus" },
  { id = "z-ai/glm-5.3-flash", slot = "haiku" },
  { id = "openai/gpt-5.6-sol", slot = "fable" },
]

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
     *  stays deliberately non-hot-reloadable; this only makes the required restart visible. The
     *  one exception is the context windows (V4-162): TopologyWindows re-reads those, and /health
     *  then publishes the version the daemon RUNS, which a window-only edit moves. */
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
        // ast-grep-ignore: kt-no-silent-result-collapse -- 2026-09-17 (V4-112): fail-open by design (see the doc above): an unreadable or absent splice.toml must degrade the staleness signal, never break /health or a launch, so null IS the whole reading.
        Cancellables.runCatchingCancellable { sha256Hex(Files.readAllBytes(path)) }.getOrNull()

    /** sha-256 of splice.toml bytes, the one spelling of a topology digest: boot, [currentDigest] and
     *  the live window re-read (V4-162, TopologyWindows) all hash through here. */
    public fun sha256Hex(bytes: ByteArray): String =
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
