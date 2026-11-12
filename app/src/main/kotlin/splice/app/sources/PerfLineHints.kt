// NEW: Oct 11, 2026 — what a raw perf line says before it is parsed: an empty or named model, and the drops counter.
// Split from PerfRowsFileSource, which only asks.
package splice.app.sources

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import splice.core.perf.PerfKeys

internal object PerfLineHints {
    /** The writer's own row shape: ts first, unquoted. Only a rejection hint, never row authority. */
    val ownRow = Regex("""^\{"ts":(\d+)[,}]""")

    fun dropsIn(obj: JsonObject): Long? = (obj[PerfKeys.ASYNC_IO_DROPS] as? JsonPrimitive)?.longOrNull

    fun tsIn(obj: JsonObject): Long? = (obj["ts"] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull

    /** The writer's row start with a model that is not empty: ts, model and outcome come first in every released
     *  shape, and a legacy probe row has the empty model its body gives (v0.3.0 to v0.3.2). */
    private val namedModel = Regex("""^\{"ts":\d+,"model":"[^"]""")

    /** A skipped line that carries the cumulative drops counter (any JSON spacing): a candidate. */
    private val dropsField = Regex(""""${PerfKeys.ASYNC_IO_DROPS}"\s*:\s*-?\d""")

    // Only empty-model candidates pay for parsing before the cutoff; JSON remains the authority.
    private val emptyModel = Regex(""""model"\s*:\s*""""")

    fun carriesDrops(line: String): Boolean = dropsField.containsMatchIn(line)

    fun hasEmptyModel(line: String): Boolean = emptyModel.containsMatchIn(line)

    fun dropsOf(line: String, count: Long?): PerfDropsHint = PerfDropsHint(carriesDrops(line), count)

    /** [modelOf] for a line the streaming reader decoded: that reader refuses an empty model, so only the anchored
     *  start is asked and the whole line is not searched again (jfr on desk d, Oct 12: a tenth of each decode). */
    fun decodedModelOf(line: String): PerfModelHint =
        if (namedModel.containsMatchIn(line)) PerfModelHint.NAMED else PerfModelHint.UNSEEN

    fun modelOf(line: String): PerfModelHint = when {
        emptyModel.containsMatchIn(line) -> PerfModelHint.EMPTY
        namedModel.containsMatchIn(line) -> PerfModelHint.NAMED
        else -> PerfModelHint.UNSEEN
    }
}
