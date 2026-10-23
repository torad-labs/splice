// NEW: LAN share, phase 2, milestone 5 (docs/specs/lan-share.md). The machines on his tailnet, from what
// `tailscale status --json` says: each online peer is asked at its Tailscale IPv4 address, never this machine and never
// one that is offline. A Mac with no Tailscale, or a status that does not parse, finds none.
package splice.share.remote

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

// why: the default share port, which each peer is asked at.
private const val PORT = 3097

// The shape `tailscale status --json` prints (1.9x), cut to the fields the source reads.
private val STATUS = """
    {
      "Self": {"HostName": "Marcos's Mac mini", "TailscaleIPs": ["100.101.88.58", "fd7a:115c:a1e0::3b2e:583b"],
               "Online": true},
      "Peer": {
        "nodekey:a": {"HostName": "torad-server", "TailscaleIPs": ["100.104.52.116", "fd7a:115c:a1e0::2"],
                      "Online": true},
        "nodekey:b": {"HostName": "codex-environment", "TailscaleIPs": ["100.70.92.57"], "Online": false},
        "nodekey:c": {"HostName": "studio", "TailscaleIPs": ["fd7a:115c:a1e0::9", "100.90.0.3"], "Online": true}
      }
    }
""".trimIndent()

class TailscalePeersTest {

    private fun hosts(status: String?) = TailscalePeers { status }.candidates(PORT).map { it.host to it.port }

    @Test
    fun `each online peer is asked at its Tailscale IPv4 address, never this machine or one offline`() {
        assertEquals(listOf("100.104.52.116" to PORT, "100.90.0.3" to PORT), hosts(STATUS))
    }

    @Test
    fun `no tailscale here, or a status that does not parse, finds no peer`() {
        assertEquals(emptyList<Pair<String, Int>>(), hosts(null))
        assertEquals(emptyList<Pair<String, Int>>(), hosts("tailscale: not running"))
        assertEquals(emptyList<Pair<String, Int>>(), hosts("{\"Self\": {}}"))
    }
}
