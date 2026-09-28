// NEW: V4-368 — guarded per-head inventory and deletion of retained trace day files.
package splice.head.wire

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.config.ConfigService
import splice.core.storage.DayFiles
import splice.core.storage.DayInventory
import splice.core.util.Cancellables
import splice.core.util.SafeFailureText
import splice.head.TurnsHeadLookup
import splice.head.trace.TRACE_DELETED_REASON
import splice.head.trace.TRACE_DELETED_STATE
import splice.head.trace.TraceDirPort
import splice.http.JsonReply
import java.nio.file.Path

private const val TRACE_KEEP_UNWIRED = "the daemon wired no trace directory; kept trace cannot be read or deleted"

/** One configured head's private day files, whether its capture switch currently runs or not. */
public class TraceDeleteRoutes(
    private val heads: TurnsHeadLookup,
    private val dir: TraceDirPort,
    private val config: ConfigService,
) {
    /** Count each real record and byte before the operator decides to delete. */
    public fun kept(head: String): JsonReply = answer(head, delete = false)

    /** Delete on the file lane after earlier writes; other heads and non-day entries are untouched. */
    public fun delete(head: String): JsonReply = answer(head, delete = true)

    private fun answer(head: String, delete: Boolean): JsonReply {
        val key = heads.byName(head).firstOrNull()?.key
            ?: return refuse(HttpStatusCode.BadRequest, "unknown head: $head")
        val traceDir = dir() ?: return refuse(HttpStatusCode.ServiceUnavailable, TRACE_KEEP_UNWIRED)
        return Cancellables.runCatchingCancellable { read(key, traceDir, delete) }
            .fold(
                onSuccess = { JsonReply(HttpStatusCode.OK, it) },
                onFailure = { failure ->
                    refuse(
                        HttpStatusCode.InternalServerError,
                        "cannot read or delete $key's trace under $traceDir: ${SafeFailureText.render(failure)}",
                    )
                },
            )
    }

    private fun read(key: String, traceDir: Path, delete: Boolean): String {
        val files = DayFiles(traceDir, key, ownerOnly = true)
        val retention = config.getConfig(key).traceRetentionDays
        val inventory = if (delete) files.deleteKept(retention) else files.inventory(retention)
        return inventoryJson(key, inventory, files.deleted())
    }

    private fun inventoryJson(key: String, inventory: DayInventory, deleted: Boolean): String = buildJsonObject {
        put("head", key)
        val state = when {
            deleted -> TRACE_DELETED_STATE
            inventory.days > 0 -> "kept"
            else -> "empty"
        }
        put("state", state)
        if (deleted) put("reason", TRACE_DELETED_REASON)
        put("days", inventory.days)
        put("records", inventory.rows)
        put("bytes", inventory.bytes)
        put("oldest", inventory.oldest?.toString())
        put("ages_out", inventory.agesOut?.toString())
    }.toString()

    private fun refuse(status: HttpStatusCode, message: String): JsonReply =
        JsonReply(status, buildJsonObject { put("error", message) }.toString())
}
