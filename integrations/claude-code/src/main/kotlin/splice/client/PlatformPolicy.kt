// NEW: #791 (managed settings detection). The managed settings an organisation sets for Claude Code through the
// operating system rather than a file: on macOS the `com.anthropic.claudecode` managed preferences domain a
// configuration profile installs, and on Windows the JSON `Settings` value under `SOFTWARE\Policies\ClaudeCode`, in
// HKLM (an admin source) and in HKCU (the user-writable fallback). They are read where and as Claude Code 2.1.330 reads
// them: the person's own `/Library/Managed Preferences/<user>/com.anthropic.claudecode.plist` when it can be read and
// the device's `/Library/Managed Preferences/com.anthropic.claudecode.plist` otherwise, each converted to JSON by
// `/usr/bin/plutil -convert json`; the registry through `reg.exe query <key> /v Settings`, a REG_SZ or REG_EXPAND_SZ
// value whose `%NAME%` references expand from the environment when it is REG_EXPAND_SZ. Each command runs for at most
// five seconds and its output is read up to 2 MiB, as Claude Code bounds its own. Linux and every other system hold no
// such policy, and WSL's inheritance of the Windows chain (`wslInheritsWindowsSettings`) is not read.
package splice.client

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import splice.core.util.EnvReader
import splice.core.util.JsonScalars
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/** One managed settings document as the system holds it: its [settings] when it could be read as a JSON object, and
 *  whether it exists but could not be read ([unreadable]), which Claude Code counts as a document that is present. */
public data class PolicyDocument(public val settings: JsonObject?, public val unreadable: Boolean)

/** The operating-system policy of a machine on [osName]. [preferences] is macOS's managed preferences folder and
 *  [plutil] the converter that reads it; [reg] is Windows' `reg.exe`, `%SystemRoot%\System32\reg.exe` unless given
 *  (a test's stand-in); [user] names the person whose per-user preferences macOS may hold. */
