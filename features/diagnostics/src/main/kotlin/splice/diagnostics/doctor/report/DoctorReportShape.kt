// NEW: v0.4.0 FEATURES.md §6 — the topology SHAPE in the doctor report — provider kinds, dialects,
// model ids and windows, quirk names, base URL host only. Two allowlists: a KEY absent here is a
// key the report does not know, and every operator-authored VALUE (provider and head names, model
// ids, prefixes, labels, effort words, MCP server names) must be TOKEN-SHAPED and secret-free to
// appear at all — prose, an e-mail, a key are not shapes the report has, so the field is omitted;
// a name in that state becomes a deterministic positional alias from a RESERVED namespace
// (<provider-2>, <head-1>, <account-3>: the angle brackets are not token characters, so no real
// name can collide), so the shape keeps every entry distinct and every reference resolves. Every
// free-text field of the report (check ids and details, log tags) is additionally SCRUBBED by
// SafeNames (SafeNames.kt) of the unsafe operator-authored values the topology holds, so
// config-injected prose cannot reach the report through a sentence that quotes it. Paths go
// through the path allowlist. Split from DoctorJsonReport.kt (2026-09-13).
package splice.diagnostics.doctor.report

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import splice.accounts.pool.HeadAccountPoolView
import splice.accounts.pool.HeadAccountView
import splice.core.topology.HeadConfig
import splice.core.topology.ProviderConfig
import splice.core.topology.Topology
import splice.diagnostics.doctor.DoctorCheck

private const val CONTEXT_WINDOW = "context_window"
private const val MODELS = "models"

/** V4-127 §6: the oldest file a state-directory scan found, and how long it has been there. The
 *  MEASUREMENT is not this file's job — every shaper here takes values and returns JSON — so the
 *  route walks the directory and hands the result in. [ageMs] is an age rather than a timestamp for
 *  the same reason the perf rows report latency: an age survives being read on another machine. */
internal data class StateDirFile(val name: String, val ageMs: Long)
internal class DoctorReportShape(private val redaction: DoctorRedaction, private val names: SafeNames) {

    fun topology(t: Topology): JsonObject = buildJsonObject {
        putJsonObject("daemon") {
            put("control_port", t.daemon.controlPort)
            put("state_dir", t.daemon.stateDir?.let(redaction::text))
            put("show_reasoning", t.daemon.showReasoning)
            put("effort", t.daemon.effort?.let(names::token))
            put("mcp_hosting", t.daemon.mcpHosting)
            putJsonArray("mcp_hosting_exclude") {
                t.daemon.mcpHostingExclude.orEmpty().mapNotNull(names::token).forEach { add(JsonPrimitive(it)) }
            }
        }
        putJsonObject("providers") { t.providers.forEach { (key, p) -> put(names.provider(key), provider(p)) } }
        putJsonObject("heads") { t.heads.forEach { (key, h) -> put(names.head(key), head(h)) } }
    }

    /** V4-127 §6, the state directory's own footprint: how much the daemon is holding on disk and
     *  the oldest file it is still holding. The console shows this beside the topology block because
     *  "which directory" without "how big" is the question the page was opened to ask — a state dir
     *  that has grown for months is the one thing a doctor report can see and a config dump cannot.
     *
     *  THE NAME IS REDACTED, deliberately: a state filename carries an account label on several
     *  heads, which is exactly the operator-authored text [redaction] exists for, and [topology]
     *  already redacts the directory itself for the same reason. */
    fun stateDirUsage(sizeBytes: Long, fileCount: Int, oldest: StateDirFile?): JsonObject = buildJsonObject {
        put("size_bytes", sizeBytes)
        put("file_count", fileCount)
        // An EMPTY state dir reports nulls rather than a 1970 timestamp or a fabricated name: the
        // console renders "no files" from an explicit null, and a sentinel would render as data.
        put("oldest_file", oldest?.let { JsonPrimitive(redaction.text(it.name)) } ?: JsonNull)
        put("oldest_age_ms", oldest?.let { JsonPrimitive(it.ageMs) } ?: JsonNull)
    }

