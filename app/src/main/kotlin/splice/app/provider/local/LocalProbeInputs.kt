// NEW: v0.4.0 FEATURES.md §10 — what the local-runtime probe carries and what it checks — shared by
// the boot refusal (ChatArm) and doctor (DoctorLocalRuntime) so both see the same runtime and the
// same rows. Headers: the provider's static extra_headers plus its bearer, because a runtime that
// guards /v1/models (vLLM --api-key) must answer the probe exactly as it answers a turn. Rows: the
// HEAD's effective catalog (head context_window override applied, picker suffixes stripped, the
// widest row per upstream id), never the raw provider table.
package splice.app.provider.local

import splice.app.server.HealthHttp
import splice.core.model.DiscoveredModel
import splice.core.model.ModelCatalog
import splice.core.model.UpstreamWindows
import splice.core.topology.ProviderConfig
import splice.core.util.EnvReader
import splice.core.wire.HttpStatus
import splice.provider.openai.ApiKeyAuthProvider
import splice.topology.TopologyLoader
import splice.upstream.local.LocalModel
import splice.upstream.local.LocalPropsWindow
import splice.upstream.local.LocalRowVerdict
import splice.upstream.local.LocalRuntime
import splice.upstream.local.LocalRuntimeKind
import splice.upstream.local.LocalRuntimeProbe
import splice.upstream.transport.LocalHttp
import splice.upstream.transport.LocalHttpReply
import java.nio.file.Paths
import java.util.concurrent.ConcurrentHashMap

internal class LocalProbeInputs(
    private val http: LocalHttp? = null,
    private val healthHttp: HealthHttp = JdkHealthHttp(),
) {
    /** #703: detected runtime kind per base URL, so periodic window refreshes do not re-probe `/api/version` and
     *  `/api/v0/models` on every tick. Evicted if a cached refresh stops answering. */
    private val detected = ConcurrentHashMap<String, CachedRuntime>()

    fun headers(provider: ProviderConfig, bearer: String?): Map<String, String> =
        provider.staticHeaders + (bearer?.let { mapOf("Authorization" to "Bearer $it") } ?: emptyMap())

    /** The api-key a turn would send, read the way the head reads it (env, then key file / store). */
    fun bearer(key: String, provider: ProviderConfig, env: EnvReader): String? {
        if (provider.auth.kind != "api-key") return null
        return ApiKeyAuthProvider(
            envVar = provider.auth.effectiveApiKeyEnv(key),
            keyFile = provider.auth.file?.let { Paths.get(TopologyLoader.expandHome(it)) },
            envReader = env,
        ).keyNow()
    }

    /** Ask the runtime behind [provider] about [catalog]'s rows. ONE sequence for the boot refusal
     *  (ChatArm) and for a window edited while the daemon runs (V4-162, TopologyWindows), so the two
     *  cannot come to disagree about what the runtime allows. Blocking network: never on a request
     *  thread. */
    fun check(
        provider: ProviderConfig,
        bearer: String?,
        catalog: ModelCatalog,
        listsEvery: Boolean = false,
    ): LocalRowsCheck {
        val transport = OnceGetHttp(http ?: healthHttp.with(headers(provider, bearer)))
        val baseKey = provider.baseUrl.trimEnd('/')
        val rows = UpstreamWindows(catalog).byId()
        val declared = rows.mapValues { (id, window) ->
            window.takeIf { provider.declaredWindowFor(id, catalog.windows.headWindow) != null }
        }
        val query = ProbeRows(provider.baseUrl, baseKey, rows, declared, listsEvery)
        val cached = detected[baseKey]
        if (cached?.canReuseProps(listsEvery) == true) {
            val fast = checkCachedProps(query, transport, cached.runtime)
            if (fast != null) return fast
            detected.remove(baseKey)
        }
        return checkFull(query, transport)
    }

    private fun checkCachedProps(
        query: ProbeRows,
        transport: LocalHttp,
        runtime: LocalRuntime,
    ): LocalRowsCheck.Checked? {
        val allocated = LocalPropsWindow(query.baseUrl, transport).read() ?: return null
        val probe = LocalRuntimeProbe(query.baseUrl, transport)
        val listed = query.rows.keys.map { id -> LocalModel(id, allocated) }
        val models = query.rows.keys.map { id -> DiscoveredModel(id, contextWindow = allocated) }
        return LocalRowsCheck.Checked(
            runtime,
            query.rows.mapValues { (id, window) -> query.declared[id] ?: allocated.takeIf { it > 0 } ?: window },
            probe.validate(query.declared, listed, runtime.kind).filterNot { it.ok },
            models,
        )
    }

    private fun checkFull(query: ProbeRows, transport: OnceGetHttp): LocalRowsCheck {
        val probe = LocalRuntimeProbe(query.baseUrl, transport)
        val existing = detected[query.baseKey]?.runtime
        val runtime = existing ?: probe.detect() ?: return LocalRowsCheck.Down
        val inferred = query.declared.filterValues { it == null }.keys
        val listed = probe.models(runtime, query.rows.keys, inferred)
        if (listed == null) {
            detected.remove(query.baseKey)
            return LocalRowsCheck.Unlisted(runtime)
        }
        if (existing == null) recordRuntime(query.baseKey, runtime, transport)
        val models = resolvedModels(query, listed, runtime.kind)
        val windows = models.associate { it.id to it.contextWindow }
        return LocalRowsCheck.Checked(
            runtime,
            query.rows.mapValues { (id, window) -> query.declared[id] ?: windows[id] ?: window },
            probe.validate(query.declared, listed, runtime.kind).filterNot { it.ok },
            models,
        )
    }

    private fun recordRuntime(baseKey: String, runtime: LocalRuntime, transport: OnceGetHttp) {
        val usesProps = runtime.kind == LocalRuntimeKind.OPENAI_COMPATIBLE &&
            transport.answeredProps(baseKey.removeSuffix("/v1"))
        detected[baseKey] = CachedRuntime(runtime, usesProps)
    }

    private fun resolvedModels(
        query: ProbeRows,
        listed: List<LocalModel>,
        kind: LocalRuntimeKind,
    ): List<DiscoveredModel> {
        val matched = query.rows.keys.map { id ->
            val model = listed.firstOrNull { it.id == id } ?: listed.firstOrNull { it.id == "$id:latest" }
                ?: listed.singleOrNull()?.takeIf { kind == LocalRuntimeKind.OPENAI_COMPATIBLE }
            DiscoveredModel(id, contextWindow = model?.contextLength?.takeIf { it > 0 })
        }
        val extra = listed.filter { query.listsEvery && it.id !in query.rows.keys }
            .map { DiscoveredModel(it.id, contextWindow = it.contextLength?.takeIf { n -> n > 0 }) }
        return matched + extra
    }
}

