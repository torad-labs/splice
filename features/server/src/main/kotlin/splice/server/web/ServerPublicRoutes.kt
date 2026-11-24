// NEW: server mode (docs/specs/server-mode.md, F13 and F15). Routes on the server listener that authenticate via
// their own cookie/key check or require no key (`/server/forward-auth`, `/server/install`).
package splice.server.web

import io.ktor.server.application.ApplicationCall
import splice.core.topology.ServerConfig
import splice.server.caller.ClientAddresses
import splice.share.RefusalVoice

internal class ServerPublicRoutes(
    server: ServerConfig,
    addresses: ClientAddresses,
    secure: Boolean,
    voice: RefusalVoice,
    private val forwardAuth: ForwardAuthRoute,
) {
    private val installRoute = ClientInstallRoute(server, addresses, secure, voice)

    suspend fun answered(call: ApplicationCall, path: List<String>): Boolean = when {
        forwardAuth.asks(call, path) -> {
            forwardAuth.answer(call, path)
            true
        }
        installRoute.asks(call, path) -> {
            installRoute.answer(call)
            true
        }
        else -> false
    }
}