    /** Exactly {id, status, detail} (schema 1): the fix rides inside the detail. A check's name and
     *  detail are the doctor's own sentences, but they quote config values — so every unsafe
     *  operator-authored value is scrubbed to its alias or <omitted> before the shape pass. */
    fun checks(sections: List<Pair<String, List<DoctorCheck>>>): JsonArray =
        buildJsonArray {
            sections.forEach { (section, checks) ->
                checks.forEach { c ->
                    add(
                        buildJsonObject {
                            put("id", safe("$section/${c.name}"))
                            put("status", c.status.name.lowercase())
                            put("detail", safe(c.fix?.let { "${c.detail} — fix: $it" } ?: c.detail))
                        },
                    )
                }
            }
        }

    private fun safe(text: String): String = redaction.text(names.scrub(text))

    /** v0.4.0 (FEATURES.md §11): per pooled head, labels, flags, plan windows and the last switch — no ids. */
    fun accounts(pools: Map<String, HeadAccountPoolView>): JsonObject = buildJsonObject {
        pools.forEach { (head, view) ->
            val labels = view.accounts.map { it.label }
            putJsonObject(names.head(head)) {
                // A selection the daemon could not report (V4-10 S3) is unknown here too, never the primary.
                val selected = if (view.selectionUnknown) null else view.selectedAccount()
                put("selected", selected?.label?.let { names.label(labels, it) })
                put("selection_unknown", view.selectionUnknown)
                putJsonArray("accounts") { view.accounts.forEach { add(account(labels, it)) } }
                view.lastSwitch?.let { s ->
                    putJsonObject("last_switch") {
                        put("from", names.label(labels, s.from))
                        put("to", names.label(labels, s.to))
                        put("reason", redaction.text(s.reason))
                        put("at_epoch_millis", s.atEpochMillis)
                    }
                }
            }
        }
    }

    private fun account(labels: List<String>, a: HeadAccountView): JsonObject = buildJsonObject {
        put("label", names.label(labels, a.label))
        put("primary", a.primary)
        put("selected", a.selected)
        put("available", a.available)
        put("credential_present", a.credentialPresent)
        put("auth_excluded_until_epoch_millis", a.authExcludedUntilEpochMillis)
        put("auth_exclusion_reason", a.authExclusionReason?.let(redaction::text))
        put("plan", a.plan?.let(names::token))
        put("five_hour_used_percent", a.fiveHourUsedPercent)
        put("five_hour_reset_epoch_seconds", a.fiveHourResetEpochSeconds)
        put("seven_day_used_percent", a.sevenDayUsedPercent)
        put("seven_day_reset_epoch_seconds", a.sevenDayResetEpochSeconds)
    }

    private fun provider(p: ProviderConfig): JsonObject = buildJsonObject {
        put("dialect", p.dialect.name.lowercase())
        put("auth_kind", names.token(p.auth.kind))
        put("base_url_host", redaction.host(p.baseUrl))
        put("default_context_window", p.defaultContextWindow)
        put("extra_headers", p.extraHeaders.size)
        putJsonArray(MODELS) {
            p.models.forEach { m ->
                add(
                    buildJsonObject {
                        put("id", names.token(m.id))
                        put(CONTEXT_WINDOW, m.contextWindow)
                    },
                )
            }
        }
        putJsonArray("window_rules") {
            p.windowRules.forEach { rule ->
                names.token(rule.prefix)?.let { add(JsonPrimitive("$it=${rule.contextWindow}")) }
            }
        }
        put("quirks", quirks(p))
    }

    /** The quirk knobs by name, only when set; a knob absent here is a knob the report does not know. */
    private fun quirks(p: ProviderConfig): JsonObject = buildJsonObject {
        val q = p.quirks
        put("code_mode", p.codeModeEnabled)
        put("cache_key", names.token(q.cacheKey))
        put("effort_ceiling", names.token(q.effortCeiling))
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
            "stream_usage" to q.streamUsage,
            "slot_affinity" to q.slotAffinity,
        ).forEach { (name, value) -> value?.let { put(name, it) } }
        q.toolSurface?.let { put("tool_surface", it.enabled) }
    }

    private fun head(h: HeadConfig): JsonObject = buildJsonObject {
        put("provider", names.provider(h.provider))
        put("port", h.port)
        put("discovery_prefix", names.token(h.discoveryPrefix))
        put("pinned_model", names.token(h.pinnedModel))
        put(CONTEXT_WINDOW, h.contextWindow)
        putJsonArray(MODELS) { h.models.orEmpty().mapNotNull { names.token(it.id) }.forEach { add(JsonPrimitive(it)) } }
    }
}
