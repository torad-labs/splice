// Support types for local-runtime probing: per-pass GET deduplication, cached runtime identity (#703),
// and the checked-rows result returned by LocalProbeInputs.
package splice.app.provider.local

import splice.core.model.DiscoveredModel
import splice.core.wire.HttpStatus
import splice.upstream.local.LocalRowVerdict
import splice.upstream.local.LocalRuntime
import splice.upstream.transport.LocalHttp
import splice.upstream.transport.LocalHttpReply

internal data class ProbeRows(
    val baseUrl: String,
    val baseKey: String,
    val rows: Map<String, Long>,
    val declared: Map<String, Long?>,
    val listsEvery: Boolean,
)

internal data class CachedRuntime(val runtime: LocalRuntime, val usesProps: Boolean) {
    fun canReuseProps(listsEvery: Boolean): Boolean = usesProps && !listsEvery
}

/** Deduplicates identical GETs within one probe pass so `detect()` and `models()` do not fetch `/v1/models`
 *  or `/api/v0/models` twice (#703). */
internal class OnceGetHttp(private val delegate: LocalHttp) : LocalHttp {
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
