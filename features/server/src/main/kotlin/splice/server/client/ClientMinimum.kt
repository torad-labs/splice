// NEW: server mode (docs/specs/server-mode.md, F9, the server's half). The oldest splice a laptop may run against this
// server, `[server.client] minimum`, and what an older one is told. Each launch names its splice in `x-splice-client`,
// or in the header `[server.client] version_header` names for a deployment's own launcher (#791); a launcher that sends
// none on a turn may report its version once at `POST /server/client/checkin` (ClientCheckins), and the person's
// latest report then stands for it. One older than the minimum, compared number by number, has its turn (on the
// Messages route, at the team's door, or a chat completion) answered in place of a turn and nothing reaches the
// upstream: by default with a 200 that says the version it runs, the minimum and `splice upgrade` in the conversation,
// which Claude Code shows once where it would retry a refusal about six times; with `outdated_status = 400` or `426` it
// is refused with that status instead. `outdated_message` replaces the words, `{version}` and `{minimum}` filled in. A
// version at or past the minimum is served, and so is a request that names none and has no check-in (a script's), or
// names something no version can be read from.
package splice.server.client

import io.ktor.server.application.ApplicationCall
import splice.core.client.ClientVersion
import splice.core.client.ClientVersionParser
import splice.core.topology.OUTDATED_IN_CONVERSATION
import splice.core.topology.ServerClient
import splice.core.turn.SERVED_NOTICE_HEADER
import splice.share.RefusalVoice

internal class ClientMinimum(private val client: ServerClient, private val checkins: ClientCheckins) {
    private val versions = ClientVersionParser()
    private val floor = client.floor

    /** What [call]'s laptop, [person]'s, is told in place of an answer when its splice is older than the minimum;
     *  else null. */
    fun told(call: ApplicationCall, person: String): String? {
        val floor = floor ?: return null
        val runs = runs(call, person)?.takeIf { it < floor } ?: return null
        return words(runs, floor)
    }

    /** What a laptop on [runs] is told about [floor]: the operator's words with both filled in, or splice's own. */
    private fun words(runs: ClientVersion, floor: ClientVersion): String {
        val own = client.outdatedMessage.takeIf(String::isNotBlank)
            ?: return "This laptop runs splice $runs, and this team server serves splice $floor or newer. " +
                "Run `splice upgrade` on this laptop, then start Claude Code again."
        return own.replace(VERSION_SLOT, runs.wire).replace(MINIMUM_SLOT, floor.wire)
    }

    /** The header a head is handed [told] in, to answer the turn with it in the conversation. */
    fun notice(told: String): Map<String, String> = mapOf(SERVED_NOTICE_HEADER to told)

    /** Refuses [call] in [voice] with [told] when an older laptop's turn is refused rather than answered in the
     *  conversation; false, with nothing answered, when it is answered. */
    suspend fun refused(call: ApplicationCall, voice: RefusalVoice, told: String): Boolean {
        if (client.outdatedStatus == OUTDATED_IN_CONVERSATION) return false
        voice.refuse(call, client.outdatedStatus, told)
        return true
    }

    /** The version [call] names in the header the server reads, a launcher's `name/1.2.3` as its `1.2.3`; else the one
     *  [person] last checked in with. */
    private fun runs(call: ApplicationCall, person: String): ClientVersion? {
        val sent = call.request.headers[client.header]?.trim()?.takeIf(String::isNotEmpty)
            ?: return checkins.of(person)
        return versions.parse(sent.substringAfterLast('/'))
    }
}

private const val VERSION_SLOT = "{version}"
private const val MINIMUM_SLOT = "{minimum}"
