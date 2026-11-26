// NEW: server mode (docs/specs/server-mode.md, F8), and #791 (managed settings detection). The settings an organisation
// manages for Claude Code, which no other settings override, at Claude Code's own places, written once here: the
// platform's `managed-settings.json` and, beside it, the `managed-settings.d` folder whose `*.json` drop-ins Claude Code
// merges over it in filename order, skipping hidden files; the `remote-settings.json` Claude Code keeps in a config tree
// for settings the organisation sends it; and the operating system's policy ([PlatformPolicy]): macOS's
// `com.anthropic.claudecode` managed preferences, and Windows' `Settings` value under HKLM and, as a fallback, HKCU.
// `splice claude check`, `splice desktop wrapper` and the doctor's managed settings row (which read the file and only
// find the other places), the MCP plan and a launch's telemetry decision all read those places from here. Claude Code
// ranks the sources remote settings, then the system's admin policy (the plist or HKLM), then the files merged as it merges them (a later drop-in's value replacing or, with `null`, clearing an earlier
// one's), and reads HKCU only when none of those holds a document. Most keys come from the first source that sets a
// policy key, but `env` merges per variable across the three admin sources, the highest that defines a variable giving
// its value. Where the settings that reach a session set telemetry of their own (a `null` or blank value sets nothing),
// a launch adds none of splice's: the organisation's collector stays the one the session reports to. A document that is
// absent, can't be read, or is no JSON object as `JSON.parse` reads one (an unquoted word or a raw control character in
// a string makes it none; a key written twice keeps its last value) sets nothing, as Claude Code itself reads it.
package splice.client

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import splice.core.util.JsonScalars
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import kotlin.io.path.name

/** The managed settings of a machine on [osName] (this one's when it is not given), whose platform folder is
 *  [platformDir] where one is given (a test's own folder) and Claude Code's own for that system otherwise:
 *  `/Library/Application Support/ClaudeCode` on macOS, `C:\Program Files\ClaudeCode` on Windows, and
 *  `/etc/claude-code` on Linux and every other system. [policy] is the system's own policy store. */
public class ManagedSettings(
    osName: String = System.getProperty(OS_NAME_PROPERTY).orEmpty(),
    platformDir: Path? = null,
    private val policy: PlatformPolicy = PlatformPolicy(osName),
) {
    private val platform: Path = platformDir ?: Path.of(
        when {
            osName.lowercase().startsWith("mac") -> MAC_MANAGED_DIR
            osName.lowercase().startsWith("windows") -> WINDOWS_MANAGED_DIR
            else -> LINUX_MANAGED_DIR
        },
    )

    /** The platform's own managed settings file. */
    public val file: Path get() = platform.resolve(MANAGED_SETTINGS_FILE)

    /** Every managed settings file of the platform, in the order Claude Code merges them: [file], then each `*.json`
     *  of `managed-settings.d` in filename order. A folder that is absent or can't be listed adds no drop-in. */
    public fun sources(): List<Path> = listOf(file) + dropIns()

    /** What splice can say of the main file, and the other places a session whose config tree is [configDir] reads
     *  managed settings from, which are only found, never read for their keys: [ManagedReading]. */
    public fun reading(configDir: Path): ManagedReading {
        val state = if (Files.notExists(file, NOFOLLOW_LINKS)) {
            ManagedFile.Absent
        } else {
            read(file)?.let(ManagedFile::Read) ?: ManagedFile.Unreadable
        }
        val remote = configDir.resolve(REMOTE_SETTINGS).takeUnless { Files.notExists(it, NOFOLLOW_LINKS) }
        val system = policy.admin()?.let { SYSTEM_POLICY }
        val dropIns = listedDropIns()?.map(Path::toString) ?: listOf("$dropInFolder ($UNLISTED)")
        return ManagedReading(file, state, listOfNotNull(remote?.toString(), system) + dropIns)
    }

    /** Whether the managed settings that reach a session of the tree at [configDir] set telemetry: the switch or any
     *  OpenTelemetry variable in their `env`, merged per variable across the admin sources, or a headers helper of the
     *  one source Claude Code selects. Where no admin source holds a document, Windows' HKCU value is that source. */
    public fun setsTelemetry(configDir: Path): Boolean {
        val admin = listOfNotNull(remote(configDir), policy.admin(), files())
        val selected = admin.firstOrNull { policyKeyed(it.settings) }
        if (selected != null) return telemetry(adminEnv(admin), selected.settings)
        val fallback = policy.userFallback()?.settings?.takeIf { admin.none(PolicyDocument::unreadable) }
        return fallback != null && telemetry(fallback[MANAGED_ENV] as? JsonObject ?: EMPTY, fallback)
    }

    /** The tree's remote settings, or null where the tree holds none it can read. */
    private fun remote(configDir: Path): PolicyDocument? =
        read(configDir.resolve(REMOTE_SETTINGS))?.let { PolicyDocument(it, unreadable = false) }

    /** The platform's managed settings files as one document ([effective]), unreadable where a file of them exists
     *  and can't be read as an object; null where no file exists. */
    private fun files(): PolicyDocument? {
        val present = sources().filter(Files::exists)
        if (present.isEmpty()) return null
        return PolicyDocument(effective(), unreadable = present.any { read(it) == null })
    }

    /** The `env` of the admin sources, ranked highest first, merged per variable: each variable's value comes from
     *  the highest source whose `env` defines it, and a `null` there defines nothing. */
    private fun adminEnv(admin: List<PolicyDocument>): JsonObject {
        val laid = admin.reversed().mapNotNull { it.settings?.get(MANAGED_ENV) as? JsonObject }
            .fold(emptyMap<String, JsonElement>()) { under, over -> under + over.filterValues { it !is JsonNull } }
        return JsonObject(laid)
    }

    /** Whether [settings] holds a policy key: any key but Claude Code's two control keys, set to a value other than
     *  `null`. A document without one leaves the next source in the ranking to supply the policy. */
    private fun policyKeyed(settings: JsonObject?): Boolean =
        settings.orEmpty().any { (key, value) -> key !in CONTROL_KEYS && value !is JsonNull }

    /** The platform's managed settings as Claude Code merges its files ([sources]), each over the ones before it: a
     *  later file's value for a key replaces an earlier one, a list joins the earlier one's, an object such as `env`
     *  merges key by key, and a key a later file sets to `null` is no longer set. A file that is absent, unreadable
     *  or no object adds nothing. */
    private fun effective(): JsonObject = sources().mapNotNull(::read).fold(EMPTY, ::merged)

    /** [over] laid on [under], key by key: a `null` in [over] at any depth, the first file's own included, sets
     *  nothing. */
    private fun merged(under: JsonObject, over: JsonObject): JsonObject {
        val keys = under.keys + over.keys
        val laid = keys.mapNotNull { key ->
            val value = if (key in over) over.getValue(key) else under.getValue(key)
            val below = under[key]
            when {
                value is JsonNull -> null
                value is JsonObject -> key to merged(below as? JsonObject ?: EMPTY, value)
                value is JsonArray && below is JsonArray -> key to JsonArray((below + value).distinct())
                else -> key to value
            }
        }
        return JsonObject(laid.toMap())
    }

    private val dropInFolder: Path get() = platform.resolve(MANAGED_DROP_IN_DIR)

    private fun dropIns(): List<Path> = listedDropIns().orEmpty()

    /** The drop-ins in filename order: none where nothing is at the folder's path, and null where something is there
     *  that can't be listed. */
    private fun listedDropIns(): List<Path>? {
        if (Files.notExists(dropInFolder, NOFOLLOW_LINKS)) return emptyList()
        return try {
            Files.list(dropInFolder).use { listed ->
                listed.toList().filter { it.name.endsWith(DROP_IN_SUFFIX) && !it.name.startsWith(".") }
                    .sortedBy { it.name }
            }
        } catch (_: IOException) {
            null
        }
    }

    /** Whether [env] or [selected]'s headers helper sets telemetry; a key whose value is blank sets nothing, as an
     *  unset one does. */
    private fun telemetry(env: JsonObject, selected: JsonObject?): Boolean {
        val set = env.filterValues(::sets).keys
        val helper = selected?.get(Keys.OTEL_HEADERS_HELPER)?.let(::sets) == true
        return helper || set.any { it == TELEMETRY_SWITCH || it.startsWith(OTEL_PREFIX) }
    }

    private fun sets(value: JsonElement): Boolean =
        value !is JsonNull && (value !is JsonPrimitive || value.content.isNotBlank())

    private fun read(file: Path): JsonObject? = try {
        // why: Claude Code reads these files with `JSON.parse`, which keeps the last of a repeated key.
        JsonScalars.strictObjectOrNull(Json, Files.readString(file), lastKeyWins = true)
    } catch (_: IOException) {
        null
    }
}

