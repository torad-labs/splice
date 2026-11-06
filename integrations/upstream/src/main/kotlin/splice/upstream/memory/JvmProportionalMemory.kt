package splice.upstream.memory

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

private const val MAX_ROLLUP_LINES = 64
private const val BYTES_PER_KIB = 1_024L
