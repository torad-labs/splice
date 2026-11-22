// NEW: desktop-full (docs/specs/desktop-full.md, df-app-on). Claude Desktop's third-party ("3P") local configuration,
// as Desktop 5.11492.5 reads it (Nov 18, 2026;
// https://claude.com/docs/third-party/claude-desktop/configuration-format): a library folder under Electron's appData
// (`$XDG_CONFIG_HOME/Claude-3p/configLibrary/` on Linux, `~/.config` when unset;
// `~/Library/Application Support/Claude-3p/configLibrary/` on macOS; or `$CLAUDE_USER_DATA_DIR/configLibrary/` when
// `CLAUDE_USER_DATA_DIR` is set) that holds one `<id>.json` per configuration and `_meta.json`, which lists them
// (`entries`, each `{id, name}`) and names the one Desktop applies (`appliedId`). Desktop reads it at launch and opens
// in 3P mode when the applied configuration names a provider. A managed configuration ([DESKTOP_MANAGED_FILE] on Linux,
// MDM plists under `/Library/Managed Preferences/` on macOS) decides the provider over any local one. splice writes one
// configuration of its own there, at the person's command, pointing Desktop at its door as a gateway; the credential is
// a helper Desktop runs, so no key is ever in the library.
package splice.client.desktop

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import splice.core.config.UserHome
import splice.core.util.EnvReader
import splice.core.util.JsonScalars
import java.nio.file.Files
import java.nio.file.Path

