// NEW: LAN share (docs/specs/lan-share.md). What a sharing machine offers, as its `GET /` answers it: the commands
// `splice share status` prints on the server, and `splice remote` checks an address against and lists on a node. A
// server-mode server (docs/specs/server-mode.md) offers the same to a key holder, so the ask carries this laptop's key
// for it when `splice login` signed in there.
package splice.share

import kotlinx.coroutines.CoroutineDispatcher
import java.net.URI

public class ShareDirectory(
    io: CoroutineDispatcher,
    /** The key this laptop holds for a server, which a server-mode server needs to answer at all (F2). */
    private val logins: ServerLogins? = null,
) {
    private val http = ShareHttp(io)

    /** What [server] (`http://devbox.lan:3097`) offers, or why it could not be had, in words naming it. Only a
     *  server-mode server answers 401 (a LAN share holds no identity): its key is missing, expired or revoked, so the
     *  refusal says how to sign in. */
    public suspend fun read(server: String): Asked<ShareInfo> = http.read(
        server,
        http.get(URI.create("$server/"), logins?.of(server)),
        not = "not as a machine sharing splice commands",
        unauthorized = "; sign in there first: splice login $server",
    ) { http.decoded(it, ShareInfo.serializer()) }

    /** What [server]'s door (door-network) launches `claude` with, or why it could not be had: a machine on an older
     *  splice answers no door, and a team server reached without a key answers 401, said with how to sign in. */
    public suspend fun door(server: String): Asked<SharedDoorLaunch> = http.read(
        server,
        http.get(URI.create("$server/$DOOR_SEGMENT/$HEAD_LAUNCH_ROUTE")),
        not = "not as a machine whose door is on its network",
        unauthorized = "; sign in there first: splice login $server",
    ) { http.decoded(it, SharedDoorLaunch.serializer()) }
}
