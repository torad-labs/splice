// PORT-OF: ControlServer.kt (HeadResolver) @ a77531a — invariants unchanged: head lookup by the
// name a route carries — the three name->head resolutions the control routes share. Widened
// private -> internal: it now serves headAction, authAction, logsJson and launch across HeadRoutes,
// AuthRoutes, LaunchRoutes and StatuslineRoute, not just members of ControlServer.
package splice.control.api

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondText
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import splice.control.ManagedHead
import splice.core.topology.TopologyMessages

private const val KEY = "key"
private const val LABEL = "label"
private const val HEADS = "heads"

internal class HeadResolver(
    private val heads: Map<String, ManagedHead>,
    private val payloads: ControlPayloads,
) {
    // The shim names a head by its wrapper command (argv[0]); the topology keys heads independently
    // (starter: head `openrouter`, command `claude-openrouter`). Accept either name — a map-KEY match (unique)
    // comes first for precedence, then every LABEL (wrapper command) match. Two label matches mean a
    // misconfigured topology sharing one command; callers decide unknown-vs-ambiguous from the size.
    fun headByName(name: String): List<ManagedHead> {
        val byKey = heads[name]
        val byLabel = heads.values.filter { it.head.label == name && it !== byKey }
        return listOfNotNull(byKey) + byLabel
    }

    // One head for a by-name /api route, or null after answering the error itself: an exact KEY match
    // wins outright (the dashboard always sends keys, and a key must never be shadowed by another
    // head's colliding command); otherwise wrapper-command matches — none is a 404, and 2+ is a 409
    // naming the colliding heads so a shared-command misconfiguration never reads as a typo.
    suspend fun resolveHeadOrRespond(call: ApplicationCall, name: String): ManagedHead? {
        heads[name]?.let { return it }
        val byLabel = heads.values.filter { it.head.label == name }
        return when {
            byLabel.size > 1 -> {
                call.respondText(
                    payloads.errorJson(TopologyMessages.ambiguousHeadMessage(name, byLabel.map { it.head.key })),
                    ContentType.Application.Json,
                    HttpStatusCode.Conflict,
                )
                null
            }
            byLabel.isEmpty() -> {
                call.respondText(
                    payloads.errorJson("unknown head"),
                    ContentType.Application.Json,
                    HttpStatusCode.NotFound,
                )
                null
            }
            else -> byLabel.single()
        }
    }

    // The launchable heads a `/launch/<name>` resolves to, with precedence applied so the launchable
    // filter runs across BOTH key- and label-matched candidates: a launchable KEY match wins outright
    // (fixes the latent case where a bare key match with no launchSpec shadowed a launchable command);
    // otherwise every launchable LABEL match — 0 = unknown, 1 = ready, 2+ = ambiguous (shared command).
    fun launchTargets(name: String): List<ManagedHead> {
        heads[name]?.takeIf { it.launchSpec != null }?.let { return listOf(it) }
        return headByName(name).filter { it.launchSpec != null }
    }

    fun headsJson(): String = buildJsonObject {
        putJsonArray(HEADS) { heads.values.forEach { add(headStatus(it)) } }
    }.toString()

    // PORT-OF server/launcher/heads.mjs status shape @ pre-public-port-baseline. In the single daemon the head is
    // in-process, so healthy/version are authoritative and versionMatch is always true when up.
    fun headStatus(m: ManagedHead) = buildJsonObject {
        val h = m.head.healthSnapshot()
        put(KEY, m.head.key)
        put(LABEL, m.head.label)
        put("name", m.head.key)
        put("port", m.head.port)
        put("authKind", m.authKind)
        put("wantVersion", payloads.gatewayVersion())
        put("running", h.running)
        put("healthy", h.ok)
        put("version", if (h.running) payloads.gatewayVersion() else null as String?)
        put("versionMatch", if (h.running) true else null as Boolean?)
        put("mode", null as String?)
        // Live InflightGate snapshot for the dashboard (was permanently null after the Kotlin port).
        putJsonObject("gate") {
            put("inflight", h.gateInflight)
            put("queued", h.gateQueued)
            if (h.gateLimit <= 0) put("max", "unlimited") else put("max", h.gateLimit)
            // Counters the Node gate tracked; Kotlin gate has no acquired/released totals —
            // zero-fill so the GateSnapshot shape stays stable for the webui.
            put("acquired", 0)
            put("released", 0)
            put("waited", 0)
            put("avg_wait_ms", 0)
            putJsonArray("live") {}
            put("stream_idle_ms", 0)
        }
        put("maxInflight", if (h.gateLimit <= 0) null else h.gateLimit)
        // G20: passive per-head health counters, local-origin vs provider-error split — diagnosis
        // only, surfaced through this aggregation (never the per-head /health liveness route).
        putJsonObject("health") {
            put("localOriginErrors", h.localOriginErrors)
            put("providerErrors", h.providerErrors)
        }
        putJsonArray("pids") {}
    }
}
