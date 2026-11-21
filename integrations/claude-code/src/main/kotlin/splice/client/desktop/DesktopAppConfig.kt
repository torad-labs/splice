// NEW: desktop-full (docs/specs/desktop-full.md, df-app-on). Claude Desktop's third-party ("3P") local configuration,
// as Desktop 5.11492.5 reads it (Nov 18, 2026;
// https://claude.com/docs/third-party/claude-desktop/configuration-format): a library folder,
// `$XDG_CONFIG_HOME/Claude-3p/configLibrary/` (Electron's appData on Linux, `~/.config` when unset), that holds one
// `<id>.json` per configuration and `_meta.json`, which lists them (`entries`, each `{id, name}`) and names the one
// Desktop applies (`appliedId`). Desktop reads it at launch and opens in 3P mode when the applied
// configuration names a provider. A managed configuration in `/etc/claude-desktop/managed-settings.json` decides the
// provider over any local one. splice writes one configuration of its own there, at the person's command, pointing
// Desktop at its door as a gateway; the credential is a helper Desktop runs, so no key is ever in the library.
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
import java.nio.file.Path

/** Where Desktop keeps its 3P configuration, and what splice's own configuration and its `_meta.json` entry hold. */
public class DesktopAppConfig(private val env: EnvReader = EnvReader(System::getenv)) {
    /** The library folder: `$XDG_CONFIG_HOME/Claude-3p/configLibrary`, `~/.config` when that is unset. */
    public val library: Path
        get() {
            val config = env(XDG_CONFIG_HOME)?.takeIf(String::isNotBlank)?.let(Path::of)
                ?: UserHome.dir(env).resolve(DOT_CONFIG)
            return config.resolve(APP_DATA).resolve(LIBRARY)
        }

    /** The library's `_meta.json`. */
    public val meta: Path get() = library.resolve(META_FILE)

    /** Configuration [id]'s file in the library. */
    public fun file(id: String): Path = library.resolve("$id$JSON_SUFFIX")

    /** The managed configuration, root's, which decides Desktop's provider over any local one. splice only asks
     *  whether it exists, and never opens it. */
    public val managed: Path get() = DesktopManagedPolicy.file

    /** splice's configuration: Desktop's gateway provider at [door], the credential from the executable [helper]
     *  (an absolute path), every request carrying [APP_MODE_HEADER], discovery filling the picker, and auto mode on
     *  with Claude Code's own check, which the door runs as a model call on every head. No model list (discovery
     *  fills the picker, and a list would replace it) and no key. */
    public fun config(door: String, helper: String): JsonObject = buildJsonObject {
        put("inferenceProvider", "gateway")
        // The live test (Nov 19, 2026, Desktop 5.11492.5): unset, Desktop first shows its chooser (Continue, or sign in
        // with claude.ai) and asks the door only once a person picks; "3p" opens it straight on splice
        put("deploymentMode", "3p")
        put(GATEWAY_BASE_URL, door)
        put("inferenceGatewayAuthScheme", "bearer")
        put("inferenceCredentialHelper", helper)
        put("inferenceCredentialHelperTtlSec", HELPER_TTL_SEC)
        putJsonObject("inferenceCustomHeaders") { put(APP_MODE_HEADER, APP_MODE) }
        put("modelDiscoveryEnabled", true)
        put("autoModeEnabled", true)
        put("inferenceGatewayAutoModeServerChecks", false)
    }

    /** The door address configuration [config] gives Desktop, or null when it gives none. */
    public fun door(config: JsonObject): String? = JsonScalars.str(config, GATEWAY_BASE_URL)

    /** The configuration [meta] applies, or null when it applies none. */
    public fun applied(meta: JsonObject): String? = JsonScalars.str(meta, APPLIED_ID)?.ifEmpty { null }

    /** The name [meta]'s entry [id] has, or null when it lists no such entry. */
    public fun nameOf(meta: JsonObject, id: String): String? =
        entries(meta).firstOrNull { JsonScalars.str(it, ID) == id }?.let { JsonScalars.str(it, NAME) }

    /** [meta] (or a new one, when there is none) with splice's entry [id] listed once and applied. Every other entry
     *  and field is kept as it was. */
    public fun withSplice(meta: JsonObject?, id: String): JsonObject {
        val before = meta ?: JsonObject(emptyMap())
        val listed = entries(before).any { JsonScalars.str(it, ID) == id }
        val entry = buildJsonObject {
            put(ID, id)
            put(NAME, SPLICE_NAME)
        }
        val entries = if (listed) before[ENTRIES] ?: JsonArray(listOf(entry)) else JsonArray(entries(before) + entry)
        return JsonObject(applying(before, id) + (ENTRIES to entries))
    }

    /** [meta] without splice's entry [id]. Where [meta] still applies it, [previous] is applied again, or none. */
    public fun withoutSplice(meta: JsonObject, id: String, previous: String?): JsonObject {
        val entries = JsonArray(entries(meta).filterNot { JsonScalars.str(it, ID) == id })
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

/** Where Claude Desktop's managed configuration is, the platform's one place: root's, read by Desktop over any local
 *  configuration. Nothing in the environment moves it. [within] is the test seam: a test lends the process a fixture
 *  path for one block, since the machine running it may have a managed file of its own. */
public object DesktopManagedPolicy {
    @PublishedApi
    @Volatile
    internal var redirected: Path? = null

    /** The managed configuration's path. */
    public val file: Path get() = redirected ?: Path.of(MANAGED)

    /** [block] run with [fixture] as the managed configuration's path, then the platform's again. */
    public inline fun <T> within(fixture: Path, block: () -> T): T {
        val previous = redirected
        redirected = fixture
        try {
            return block()
        } finally {
            redirected = previous
        }
    }
}

/** The header every request Desktop sends in app mode carries, which splice writes into its configuration's custom
 *  headers: how the door tells an app-mode request from a standard one. */
public const val APP_MODE_HEADER: String = "x-splice-desktop"

/** [APP_MODE_HEADER]'s value. */
public const val APP_MODE: String = "app"

/** The name splice's entry has in `_meta.json`, which Desktop shows in its configuration window. */
public const val SPLICE_NAME: String = "splice"

/** The library's index file. */
public const val META_FILE: String = "_meta.json"

// why: Desktop runs the helper again once its credential is this old, so a rotated key reaches it within five minutes.
private const val HELPER_TTL_SEC = 300

private const val XDG_CONFIG_HOME = "XDG_CONFIG_HOME"
private const val DOT_CONFIG = ".config"
private const val APP_DATA = "Claude-3p"
private const val LIBRARY = "configLibrary"
private const val JSON_SUFFIX = ".json"
private const val MANAGED = "/etc/claude-desktop/managed-settings.json"
private const val GATEWAY_BASE_URL = "inferenceGatewayBaseUrl"
private const val APPLIED_ID = "appliedId"
private const val ENTRIES = "entries"
private const val ID = "id"
private const val NAME = "name"
