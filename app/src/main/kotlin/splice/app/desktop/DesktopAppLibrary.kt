// NEW: desktop-full (docs/specs/desktop-full.md, df-app-on). splice's configuration in Claude Desktop's 3P library
// (DesktopAppConfig), put in by `splice desktop on` and taken out by `off` and `splice uninstall`.
// Putting it in writes splice's own `<id>.json` and its `_meta.json` entry, applied, and leaves every other file and
// entry as it was; what `_meta.json` held before is kept in splice's desktop record (DesktopAppRecord). Taking it out
// removes both and applies again what splice replaced, unless the person applied another configuration meanwhile, in
// which case their choice stays; a `_meta.json` that splice found is written back byte for byte where nothing else in
// it changed, and one splice made is removed, with the folder when splice made it and it is empty. splice never opens
// the managed file, the keyring or any of Desktop's encrypted stores.
package splice.app.desktop

import splice.client.desktop.DesktopAppConfig
import splice.core.util.Cancellables
import splice.core.util.SafeFailureText
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

internal class DesktopAppLibrary(private val config: DesktopAppConfig = DesktopAppConfig()) {
    private val files = DesktopLibraryFiles()

    /** Why splice's configuration can't be put in now, or null when it can: a managed configuration exists (which
     *  decides Desktop's provider over any local one; only its existence is asked), the library holds a file splice
     *  can't read, or the library can't be written. */
    fun refusal(): String? = when {
        managed() -> "${config.managed} $MANAGED_DECIDES"
        !Files.isDirectory(config.library) -> files.unwritable(config.library)
        else -> unreadable(config.library) ?: files.unwritable(config.library)
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
    fun managed(): Boolean = Files.exists(config.managed, LinkOption.NOFOLLOW_LINKS)

    /** The door address splice's configuration [id] gives Desktop, or null when it gives none. */
    fun doorOf(id: String): String? = files.read(config.file(id))?.let(config::door)

    /** splice's configuration [id] written for [door] and [helper]. */
    fun rewrite(id: String, door: String, helper: Path) =
        files.write(config.file(id), config.config(door, helper.toString()))

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

// Why a managed configuration refuses app mode, after its path.
private const val MANAGED_DECIDES = "exists: a managed configuration decides Claude Desktop's provider over any " +
    "local one, so splice's would not be used"
