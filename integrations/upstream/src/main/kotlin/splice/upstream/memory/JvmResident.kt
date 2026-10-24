// NEW: read this process's Linux RSS only; never inspect environment, command line or another process.
package splice.upstream.memory

import java.io.BufferedReader
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

// why: /proc/self/status is a small fixed kernel record, and diagnostics stop after a bounded number of lines.
private const val RESIDENT_STATUS_LINE_LIMIT = 256

// why: Linux VmRSS reports binary kibibytes as kB; converting to bytes needs 1024, not decimal 1000.
private const val RESIDENT_KIB_BYTES = 1024L

internal object JvmResident {
    private val rss = Regex("""^VmRSS:\s*(\d+)\s+kB\s*$""")

    fun bytes(): Long? = try {
        if (System.getProperty("os.name") != "Linux") {
            null
        } else {
            Files.newBufferedReader(Path.of("/proc/self/status")).use(::read)
        }
    } catch (_: IOException) {
        null
    } catch (_: SecurityException) {
        null
    }

    private fun read(reader: BufferedReader): Long? {
        repeat(RESIDENT_STATUS_LINE_LIMIT) {
            val line = reader.readLine() ?: return null
            if (line.startsWith("VmRSS:")) return parse(line)
        }
        return null
    }

    private fun parse(line: String): Long? = rss.matchEntire(line)?.groupValues?.get(1)?.toLongOrNull()
        ?.takeIf { it <= Long.MAX_VALUE / RESIDENT_KIB_BYTES }?.times(RESIDENT_KIB_BYTES)
}
