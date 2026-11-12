package splice.app.observability

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import splice.app.sources.observability.PerfSourceCostSnapshot
import splice.core.perf.observation.PerfIdentity
import splice.core.perf.observation.PerfObservationBudget
import splice.core.perf.observation.PerfSinkStatus
import splice.core.util.PerfWorkBudget

/** Cheap scalars only. No accumulator generation, directory walk, queue flush, or observer feedback. */
internal class PerfInstrumentationStatus(
    private val identity: PerfIdentity,
    private val enabled: Boolean,
    private val read: PerfStatusRead,
) {
    fun status(): JsonObject {
        val lease = PerfWorkBudget.acquire(STATUS_BYTES) ?: return BUSY
        var retained = false
        return try {
            val sample = read.current()
            buildJsonObject {
                put("schema_version", 1)
                put("status", if (sample.issues.isEmpty()) "ok" else "partial")
                put("identity", Json.encodeToJsonElement(identity))
                put("process_id", ProcessHandle.current().pid())
                put("as_of_ms", System.currentTimeMillis())
                put("collection_mode", if (enabled) "local" else "minimal")
                put("memory", memory())
                put("sinks", Json.encodeToJsonElement(sample.sinks))
                put("issues", JsonArray(sample.issues.map(::JsonPrimitive)))
                put(
                    "sources",
                    buildJsonObject {
                        put("aggregate", Json.encodeToJsonElement(sample.aggregate))
                        put("evidence", Json.encodeToJsonElement(sample.evidence))
                    },
                )
                put("source_retained_bytes_scope", "process_shared_work_pool")
            }.also { lease.retain(it, STATUS_BYTES); retained = true }
        } finally {
            if (!retained) lease.close()
        }
    }

    private fun memory(): JsonObject = buildJsonObject {
        val observation = PerfObservationBudget.status()
        put(
            "observation",
            buildJsonObject {
                put("reserved_bytes", observation.reservedBytes)
                put("allocatable_limit_bytes", observation.allocatableLimitBytes)
                put("total_limit_bytes", observation.totalLimitBytes)
                put("snapshot_active", observation.snapshotActive)
                put("snapshot_reserve_bytes", observation.snapshotReserveBytes)
                put("headroom_bytes", observation.headroomBytes)
            },
        )
        put("work", workMemory())
        put(
            "owners",
            buildJsonObject {
                listOf("aggregate", "evidence", "cursor", "profile").forEach { owner ->
                    put(
                        owner,
                        buildJsonObject {
                            put("accounted_bytes", JsonNull)
                            put("reason", "per_owner_attribution_unavailable")
                        },
                    )
                }
            },
        )
    }

    private fun workMemory(): JsonObject {
        val snapshot = PerfWorkBudget.snapshot()
        return buildJsonObject {
            put("leased_bytes", snapshot.accounting.leasedBytes)
            put("limit_bytes", PerfWorkBudget.LIMIT_BYTES)
            put("accounting", accounting(snapshot.accounting))
            put(
                "last_refusal",
                snapshot.lastRefusal?.let { refusal ->
                    buildJsonObject {
                        put("requested_bytes", refusal.requestedBytes)
                        put("requested_kind", refusal.requestedKind.code)
                        put("accounting", accounting(refusal.accounting))
                    }
                } ?: JsonNull,
            )
        }
    }

    private fun accounting(value: PerfWorkBudget.Accounting): JsonObject = buildJsonObject {
        put("basis", "lease_accounting_not_heap")
        put("retained_ticket_liveness", "not_inspected_includes_pending_cleaner")
        put("leased_bytes", value.leasedBytes)
        put("unretained_reservation_bytes", value.unretainedReservationBytes)
        put("retained_ticket_bytes", value.retainedTicketBytes)
        put(
            "kinds",
            buildJsonObject {
                value.kinds.forEach { group ->
                    put(
                        group.kind.code,
                        buildJsonObject {
                            put("unretained_reservation_bytes", group.unretainedReservationBytes)
                            put("retained_ticket_bytes", group.retainedTicketBytes)
                        },
                    )
                }
            },
        )
    }
}

internal fun interface PerfStatusRead {
    fun current(): PerfStatusSnapshot
}

internal data class PerfStatusSnapshot(
    val sinks: List<PerfSinkStatus>,
    val aggregate: PerfSourceCostSnapshot,
    val evidence: PerfSourceCostSnapshot,
    val issues: List<String>,
)

// Reserve 128 KiB for an independent instrumentation-status tree until its response owner releases it.
private const val STATUS_BYTES = 128L * 1_024
private val BUSY = buildJsonObject {
    put("schema_version", 1)
    put("status", "busy")
    put("issues", JsonArray(listOf(JsonPrimitive("shared_work_budget"))))
}
