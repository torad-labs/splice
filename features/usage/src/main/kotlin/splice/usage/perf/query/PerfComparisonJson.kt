package splice.usage.perf.query

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.util.JsonScalars
import splice.usage.perf.query.PerfQueryJson.strings
import splice.usage.perf.query.PerfQueryJson.textValue

internal object PerfComparisonJson {
    fun render(query: PerfQuery, data: QueryData): JsonObject {
        val reasons = (data.workload["reasons"] as? JsonArray).orEmpty().toMutableList()
        val configKnown = JsonScalars.str(data.configuration, "comparison_compatible") == "true"
        if (!configKnown) {
            reasons.add(
                kotlinx.serialization.json.JsonPrimitive("effective_configuration_unknown_or_mixed"),
            )
        }
        val scopeKnown = JsonScalars.str(data.configuration, "comparison_scope") == "effective_knob_policy"
        if (!scopeKnown) reasons.add(kotlinx.serialization.json.JsonPrimitive("configuration_scope_unknown"))
        if (data.identities.map { it.build }.distinct().size != 1) {
            reasons.add(kotlinx.serialization.json.JsonPrimitive("build_cohort_unknown_or_mixed"))
        }
        if (data.identities.any { it.build in setOf("", "unknown") }) {
            reasons.add(kotlinx.serialization.json.JsonPrimitive("build_identity_unknown"))
        }
        if (data.coverage.issues.any { it.source == "accounts" }) {
            reasons.add(kotlinx.serialization.json.JsonPrimitive("account_mapping_unavailable"))
        }
        val ready = reasons.isEmpty() && JsonScalars.str(data.workload, "status") == "complete"
        return buildJsonObject {
            put("status", if (ready) "ready" else "insufficient_evidence")
            put("readiness_scope", "measured_workload_and_effective_knob_policy_only")
            put("reasons", JsonArray(reasons))
            put("workload", data.workload)
            put("measurement_semantics", semantics())
            put("provenance", provenance(query, data))
            put(
                "environment",
                buildJsonObject {
                    put("provider_topology", "unknown")
                    put("remote_environment", "unknown")
                    put("causal_equivalence", "not_established")
                },
            )
        }
    }

    private fun provenance(query: PerfQuery, data: QueryData): JsonObject = buildJsonObject {
        put("builds", strings(data.identities.map { it.build }.distinct().sorted()))
        put("versions", strings(data.identities.map { it.version }.distinct().sorted()))
        put(
            "schema_versions",
            JsonArray(
                data.identities.map {
                    kotlinx.serialization.json.JsonPrimitive(it.schemaVersion)
                }.distinct(),
            ),
        )
        put("source", query.source)
        put("objective_id", textValue(query.objectiveId ?: query.objective?.id))
        put("objective_revision", textValue(query.objective?.revision))
        put("configuration_status", data.configuration["status"] ?: textValue(null))
        put("configuration_scope", data.configuration["comparison_scope"] ?: textValue(null))
        put("query", PerfQueryJson.queryJson(query))
        put("as_of_ms", data.asOf)
        put("settled_until_ms", PerfQueryJson.numeric(data.settledUntilMs))
    }

    fun semantics(): JsonObject = buildJsonObject {
        put("population", "arrivals_in_requested_window_followed_through_as_of")
        put("filter_attribution", "request_scope_resolved_at_terminal_or_latest_checkpoint")
        put(
            "input_tokens",
            "canonical_inclusive_input_across_owned_rounds; includes_cache_read_and_write; not_prompt_length",
        )
        put("output_tokens", "canonical_output_including_reasoning_and_absorbed_output_once")
        put(
            "cache_write_1h_tokens",
            "subset_of_cache_write_tokens; requires_independent_measurement_presence_" +
                "even_when_billing_defaults_to_zero",
        )
        put("rounds", "absorbed_totals_do_not_identify_individual_round_sizes; cut_rounds_have_missing_usage")
        put("matching_basis", MATCHING_BASIS)
        put(
            "distribution_id",
            "sha256_of_sorted_exact_joint_facts_and_integer_frequencies_reduced_by_gcd; counts_remain_explicit",
        )
        put("scope", "head_model_transport_work_local_compact_streaming_and_actual_terminal_outcome")
        put(
            "presence",
            "requires_usage_observed_input_output_cache_read_cache_write_cache_write_1h; " +
                "billing_defaults_are_not_measurements",
        )
        put("assurance", "matching_workload_and_knob_revision_do_not_prove_provider_topology_or_causal_equivalence")
    }
}
