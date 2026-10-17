// NEW: MCP hub step 6 (docs/specs/mcp-hub.md, proof f): what splice's own check found about `--plugin-dir-no-mcp`
// for each Claude Code version. The launch passes the flag only for a version whose entry holds, and a plugin keeps
// its own server copies on every other one. The file is <state>/mcp-hub/plugin-flag.json, owner-only, in the shape
// agreed with splice-sessions: {"versions":{"<version>":{"holds":true|false,"checked_at":"<ISO>","why":"<line>"}}},
// "why" only on a version that failed.
package splice.client.mcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import splice.core.util.SecureFile
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

// why: proof (f) ran on this version by hand (Oct 12), so it holds before splice's own check has recorded it.
private const val PROVEN_VERSION = "2.1.296"
private const val VERSIONS_KEY = "versions"
private const val HOLDS_KEY = "holds"
private const val CHECKED_AT_KEY = "checked_at"
private const val WHY_KEY = "why"

/** One version's verdict: [holds] when the flag loaded the plugin with its MCP off and its skill kept. */
public data class PluginFlagVerdict(val holds: Boolean, val checkedAt: String, val why: String? = null)

/** The verdict file under [stateDir], and the installed Claude Code versions under [versionsDir]. */
public class PluginFlagVerdicts(stateDir: Path, public val versionsDir: Path) {
    private val dir = stateDir.resolve("mcp-hub")
    private val file = dir.resolve("plugin-flag.json")
    private val json = Json { prettyPrint = true }

    /** True when the launch may pass `--plugin-dir-no-mcp` to this version. Unreadable reads as no verdict. */
    public fun holds(version: String): Boolean = version == PROVEN_VERSION || all()[version]?.holds == true

    /** Every recorded verdict by version; an absent or unreadable file is none. */
    public fun all(): Map<String, PluginFlagVerdict> {
        val versions = read()[VERSIONS_KEY] as? JsonObject ?: return emptyMap()
        return versions.mapNotNull { (version, entry) -> verdict(entry as? JsonObject)?.let { version to it } }.toMap()
    }

    /** The failed verdicts of versions still installed: the ones doctor names. */
    public fun installedFailures(): Map<String, PluginFlagVerdict> =
        all().filter { (version, verdict) -> !verdict.holds && Files.isRegularFile(versionsDir.resolve(version)) }

    /** Records [verdict] for [version], keeping every other entry. */
    @Synchronized
    public fun record(version: String, verdict: PluginFlagVerdict) {
        val kept = all() + (version to verdict)
        val body = buildJsonObject {
            put(VERSIONS_KEY, JsonObject(kept.mapValues { (_, it) -> entry(it) }))
        }
        Files.createDirectories(dir)
        val _ = SecureFile.ownerOnlyDirectory(dir)
        SecureFile.writeAtomic0600(file, json.encodeToString(JsonObject.serializer(), body) + "\n")
    }

    private fun entry(verdict: PluginFlagVerdict): JsonObject = buildJsonObject {
        put(HOLDS_KEY, verdict.holds)
        put(CHECKED_AT_KEY, verdict.checkedAt)
        verdict.why?.let { put(WHY_KEY, it) }
    }

    private fun verdict(entry: JsonObject?): PluginFlagVerdict? {
        val holds = (entry?.get(HOLDS_KEY) as? JsonPrimitive)?.booleanOrNull ?: return null
        val at = (entry[CHECKED_AT_KEY] as? JsonPrimitive)?.contentOrNull.orEmpty()
        return PluginFlagVerdict(holds, at, (entry[WHY_KEY] as? JsonPrimitive)?.contentOrNull)
    }

    private fun read(): JsonObject = try {
        if (Files.isRegularFile(file)) Json.parseToJsonElement(Files.readString(file)) as? JsonObject else null
    } catch (_: IOException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    } ?: JsonObject(emptyMap())
}