private const val OS_NAME_PROPERTY = "os.name"
private const val LINUX_MANAGED_DIR = "/etc/claude-code"
private const val MAC_MANAGED_DIR = "/Library/Application Support/ClaudeCode"
private const val WINDOWS_MANAGED_DIR = "C:\\Program Files\\ClaudeCode"
private const val MANAGED_SETTINGS_FILE = "managed-settings.json"
private const val MANAGED_DROP_IN_DIR = "managed-settings.d"
private const val DROP_IN_SUFFIX = ".json"
private const val SYSTEM_POLICY = "the system's managed policy"

// A drop-in folder splice could not list, said in words: it may hold drop-ins.
private const val UNLISTED = "splice could not list it, so there may be files in it"

/** The file Claude Code keeps an organisation's remotely managed settings in, inside a config tree. */
private const val REMOTE_SETTINGS = "remote-settings.json"
private const val MANAGED_ENV = "env"

// why: the two keys that steer how Claude Code reads its managed sources and are no policy of their own.
private val CONTROL_KEYS = setOf("wslInheritsWindowsSettings", "managedSourcesBehavior")

/** The variable that turns Claude Code's telemetry on. */
public const val TELEMETRY_SWITCH: String = "CLAUDE_CODE_ENABLE_TELEMETRY"

// Every OpenTelemetry variable, the exporters and the endpoint among them, starts so.
private const val OTEL_PREFIX = "OTEL_"

private val EMPTY = JsonObject(emptyMap())

/** The managed setting that names the program Claude Code starts every session through. */
public const val PROCESS_WRAPPER_KEY: String = "processWrapper"

/** The managed setting that decides whether the policy a parent such as Claude Desktop hands a session it starts (the
 *  SDK parent tier) layers under the administrator's own. Claude Code's default, "first-wins", drops it wherever any
 *  managed settings exist, so Desktop's limits for its Code sessions are not applied and Desktop warns in each of
 *  them; only [PARENT_SETTINGS_MERGE] layers its restrictions in. Claude Code honours the key only from managed
 *  settings, and takes it from the highest source that sets it. */
public const val PARENT_SETTINGS_KEY: String = "parentSettingsBehavior"

/** The value of [PARENT_SETTINGS_KEY] under which the parent's limits still apply beside the administrator's. */
public const val PARENT_SETTINGS_MERGE: String = "merge"