public class PlatformPolicy(
    osName: String,
    private val preferences: Path = Path.of(MAC_MANAGED_PREFERENCES),
    private val plutil: Path = Path.of(PLUTIL),
    reg: Path? = null,
    private val user: String = System.getProperty(USER_NAME_PROPERTY).orEmpty(),
    private val env: EnvReader = EnvReader(System::getenv),
) {
    private val os = osName.lowercase()
    private val reg: Path = reg ?: Path.of(env(SYSTEM_ROOT) ?: WINDOWS_ROOT, "System32", "reg.exe")

    /** The admin source the system holds, ranked between remote settings and the managed settings files: macOS's
     *  managed preferences or Windows' HKLM value; null where the system holds none. */
    public fun admin(): PolicyDocument? = when {
        os.startsWith(MAC_FAMILY) -> preferences()
        os.startsWith(WINDOWS_FAMILY) -> registry(HKLM_KEY)
        else -> null
    }

    /** Windows' HKCU value, which Claude Code reads only when no admin document is present; null everywhere else. */
    public fun userFallback(): PolicyDocument? = if (os.startsWith(WINDOWS_FAMILY)) registry(HKCU_KEY) else null

    /** The system's policy stores Claude Code reads, in words, named without looking at them: macOS's plists, Windows'
     *  HKLM and HKCU `Settings` values; none on any other system. */
    internal fun stores(): List<String> = when {
        os.startsWith(MAC_FAMILY) -> plists().map(Path::toString)
        os.startsWith(WINDOWS_FAMILY) -> listOf(HKLM_KEY, HKCU_KEY).map { "the registry value $it\\$SETTINGS_VALUE" }
        else -> emptyList()
    }

    /** The person's plist, where macOS names a person, then the device's. */
    private fun plists(): List<Path> = listOfNotNull(
        user.takeIf { it.isNotBlank() }?.let { preferences.resolve(it).resolve(PLIST) },
        preferences.resolve(PLIST),
    )

    /** The first of the person's and the device's plists that exists and converts, as Claude Code takes the first
     *  readable one; null when neither exists, and unreadable when none that exists converts to a JSON object. */
    private fun preferences(): PolicyDocument? {
        val candidates = plists().filter(Files::exists)
        if (candidates.isEmpty()) return null
        val settings = candidates.firstNotNullOfOrNull { plist ->
            run(listOf(plutil.toString()) + PLUTIL_ARGS + plist.toString())?.let(::parsed)
        }
        return PolicyDocument(settings, unreadable = settings == null)
    }

    /** The `Settings` value under [key], or null when `reg.exe` reports no such key or value (any failing exit); a
     *  value that is there and is no string holding a JSON object is unreadable. */
    private fun registry(key: String): PolicyDocument? {
        val out = run(listOf(reg.toString(), "query", key, "/v", SETTINGS_VALUE)) ?: return null
        val match = REG_LINE.find(out) ?: return PolicyDocument(null, unreadable = true)
        val raw = match.groupValues[2].trim()
        val text = if (match.groupValues[1].isNotEmpty()) expanded(raw) else raw
        val settings = parsed(text)
        return PolicyDocument(settings, unreadable = settings == null)
    }

    /** [text] with each `%NAME%` an environment variable sets replaced by its value, as Windows expands a
     *  REG_EXPAND_SZ value; a name the environment does not set stays as written. */
    private fun expanded(text: String): String =
        EXPANDABLE.replace(text) { found -> env(found.groupValues[1]) ?: found.value }

    private fun parsed(text: String): JsonObject? =
        // why: Claude Code reads the converted policy with `JSON.parse`, which keeps the last of a repeated key.
        JsonScalars.strictObjectOrNull(Json, text, lastKeyWins = true)

    /** [command]'s standard output when it exits 0 within [COMMAND_WAIT_MS] having written at most [OUTPUT_LIMIT]
     *  bytes, and null otherwise. A watcher kills a run past the wait, so output larger than the pipe never deadlocks
     *  against it and a stuck command never holds a launch. */
    private fun run(command: List<String>): String? = try {
        val process = ProcessBuilder(command)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
        Thread.ofVirtual().name("splice-managed-policy").start {
            if (!process.waitFor(COMMAND_WAIT_MS, TimeUnit.MILLISECONDS)) process.destroyForcibly()
        }
        val bytes = process.inputStream.use { it.readNBytes(OUTPUT_LIMIT + 1) }
        val finished = process.waitFor(COMMAND_WAIT_MS, TimeUnit.MILLISECONDS)
        if (!finished) process.destroyForcibly()
        val clean = finished && process.exitValue() == 0 && bytes.size <= OUTPUT_LIMIT
        if (clean) bytes.toString(Charsets.UTF_8) else null
    } catch (_: IOException) {
        null
    }
}

private const val USER_NAME_PROPERTY = "user.name"
private const val MAC_FAMILY = "mac"
private const val WINDOWS_FAMILY = "windows"
private const val MAC_MANAGED_PREFERENCES = "/Library/Managed Preferences"
private const val PLIST = "com.anthropic.claudecode.plist"

// why: macOS's own converter, by absolute path, so a PATH entry cannot stand in for it.
private const val PLUTIL = "/usr/bin/plutil"
private val PLUTIL_ARGS = listOf("-convert", "json", "-o", "-", "--")
private const val SYSTEM_ROOT = "SystemRoot"
private const val WINDOWS_ROOT = "C:\\Windows"
private const val HKLM_KEY = "HKLM\\SOFTWARE\\Policies\\ClaudeCode"
private const val HKCU_KEY = "HKCU\\SOFTWARE\\Policies\\ClaudeCode"
private const val SETTINGS_VALUE = "Settings"

// why: Claude Code's own bounds on the same commands: five seconds, and 2 MiB of output.
private const val COMMAND_WAIT_MS = 5_000L

// why: 2 MiB of JSON is far past any real policy; more is a stuck or hostile command, read as no document.
private const val OUTPUT_LIMIT = 2 * 1024 * 1024

// why: `reg query` prints the value as `    Settings    REG_SZ    <data>` under the key's name.
private val REG_LINE = Regex("""^\s+\S.*?\s+REG_(EXPAND_)?SZ\s+(.+)$""", RegexOption.MULTILINE)
private val EXPANDABLE = Regex("%([^%]+)%")
