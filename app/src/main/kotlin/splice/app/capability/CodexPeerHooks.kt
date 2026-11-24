// NEW: inspects and installs or migrates `~/.codex/hooks.json` for Splice's Codex peer hooks (docs/peers.md).
// Detects legacy `sh -c 'id=$(jq ...)'` wrappers (which consume stdin so HarnessHook loses cwd) and wrong
// SessionEnd timeouts (e.g. 30s instead of 3s), migrating them to direct `splice peer codex attach|detach`
// while preserving all other user and hookd entries untouched. Never touches `~/.gemini`.
package splice.app.capability

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import splice.core.util.EnvReader
import splice.core.util.JsonScalars
import splice.core.util.PathProbe
import splice.core.util.SecureFile
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/** What inspecting `~/.codex/hooks.json` found. */
internal enum class CodexHooksState {
    /** All three events run direct `splice peer codex attach|detach` with the required timeouts and no matcher. */
    CURRENT,

    /** File does not exist, or contains no splice peer codex hooks yet. */
    MISSING,

    /** Present with legacy shell wrappers, wrong timeouts, unexpected matchers, or missing events. */
    NEEDS_MIGRATION,

    /** File exists but is unreadable or not valid JSON; never overwritten automatically. */
    UNREADABLE,
}

/** One read of `~/.codex/hooks.json`. */
internal data class CodexHooksReading(
    val file: Path,
    val state: CodexHooksState,
    val issues: List<String> = emptyList(),
)

/** Outcome of installing or migrating `~/.codex/hooks.json`. */
internal sealed class CodexHooksOutcome {
    data class Written(val file: Path, val migrated: Boolean) : CodexHooksOutcome()

    data class Current(val file: Path) : CodexHooksOutcome()

    data class Refused(val file: Path, val reason: String) : CodexHooksOutcome()
}