/** Where Desktop keeps its 3P configuration, and what splice's own configuration and its `_meta.json` entry hold. */
public class DesktopAppConfig(
    public val env: EnvReader = EnvReader(System::getenv),
    public val osName: String = System.getProperty("os.name").orEmpty(),
    private val userName: String = System.getProperty("user.name").orEmpty(),
) {
    private val isMac: Boolean get() = osName.startsWith(DESKTOP_MAC_OS_PREFIX, ignoreCase = true)

    /** The config folder Desktop keeps its data under: `$XDG_CONFIG_HOME` when set, otherwise
     *  `~/Library/Application Support` on macOS and `~/.config` on Linux. */
    private val home: Path
        get() = env(XDG_CONFIG_HOME)?.takeIf(String::isNotBlank)?.let(Path::of)
            ?: if (isMac) {
                UserHome.dir(env).resolve(DESKTOP_MAC_APP_SUPPORT)
            } else {
                UserHome.dir(env).resolve(DOT_CONFIG)
            }

    /** What makes and fills splice's policy, over the person's own Claude folder. */
    private val policies: DesktopPolicy get() = DesktopPolicy(ClaudeCodeSetup(UserHome.claudeDir(env)))

    /** The library folder: `$CLAUDE_USER_DATA_DIR/configLibrary` when `CLAUDE_USER_DATA_DIR` is set, otherwise
     *  `<home>/Claude-3p/configLibrary` (`$XDG_CONFIG_HOME`, else `~/Library/Application Support` on macOS or
     *  `~/.config` on Linux). */
    public val library: Path
        get() = env(CLAUDE_USER_DATA_DIR)?.takeIf(String::isNotBlank)?.let(Path::of)?.resolve(LIBRARY)
            ?: home.resolve(APP_DATA).resolve(LIBRARY)

    /** The platform managed configuration files Desktop reads over any local configuration: on Linux,
     *  [DESKTOP_MANAGED_FILE]; on macOS, the machine- and user-scope MDM preference plists under
     *  `/Library/Managed Preferences/` (`com.anthropic.claudefordesktop.plist`). */
    public val managedFiles: List<Path>
        get() = if (isMac) {
            listOfNotNull(
                Path.of(DESKTOP_MAC_MANAGED_PREFS_DIR, DESKTOP_MAC_MANAGED_PLIST),
                userName.takeIf(String::isNotBlank)?.let {
                    Path.of(DESKTOP_MAC_MANAGED_PREFS_DIR, it, DESKTOP_MAC_MANAGED_PLIST)
                },
            )
        } else {
            listOf(Path.of(DESKTOP_MANAGED_FILE))
        }

    /** The configuration of each Claude Desktop started on a config folder of its own one folder below this one's
     *  (`~/.config/<name>` on Linux, `~/Library/Application Support/<name>` on macOS, or `$XDG_CONFIG_HOME/<name>`):
     *  a second Desktop run beside the person's own, which keeps its claude.ai account, has its library there. Only a
     *  folder that holds a library is one. */
    public fun beside(): List<DesktopAppConfig> {
        if (!Files.isDirectory(home)) return emptyList()
        val folders = Files.newDirectoryStream(home) { Files.isDirectory(it.resolve(APP_DATA).resolve(LIBRARY)) }
            .use { it.toList() }
        return folders.sorted().map { folder ->
            DesktopAppConfig(
                EnvReader { key ->
                    when (key) {
                        XDG_CONFIG_HOME -> folder.toString()
                        CLAUDE_USER_DATA_DIR -> null
                        else -> env(key)
                    }
                },
                osName,
                userName,
            )
        }
    }

    /** [config] with [models] as its model list (`inferenceModels`), which is where Desktop reads the Claude model
     *  another lab's model takes its Effort control from (`behavesAs`) and the highest level it offers (`maxEffort`):
     *  its gateway discovery reads neither. Discovery is kept on where [config] says nothing of it, so the gateway's
     *  `/v1/models` still gives each listed model its description. */
    public fun withModels(config: JsonObject, models: JsonArray): JsonObject {
        val discovery = if (MODEL_DISCOVERY in config) emptyMap() else mapOf(MODEL_DISCOVERY to JsonPrimitive(true))
        return JsonObject(config + (INFERENCE_MODELS to models) + discovery)
    }

    /** The library's `_meta.json`. */
    public val meta: Path get() = library.resolve(META_FILE)

    /** Configuration [id]'s file in the library. */
    public fun file(id: String): Path = library.resolve("$id$LIBRARY_FILE_SUFFIX")

    /** splice's configuration: the [policy] it was made with, and over that Desktop's gateway provider at [door], the
     *  credential from the executable [helper] (an absolute path), every request carrying [APP_MODE_HEADER], and what
     *  every configuration on the door holds (DesktopPolicy.standing). No key, and no model list here: the door's
     *  models are written into it as they stand ([withModels]). */
    public fun config(door: String, helper: String, policy: JsonObject = JsonObject(emptyMap())): JsonObject {
        val connection = buildJsonObject {
            put("inferenceProvider", "gateway")
            put(APP_GATEWAY_BASE_URL, door)
            put("inferenceGatewayAuthScheme", "bearer")
            put("inferenceCredentialHelper", helper)
            put("inferenceCredentialHelperTtlSec", HELPER_TTL_SEC)
            putJsonObject(APP_HEADERS) { put(APP_MODE_HEADER, APP_MODE) }
        }
        return JsonObject(policy + policies.standing() + connection)
    }

    /** splice's policy for Desktop: [defaults], the policy the build ships, with what the person already set up in
     *  Claude Code laid over it (the effort their sessions run at, the marketplaces they added), so Desktop on splice
     *  starts out matching their Claude Code. */
    public fun policy(defaults: JsonObject): JsonObject = policies.generated(defaults)

    /** [config], a configuration that points Desktop at the door, given what it leaves unset of [policy] and of what
     *  every configuration on the door holds, the marketplaces of [policy] its list lacks, and [APP_MODE_HEADER]
     *  among its own headers. A key it sets stays as the person set it. */
    public fun withPolicy(config: JsonObject, policy: JsonObject): JsonObject = policies.filled(config, policy)

    /** The door address configuration [config] gives Desktop, or null when it gives none. */
    public fun door(config: JsonObject): String? = JsonScalars.str(config, APP_GATEWAY_BASE_URL)

    /** The configuration [meta] applies, or null when it applies none. */
    public fun applied(meta: JsonObject): String? = JsonScalars.str(meta, APPLIED_ID)?.ifEmpty { null }

    /** The name [meta]'s entry [id] has, or null when it lists no such entry. */
    public fun nameOf(meta: JsonObject, id: String): String? =
        entries(meta).firstOrNull { JsonScalars.str(it, ENTRY_ID) == id }?.let { JsonScalars.str(it, ENTRY_NAME) }

    /** [meta] (or a new one, when there is none) with splice's entry [id] listed once and applied. Every other entry
     *  and field is kept as it was. */
    public fun withSplice(meta: JsonObject?, id: String): JsonObject {
        val before = meta ?: JsonObject(emptyMap())
        val listed = entries(before).any { JsonScalars.str(it, ENTRY_ID) == id }
        val entry = buildJsonObject {
            put(ENTRY_ID, id)
            put(ENTRY_NAME, SPLICE_NAME)
        }
        val entries = if (listed) before[ENTRIES] ?: JsonArray(listOf(entry)) else JsonArray(entries(before) + entry)
        return JsonObject(applying(before, id) + (ENTRIES to entries))
    }

    /** [meta] without splice's entry [id]. Where [meta] still applies it, [previous] is applied again, or none. */
    public fun withoutSplice(meta: JsonObject, id: String, previous: String?): JsonObject {
        val entries = JsonArray(entries(meta).filterNot { JsonScalars.str(it, ENTRY_ID) == id })
        val kept = if (applied(meta) == id) applying(meta, previous) else meta
        return JsonObject(kept + (ENTRIES to entries))
    }

    /** Whether [meta] lists no configuration and applies none: the `_meta.json` splice made, once splice is out. */
    public fun empty(meta: JsonObject): Boolean = entries(meta).isEmpty() && applied(meta) == null

    /** [meta] applying [id], with `appliedId` in its place; no `appliedId` at all for none. */
    private fun applying(meta: JsonObject, id: String?): Map<String, JsonElement> {
        if (id == null) return meta - APPLIED_ID
        if (APPLIED_ID !in meta) return mapOf(APPLIED_ID to JsonPrimitive(id)) + meta
        return meta.mapValues { (key, value) -> if (key == APPLIED_ID) JsonPrimitive(id) else value }
    }

    private fun entries(meta: JsonObject): List<JsonObject> =
        (meta[ENTRIES] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
}

/** Where Claude Desktop's managed configuration lives on Linux: root's file, read by Desktop over any local
 *  configuration. On macOS Desktop reads MDM plists under `/Library/Managed Preferences/` instead (see
 *  [DesktopAppConfig.managedFiles]). Nothing in the environment moves it. splice only asks whether it exists, and
 *  never opens it. */
public const val DESKTOP_MANAGED_FILE: String = "/etc/claude-desktop/managed-settings.json"

/** The header every request Desktop sends in app mode carries, which splice writes into its configuration's custom
 *  headers: how the door tells an app-mode request from a standard one. */
public const val APP_MODE_HEADER: String = "x-splice-desktop"

/** [APP_MODE_HEADER]'s value. */
internal const val APP_MODE: String = "app"

/** The name splice's entry has in `_meta.json`, which Desktop shows in its configuration window. */
internal const val SPLICE_NAME: String = "splice"

/** The library's index file. */
internal const val META_FILE: String = "_meta.json"

// why: Desktop runs the helper again once its credential is this old, so a rotated key reaches it within five minutes.
private const val HELPER_TTL_SEC = 300

private const val XDG_CONFIG_HOME = "XDG_CONFIG_HOME"
private const val CLAUDE_USER_DATA_DIR = "CLAUDE_USER_DATA_DIR"
private const val DOT_CONFIG = ".config"
private const val DESKTOP_MAC_OS_PREFIX = "Mac"
private const val DESKTOP_MAC_APP_SUPPORT = "Library/Application Support"
private const val DESKTOP_MAC_MANAGED_PREFS_DIR = "/Library/Managed Preferences"
private const val DESKTOP_MAC_MANAGED_PLIST = "com.anthropic.claudefordesktop.plist"
private const val APP_DATA = "Claude-3p"
private const val LIBRARY = "configLibrary"
private const val LIBRARY_FILE_SUFFIX = ".json"
private const val APP_GATEWAY_BASE_URL = "inferenceGatewayBaseUrl"

/** Desktop's key for the headers every request of its carries, [APP_MODE_HEADER] among them. */
internal const val APP_HEADERS: String = "inferenceCustomHeaders"
private const val INFERENCE_MODELS = "inferenceModels"
internal const val MODEL_DISCOVERY: String = "modelDiscoveryEnabled"
private const val APPLIED_ID = "appliedId"
private const val ENTRIES = "entries"
private const val ENTRY_ID = "id"
private const val ENTRY_NAME = "name"
