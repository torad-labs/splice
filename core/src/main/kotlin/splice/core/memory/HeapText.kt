// NEW: reserve whole-file decoding peaks before a reader allocates its first body byte.
package splice.core.memory

import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path

// why: wire bytes, decoder buffers, UTF-16 text, JSON nodes and the decoded owner coexist.
private const val DECODE_EXPANSION = 16L

// why: stream, decoder and decoded-string metadata exist even for an empty counted span.
private const val TEXT_METADATA_BYTES = 256L

/** A charged decoding stage. Adopt its result before closing the stage. */
public class HeapText private constructor(
    public val text: String,
    private val lease: HeapLease,
) : AutoCloseable {
    public fun retain(owner: Any) {
        HeapOwners.keep(owner, lease.share())
    }

    /** Keep only the adopted graph's charge; the rest remains charged until this stage closes. */
    public fun retain(owner: Any, bytes: Long) {
        HeapOwners.keep(owner, lease.split(bytes))
    }

    override fun close() {
        lease.close()
    }

    public object Reader {
        /** A growing file is refused rather than read past its reserved size. */
        public fun read(path: Path, heap: HeapBudget): HeapText =
            Files.newInputStream(path).use { read(it, boundedSize(path), heap) }

        /** Reads one counted span without closing its caller-owned stream. */
        public fun read(input: InputStream, bytes: Long, heap: HeapBudget): HeapText {
            require(bytes >= 0L && bytes < Int.MAX_VALUE)
            val weight = HeapWeights.multiply(bytes + TEXT_METADATA_BYTES, DECODE_EXPANSION)
            val lease = heap.reserve(weight) ?: throw HeapCapacityException()
            var adopted = false
            try {
                val wire = input.readNBytes(bytes.toInt() + 1)
                if (wire.size.toLong() > bytes) throw HeapCapacityException()
                return HeapText(wire.toString(Charsets.UTF_8), lease).also { adopted = true }
            } finally {
                if (!adopted) lease.close()
            }
        }

        private fun boundedSize(path: Path): Long = Files.size(path).also {
            if (it >= Int.MAX_VALUE) throw HeapCapacityException()
        }
    }
}
