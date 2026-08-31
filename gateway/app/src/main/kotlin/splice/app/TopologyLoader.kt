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

    // ktoml loops instead of rejecting a head roster spelled as an array of strings. Mask quoted
    // text/comments first; '?' keeps a real quoted array element visible to this pre-decode guard.
    private val MODEL_ARRAY_ASSIGNMENT = Regex("(?<![A-Za-z0-9_-])models[ \\t]*=[ \\t]*\\[")

    public fun parse(text: String): Topology {
        validateInlineModelArrays(text)
        return Toml.decodeFromString(text)
    }

    private fun validateInlineModelArrays(text: String) {
        val structure = TomlStructureMasker(text).mask()
        MODEL_ARRAY_ASSIGNMENT.findAll(structure).forEach { assignment ->
            val valueStart = assignment.range.last + 1
            val firstElement = structure.asSequence()
                .drop(valueStart)
                .firstOrNull { !it.isWhitespace() }
            require(firstElement == '{' || firstElement == ']') {
                "models must be an array of inline tables; write models = [{ id = \"...\" }]"
            }
        }
    }

    public fun expandHome(raw: String): String =
        if (raw.startsWith("~/")) System.getProperty("user.home") + raw.substring(1) else raw

    // Version seams so CLI files can drop a splice.core import (median 1.0) without
    // taking the floor. Same pattern as DaemonHealth.cliVersion / ControlPayloads.gatewayVersion.
    public fun gatewayVersion(): String = GATEWAY_VERSION
    public fun shimVersion(): String = SHIM_VERSION
}

private class TomlStructureMasker(private val text: String) {
    private val masked = StringBuilder(text)

    fun mask(): String {
        var index = 0
        while (index < text.length) {
            val keyLength = quotedModelsKeyLength(index)
            index = when {
                keyLength > 0 -> preserveModelsKey(index, keyLength)
                text[index] == '#' -> maskComment(index)
                text.startsWith("\"\"\"", index) -> maskQuoted(index, "\"\"\"", escapes = true)
                text.startsWith("'''", index) -> maskQuoted(index, "'''", escapes = false)
                text[index] == '"' -> maskQuoted(index, "\"", escapes = true)
                text[index] == '\'' -> maskQuoted(index, "'", escapes = false)
                else -> index + 1
            }
        }
        return masked.toString()
    }

    private fun quotedModelsKeyLength(start: Int): Int {
        val token = when {
            text.startsWith("\"models\"", start) -> "\"models\""
            text.startsWith("'models'", start) -> "'models'"
            else -> return 0
        }
        var cursor = start + token.length
        while (cursor < text.length && isHorizontalSpace(text[cursor])) cursor++
        return if (text.getOrNull(cursor) == '=') token.length else 0
    }

    private fun preserveModelsKey(start: Int, length: Int): Int {
        "models".padEnd(length).forEachIndexed { offset, char -> masked.setCharAt(start + offset, char) }
        return start + length
    }

    private fun maskComment(start: Int): Int {
        var cursor = start
        while (cursor < masked.length && !isLineBreak(masked[cursor])) {
            masked.setCharAt(cursor++, ' ')
        }
        return cursor
    }

    private fun maskQuoted(start: Int, delimiter: String, escapes: Boolean): Int {
        masked.setCharAt(start, '?')
        maskRange(start + 1, start + delimiter.length)
        var cursor = start + delimiter.length
        while (cursor < text.length) {
            if (text.startsWith(delimiter, cursor)) {
                val closingLength = closingDelimiterLength(cursor, delimiter)
                maskRange(cursor, cursor + closingLength)
                return cursor + closingLength
            }
            val escaped = escapes && text[cursor] == '\\'
            val escapePair = escaped && cursor + 1 < text.length
            val count = if (escapePair) 2 else 1
            maskRange(cursor, cursor + count)
            cursor += count
        }
        return cursor
    }

    private fun closingDelimiterLength(start: Int, delimiter: String): Int {
        if (delimiter.length == 1) return 1
        var length = delimiter.length
        val maxLength = delimiter.length + 2
        while (length < maxLength && text.getOrNull(start + length) == delimiter.first()) length++
        return length
    }

    private fun maskRange(start: Int, end: Int) {
        for (index in start until end) {
            if (!isLineBreak(masked[index])) masked.setCharAt(index, ' ')
        }
    }

    private fun isHorizontalSpace(char: Char): Boolean = char == ' ' || char == '\t'
    private fun isLineBreak(char: Char): Boolean = char == '\r' || char == '\n'
}
