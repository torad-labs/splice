// NEW: desktop-full (docs/specs/desktop-full.md, df-app-on). splice's configuration in Claude Desktop's 3P library
// (DesktopAppConfig), put in by `splice desktop on` and taken out by `off` and `splice uninstall`.
// Putting it in writes splice's own `<id>.json` and its `_meta.json` entry, applied, and leaves every other file and
// entry as it was; what `_meta.json` held before is kept in splice's desktop record (DesktopAppRecord). Taking it out
// removes both and applies again what splice replaced, unless the person applied another configuration meanwhile, in
// which case their choice stays; a `_meta.json` that splice found is written back byte for byte where nothing else in
// it changed, and one splice made is removed, with the folder when splice made it and it is empty. splice never opens
// the managed file, the keyring or any of Desktop's encrypted stores.
//
// desktop-policy (outcome 9): the configuration splice writes carries its policy for Desktop, the default the build
// ships with what the person set up in Claude Code laid over it, and a daemon start gives the same to any applied
// configuration that points Desktop at the door, filling what it leaves unset and changing nothing it sets but
// the values the person set in the console (DesktopPolicyLayers).
package splice.app.desktop.setup

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import splice.app.desktop.record.DesktopAppRecord
import splice.client.desktop.DesktopAppConfig
import splice.core.util.Cancellables
import splice.core.util.LogSink
import splice.core.util.SafeFailureText
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

