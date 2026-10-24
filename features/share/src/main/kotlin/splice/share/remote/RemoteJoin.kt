// NEW: LAN share, phase 2, milestone 2 (docs/specs/lan-share.md). Joining a sharing machine, for `splice remote` and
// the console's Sharing tab alike: the join goes only over a connection that shows the certificate the machine stated
// in its info (trust on first contact), so the token it issues is only ever sent back to that certificate, which this
// machine pins from then on. The answer is words, not output: each surface says it its own way. Since round 15 of PR
// #419's review a refusal is said in the sharing machine's own words, which the join reads as RemoteUnanswered.
package splice.share.remote

import splice.core.util.Cancellables
import splice.core.util.SafeFailureText

/** Joins machines as [client], this machine's name as the sharing machine will list it. */
public class RemoteJoin(private val client: String) {

    public fun join(machine: RemoteFound): RemoteJoined {
        val server = machine.info.server
        val pin = machine.info.fingerprint
            ?: return RemoteJoined.Refused("$server runs a splice that shares without TLS; update it, then join again")
        val http = RemoteHttp(RemoteTrust(pin, server))
        return Cancellables.runCatchingCancellable { http.join(machine.address, machine.port, client) }.fold(
            onSuccess = { RemoteJoined.Joined(http, it.token, pin) },
            onFailure = { failure ->
                val said = (failure as? RemoteUnanswered)?.said
                val why = http.refusal(failure) ?: said ?: SafeFailureText.render(failure)
                RemoteJoined.Refused("$server did not let this machine join: $why")
            },
        )
    }
}

public sealed class RemoteJoined {
    /** Joined: [token] is this machine's on that one, [pin] the certificate it showed, and [http] trusts only that. */
    public class Joined(
        public val http: RemoteHttp,
        public val token: String,
        public val pin: String,
    ) : RemoteJoined() {
        // The token is password-equivalent on the machine that issued it; a log of this names the certificate only.
        override fun toString(): String = "Joined(pin=$pin)"
    }

    /** Not joined, and [why] in words a person can act on. */
    public data class Refused(public val why: String) : RemoteJoined()
}

/** A machine's name as a `[remotes]` key and a command suffix: lower case, anything but letters, digits and dashes as
 *  a dash. */
public object RemoteNames {
    public fun of(server: String): String =
        server.lowercase().replace(Regex("[^a-z0-9-]"), "-").trim('-').ifEmpty { "remote" }
}
