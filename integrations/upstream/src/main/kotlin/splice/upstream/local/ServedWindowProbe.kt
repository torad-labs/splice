// NEW: custom-provider (issue #680, cp-window). A provider may name where it reports the window it really serves,
// `served_window = { url, field }`: the same idea as the /props probe a local runtime gets (LocalRuntimeProbe), written
// explicitly for any provider. It is read once when the daemon starts; a failure never refuses boot or a turn, it only
// leaves the declared window in force and says why.
package splice.upstream.local

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import splice.core.topology.ProviderConfig
import splice.core.topology.ServedWindowConfig
import splice.core.util.Cancellables
import splice.core.wire.HttpStatus
import splice.upstream.transport.LocalHttp

/** Reads a provider's `served_window` through [http], which carries the provider's own headers. */
public class ServedWindowProbe(private val http: LocalHttp) {
    /** What reading it found: the window, or what happened instead, in words a doctor line ends with. */
    public sealed class Read {
        public data class Window(val tokens: Long) : Read()

        public data class Failed(val what: String) : Read() {
            /** The line the daemon's log and `splice doctor` say it with, for provider [key]. */
            public fun said(key: String, provider: ProviderConfig): String {
                val declared = provider.models.map { it.contextWindow }.distinct().joinToString(", ")
                val url = provider.servedWindow?.url.orEmpty()
                return "providers.$key.served_window: $url $what; the declared context_window ($declared) is in force"
            }
        }
    }

    public fun read(served: ServedWindowConfig): Read {
        val reply = http("GET", served.url, null) ?: return Read.Failed("did not answer (timed out or unreachable)")
        if (reply.status !in OK_STATUSES) return Read.Failed("answered HTTP ${reply.status}")
        return windowIn(reply.body, served.field)
    }

    /** The window [body] holds at the dotted [field], or why it holds none. */
    private fun windowIn(body: String, field: String): Read {
        val json = Cancellables.runCatchingCancellable { Json.parseToJsonElement(body) }
            .fold(onSuccess = { it }, onFailure = { null }) ?: return Read.Failed("answered with no JSON")
        return at(json, field)?.takeIf { it > 0 }?.let { Read.Window(it) }
            ?: Read.Failed("has no positive integer at $field")
    }

    /** The number at the dotted [path] of [json], or null when there is none. */
    private fun at(json: JsonElement, path: String): Long? {
        val leaf = path.split('.').fold<String, JsonElement?>(json) { node, key -> (node as? JsonObject)?.get(key) }
        return (leaf as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull
    }
}

// why: any 2xx is an answer whose body carries the window.
private val OK_STATUSES = HttpStatus.OK..HttpStatus.MAX_SUCCESS
