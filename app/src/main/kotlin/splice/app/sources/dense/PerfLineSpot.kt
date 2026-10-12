// NEW: Oct 11, 2026 — where a retained perf line lies, so a compact row can be read back whole.
package splice.app.sources.dense

import java.nio.file.Path

/** One cached generation's current path. A rotation renames the file the generation stands for; every spot follows. */
internal data class PerfGenerationRef(var path: Path)

/** The bytes [start, end) of a generation's line. */
internal data class PerfLineSpot(val generation: PerfGenerationRef, val start: Long, val end: Long)
