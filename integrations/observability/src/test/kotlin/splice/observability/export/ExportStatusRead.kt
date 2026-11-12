package splice.observability.export

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import splice.core.util.PerfWorkBudget

/** Test consumers follow the same explicit returned-status ownership contract as app composition. */
internal class ExportStatusRead(exports: PerfExports) : AutoCloseable {
    val value: JsonObject = exports.status()
    val coverage: JsonObject get() = value.getValue("coverage").jsonObject

    override fun close() = PerfWorkBudget.release(value)
}
