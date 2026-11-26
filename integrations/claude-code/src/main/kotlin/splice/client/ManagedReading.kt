// NEW: what splice can say of Claude Code's managed settings file, in explicit states, for the text that speaks of
// it (`splice desktop wrapper`, the doctor's managed settings row and the upgrade items): the file read, could not be
// read, or known to be absent; each key in it a string, a value that is not one, or absent; and the other places
// Claude Code can take managed settings from that splice did not read, split into those that hold something and those
// splice could not check. Nothing is called absent unless splice established it. ManagedSettings.reading builds it.
package splice.client

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.IOException
import java.io.UncheckedIOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path

/** What splice read of the platform's managed settings: the [file] in its [state], and the other places Claude Code can
 *  take managed settings from that it did not read ([unread]). A value set in one of those may be the one Claude Code
 *  takes, so the file alone says what reaches a session only where there are none. */
public data class ManagedReading(public val file: Path, public val state: ManagedFile, public val unread: UnreadPlaces)

/** Places Claude Code can take managed settings from that splice did not read, in words: those it found holding
 *  something ([held]), and those where it could not tell ([unsure]). They are found, never read for their keys. */
public data class UnreadPlaces(
    public val held: List<String> = emptyList(),
    public val unsure: List<String> = emptyList(),
) {
    /** Whether there is no such place. */
    public val none: Boolean get() = held.isEmpty() && unsure.isEmpty()

    public operator fun plus(other: UnreadPlaces): UnreadPlaces = UnreadPlaces(held + other.held, unsure + other.unsure)
}

/** The managed settings file as splice found it: [Read] as a JSON object; [Unreadable], where something may be at its
 *  path and splice could not read it (no permission on the path, a link to nothing, no JSON object), so splice does
 *  not know whether it exists; or [Absent], only where splice established that nothing at all is at its path. */
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

/** What splice can establish about a path without reading what it holds. */
internal object PathAbsence {
    /** Whether nothing at all is at [path]: it and each missing folder above it are absent, asked without following
     *  links, up to a folder that is there, is a folder, and can be listed. A link to nothing, or a folder splice
     *  cannot look into, establishes nothing. */
    fun known(path: Path): Boolean {
        val absolute = path.toAbsolutePath()
        if (!Files.notExists(absolute, NOFOLLOW_LINKS)) return false
        val above = generateSequence(absolute.parent) { it.parent }.firstOrNull { !Files.notExists(it, NOFOLLOW_LINKS) }
        return above != null && Files.isDirectory(above) && listed(above) != null
    }

    /** What [folder] holds, or null where it cannot be listed, a listing that fails part-way included. */
    fun listed(folder: Path): List<Path>? = try {
        Files.list(folder).use { it.toList() }
    } catch (_: IOException) {
        null
    } catch (_: UncheckedIOException) {
        null
    }

    /** [path] as a place splice did not read: held where something is there, nothing where nothing is known to be. */
    fun place(path: Path): UnreadPlaces = when {
        Files.exists(path, NOFOLLOW_LINKS) -> UnreadPlaces(held = listOf(path.toString()))
        known(path) -> UnreadPlaces()
        else -> UnreadPlaces(unsure = listOf(path.toString()))
    }
}
