// NEW (v0.4.0, FEATURES.md §6): the topology SHAPE in the doctor report — provider kinds, dialects,
// model ids and windows, quirk names, base URL host only. An allowlist: a key absent here is a
// key the report does not know. Split from DoctorReport.kt (concentration, 2026-09-13).
package splice.app.cli

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import splice.core.topology.HeadConfig
import splice.core.topology.ProviderConfig
import splice.core.topology.Topology

private const val CONTEXT_WINDOW = "context_window"
private const val MODELS = "models"

internal class DoctorReportShape(private val redaction: DoctorRedaction) {

    fun topology(t: Topology): JsonObject = buildJsonObject {
        putJsonObject("daemon") {
            put("control_port", t.daemon.controlPort)
            put("state_dir", t.daemon.stateDir?.let { redaction.text(it) })
            put("show_reasoning", t.daemon.showReasoning)
            put("effort", t.daemon.effort)
            put("mcp_hosting", t.daemon.mcpHosting)
            putJsonArray("mcp_hosting_exclude") {
                t.daemon.mcpHostingExclude.orEmpty().forEach { add(JsonPrimitive(it)) }
            }
        }
        putJsonObject("providers") { t.providers.forEach { (key, p) -> put(key, provider(p)) } }
        putJsonObject("heads") { t.heads.forEach { (key, h) -> put(key, head(h)) } }
    }

    private fun provider(p: ProviderConfig): JsonObject = buildJsonObject {
        put("dialect", p.dialect.name.lowercase())
        put("auth_kind", p.auth.kind)
        put("base_url_host", redaction.host(p.baseUrl))
        put("default_context_window", p.defaultContextWindow)
        put("extra_headers", p.extraHeaders.size)
        putJsonArray(MODELS) {
            p.models.forEach { m ->
                add(
                    buildJsonObject {
                        put("id", m.id)
                        put(CONTEXT_WINDOW, m.contextWindow)
                    },
                )
            }
        }
        putJsonArray("window_rules") {
            p.windowRules.forEach { add(JsonPrimitive("${it.prefix}=${it.contextWindow}")) }
        }
        put("quirks", quirks(p))
    }

    /** The quirk knobs by name, only when set; a knob absent here is a knob the report does not know. */
    private fun quirks(p: ProviderConfig): JsonObject = buildJsonObject {
        val q = p.quirks
        put("code_mode", p.codeModeEnabled)
        put("cache_key", q.cacheKey)
        put("effort_ceiling", q.effortCeiling)
        put("store", q.store)
        put("tool_choice", q.toolChoice)
        put("account_id_header", q.accountIdHeader)
        listOf(
            "websocket" to q.webSocket,
            "reasoning_cache" to q.reasoningCache,
            "parallel_tool_calls" to q.parallelToolCalls,
            "zstd_request_body" to q.zstdRequestBody,
            "reasoning_effort" to q.reasoningEffort,
            "mfjs" to q.mfjs,
            "strip_cache_control" to q.stripCacheControl,
            "synthesize_signatures" to q.synthesizeSignatures,
            "map_thinking_adaptive" to q.mapThinkingAdaptive,
            "strip_sampling_params" to q.stripSamplingParams,
        ).forEach { (name, value) -> value?.let { put(name, it) } }
        q.compactEffort?.let { put("compact_effort", it) }
        q.toolSurface?.let { put("tool_surface", it.enabled) }
    }

    private fun head(h: HeadConfig): JsonObject = buildJsonObject {
        put("provider", h.provider)
        put("port", h.port)
        put("discovery_prefix", h.discoveryPrefix)
        put("pinned_model", h.pinnedModel)
        put(CONTEXT_WINDOW, h.contextWindow)
        putJsonArray(MODELS) { h.models.orEmpty().forEach { add(JsonPrimitive(it.id)) } }
    }
}