internal class DesktopAppLibrary(
    private val config: DesktopAppConfig = DesktopAppConfig(),
    /** The policy the build ships, which what the person set up in Claude Code is laid over. */
    private val defaults: DesktopPolicyDefaults = DesktopPolicyDefaults(),
    private val profileSync: DesktopProfileSync = DesktopProfileSync(config.env, config.osName),
    private val managedPaths: List<Path> = config.managedFiles,
    /** The Desktop policy values the person set in the console, laid over splice's policy; none when null. */
    private val overrides: DesktopPolicyOverrides? = null,
    /** Where a profile sync that failed is said: the daemon log, or the command's stderr. */
    private val log: LogSink = LogSink {},
) {
    private val files = DesktopLibraryFiles()

    /** splice's policy for this Desktop, layer by layer, the console's values among them. */
    val layers: DesktopPolicyLayers = DesktopPolicyLayers(config, defaults, overrides)

    /** The first existing managed configuration file for this platform (or the test fixture when redirected).
     *  Checking file existence without opening or parsing macOS MDM plists is intentionally conservative per
     *  `docs/specs/desktop-full.md`. */
    private val managedFile: Path?
        get() = (DesktopManagedPolicy.redirected?.let(::listOf) ?: managedPaths)
            .firstOrNull { Files.exists(it, LinkOption.NOFOLLOW_LINKS) }

    /** Why splice's configuration can't be put in now, or null when it can: a managed configuration exists (which
     *  decides Desktop's provider over any local one; only its existence is asked), the library holds a file splice
     *  can't read, or the library can't be written. */
    fun refusal(): String? {
        val activeManaged = managedFile
        return when {
            activeManaged != null -> "$activeManaged $MANAGED_DECIDES"
            !Files.isDirectory(config.library) -> files.unwritable(config.library)
            else -> unreadable(config.library) ?: files.unwritable(config.library)
        }
    }

    /** splice's configuration [id] put in and applied, pointing Desktop at [door] with the credential [helper]. [held]
     *  is what an earlier `on --app` recorded, kept while splice stays in; null puts splice in afresh. Answers what to
     *  record, and the name of the configuration splice now stands in for, if there is one. */
    fun putIn(id: String, door: String, helper: Path, held: DesktopAppRecord?): Pair<DesktopAppRecord, String?> {
        val made = !Files.isDirectory(config.library)
        val raw = files.text(config.meta)
        val meta = raw?.let(files::parsed)
        val applied = meta?.let(config::applied)
        val record = when {
            held == null -> DesktopAppRecord(applied, raw, made)
            applied != null && applied != id && applied != held.replacedApplied -> held.copy(replacedApplied = applied)
            else -> held
        }
        rewrite(id, door, helper)
        files.write(config.meta, config.withSplice(meta, id))
        syncProfileBestEffort()
        val replaced = record.replacedApplied?.let { other -> meta?.let { config.nameOf(it, other) } ?: other }
        return record to replaced
    }

    /** splice's configuration [id] taken out, as [held] recorded it was put in. */
    fun takeOut(id: String, held: DesktopAppRecord) {
        Files.deleteIfExists(config.file(id))
        files.read(config.meta)?.let { meta ->
            val out = config.withoutSplice(meta, id, held.replacedApplied)
            val before = held.metaBefore
            when {
                before != null && files.parsed(before) == out -> files.writeText(config.meta, before)
                before == null && config.empty(out) -> Files.deleteIfExists(config.meta)
                else -> files.write(config.meta, out)
            }
        }
        if (held.madeLibrary) files.removeIfEmpty(config.library)
    }

    /** Whether the library applies splice's configuration [id] now. */
    fun applies(id: String): Boolean = files.read(config.meta)?.let(config::applied) == id

    /** The configuration the library applies now, by its name, when it is not [id]; null when it applies [id] or
     *  none. */
    fun appliedOther(id: String?): String? {
        val meta = files.read(config.meta) ?: return null
        val applied = config.applied(meta)?.takeIf { it != id } ?: return null
        return config.nameOf(meta, applied) ?: applied
    }

    /** Whether a managed configuration exists, asked of its existence alone. */
    fun managed(): Boolean = managedFile != null

    /** The door address splice's configuration [id] gives Desktop, or null when it gives none. */
    fun doorOf(id: String): String? = files.read(config.file(id))?.let(config::door)

    /** The library of each Claude Desktop started on a config folder of its own beside this one
     *  (DesktopAppConfig.beside). */
    fun beside(): List<DesktopAppLibrary> =
        config.beside().map { DesktopAppLibrary(it, defaults, profileSync, it.managedFiles, overrides, log) }

    /** [models] written as the model list of the configuration this library applies, and splice's policy given to it
     *  where it says nothing (DesktopAppConfig.withPolicy), when that configuration points Desktop at [door]; whether
     *  anything was written. A library that applies none, or one that points elsewhere, is left as it is: this never
     *  applies a configuration, changes a key one sets only to the value the console holds for it, and never opens the
     *  managed file. Desktop reads it at its next launch. The console's policy values are given over what the
     *  configuration says, and each key of [reset], which the console no longer holds, goes back to splice's policy
     *  (DesktopPolicyLayers.forced). */
    fun publish(door: String, models: JsonArray? = null, reset: Set<String> = emptySet()): Boolean {
        val (file, current) = onDoor(door) ?: return false
        syncProfileBestEffort()
        val withModels = if (models != null) config.withModels(current, models) else current
        val updated = layers.forced(config.withPolicy(withModels, policy()), reset)
        return if (updated == current) {
            false
        } else {
            files.write(file, updated)
            true
        }
    }

    /** The configuration this library applies, its file and what it holds, when it is one of the library's own and
     *  points Desktop at [door]; null otherwise. */
    fun onDoor(door: String): Pair<Path, JsonObject>? {
        val id = files.read(config.meta)?.let(config::applied) ?: return null
        val file = config.file(id).normalize().takeIf { it.parent == config.library.normalize() } ?: return null
        return file.takeIf { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }
            ?.let(files::read)
            ?.takeIf { config.door(it)?.trimEnd('/') == door.trimEnd('/') }
            ?.let { file to it }
    }

    /** The gateway profile given what the person has in Claude Code and the 1P Desktop (DesktopProfileSync). It never
     *  stops splice's configuration going in, and a failure is said, with the profile, so a missing server or skill can
     *  be traced. */
    private fun syncProfileBestEffort() {
        val appData = config.library.parent ?: return
        Cancellables.runCatchingCancellable { profileSync.syncInto(appData) }.onFailure { failure ->
            val why = SafeFailureText.render(failure)
            log("[desktop] $appData was not given your MCP servers, extensions and skills ($why)\n")
        }
    }

    /** splice's configuration [id] written for [door] and [helper], with its policy for Desktop. */
    fun rewrite(id: String, door: String, helper: Path) =
        files.write(config.file(id), config.config(door, helper.toString(), policy()))

    /** splice's policy for Desktop now: the build's default, with what the person set up in Claude Code over it,
     *  and the values they set in the console over that. */
    private fun policy(): JsonObject = layers.effective()

    /** Why [library] can't be read: it can't be listed, a file in it can't be read, or its `_meta.json` holds no JSON
     *  object. Null when it can. */
    private fun unreadable(library: Path): String? {
        val listed = Cancellables.runCatchingCancellable { Files.list(library).use { it.toList() } }
            .getOrElse { return "$library can't be read (${SafeFailureText.render(it)})" }
        val unread = listed.firstOrNull { !Files.isReadable(it) }
        val meta = files.read(config.meta)
        return when {
            unread != null -> "$unread can't be read"
            Files.exists(config.meta) && meta == null -> "${config.meta} can't be read as a JSON object"
            else -> null
        }
    }
}

/** Test seam for redirecting Claude Desktop's managed configuration check away from the host's platform files
 *  ([DesktopAppConfig.managedFiles]). [within] lends the process a fixture path for one block so tests never
 *  depend on whether the machine running them carries a managed configuration of its own. */
internal object DesktopManagedPolicy {
    @Volatile
    var redirected: Path? = null

    /** [block] run with [fixture] as the managed configuration's path, then the platform's again. */
    inline fun <T> within(fixture: Path, block: () -> T): T {
        val previous = redirected
        redirected = fixture
        try {
            return block()
        } finally {
            redirected = previous
        }
    }
}

// Why a managed configuration refuses app mode, after its path.
private const val MANAGED_DECIDES = "exists: a managed configuration decides Claude Desktop's provider over any " +
    "local one, so splice's would not be used"