private data class ProbeRows(
    val baseUrl: String,
    val baseKey: String,
    val rows: Map<String, Long>,
    val declared: Map<String, Long?>,
    val listsEvery: Boolean,
)

private data class CachedRuntime(val runtime: LocalRuntime, val usesProps: Boolean) {
    fun canReuseProps(listsEvery: Boolean): Boolean = usesProps && !listsEvery
}

/** Deduplicates identical GETs within one probe pass so `detect()` and `models()` do not fetch `/v1/models`
 *  or `/api/v0/models` twice (#703). */
private class OnceGetHttp(private val delegate: LocalHttp) : LocalHttp {
    private val replies = HashMap<String, LocalHttpReply?>()

    override fun invoke(method: String, url: String, body: String?): LocalHttpReply? {
        if (method != "GET" || body != null) return delegate(method, url, body)
        if (url in replies) return replies[url]
        return delegate(method, url, null).also { replies[url] = it }
    }

    fun answeredProps(root: String): Boolean = replies["$root/props"]?.status == HttpStatus.OK
}

/** What asking a local runtime about a head's rows found ([LocalProbeInputs.check]). */
internal sealed class LocalRowsCheck {
    /** Nothing answers at the base URL. Boot proceeds as it always has (per-turn errors say why). */
    data object Down : LocalRowsCheck()

    /** The runtime answered and its model list did not, so the rows stay unchecked. */
    data class Unlisted(val runtime: LocalRuntime) : LocalRowsCheck()

    /** The runtime's verdict on every row; [refused] is empty when it accepts them all. */
    data class Checked(
        val runtime: LocalRuntime,
        val rows: Map<String, Long>,
        val refused: List<LocalRowVerdict>,
        val models: List<DiscoveredModel> = emptyList(),
    ) : LocalRowsCheck()
}
