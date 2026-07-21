// PORT-OF: server/launcher/prepare-config.mjs @ pre-public-port-baseline as a TRANSLITERATION (high blast radius —
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
//     portKeys inherit (only when absent locally), hasCompletedOnboarding = true.
// isolate list overrides share per item (an isolated item gets a seeded copy, not a link).
package splice.core.launch

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import splice.core.util.runCatchingCancellable
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.isSymbolicLink

/** On-disk items a head may share by symlinking into the operator's global ~/.claude/<item>. */
public val sharedLinkItems: List<String> =
    listOf(Keys.SETTINGS, "agents", "commands", "skills", "hooks", "plugins", Keys.CLAUDE_MD, Keys.MCPS)

/** ~/.claude.json keys carried into a head's isolated state (only when absent locally). */
public val portKeys: List<String> = listOf(
    "verbose", "showSpinnerTree", "tipsHistory", "effortCalloutV2Dismissed",
    "unpinOpus47LaunchEffort", "unpinOpus48LaunchEffort", "unpinFable5LaunchEffort",
    "opusProMigrationComplete", "sonnet1m45MigrationComplete", Keys.ONBOARDING,
    "lastOnboardingVersion", "autoUpdates", "theme",
)

public data class MaterializeResult(val configDir: Path, val models: Int, val mcpServers: Int)

public data class ClaudePolicy(
    val share: Set<String>,
    val isolate: Set<String>,
)

/** Everything a single head needs materialized. availableModelIds REPLACES the picker;
 *  modelOptionsCache is the catalog for .claude.json; defaultModel is the pinned fallback. */
public data class MaterializeSpec(
    val configDir: Path,
    val policy: ClaudePolicy,
    val availableModelIds: List<String>,
    val defaultModel: String,
    val modelOptionsCache: JsonElement,
    val statuslineCommand: String,
    // Shell command that runs THIS head's provider sign-in (e.g. `claudex login`). The built-in
    // Anthropic /login is disabled in the launch env; a materialized custom /login command + a
    // UserPromptSubmit hook route the user to this instead. Empty disables the interception.
    val loginCommand: String = "",
    // Human label for the head's provider in the /login UX (e.g. "Codex (ChatGPT)", "Grok (xAI)").
    val signInLabel: String = "",
)

