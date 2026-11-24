// NEW: server mode (docs/specs/server-mode.md, F1 "Admin over the listener", and F6). The server's own API on its
// listener, under `/server/api/`, for a key whose person is in `[server] admins`: the same key operations the control
// plane offers on loopback, reachable from wherever the admin is; and beside it the metrics a Prometheus scrapes with
// an admin's key. Anyone else's key is 403.
//
//   GET    /server/api/keys[?person=]   every key, or one person's
//   POST   /server/api/keys             mint one: {"person", "name"?, "expires"?}
//   DELETE /server/api/keys/<prefix>    revoke one
//   GET    /server/api/usage            the usage report (F5): by, since or from and to, format, as UsageApi reads them
//   GET    /server/metrics              each person's traffic, live, as Prometheus text (F6, ServerMetrics)
package splice.server

import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.httpMethod
import io.ktor.server.response.respondText
import splice.core.wire.HttpStatus
import splice.http.JsonBody
import splice.server.audit.DEFAULT_AUDIT_TAIL
import splice.server.audit.ServerAudit
import splice.server.backup.ServerBackupStore
import splice.server.caller.AdminAccess
import splice.server.ledger.UsageApi
import splice.server.metrics.PROMETHEUS_TEXT
import splice.server.metrics.ServerMetrics
import splice.share.RefusalVoice

/** What an admin reads or triggers over the listener beside the keys: reports (F5), metrics (F6), audit (F16) and
 *  durable state backups (F14). */
internal data class AdminReads(
    val usage: UsageApi,
    val metrics: ServerMetrics,
    val audit: ServerAudit? = null,
    val backup: ServerBackupStore? = null,
)

internal class ServerAdmin(
    private val access: AdminAccess,
    private val keys: KeyApi,
    private val reads: AdminReads,
    private val voice: RefusalVoice,
) {
    private val jsonBody = JsonBody()

    /** [rest], the path after `/server`, asked with [key]. */
    suspend fun answer(call: ApplicationCall, key: ServerKey, rest: List<String>) {
        val metrics = rest == listOf(METRICS_ROUTE) && call.request.httpMethod == HttpMethod.Get
        when {
            rest.firstOrNull() != API_ROUTE && !metrics -> noSuchPath(call)
            !access.names(key.person) -> {
                val what = if (metrics) "metrics are" else "API is"
                val why = "the server's $what for its admins, and ${key.person} is not one of them"
                voice.refuse(call, HttpStatus.FORBIDDEN, why)
            }
            !access.allowsAddress(call) ->
                voice.refuse(call, HttpStatus.FORBIDDEN, "the server's admin routes are not allowed from this address")
            metrics -> call.respondText(reads.metrics.prometheus(), ContentType.parse(PROMETHEUS_TEXT))
            else -> say(call, asked(call, key.person, rest.drop(1)))
        }
    }

    /** What an admin's request for [path], the path after `/server/api`, comes to; null for a path that is none. */
    private suspend fun asked(call: ApplicationCall, actor: String, path: List<String>): ServerAnswer? {
        if (path == listOf(KEYS_ROUTE, "import") && call.request.httpMethod == HttpMethod.Post) {
            return keys.importKey(jsonBody.parse(call), actor)
        }
        if (path.firstOrNull() == KEYS_ROUTE && path.size <= 2) return keyAction(call, actor, path.getOrNull(1))
        return adminRoute(call, path)
    }

    private suspend fun adminRoute(call: ApplicationCall, path: List<String>): ServerAnswer? = when (path) {
        listOf(USAGE_ROUTE) ->
            reads.usage.report(call.request.queryParameters).takeIf { call.request.httpMethod == HttpMethod.Get }
        listOf(AUDIT_ROUTE) ->
            reads.audit
                ?.takeIf { call.request.httpMethod == HttpMethod.Get }
                ?.list(call.request.queryParameters["tail"]?.toIntOrNull() ?: DEFAULT_AUDIT_TAIL)
        listOf(BACKUP_ROUTE) -> reads.backup?.takeIf { call.request.httpMethod == HttpMethod.Post }?.now()
        else -> null
    }

    private suspend fun keyAction(call: ApplicationCall, actor: String, prefix: String?): ServerAnswer? = when {
        prefix == null -> everyKey(call, actor)
        call.request.httpMethod == HttpMethod.Delete -> keys.revoke(prefix, actor)
        else -> null
    }

    /** `/server/api/keys` itself: the list, or a new key. */
    private suspend fun everyKey(call: ApplicationCall, actor: String): ServerAnswer? = when (call.request.httpMethod) {
        HttpMethod.Get -> keys.list(call.request.queryParameters["person"])
        HttpMethod.Post -> keys.create(jsonBody.parse(call), actor)
        else -> null
    }

    private suspend fun say(call: ApplicationCall, answer: ServerAnswer?) {
        when (answer) {
            null -> noSuchPath(call)
            is ServerAnswer.Done -> {
                call.respondText(answer.body.toString(), ContentType.Application.Json, answer.status)
            }
            is ServerAnswer.Refused -> voice.refuse(call, answer.status.value, answer.why)
            is ServerAnswer.Text -> call.respondText(answer.text, ContentType.parse(answer.contentType), answer.status)
        }
    }

    private suspend fun noSuchPath(call: ApplicationCall) {
        voice.refuse(call, HttpStatus.NOT_FOUND, "no such path on this server")
    }
}

private const val API_ROUTE = "api"
private const val KEYS_ROUTE = "keys"
private const val USAGE_ROUTE = "usage"
private const val AUDIT_ROUTE = "audit"
private const val BACKUP_ROUTE = "backup"
private const val METRICS_ROUTE = "metrics"
