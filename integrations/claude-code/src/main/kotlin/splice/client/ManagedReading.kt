// NEW: what splice can say of Claude Code's managed settings files, in explicit states, for the text that speaks of
// them (`splice desktop wrapper`, the doctor's managed settings row and the upgrade items): the main file read, could
// not be read, or known to be absent; for each key, the file whose value the merged files give, and that value as a
// string or a value that is not one; and the places splice could not read, named. Nothing is called absent unless
// splice established it. ManagedSettings.reading builds it.
package splice.client

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.IOException
import java.io.UncheckedIOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path

/** What splice read of the platform's managed settings: the main [file] in its [state]; the files it [read], in the
 *  order Claude Code merges them (the main file, then each drop-in in filename order); and the places Claude Code may
 *  take managed settings from that splice could not read ([unread], in words). A value set in one of those may be the
 *  one Claude Code takes. */
public data class ManagedReading(
    public val file: Path,
    public val state: ManagedFile,
    public val read: List<Pair<Path, JsonObject>>,
    public val unread: List<String>,
) {
    /** The file whose value for [key] the merged files give, the last one read that sets it; null where none does. */
    public fun held(key: String): ManagedHeld? = read.lastOrNull { key in it.second }
        ?.let { (path, settings) -> ManagedHeld(path, ManagedFile.Read(settings).value(key)) }
}

/** The [file] that holds a key, and its [value] there: [ManagedValue.Text] or [ManagedValue.NotText]. */
public data class ManagedHeld(public val file: Path, public val value: ManagedValue)

/** The managed settings file as splice found it: [Read] as a JSON object; [Unreadable], where something may be at its
 *  path and splice could not read it (no permission on the path, a link to nothing, no JSON object), so splice does
 *  not know whether it exists; [Unlistable], where the file itself is absent but an ancestor [folder] could not be
 *  listed so sibling contents and drop-ins were not seen; or [Absent], only where splice established that nothing at
 *  all is at its path. */
public sealed class ManagedFile {
    /** The file, read: its [settings]. */
    public data class Read(public val settings: JsonObject) : ManagedFile() {
        /** What the file holds for [key]. */
        public fun value(key: String): ManagedValue {
            val found = settings[key] ?: return ManagedValue.Absent
            val text = found is JsonPrimitive && found.isString
            return if (text) ManagedValue.Text(found.content) else ManagedValue.NotText
        }
    }

    /** Something may be at the path, and splice could not read it. */
    public data object Unreadable : ManagedFile()

    /** The file is absent, but its ancestor [folder] could not be listed. */
    public data class Unlistable(public val folder: Path) : ManagedFile()

    /** Nothing is at the path. */
    public data object Absent : ManagedFile()
}

/** What a managed settings file holds for one key: a string ([Text]); a value of any other kind, `null` included
 *  ([NotText]), whose contents splice never says; or nothing ([Absent]). */
public sealed class ManagedValue {
    /** A string, [text]. */
    public data class Text(public val text: String) : ManagedValue()

    /** A value that is not a string. */
    public data object NotText : ManagedValue()

    /** The key is not in the file. */
    public data object Absent : ManagedValue()
}

/** What splice can establish about a path it reads. */
internal object PathAbsence {
    /** Whether nothing at all is at [path]: it and each missing folder above it are absent, asked without following
     *  links, up to the first thing that is there, which must be a folder splice can list. A link to nothing, or a
     *  folder that can be entered without being listed, establishes nothing. */
    fun known(path: Path): Boolean {
        val above = missingBelow(path) ?: return false
        return listable(above)
    }

    /** The first existing ancestor directory above an absent [path] when that directory cannot be listed (for example
     *  mode `--x--x--x`); null when [path] is not established as missing or its ancestor directory can be listed. */
    fun unlistableAncestor(path: Path): Path? {
        val above = missingBelow(path) ?: return null
        return above.takeUnless(::listable)
    }

    /** Classifies the main managed settings [file] given its already-parsed [main] object (or null). */
    fun mainState(file: Path, main: JsonObject?): ManagedFile = when {
        known(file) -> ManagedFile.Absent
        main != null -> ManagedFile.Read(main)
        else -> unlistableAncestor(file)?.let(ManagedFile::Unlistable) ?: ManagedFile.Unreadable
    }

    /** Lists [folder] when it exists or is established absent; when it cannot be listed (or sits under an unlistable
     *  ancestor), returns null paired with the unread description naming that folder. */
    fun dropInListing(folder: Path): Pair<List<Path>?, String?> {
        val unlistable = unlistableAncestor(folder)
        val listed = when {
            known(folder) -> emptyList()
            unlistable != null -> null
            else -> listed(folder)
        }
        val unread = when {
            listed != null -> null
            unlistable != null -> "$unlistable (folder could not be listed)"
            else -> folder.toString()
        }
        return listed to unread
    }

    /** What [folder] holds, or null where it cannot be listed, a listing that fails part-way included. */
    fun listed(folder: Path): List<Path>? = try {
        Files.list(folder).use { it.toList() }
    } catch (_: IOException) {
        null
    } catch (_: UncheckedIOException) {
        null
    }

    /** The first existing ancestor of [path] when [path] itself does not exist (without following links) and that
     *  ancestor is a directory; null otherwise. */
    private fun missingBelow(path: Path): Path? {
        val absolute = path.toAbsolutePath()
        if (!Files.notExists(absolute, NOFOLLOW_LINKS)) return null
        val above = generateSequence(absolute.parent) { it.parent }.firstOrNull { !Files.notExists(it, NOFOLLOW_LINKS) }
        return above?.takeIf { Files.isDirectory(it) }
    }

    /** Whether [folder] can be listed at all: an execute-only (`--x`) folder answers `ENOENT` to a lookup by name
     *  without letting splice see its other contents, drop-ins included. */
    private fun listable(folder: Path): Boolean = try {
        Files.list(folder).use { it.findFirst() }
        true
    } catch (_: IOException) {
        false
    } catch (_: UncheckedIOException) {
        false
    }
}
