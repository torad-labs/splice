// NEW: door-default (docs/specs/door-default.md). One record of the door's in splice's state folder, kept for its owner
// only: read once and kept in memory, and written whole and atomically before a change takes effect. A record splice
// can't read starts empty, said in the log. Claude Desktop's record (DesktopRecords) and the terminal lane's
// (ClaudeLaneRecords) are each one of these. Kept here and never beside the person's settings file, which is theirs.
package splice.app.desktop

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import splice.core.util.LogSink
import splice.core.util.SafeFailureText
import splice.core.util.SecureFile
import java.io.IOException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path

internal open class StateRecords<T>(
    private val file: Path,
    private val serializer: KSerializer<T>,
    private val empty: T,
    private val tag: String,
    private val log: LogSink,
) {
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    @Volatile
    private var current: T = read()

    fun get(): T = current

    /** [change] made to the record, written, then kept. Throws the disk's [IOException] with nothing changed. */
    @Synchronized
    fun update(change: RecordChange<T>): T {
        val next = change(current)
        if (next != current) SecureFile.writeAtomic0600(file, json.encodeToString(serializer, next))
        current = next
        return next
    }

    private fun read(): T = try {
        json.decodeFromString(serializer, Files.readString(file))
    } catch (_: NoSuchFileException) {
        empty
    } catch (e: IOException) {
        unread(e)
    } catch (e: SerializationException) {
        unread(e)
    }

    private fun unread(e: Exception): T {
        log("[$tag] ${file.fileName} could not be read, so it starts with no record: ${SafeFailureText.render(e)}\n")
        return empty
    }
}

/** One change to a record. */
internal fun interface RecordChange<T> {
    operator fun invoke(record: T): T
}
