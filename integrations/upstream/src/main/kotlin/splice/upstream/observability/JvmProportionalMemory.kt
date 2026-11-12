package splice.upstream.observability

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/** Linux rollup only: PSS is shared proportionally, never exclusive ownership or allocation. */
internal object JvmProportionalMemory {
    fun bytes(): Long? = try {
        Files.newBufferedReader(Path.of("/proc/self/smaps_rollup")).use { reader ->
            reader.lineSequence().take(MAX_ROLLUP_LINES).firstOrNull { it.startsWith("Pss:") }
                ?.removePrefix("Pss:")?.trim()?.substringBefore(' ')?.toLongOrNull()?.times(BYTES_PER_KIB)
        }
    } catch (_: IOException) {
        null
    } catch (_: SecurityException) {
        null
    }
}

// Read at most sixty-four procfs rollup lines while locating Pss so the sampler's textual scan remains bounded.
private const val MAX_ROLLUP_LINES = 64

// Linux smaps_rollup reports Pss in binary kilobytes, so multiply by 1,024 to expose bytes.
private const val BYTES_PER_KIB = 1_024L
