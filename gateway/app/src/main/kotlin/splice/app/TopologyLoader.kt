// NEW: load the topology TOML (~/.config/splice/splice.toml, XDG) into the :core schema, with
// jar-bundled defaults materialized on first run (mirrors how ensureMgmtKey lazily writes state).
// ktoml adopted per spike P0-TOML. Loaded ONCE at daemon start — adding a provider/head is an
// operator action that implies a restart (no hot topology).
package splice.app

import com.akuleshov7.ktoml.Toml
import kotlinx.serialization.decodeFromString
import splice.core.topology.Topology
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

# Supported starter route: create an OpenRouter API key, export OPENROUTER_API_KEY, then run
# `claudeor`. Experimental vendor-OAuth examples remain opt-in in config/splice.example.toml.
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
command = "claudeor"
"""

    public fun configPath(env: (String) -> String? = System::getenv): Path {
        val override = env("SPLICE_CONFIG")
        if (override != null) return Paths.get(expandHome(override))
        val xdg = env("XDG_CONFIG_HOME")
        val base = if (xdg != null) Paths.get(xdg) else Paths.get(System.getProperty("user.home"), ".config")
        return base.resolve("splice").resolve("splice.toml")
    }

    public fun loadOrMaterialize(path: Path): Topology {
        if (!Files.exists(path)) {
            Files.createDirectories(path.parent)
            Files.writeString(path, DEFAULT_TOML.trimIndent() + "\n")
        }
        return parse(Files.readString(path))
    }

    public fun parse(text: String): Topology = Toml.decodeFromString(text)

    public fun expandHome(raw: String): String =
        if (raw.startsWith("~/")) System.getProperty("user.home") + raw.substring(1) else raw
}
