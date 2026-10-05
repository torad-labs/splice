// NEW: selected-read pack descriptors close together, including partially failed opens.
package splice.head.trace.body

import splice.core.memory.HeapBudget
import splice.core.util.Cancellables
import splice.upstream.memory.JvmHeap
import java.io.IOException
import java.nio.file.Path

/** One header index per pack per selected read; channels and failure results never outlive that read. */
internal class TraceBodyReaders(private val heap: HeapBudget = JvmHeap.budget) : AutoCloseable {
    private val readers = LinkedHashMap<Path, Result<TraceBodyReader>>()

    fun of(file: Path, format: TracePackFormat): TraceBodyReader =
        readers.getOrPut(file) {
            Cancellables.runCatchingCancellable { TraceBodyReader(file, format, heap) }
        }.getOrThrow()

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
