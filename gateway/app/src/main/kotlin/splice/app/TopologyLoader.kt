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

[providers.codex]
dialect = "openai-responses"
base_url = "https://chatgpt.com/backend-api/codex"
auth = { kind = "chatgpt-oauth", file = "~/.codex/auth.json" }
quirks = { store = false, account_id_header = true, cache_key = "first-message-hash", effort_ceiling = "max", summary_field = true }

[[providers.codex.models]]
id = "gpt-5.6-sol"
label = "Codex 5.6 Sol"
context_window = 272000

[heads.claudex]
provider = "codex"
port = 3099
discovery_prefix = "claude-codex--"
pinned_model = "gpt-5.6-sol"
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
