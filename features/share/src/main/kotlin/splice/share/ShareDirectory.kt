// NEW: LAN share (docs/specs/lan-share.md). What a sharing machine offers, as its `GET /` answers it: the commands
// `splice share status` prints on the server, and `splice remote` checks an address against and lists on a node.
package splice.share

import kotlinx.coroutines.CoroutineDispatcher
import splice.core.wire.HttpStatus
import java.net.URI

/** What reading a sharing machine's offer came to: the offer, or why it could not be had, in words naming it. */
public sealed class ShareDirectoryRead {
    public data class Read(val info: ShareInfo) : ShareDirectoryRead()

    public data class Refused(val why: String) : ShareDirectoryRead()
}

public class ShareDirectory(io: CoroutineDispatcher) {
    private val http = ShareHttp(io)

    /** What [server] (`http://devbox.lan:3097`) offers. */
    public suspend fun read(server: String): ShareDirectoryRead {
        val asked = when (val answer = http.get(URI.create("$server/"))) {
            is ShareAnswer.Unreachable -> return refused("$server can't be reached: ${answer.why}")
            is ShareAnswer.Answered -> answer
        }
        val info = asked.takeIf { it.status == HttpStatus.OK }?.let { http.decoded(it.body, ShareInfo.serializer()) }
        return when {
            asked.status != HttpStatus.OK -> refused("$server refused: ${http.said(asked.body)}")
            info == null -> refused("$server answered, but not as a machine sharing splice commands")
            else -> ShareDirectoryRead.Read(info)
        }
    }

    private fun refused(why: String): ShareDirectoryRead = ShareDirectoryRead.Refused(why)
}
