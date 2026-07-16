// NEW: P0-TOML spike — can ktoml decode the real splice.toml topology shape?
// The exact plan sketch: [daemon], [providers.<name>] maps with inline auth/quirks
// tables, [[providers.<name>.models]] array-of-tables, [heads.<name>] with
// [heads.<name>.overrides]. Receipt records verdict + any shape adjustments.
import com.akuleshov7.ktoml.Toml
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import org.junit.jupiter.api.Test

@Serializable
data class Topology(
    val daemon: DaemonConfig = DaemonConfig(),
    val providers: Map<String, ProviderConfig> = emptyMap(),
    val heads: Map<String, HeadConfig> = emptyMap(),
)

@Serializable
data class DaemonConfig(val control_port: Int = 3096)

@Serializable
data class ProviderConfig(
    val dialect: String,
    val base_url: String,
    val auth: AuthConfig,
    val quirks: QuirksConfig = QuirksConfig(),
    val models: List<ModelEntry> = emptyList(),
)

@Serializable
data class AuthConfig(
    val kind: String,
    val file: String? = null,
    val env: String? = null,
)

@Serializable
data class QuirksConfig(
    val store: Boolean = false,
    val account_id_header: Boolean = false,
    val cache_key: String = "first-message-hash",
    val effort_ceiling: String = "max",
    val summary_field: Boolean = true,
    val compact_effort: String? = null,
    val tool_choice: Boolean = false,
)

@Serializable
data class ModelEntry(
    val id: String,
    val label: String? = null,
    val context_window: Long,
)

@Serializable
data class HeadConfig(
    val provider: String,
    val port: Int,
    val discovery_prefix: String,
    val pinned_model: String,
    val overrides: Map<String, String> = emptyMap(),
)

private val SAMPLE = """
[daemon]
control_port = 3096

[providers.codex]
dialect = "openai-responses"
base_url = "https://chatgpt.com/backend-api/codex"
auth = { kind = "chatgpt-oauth", file = "~/.codex/auth.json" }
quirks = { store = false, account_id_header = true, cache_key = "first-message-hash", effort_ceiling = "max", summary_field = true }

[[providers.codex.models]]
id = "gpt-5.6-sol"
label = "sol"
context_window = 500000

[[providers.codex.models]]
id = "gpt-5.3-codex-spark"
context_window = 272000

[providers.xai]
dialect = "openai-responses"
base_url = "https://api.x.ai/v1"
auth = { kind = "api-key", env = "XAI_API_KEY", file = "~/.local/share/claude-grok/auth.json" }
quirks = { cache_key = "session-id", effort_ceiling = "high", summary_field = false, compact_effort = "low", tool_choice = true }

[[providers.xai.models]]
id = "grok-4.5"
context_window = 1000000

[heads.claudex]
provider = "codex"
port = 3099
discovery_prefix = "claude-codex--"
pinned_model = "gpt-5.6-sol"

[heads.claudex.overrides]
effort = "high"

[heads.grok]
provider = "xai"
port = 3100
discovery_prefix = "claude-grok--"
pinned_model = "grok-4.5"
""".trimIndent()

class KtomlShapeSpike {

    @Test
    fun `ktoml decodes the topology shape`() {
        val results = File(System.getProperty("spike.results.dir")).apply { mkdirs() }
        val receipt = File(results, "ktoml.md")
        val report = StringBuilder()
        report.appendLine("# P0-TOML receipt: ktoml 0.7.1 vs the splice.toml topology shape (${java.time.LocalDate.now()})")
        report.appendLine()
        try {
            val topo = Toml.decodeFromString<Topology>(SAMPLE)
            val checks = listOf(
                "daemon.control_port == 3096" to (topo.daemon.control_port == 3096),
                "two providers decoded" to (topo.providers.keys == setOf("codex", "xai")),
                "codex inline auth table" to (topo.providers["codex"]?.auth?.kind == "chatgpt-oauth"),
                "codex inline quirks table" to (topo.providers["codex"]?.quirks?.account_id_header == true),
                "codex [[models]] array-of-tables (2 rows)" to (topo.providers["codex"]?.models?.size == 2),
                "model row fields" to (topo.providers["codex"]?.models?.first()?.context_window == 500_000L),
                "xai quirks overrides" to (topo.providers["xai"]?.quirks?.cache_key == "session-id"),
                "heads map" to (topo.heads.keys == setOf("claudex", "grok")),
                "head sub-table overrides" to (topo.heads["claudex"]?.overrides?.get("effort") == "high"),
                "head without overrides defaults empty" to (topo.heads["grok"]?.overrides?.isEmpty() == true),
            )
            checks.forEach { (name, ok) -> report.appendLine("- ${if (ok) "PASS" else "FAIL"}: $name") }
            val allOk = checks.all { it.second }
            report.appendLine()
            report.appendLine("## Verdict")
            report.appendLine(
                if (allOk) {
                    "ktoml 0.7.1 handles the full topology shape (maps of tables, inline tables, array-of-tables, nested sub-tables). ADOPTED for P1-CORE config schema."
                } else {
                    "ktoml decoded but with shape gaps above — evaluate tomlj fallback before P1-CORE."
                },
            )
            check(allOk) { "shape checks failed:\n$report" }
        } catch (e: Exception) {
            report.appendLine("- DECODE FAILED: ${e.message?.take(500)}")
            report.appendLine()
            report.appendLine("## Verdict")
            report.appendLine("ktoml REJECTED on this shape — fall back to tomlj (parse tree + hand-map) per ledger slot.")
            receipt.writeText(report.toString())
            throw e
        }
        receipt.writeText(report.toString())
        println(receipt.readText())
    }
}

// P1-CORE rider: does ktoml honor @SerialName? (product schema wants camelCase
// properties + snake_case TOML keys; detekt bans snake_case ctor params)
@kotlinx.serialization.Serializable
data class SerialNameProbe(
    @kotlinx.serialization.SerialName("control_port") val controlPort: Int,
    @kotlinx.serialization.SerialName("base_url") val baseUrl: String = "x",
)

class KtomlSerialNameSpike {
    @Test
    fun `ktoml honors SerialName`() {
        val decoded = Toml.decodeFromString<SerialNameProbe>(
            "control_port = 3096\nbase_url = \"https://example\"",
        )
        check(decoded.controlPort == 3096 && decoded.baseUrl == "https://example") { "SerialName not honored: $decoded" }
        println("SerialName probe: PASS -> $decoded")
    }
}
