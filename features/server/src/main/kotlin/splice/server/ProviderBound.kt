// NEW: server mode (docs/specs/server-mode.md, F1 and F4). What of a caller's request goes on toward a provider's own
// server, on either route the listener serves. That server is not this machine, so the rule is the one a head's turn
// follows (README, "Headers for a gateway in front of a provider"): the provider's own headers and key, and of what
// the caller sent only the headers the provider's `forward_headers` names. Everything else the caller sent stays
// here: the key that admitted it, wherever it was put (`api-key`, a header of the client's own), its cookies, its
// address, what it runs on.
//
// A person's key is the server's secret, so nothing that holds one of this server's keys goes on, whoever's key it
// is: not a named header, not the body's type, not a parameter of the query. Each is left off and the request goes
// on without it, where refusing it would stop the client; the log says what was left off and never repeats it.
//
// An OpenAI-style client's request is relayed to the provider's server by the listener itself (OpenAiRoute), which
// asks here for all of what it carries. A named header that rides replaces the provider's `extra_headers` value of the
// same name, as on a head. The body's type rides with the body, which is relayed as the client sent it. A turn on the
// Messages route goes through the command's head, which sends the named headers on itself and knows none of this
// server's keys: so the listener asks here which of them hold one ([keyed]), and the head is never handed those.
package splice.server

import io.ktor.http.HttpHeaders
import io.ktor.http.URLDecodeException
import io.ktor.http.decodeURLQueryComponent
import io.ktor.server.application.ApplicationCall
import splice.core.util.LogSink
import splice.share.OutwardHeaders

internal class ProviderBound(private val keys: ServerKeys, private val log: LogSink) {
    private val bodyType = listOf(HttpHeaders.ContentType.lowercase())

    /** The headers [call]'s request carries to a provider's server: of the caller's own the ones among [forwarded],
     *  the names its `forward_headers` lists, and the provider's [own] headers as they stand now. */
    fun headers(call: ApplicationCall, forwarded: List<String>, own: Map<String, String>): OutwardHeaders {
        val named = keyFree(call, forwarded)
        return OutwardHeaders(named + keyFree(call, bodyType), own.filterKeys { it.lowercase() !in named })
    }

    /** [sent], a request's query with its `?`, as it goes on: as the client wrote it, less any parameter that holds
     *  one of this server's keys. Empty when nothing is left. */
    fun query(sent: String): String {
        val parameters = sent.removePrefix("?").split('&')
        val free = parameters.filterNot { keys.heldIn(it) || keys.heldIn(decoded(it)) }
        if (free.size == parameters.size) return sent
        // A parameter's name is the caller's own text and may be the key itself, so the log names none.
        val left = parameters.size - free.size
        log("[server] did not forward $left parameter(s) of a query: each carried one of this server's keys\n")
        return free.joinToString("&").takeIf(String::isNotEmpty)?.let { "?$it" }.orEmpty()
    }

    /** The headers among [names], lowercase, in which [call] sent one of this server's keys, anywhere in a value.
     *  None of them goes on, and the log names each and never what it held. */
    fun keyed(call: ApplicationCall, names: List<String>): Set<String> {
        val keyed = names.filter { name -> call.request.headers.getAll(name).orEmpty().any(keys::heldIn) }
        if (keyed.isNotEmpty()) {
            log("[server] did not forward ${keyed.joinToString()}: it carried one of this server's keys\n")
        }
        return keyed.toSet()
    }

    /** The headers among [names] that [call] sent and that hold none of this server's keys, by lowercase name. */
    private fun keyFree(call: ApplicationCall, names: List<String>): Set<String> {
        val sent = names.filter { name -> call.request.headers.getAll(name).orEmpty().any(String::isNotBlank) }
        return sent.toSet() - keyed(call, sent)
    }

    private fun decoded(parameter: String): String = try {
        parameter.decodeURLQueryComponent()
    } catch (_: URLDecodeException) {
        // A `%` that starts no escape: the text is read as it was written, which heldIn was asked too.
        parameter
    }
}
