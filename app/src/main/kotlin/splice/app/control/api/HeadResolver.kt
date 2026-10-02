// PORT-OF: ControlServer.kt (HeadResolver) @ a77531a — invariants unchanged: head lookup by the
// name a route carries — the three name->head resolutions the control routes share. Widened
// private -> internal: it now serves headAction, authAction, logsJson and launch across HeadRoutes,
// the accounts routes (through AccountHeadAdapter), LaunchRoutes and StatuslineRoute, not just members
// of ControlServer.
package splice.app.control.api

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondText
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import splice.app.control.ManagedHead
import splice.core.topology.TopologyMessages
import splice.heads.HeadStatus

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

    /** Each head's status. Two readings ride on it that the head's own health does not say: for a local
     *  head whose runtime is silent (V4-417) the endpoint it was asked on as `runtimeNotAnswering`, from
     *  the daemon's held probe; and for a running head whose provider refuses turns until a known instant
     *  (V4-429) that instant in epoch seconds as `quotaResetAtEpochSeconds`, the figure /health carries.
     *  The console reads the first as down and the second as out of quota. A provider reading that names a
     *  window fully used rides apart as `quotaFull` (V4-452): a reading, beside a head that stays ready. */
    fun headStatuses(nowEpochMillis: Long = System.currentTimeMillis()): List<JsonObject> {
        val silent = payloads.silentRuntimes()
        val quotaResets = payloads.quotaResets(nowEpochMillis)
        val quotaFull = payloads.quotaFull()
        return heads.values.map { managed ->
            val key = managed.head.key
            val marks = listOfNotNull(
                silent[key]?.let { "runtimeNotAnswering" to JsonPrimitive(it) },
                quotaResets[key]?.let { "quotaResetAtEpochSeconds" to JsonPrimitive(it) },
                quotaFull[key]?.let { "quotaFull" to payloads.quotaFullJson(it) },
            )
            JsonObject(HeadStatus.json(managed.head, managed.authKind) + marks)
        }
    }
}
