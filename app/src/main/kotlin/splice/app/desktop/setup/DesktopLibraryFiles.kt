// NEW: desktop-full (docs/specs/desktop-full.md, df-app-on). How splice reads and writes the files of Claude Desktop's
// configuration library (DesktopAppLibrary): JSON read as objects, written whole or not at all, owner-only where splice
// makes the file and at its own mode where Desktop or the person made it; and whether a folder can be written.
package splice.app.desktop.setup

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import splice.core.util.JsonScalars
import splice.core.util.SecureFile
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

internal class DesktopLibraryFiles {
    private val json = Json { prettyPrint = true }

    /** [file]'s text, or null when there is no such file. */
    fun text(file: Path): String? = file.takeIf(Files::exists)?.let(Files::readString)

    /** [file] read as a JSON object; null when there is no such file or it holds no JSON object. */
    fun read(file: Path): JsonObject? = text(file)?.let(::parsed)

    /** [file] read as the JSON object splice is to update: an empty one where there is no such file. A file that is
     *  there but is a link, not a regular file, or holds no JSON object is refused, so splice never writes over what
     *  the person or Desktop has there. */
    fun updatable(file: Path): JsonObject {
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return JsonObject(emptyMap())
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw IOException("$file is not a regular file")
        return read(file) ?: throw IOException("$file holds no JSON object; it was left as it is")
    }

    /** [text] read as a JSON object, or null. */
    fun parsed(text: String): JsonObject? = JsonScalars.objectOrNull(Json, text)

    /** [value] at [file], as splice writes a library file. */
    fun write(file: Path, value: JsonObject) =
        writeText(file, json.encodeToString(JsonObject.serializer(), value) + "\n")

    /** [text] at [file], whole or not at all: owner-only where the file is new, at its own mode where it is not. */
    fun writeText(file: Path, text: String) {
        if (Files.exists(file)) {
            SecureFile.writeAtomic(file, text, Files.getPosixFilePermissions(file))
        } else {
            SecureFile.writeAtomic0600(file, text)
        }
    }

    /** [dir] taken away when it is a folder with nothing in it. */
    fun removeIfEmpty(dir: Path) {
        if (!Files.isDirectory(dir)) return
        val empty = Files.list(dir).use { it.findFirst().isEmpty }
        if (empty) Files.delete(dir)
    }

    /** Why [path] can't be written, or null when it can; a path that does not exist yet is judged by the nearest
     *  folder above it that does, which it would be made under. */
    fun unwritable(path: Path): String? {
        var at = path.toAbsolutePath()
        while (!Files.exists(at)) at = at.parent ?: break
        return "$at can't be written".takeUnless { Files.isWritable(at) }
    }
}
