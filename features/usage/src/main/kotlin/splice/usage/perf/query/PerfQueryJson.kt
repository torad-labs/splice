package splice.usage.perf.query

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.perf.observation.PerfCoverage
import splice.core.perf.observation.PerfIdentity
import splice.core.perf.observation.PerfIssue
import splice.core.perf.observation.PerfScope

internal object PerfQueryJson {
    fun unavailableWorkload(): JsonObject = buildJsonObject {
        put("status", "unavailable")
        put("population_complete", false)
        put("distribution_id", textValue(null))
        put("reasons", strings(listOf("canonical_request_evidence_unavailable")))
    }

    fun queryError(code: String, reason: String, status: String = "error"): JsonObject = buildJsonObject {
        put("schema_version", 1)
        put("contract_version", 1)
        put("status", status)
        put("error", buildJsonObject { put("code", code); put("reason", reason) })
    }

    fun strings(values: Iterable<String>): JsonArray = JsonArray(values.map(::JsonPrimitive))
    fun numeric(value: Number?): JsonElement = value?.let(::JsonPrimitive) ?: JsonNull
    fun textValue(value: String?): JsonElement = value?.let(::JsonPrimitive) ?: JsonNull
    fun queryJson(query: PerfQuery): JsonObject = buildJsonObject {
        put("from_ms", query.fromMs)
        put("until_ms", query.untilMs)
        put("source", query.source)
        put("objective_id", textValue(query.objectiveId ?: query.objective?.id))
        put("objective_revision", textValue(query.objective?.revision))
        put("filters", JsonObject(query.filters.mapValues { JsonPrimitive(it.value) }))
    }

    fun scopeJson(scope: PerfScope): JsonObject = buildJsonObject {
        put("head", scope.head)
        put("model", textValue(scope.model))
        put("transport", textValue(scope.transport))
        put("work", scope.work)
        put("outcome", textValue(scope.outcome))
        put("local", scope.local?.let(::JsonPrimitive) ?: JsonNull)
        put("compact", scope.compact?.let(::JsonPrimitive) ?: JsonNull)
        put("streaming", scope.streaming?.let(::JsonPrimitive) ?: JsonNull)
    }

    fun identityJson(identity: PerfIdentity): JsonObject = buildJsonObject {
        put("boot_id", identity.bootId)
        put("build", identity.build)
        put("version", identity.version)
        put("schema_version", identity.schemaVersion)
        put("boot_config_revision", identity.configRevision)
        put("effective_config_revision", JsonNull)
        put("configuration_status", "unknown")
    }

    fun coverageJson(coverage: PerfCoverage, complete: Boolean): JsonObject = buildJsonObject {
        put("from_ms", coverage.requestedFromMs)
        put("until_ms", coverage.requestedUntilMs)
        put("observed_ms", coverage.observedMs)
        put("complete", complete)
        put("issues", JsonArray(coverage.issues.map(::issueJson)))
        put("sinks", Json.parseToJsonElement(Json.encodeToString(coverage.sinks)))
    }

    fun issueJson(issue: PerfIssue): JsonObject = buildJsonObject {
        put("code", issue.code)
        put("source", issue.source)
        put("count", issue.count)
        // Source messages may contain paths; stable codes are enough for machine output.
    }

    fun envelope(query: PerfQuery, data: QueryData, payload: JsonObject): JsonObject = buildJsonObject {
        put("schema_version", 1)
        put("contract_version", 1)
        put("status", data.status())
        put("query", queryJson(query))
        put("as_of_ms", data.asOf)
        put("settled_until_ms", numeric(data.settledUntilMs))
        put("collection_mode", textValue(data.collectionMode))
        put("requested_duration_ms", query.untilMs - query.fromMs)
        put("observed_duration_ms", data.coverage.observedMs)
        put("identity_cohorts", JsonArray(data.identities.map(::identityJson)))
        put("configuration", data.configuration)
        put("coverage", coverageJson(data.coverage, data.complete))
        put("population_coverage", JsonObject(data.populationCoverage))
        put("counts", data.counts)
        put("missing_fields", strings(data.missing))
        put("resolution_ms", data.resolution)
        put("truncated", data.truncated)
        payload.forEach { (key, value) -> put(key, value) }
    }
}
