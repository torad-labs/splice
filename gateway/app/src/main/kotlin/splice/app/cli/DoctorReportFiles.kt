// NEW: v0.4.0 FEATURES.md §6 — how the doctor report reads a rotated file: a BOUNDED tail of the
// rotated generation (.1) then of the active file, through the repo's one tail reader
// (JsonlSink.readTail), never a whole 64 MiB generation; an absent file is simply empty, any other
// failure is reported (rendered through DoctorRedaction), never swallowed. Shared by the perf tail
// and the log tail (split from DoctorReportTail.kt, concentration, 2026-09-13).
package splice.app.cli

import splice.core.util.Cancellables
import splice.core.util.JsonlSink
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path

/** What the tails yielded: their lines, or the reason a generation could not be read (absence is empty). */
internal data class FileLines(val lines: List<String>, val error: String?)

internal class DoctorReportFiles(private val redaction: DoctorRedaction) {

    /** The last [maxBytes] of each generation, older first. The read is attempted, never predicted:
     *  a genuinely absent generation (nothing there, not even a dangling link) is quiet, a dangling
     *  symlink or any other failure is reported under the generation's FIXED label (the file name is
     *  head-derived and never printed here). */
    fun tails(file: Path, maxBytes: Int): FileLines {
        val errors = mutableListOf<String>()
        val generations = listOf("rotated" to file.resolveSibling("${file.fileName}.1"), "active" to file)
        val lines = generations.flatMap { (label, generation) ->
            Cancellables.runCatchingCancellable { JsonlSink.readTail(generation, maxBytes).filter { it.isNotEmpty() } }
                .getOrElse { e ->
                    failure(label, generation, e)?.let { errors += it }
                    emptyList()
                }
        }
        return FileLines(lines, errors.takeIf { it.isNotEmpty() }?.joinToString("; "))
    }

    private fun failure(label: String, generation: Path, e: Throwable): String? = when {
        e !is NoSuchFileException -> "$label: ${redaction.failure(e)}"
        Files.exists(generation, LinkOption.NOFOLLOW_LINKS) -> "$label: dangling symlink"
        else -> null
    }
}
