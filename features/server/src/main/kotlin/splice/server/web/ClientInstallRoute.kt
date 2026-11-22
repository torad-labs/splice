// NEW: server mode (docs/specs/server-mode.md, F15). `GET /server/install`: serves a POSIX shell bootstrap script
// pointing at `[server.client] download_url` and this server's origin so a new laptop installs the team's client
// build and joins the server in one command.
package splice.server.web

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.origin
import io.ktor.server.request.httpMethod
import io.ktor.server.response.respondText
import splice.core.topology.ServerConfig
import splice.core.wire.HTTPS_SCHEME
import splice.core.wire.HTTP_SCHEME
import splice.core.wire.HttpStatus
import splice.server.SERVER_ROOT
import splice.server.caller.ClientAddresses
import splice.share.RefusalVoice

internal class ClientInstallRoute(
    private val server: ServerConfig,
    private val addresses: ClientAddresses,
    private val secure: Boolean,
    private val voice: RefusalVoice,
) {
    private val route = listOf(SERVER_ROOT, INSTALL_SEGMENT)

    fun asks(call: ApplicationCall, path: List<String>): Boolean =
        path == route && call.request.httpMethod == HttpMethod.Get

    suspend fun answer(call: ApplicationCall) {
        val base = server.client.downloadUrl?.trim()?.trimEnd('/')
            ?: return voice.refuse(call, HttpStatus.NOT_FOUND, "client distribution is not configured on this server")
        val origin = reachedAt(call)
        val versionLine = server.client.minimum?.let { "SPLICE_VERSION=${shellQuote(it)}\n" }.orEmpty()
        val script = buildString {
            append("#!/bin/sh\nset -eu\n")
            append("SPLICE_RELEASE_BASE_URL=${shellQuote(base)}\n")
            append("export SPLICE_RELEASE_BASE_URL\n")
            append(versionLine)
            append("curl -fsSL \"${'$'}SPLICE_RELEASE_BASE_URL/install.sh\" | sh\n")
            append("splice remote add ${shellQuote(origin)}\n")
        }
        call.respondText(script, ContentType.parse(SHELL_CONTENT_TYPE))
    }

    private fun reachedAt(call: ApplicationCall): String {
        val peer = call.request.origin.remoteAddress
        val headers = call.request.headers
        val scheme = addresses.proxied(peer, headers[HttpHeaders.XForwardedProto])?.lowercase()
            ?.takeIf { it == HTTP_SCHEME || it == HTTPS_SCHEME }
            ?: if (secure) HTTPS_SCHEME else HTTP_SCHEME
        val host = (addresses.proxied(peer, headers[HttpHeaders.XForwardedHost]) ?: headers[HttpHeaders.Host])
            ?.takeIf(INSTALL_AUTHORITY::matches)
            ?: "${call.request.origin.localAddress}:${call.request.origin.localPort}"
        return "$scheme://$host"
    }

    private fun shellQuote(value: String): String = "'${value.replace("'", "'\\''")}'"
}

private const val INSTALL_SEGMENT = "install"
private const val SHELL_CONTENT_TYPE = "text/x-shellscript; charset=utf-8"
private val INSTALL_AUTHORITY = Regex("""^(\[[0-9A-Fa-f:.]+]|[A-Za-z0-9.-]+)(:\d{1,5})?$""")
