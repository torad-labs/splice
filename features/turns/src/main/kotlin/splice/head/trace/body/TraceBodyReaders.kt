// NEW: selected-read pack descriptors close together, including partially failed opens.
package splice.head.trace.body

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import splice.core.memory.HeapReservations
import splice.core.storage.DayBodyBudget
import splice.core.util.Cancellables
import splice.core.util.JsonScalars
import splice.upstream.memory.JvmHeap
import java.io.IOException
import java.io.Reader
import java.io.StringReader
import java.nio.file.Path

/** One header index per pack per selected read; channels and failure results never outlive that read. */
internal class TraceBodyReaders(private val heap: HeapReservations = JvmHeap.budget) : AutoCloseable {
    private val readers = LinkedHashMap<Path, Result<TraceBodyReader>>()

    fun of(file: Path, format: TracePackFormat): TraceBodyReader =
        readers.getOrPut(file) {
            Cancellables.runCatchingCancellable { TraceBodyReader(file, format, heap) }
        }.getOrThrow()

    /** Inline legacy text stays exact; packed content streams without hydrating any sibling body. */
    fun open(value: JsonElement, day: Path): Reader? = if (value is JsonObject) {
        packed(value, day)
    } else {
        JsonScalars.str(value)?.let(::StringReader)
    }

    private fun packed(reference: JsonObject, day: Path): Reader? {
        val format = TracePackFormat.entries.firstOrNull { it.names(reference) }
            ?: throw IOException("invalid trace body chunk version")
        if (JsonScalars.str(reference, "unavailable") == "true" || DayBodyBudget().evicted(day)) return null
        val parts = reference["parts"] as? JsonArray ?: throw IOException("invalid trace body chunk parts")
        return of(format.pack(day), format).open(parts)
    }

    /** Keep pack indexes and cached open failures, but stop owning the last record's hydrated bodies. */
    fun clearLiterals() {
        readers.values.forEach { it.getOrNull()?.clearLiterals() }
    }

    override fun close() {
        var failure: IOException? = null
        readers.values.forEach { result ->
            try {
                result.getOrNull()?.close()
            } catch (caught: IOException) {
                if (failure == null) failure = caught else failure.addSuppressed(caught)
            }
        }
        readers.clear()
        failure?.let { throw it }
    }
}
