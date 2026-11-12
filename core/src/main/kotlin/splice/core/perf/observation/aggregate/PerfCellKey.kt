package splice.core.perf.observation.aggregate

import splice.core.perf.observation.PerfScope

internal data class PerfCellKey(val metric: String, val scope: PerfScope, val minute: Long)

internal data class PerfSeriesKey(val metric: String, val scope: PerfScope)