/** Reads, audits, and installs/migrates Splice's Codex peer hooks in `~/.codex/hooks.json`. */
internal class CodexPeerHooks {
    private val readJson = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }
    private val writeJson = Json {
        prettyPrint = true
        prettyPrintIndent = "  "
    }

    /** Path to Codex's `hooks.json`, honouring `CODEX_HOME` when set, else `~/.codex/hooks.json`. */
    fun hooksFile(home: Path, env: EnvReader): Path {
        val codexHome = env("CODEX_HOME")?.takeIf(String::isNotBlank)?.let(PathProbe::spelled) ?: home.resolve(".codex")
        return codexHome.resolve(CODEX_HOOKS_FILE)
    }

    /** Read-only inspection of [file]. Never throws and never writes. */
    fun inspect(file: Path): CodexHooksReading {
        if (!Files.exists(file)) return CodexHooksReading(file, CodexHooksState.MISSING)
        val root = readRoot(file)
            ?: return CodexHooksReading(file, CodexHooksState.UNREADABLE, listOf("not a valid JSON object"))
        val hooksEl = root[HOOKS_ROOT_KEY]
        return when {
            hooksEl == null -> CodexHooksReading(file, CodexHooksState.MISSING)
            hooksEl is JsonObject -> audit(file, hooksEl)
            else -> CodexHooksReading(file, CodexHooksState.UNREADABLE, listOf("`hooks` is not a JSON object"))
        }
    }

    /** Installs missing Codex peer hooks or migrates legacy/incomplete entries while preserving all other hooks. */
    fun installOrMigrate(file: Path): CodexHooksOutcome {
        val current = inspect(file)
        return when (current.state) {
            CodexHooksState.CURRENT -> CodexHooksOutcome.Current(file)
            CodexHooksState.UNREADABLE ->
                CodexHooksOutcome.Refused(file, current.issues.joinToString("; ").ifBlank { "unreadable" })
            CodexHooksState.MISSING, CodexHooksState.NEEDS_MIGRATION ->
                writeMerged(file, migrated = current.state == CodexHooksState.NEEDS_MIGRATION)
        }
    }

    private fun writeMerged(file: Path, migrated: Boolean): CodexHooksOutcome {
        val root = if (Files.exists(file)) {
            readRoot(file) ?: return CodexHooksOutcome.Refused(file, "not a valid JSON object")
        } else {
            JsonObject(emptyMap())
        }
        val mergedHooks = mergeHooksObject(root[HOOKS_ROOT_KEY])
            ?: return CodexHooksOutcome.Refused(file, "cannot merge non-standard hooks layout")
        val mergedRoot = LinkedHashMap<String, JsonElement>(root)
        mergedRoot[HOOKS_ROOT_KEY] = mergedHooks
        val text = writeJson.encodeToString(JsonObject.serializer(), JsonObject(mergedRoot)) + "\n"
        return persist(file, text, migrated)
    }

    private fun mergeHooksObject(rawHooks: JsonElement?): JsonObject? {
        val existingHooks = if (rawHooks == null) {
            JsonObject(emptyMap())
        } else if (rawHooks is JsonObject) {
            rawHooks
        } else {
            return null
        }
        val mergedHooks = LinkedHashMap<String, JsonElement>(existingHooks)
        for (spec in requiredSpecs) {
            val updated = updateEvent(existingHooks[spec.event], spec) ?: return null
            mergedHooks[spec.event] = updated
        }
        return JsonObject(mergedHooks)
    }

    private fun persist(file: Path, text: String, migrated: Boolean): CodexHooksOutcome = try {
        file.parent?.let(Files::createDirectories)
        SecureFile.writeAtomic0600(file, text)
        CodexHooksOutcome.Written(file, migrated = migrated)
    } catch (e: IOException) {
        CodexHooksOutcome.Refused(file, e::class.simpleName ?: "IOException")
    }

    private fun audit(file: Path, hooks: JsonObject): CodexHooksReading {
        val issues = mutableListOf<String>()
        val missingEvents = mutableListOf<String>()
        var foundAny = false
        for (spec in requiredSpecs) {
            val entries = spliceEntries(hooks[spec.event])
            if (entries.isEmpty()) {
                missingEvents += spec.event
            } else {
                foundAny = true
                if (entries.size > 1) issues += "${spec.event} has ${entries.size} splice peer hook entries"
                entries.forEach { entry -> auditEntry(spec, entry, issues) }
            }
        }
        if (!foundAny) return CodexHooksReading(file, CodexHooksState.MISSING)
        missingEvents.forEach { event -> issues += "missing splice peer hook on $event" }
        val state = if (issues.isEmpty()) CodexHooksState.CURRENT else CodexHooksState.NEEDS_MIGRATION
        return CodexHooksReading(file, state, issues)
    }

    private fun auditEntry(spec: EventSpec, entry: SpliceEntry, issues: MutableList<String>) {
        if (entry.command.trim() != spec.command) {
            issues += "${spec.event} uses legacy wrapper instead of direct `${spec.command}`"
            return
        }
        if (entry.timeout != spec.timeoutSeconds) {
            issues += "${spec.event} timeout is ${entry.timeout ?: "unset"} (expected ${spec.timeoutSeconds})"
        }
        if (!entry.matcher.isNullOrBlank()) {
            issues += "${spec.event} sets a matcher on splice's hook"
        }
    }

    private fun updateEvent(existing: JsonElement?, spec: EventSpec): JsonArray? {
        val array = if (existing == null) {
            JsonArray(emptyList())
        } else if (existing is JsonArray) {
            existing
        } else {
            return null
        }
        return buildJsonArray {
            array.mapNotNull(::stripSpliceFromGroup).forEach(::add)
            add(canonicalGroup(spec))
        }
    }

    /** Removes splice peer codex hooks from [element]; returns null when the group held only splice hooks. */
    private fun stripSpliceFromGroup(element: JsonElement): JsonElement? {
        val group = element as? JsonObject ?: return element
        val hooksArray = group[HOOKS_ROOT_KEY] as? JsonArray ?: return group
        val kept = hooksArray.filterNot { item ->
            val cmd = (item as? JsonObject)?.let { JsonScalars.str(it, COMMAND_KEY) }
            cmd != null && SPLICE_CODEX_PEER_NEEDLE in cmd
        }
        return when {
            kept.size == hooksArray.size -> group
            kept.isEmpty() -> null
            else -> JsonObject(LinkedHashMap<String, JsonElement>(group).apply { put(HOOKS_ROOT_KEY, JsonArray(kept)) })
        }
    }

    private fun canonicalGroup(spec: EventSpec): JsonObject = buildJsonObject {
        putJsonArray(HOOKS_ROOT_KEY) {
            add(
                buildJsonObject {
                    put(TYPE_KEY, COMMAND_KEY)
                    put(COMMAND_KEY, spec.command)
                    put(TIMEOUT_KEY, spec.timeoutSeconds)
                },
            )
        }
    }

    private fun spliceEntries(eventEl: JsonElement?): List<SpliceEntry> {
        val array = eventEl as? JsonArray ?: return emptyList()
        return array.mapNotNull { it as? JsonObject }.flatMap(::entriesInGroup)
    }

    private fun entriesInGroup(group: JsonObject): List<SpliceEntry> {
        val matcher = JsonScalars.str(group, MATCHER_KEY)
        val items = group[HOOKS_ROOT_KEY] as? JsonArray ?: return emptyList()
        return items.mapNotNull { item ->
            val obj = item as? JsonObject
            val cmd = obj?.let { JsonScalars.str(it, COMMAND_KEY) }?.takeIf { SPLICE_CODEX_PEER_NEEDLE in it }
            if (obj != null && cmd != null) SpliceEntry(cmd, JsonScalars.int(obj, TIMEOUT_KEY), matcher) else null
        }
    }

    private fun readRoot(file: Path): JsonObject? = try {
        JsonScalars.objectOrNull(readJson, Files.readString(file))
    } catch (_: IOException) {
        null
    }

    private data class SpliceEntry(val command: String, val timeout: Int?, val matcher: String?)

    private data class EventSpec(val event: String, val command: String, val timeoutSeconds: Int)

    private val requiredSpecs = listOf(
        EventSpec("SessionStart", CODEX_ATTACH_CMD, CODEX_ATTACH_TIMEOUT_S),
        EventSpec("UserPromptSubmit", CODEX_ATTACH_CMD, CODEX_ATTACH_TIMEOUT_S),
        EventSpec("SessionEnd", CODEX_DETACH_CMD, CODEX_DETACH_TIMEOUT_S),
    )
}

private const val CODEX_HOOKS_FILE = "hooks.json"
private const val HOOKS_ROOT_KEY = "hooks"
private const val TYPE_KEY = "type"
private const val COMMAND_KEY = "command"
private const val TIMEOUT_KEY = "timeout"
private const val MATCHER_KEY = "matcher"
private const val SPLICE_CODEX_PEER_NEEDLE = "splice peer codex"
private const val CODEX_ATTACH_CMD = "splice peer codex attach"
private const val CODEX_DETACH_CMD = "splice peer codex detach"

// why: docs/peers.md specifies 30s for attach hooks so cold-starting the daemon on SessionStart/UserPromptSubmit succeeds.
private const val CODEX_ATTACH_TIMEOUT_S = 30

// why: docs/peers.md specifies 3s for SessionEnd detach so Codex shutdown never stalls if the daemon is down.
private const val CODEX_DETACH_TIMEOUT_S = 3
