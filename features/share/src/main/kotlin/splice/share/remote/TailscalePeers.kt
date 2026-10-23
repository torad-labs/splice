// NEW: LAN share, phase 2, milestone 5 (docs/specs/lan-share.md). The machines on his tailnet, which are his own
// devices by definition: each online peer `tailscale status --json` lists is asked at its Tailscale IPv4 address, never
// this machine and never one that is offline. A machine with no Tailscale, or a status that does not parse, finds none.
package splice.share.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import splice.core.util.Cancellables

/** What `tailscale status --json` printed, or null when there is nothing to read (TailscaleCli). */
public fun interface TailscaleStatus {
    public fun json(): String?
}

public class TailscalePeers(private val status: TailscaleStatus) : RemoteSource {
    private val json = Json { ignoreUnknownKeys = true }

    override fun candidates(port: Int): List<RemoteCandidate> {
        val text = status.json() ?: return emptyList()
        val tailnet = Cancellables.runCatchingCancellable { json.decodeFromString<Tailnet>(text) }
            .fold(onSuccess = { it }, onFailure = { null })
        return tailnet?.peers.orEmpty().values.filter { it.online }
            .mapNotNull { peer -> peer.addresses.firstOrNull { ':' !in it } }
            .map { RemoteCandidate(it, port) }
    }
}

/** The status, cut to what is read: the peers, keyed by node key. This machine is `Self`, which is not read. */
@Serializable
private data class Tailnet(@SerialName("Peer") val peers: Map<String, TailnetPeer>? = null)

@Serializable
private data class TailnetPeer(
    @SerialName("TailscaleIPs") val addresses: List<String> = emptyList(),
    @SerialName("Online") val online: Boolean = false,
)
