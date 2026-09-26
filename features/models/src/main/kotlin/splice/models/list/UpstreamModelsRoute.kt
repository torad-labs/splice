// NEW: V4-239 — GET /api/models/upstream[?provider=KEY]: what `splice models [provider]` prints, for the
// console. Each provider's published roster against splice.toml: every declared row with its verdict
// (served, capped, over its ceiling, unserved), every served model no row declares (discovered into
// the picker, or kept out and why), or the reason a provider publishes no list or could not be read.
// All rows, where the verb shows eight undeclared ones and counts the rest: the page collapses them.
//
// ON DEMAND ONLY. Every call asks the providers themselves, with the credential the daemon holds, so
// the console reads it when the operator presses Compare and never polls it.
//
// NO CREDENTIAL LEAVES. The credential is read inside the probe and presented to the provider alone;
// the answer carries the roster and the verdicts. The one field that could carry one is the URL an
// operator configured (`models_url` with `user:pass@` or a `?key=`), so the URL goes out with neither,
// in the url field and in every sentence that names it. The verb prints it whole, to the operator's
// own terminal.
package splice.models.list

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondText
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import splice.core.util.Cancellables
import splice.core.util.EnvReader
import splice.core.util.SafeFailureText

private const val SCHEME_SEPARATOR = "://"

/** What ends a URL's authority, the part `user:pass@` sits in. */
private val AUTHORITY_ENDS = listOf('/', '?', '#')

internal const val UPSTREAM_UNWIRED =
    "the daemon wired no provider comparison; /api/models/upstream cannot ask the providers"

/** The daemon's comparison, read per request because the control plane is handed it after construction. */
public fun interface ModelsReporterSource {
    public operator fun invoke(): ModelsReporter?
}

/** [io] runs the probe, whose HTTP client blocks, off the server's own threads. */
public class UpstreamModelsRoute(
    private val reporters: ModelsReporterSource,
    private val env: EnvReader,
    private val io: CoroutineDispatcher,
) {

    /** [provider] is the `provider` query parameter: one provider's key, or null for every one. */
    public suspend fun upstream(call: ApplicationCall, provider: String?) {
        val reporter = reporters() ?: return call.respondText(
            buildJsonObject { put("error", UPSTREAM_UNWIRED) }.toString(),
            ContentType.Application.Json,
            HttpStatusCode.ServiceUnavailable,
        )
        // A splice.toml that no longer reads is said, never an empty comparison.
        val report = withContext(io) {
            Cancellables.runCatchingCancellable { reporter.report(provider?.takeIf { it.isNotBlank() }, env) }
        }.getOrElse { failure ->
            return call.respondText(
                buildJsonObject { put("error", "the comparison failed: ${SafeFailureText.render(failure)}") }
                    .toString(),
                ContentType.Application.Json,
                HttpStatusCode.InternalServerError,
            )
        }
        when (report) {
            is ModelsReport.Compared -> call.respondText(json(report), ContentType.Application.Json)
            // A 400 naming the providers that exist, never a 404: the console reads 404 as a route
            // this daemon does not serve.
            is ModelsReport.NoSuchProvider -> call.respondText(
                buildJsonObject {
                    put("error", "no provider '${report.wanted.orEmpty()}' in ${report.path}")
                    putJsonArray("declared") { report.declared.forEach { add(it) } }
                }.toString(),
                ContentType.Application.Json,
                HttpStatusCode.BadRequest,
            )
        }
    }

    internal fun json(report: ModelsReport.Compared): String = buildJsonObject {
        put("path", report.path)
        putJsonArray("providers") { report.providers.forEach { add(provider(it)) } }
    }.toString()

    private fun provider(reported: ProviderReport): JsonObject = buildJsonObject {
        val url = shown(reported.url)
        put("key", reported.key)
        put("dialect", reported.dialect)
        put("url", url)
        when (val roster = reported.roster) {
            is UpstreamRoster.Published -> put("roster", "published")
            is UpstreamRoster.Unpublished -> {
                put("roster", "unpublished")
                put("reason", roster.reason.replace(reported.url, url))
            }
            is UpstreamRoster.Unreadable -> {
                put("roster", "unreadable")
                put("reason", roster.detail.replace(reported.url, url))
            }
        }
        put("agrees", reported.agrees)
        putJsonArray("rows") { reported.rows.forEach { add(row(it)) } }
    }

    private fun row(row: RosterRow): JsonObject = buildJsonObject {
        put("id", row.id)
        put("verdict", verdict(row.verdict))
        put("declared_window", row.declaredWindow)
        put("upstream_window", row.upstreamWindow)
        put("note", row.note)
    }

    private fun verdict(verdict: RosterVerdict): String = when (verdict) {
        RosterVerdict.SERVED -> "served"
        RosterVerdict.CAPPED -> "capped"
        RosterVerdict.OVER_CEILING -> "over-ceiling"
        RosterVerdict.UNSERVED -> "unserved"
        RosterVerdict.NEW -> "new"
        RosterVerdict.EXCLUDED -> "excluded"
    }

    /** [url] with no userinfo and no query or fragment: the two places a configured URL can hold a
     *  credential, cut by position so a URL that does not parse is cut too. */
    private fun shown(url: String): String {
        val scheme = url.indexOf(SCHEME_SEPARATOR)
        val authorityFrom = if (scheme == -1) 0 else scheme + SCHEME_SEPARATOR.length
        val authorityTo = AUTHORITY_ENDS.map { url.indexOf(it, authorityFrom) }.filter { it != -1 }.minOrNull()
            ?: url.length
        val at = url.lastIndexOf('@', authorityTo - 1).takeIf { it >= authorityFrom }
        val withoutUser = if (at == null) url else url.substring(0, authorityFrom) + url.substring(at + 1)
        return withoutUser.substringBefore('?').substringBefore('#')
    }
}
