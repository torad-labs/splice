package splice.control.mcp.hub.records

import kotlinx.serialization.json.JsonObject
import splice.control.mcp.hub.HubFields
import splice.core.util.JsonScalars

/** Migrates only a baseline record whose live process proves its executable, birth, scope and child. */
internal class HubLegacyRecords {
    fun claim(record: HubHolderRecord, scope: String): HubHolderScopeClaim? =
        record.takeIf { legacy(it.body) }?.let { HubHolderScopeClaim(scope, scope) }

    private fun legacy(body: JsonObject): Boolean =
        JsonScalars.str(body, HubFields.GENERATION) == null &&
            JsonScalars.str(body, HubFields.SCOPE) == null &&
            JsonScalars.long(body, HubFields.PID) != null &&
            JsonScalars.long(body, HubFields.AT) != null
}
