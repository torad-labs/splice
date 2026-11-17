// NEW: server mode (docs/specs/server-mode.md, F9, the server's half). The oldest splice a laptop may run against this
// server, `[server.client] minimum`, and what an older one is told. Each launch names its splice in `x-splice-client`;
// one older than the minimum, compared number by number, has its Messages turn answered in the conversation by the
// command's head with the version it runs, the minimum and `splice upgrade`, a 200 that Claude Code shows once where
// it would retry a refusal about six times, and nothing reaches the upstream. A version at or past the minimum is
// served, and so is a request that names none (a script's) or names something no version can be read from.
package splice.server

import io.ktor.server.application.ApplicationCall
import splice.core.client.ClientVersionParser
import splice.core.client.SPLICE_CLIENT_HEADER
import splice.core.topology.ServerClient

internal class ClientMinimum(client: ServerClient) {
    private val versions = ClientVersionParser()
    private val floor = client.floor

    /** What [call]'s laptop is told in place of an answer, when its splice is older than the minimum; else null. */
    fun notice(call: ApplicationCall): String? {
        val floor = floor ?: return null
        val runs = versions.parse(call.request.headers[SPLICE_CLIENT_HEADER])?.takeIf { it < floor } ?: return null
        return "This laptop runs splice $runs, and this team server serves splice $floor or newer. " +
            "Run `splice upgrade` on this laptop, then start Claude Code again."
    }
}