public class ClaudeConfigMaterializer(
    private val home: Path,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    /** Materialize a head's isolated CLAUDE_CONFIG_DIR from [spec]. */
    public fun materialize(spec: MaterializeSpec): MaterializeResult {
        requireIsolatedDir(spec.configDir)
        Files.createDirectories(spec.configDir)
        linkShared(spec.configDir, spec.policy)
        val loginHook = LoginInterception.wire(
            spec.configDir,
            spec.loginCommand,
            spec.signInLabel,
            globalCommands = if (shares(spec.policy, Keys.COMMANDS)) globalDir().resolve(Keys.COMMANDS) else null,
        )
        writeSettings(spec, loginHook)
        val mcpCount = writeClaudeJson(
            spec.configDir,
            spec.modelOptionsCache,
            shareMcp = shares(spec.policy, Keys.MCPS),
        )
        return MaterializeResult(spec.configDir, spec.availableModelIds.size, mcpCount)
    }

    // Guard the operator's REAL global config: the dir must look like an isolated .claude* dir AND
    // must not resolve to ~/.claude itself. `contains("claude")` let ~/.claude (and
    // ~/Documents/claude-notes, /tmp/claude) through — this closes both.
    private fun requireIsolatedDir(configDir: Path) {
        val target = configDir.toAbsolutePath().normalize()
        val isolated = target.fileName.toString().startsWith(Keys.CLAUDE_DIR) &&
            target != globalDir().toAbsolutePath().normalize()
        require(isolated) {
            "refuse to materialize into '$configDir' — must be an isolated .claude* dir, not the global ~/.claude"
        }
    }

    private fun globalDir() = home.resolve(Keys.CLAUDE)

    /**
     * Does the policy share [item]? Alias-aware, so the friendly config vocabulary (settings,
     * mcps, claude_md) matches the on-disk item names (settings.json, mcps, CLAUDE.md). isolate
     * wins over share for any alias.
     */
    private fun shares(policy: ClaudePolicy, item: String): Boolean {
        val aliases = when (item.lowercase()) {
            Keys.SETTINGS -> setOf(Keys.SETTINGS, "settings")
            "claude.md" -> setOf(Keys.CLAUDE_MD, "claude_md", "claude.md", "claudemd")
            Keys.MCPS -> setOf(Keys.MCPS, "mcp")
            else -> setOf(item)
        }
        return aliases.any { it in policy.share } && aliases.none { it in policy.isolate }
    }

    // settings is merged (not linked); mcps arrive via .claude.json. Everything else that the
    // policy shares is symlinked from the operator's global dir.
    private fun linkShared(configDir: Path, policy: ClaudePolicy) {
        // settings is merged (not linked) and mcps arrive via .claude.json, so both are skipped here.
        for (item in sharedLinkItems) {
            val linkable = item != Keys.SETTINGS && item != Keys.MCPS && shares(policy, item)
            if (linkable) linkOneShared(configDir, item)
        }
    }

    private fun linkOneShared(configDir: Path, item: String) {
        val src = globalDir().resolve(item)
        if (!Files.exists(src, NOFOLLOW_LINKS)) return
        // Best-effort: an I/O race here just leaves whatever is already on disk.
        runCatchingCancellable { replaceWithSymlink(src, configDir.resolve(item)) }
    }

    private fun replaceWithSymlink(src: Path, dst: Path) {
        if (Files.exists(dst, NOFOLLOW_LINKS)) {
            when {
                dst.isSymbolicLink() -> Files.delete(dst)
                dst.isDirectory() -> return // never delete a real dir the operator made
                else -> Files.delete(dst)
            }
        }
        Files.createSymbolicLink(dst, src)
    }

    private fun writeSettings(spec: MaterializeSpec, loginHook: JsonObject?) {
        val allow = spec.availableModelIds
        val dst = spec.configDir.resolve(Keys.SETTINGS)
        val global =
            if (shares(spec.policy, Keys.SETTINGS)) readJson(globalDir().resolve(Keys.SETTINGS)) else EMPTY_JSON
        val existing = breakSettingsSymlinkAndRead(dst)
        val savedModel = existing[Keys.MODEL]?.jsonPrimitive?.content
        val model = if (savedModel != null && savedModel in allow) savedModel else spec.defaultModel
        val hooks = LoginInterception.mergeInto(global[Keys.HOOKS], loginHook)
        val merged = buildJsonObject {
            global.forEach { (k, v) -> if (isCarriedGlobalKey(k)) put(k, v) }
            putJsonArray(Keys.AVAILABLE_MODELS) { allow.forEach { add(it) } }
            put("enforceAvailableModels", true)
            put(Keys.MODEL, model)
            put(Keys.STATUS_LINE, statusLineBlock(spec.statuslineCommand))
            if (hooks != null) put(Keys.HOOKS, hooks)
        }
        Files.writeString(dst, json.encodeToString(JsonObject.serializer(), merged) + "\n")
    }

    // A pre-existing settings.json that is a symlink would let our write clobber the operator's
    // global; break it first. A real file's model choice is read back so we can preserve it.
    private fun breakSettingsSymlinkAndRead(dst: Path): JsonObject {
        if (!Files.exists(dst, NOFOLLOW_LINKS)) return EMPTY_JSON
        if (dst.isSymbolicLink()) {
            runCatchingCancellable { Files.delete(dst) }
            return EMPTY_JSON
        }
        return readJson(dst)
    }

    private fun isCarriedGlobalKey(key: String): Boolean =
        key != Keys.MODEL && key != Keys.AVAILABLE_MODELS && key != Keys.STATUS_LINE && key != Keys.HOOKS

    private fun statusLineBlock(command: String): JsonObject = buildJsonObject {
        put("type", "command")
        put("command", command)
        put("padding", 0)
    }

    private fun writeClaudeJson(
        configDir: Path,
        modelOptionsCache: JsonElement,
        shareMcp: Boolean,
    ): Int {
        val statePath = configDir.resolve(Keys.CLAUDE_JSON)
        val global = readJson(home.resolve(Keys.CLAUDE_JSON))
        val local = readJson(statePath)
        var mcpCount = 0
        val next = buildJsonObject {
            local.forEach { (k, v) -> put(k, v) }
            put("additionalModelOptionsCache", modelOptionsCache)
            val globalMcp = (global[Keys.MCP_SERVERS] as? JsonObject)?.takeIf { shareMcp }
            if (globalMcp != null) {
                mcpCount = globalMcp.size
                put(Keys.MCP_SERVERS, globalMcp)
            }
            for (k in portKeys) {
                if (global[k] != null && local[k] == null) put(k, global[k]!!)
            }
            put(Keys.CUSTOM_API_KEY_RESPONSES, customApiKeyResponses(local))
            put(Keys.ONBOARDING, true)
        }
        Files.writeString(statePath, json.encodeToString(JsonObject.serializer(), next) + "\n")
        return mcpCount
    }

    // Preserve any approved custom keys but CLEAR rejected — a stale rejection would otherwise
    // dead-end the proxy's auth token at Claude Code's custom-key approval prompt.
    private fun customApiKeyResponses(local: JsonObject): JsonObject = buildJsonObject {
        put("approved", (local[Keys.CUSTOM_API_KEY_RESPONSES] as? JsonObject)?.get("approved") ?: buildJsonArray {})
        put("rejected", buildJsonArray {})
    }

    private fun readJson(path: Path): JsonObject {
        if (path.isSymbolicLink() || !Files.exists(path)) return EMPTY_JSON
        return runCatchingCancellable { json.parseToJsonElement(Files.readString(path)).jsonObject }
            .getOrDefault(EMPTY_JSON)
    }

    private companion object {
        val EMPTY_JSON = JsonObject(emptyMap())
    }
}

/** The `~/.claude*` path fragments and `.claude.json` keys are the byte-for-byte state contract with
 *  Claude Code; naming them once keeps the contract in a single place instead of duplicated literals. */
private object Keys {
    const val CLAUDE_DIR = ".claude"
    const val CLAUDE = ".claude"
    const val CLAUDE_JSON = ".claude.json"
    const val SETTINGS = "settings.json"
    const val CLAUDE_MD = "CLAUDE.md"
    const val MCPS = "mcps"
    const val MODEL = "model"
    const val AVAILABLE_MODELS = "availableModels"
    const val STATUS_LINE = "statusLine"
    const val MCP_SERVERS = "mcpServers"
    const val CUSTOM_API_KEY_RESPONSES = "customApiKeyResponses"
    const val ONBOARDING = "hasCompletedOnboarding"
    const val COMMANDS = "commands"
    const val HOOKS = "hooks"
}
