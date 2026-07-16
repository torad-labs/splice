// PORT-OF: server/launcher/prepare-config.mjs @ 4ca99f7 as a TRANSLITERATION (high blast radius —
// mutates the operator's ~/.claude* state), generalized to the per-head share/isolate policy.
// Invariants preserved EXACTLY:
//   - isolated CLAUDE_CONFIG_DIR (default ~/.claude-<head>); refuse to write outside it;
//   - SHARED items symlink into ~/.claude/<item>; a real file where a symlink belongs is replaced,
//     but a real DIRECTORY the operator made is NEVER deleted;
//   - settings.json is ALWAYS a real merged file (never a symlink through which we'd clobber the
//     operator's global): global settings + availableModels allowlist + enforceAvailableModels +
//     preserved model choice (when still allowed) + the statusline command; the symlink is broken
//     FIRST;
//   - .claude.json: additionalModelOptionsCache = the catalog, MCP inherit from ~/.claude.json,
//     PORT_KEYS inherit (only when absent locally), hasCompletedOnboarding = true.
// isolate list overrides share per item (an isolated item gets a seeded copy, not a link).
@file:Suppress("StringLiteralDuplication") // .claude* path fragments + json keys are the contract

package splice.core.launch

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.isSymbolicLink

@Suppress("TopLevelPropertyNaming") // ported constant name
public val SHARED_LINK_ITEMS: List<String> =
    listOf("settings.json", "agents", "commands", "skills", "hooks", "plugins", "CLAUDE.md", "mcps")

@Suppress("TopLevelPropertyNaming") // ported constant name
public val PORT_KEYS: List<String> = listOf(
    "verbose", "showSpinnerTree", "tipsHistory", "effortCalloutV2Dismissed",
    "opusProMigrationComplete", "hasCompletedOnboarding", "lastOnboardingVersion",
    "autoUpdates", "theme",
)

public data class MaterializeResult(val configDir: Path, val models: Int, val mcpServers: Int)

public data class ClaudePolicy(
    val share: Set<String>,
    val isolate: Set<String>,
)

@Suppress("TooManyFunctions")
public class ClaudeConfigMaterializer(
    private val home: Path,
    private val statuslineCommand: String,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    /**
     * Materialize a head's isolated CLAUDE_CONFIG_DIR. availableModelIds REPLACES the picker;
     * modelOptionsCache is the catalog for .claude.json; defaultModel is the pinned fallback.
     */
    @Suppress("LongParameterList")
    public fun materialize(
        configDir: Path,
        policy: ClaudePolicy,
        availableModelIds: List<String>,
        defaultModel: String,
        modelOptionsCache: JsonObject,
    ): MaterializeResult {
        require(configDir.fileName.toString().contains("claude")) {
            "refuse to materialize outside a .claude* dir: $configDir"
        }
        Files.createDirectories(configDir)
        linkShared(configDir, policy)
        writeSettings(configDir, policy, availableModelIds, defaultModel)
        val mcpCount = writeClaudeJson(configDir, modelOptionsCache)
        return MaterializeResult(configDir, availableModelIds.size, mcpCount)
    }

    private fun globalDir() = home.resolve(".claude")

    @Suppress(
        "TooGenericExceptionCaught",
        "InstanceOfCheckForException",
        "NestedBlockDepth",
        "CyclomaticComplexMethod",
        "LoopWithTooManyJumpStatements",
    ) // transliteration of the symlink-replace-but-never-delete-a-real-dir logic
    private fun linkShared(configDir: Path, policy: ClaudePolicy) {
        for (item in SHARED_LINK_ITEMS) {
            if (item == "settings.json" || item == "mcps") continue // settings merged; mcps via json
            if (item in policy.isolate || item !in policy.share) continue
            val src = globalDir().resolve(item)
            if (!Files.exists(src, java.nio.file.LinkOption.NOFOLLOW_LINKS)) continue
            val dst = configDir.resolve(item)
            try {
                if (Files.exists(dst, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                    when {
                        dst.isSymbolicLink() -> Files.delete(dst)
                        dst.isDirectory() -> continue // never delete a real dir the operator made
                        else -> Files.delete(dst)
                    }
                }
                Files.createSymbolicLink(dst, src)
            } catch (e: Exception) {
                if (e is java.util.concurrent.CancellationException) throw e
                // leave whatever is there
            }
        }
    }

    @Suppress("TooGenericExceptionCaught", "InstanceOfCheckForException", "CyclomaticComplexMethod", "ComplexCondition")
    private fun writeSettings(configDir: Path, policy: ClaudePolicy, allow: List<String>, defaultModel: String) {
        val dst = configDir.resolve("settings.json")
        val global = if ("settings" in policy.share || "settings.json" in policy.share) {
            readJson(globalDir().resolve("settings.json"))
        } else {
            JsonObject(emptyMap())
        }
        var existing: JsonObject = JsonObject(emptyMap())
        try {
            if (Files.exists(dst, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                if (dst.isSymbolicLink()) Files.delete(dst) else existing = readJson(dst)
            }
        } catch (e: Exception) {
            if (e is java.util.concurrent.CancellationException) throw e
        }
        val savedModel = existing["model"]?.jsonPrimitive?.content
        val model = if (savedModel != null && savedModel in allow) savedModel else defaultModel
        val merged = buildJsonObject {
            global.forEach { (k, v) -> if (k != "model" && k != "availableModels" && k != "statusLine") put(k, v) }
            putJsonArray("availableModels") { allow.forEach { add(it) } }
            put("enforceAvailableModels", true)
            put("model", model)
            put(
                "statusLine",
                buildJsonObject {
                    put("type", "command")
                    put("command", statuslineCommand)
                    put("padding", 0)
                },
            )
        }
        Files.writeString(dst, json.encodeToString(JsonObject.serializer(), merged) + "\n")
    }

    @Suppress("TooGenericExceptionCaught", "InstanceOfCheckForException")
    private fun writeClaudeJson(configDir: Path, modelOptionsCache: JsonObject): Int {
        val statePath = configDir.resolve(".claude.json")
        val global = readJson(home.resolve(".claude.json"))
        val local = readJson(statePath)
        var mcpCount = 0
        val next = buildJsonObject {
            local.forEach { (k, v) -> put(k, v) }
            put("additionalModelOptionsCache", modelOptionsCache)
            val globalMcp = global["mcpServers"] as? JsonObject
            if (globalMcp != null) {
                mcpCount = globalMcp.size
                put("mcpServers", globalMcp)
            }
            for (k in PORT_KEYS) {
                if (global[k] != null && local[k] == null) put(k, global[k]!!)
            }
            put("hasCompletedOnboarding", true)
        }
        Files.writeString(statePath, json.encodeToString(JsonObject.serializer(), next) + "\n")
        return mcpCount
    }

    @Suppress("TooGenericExceptionCaught", "InstanceOfCheckForException")
    private fun readJson(path: Path): JsonObject = try {
        if (Files.exists(path) && !path.isSymbolicLink()) {
            json.parseToJsonElement(Files.readString(path)).jsonObject
        } else {
            JsonObject(emptyMap())
        }
    } catch (e: Exception) {
        if (e is java.util.concurrent.CancellationException) throw e
        JsonObject(emptyMap())
    }
}
