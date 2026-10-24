// NEW: LAN share, phase 2, milestone 5 (docs/specs/lan-share.md). The machines on his tailnet, from what a real
// `tailscale status --json` run prints (a tailscale on the PATH that prints a captured status): each online peer is asked at its Tailscale IPv4 address, never this machine and never
// one that is offline. A Mac with no Tailscale, or a status that does not parse, finds none.
package splice.share.remote

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.util.EnvReader
import java.io.File

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

    @TempDir
    lateinit var dir: File

    /** What the source asks, when the tailscale on the PATH prints [status], or fails when it is null. */
    private fun hosts(status: String?): List<Pair<String, Int>> {
        val bin = dir.resolve("bin").apply { mkdirs() }
        val script = if (status == null) "exit 1" else "cat <<'STATUS'\n$status\nSTATUS"
        bin.resolve("tailscale").apply {
            writeText("#!/bin/sh\n$script\n")
            setExecutable(true)
        }
        val path = EnvReader { name -> bin.path.takeIf { name == "PATH" } }
        return TailscalePeers(TailscaleCli(path, emptyList())).candidates(PORT).map { it.host to it.port }
    }

    @Test
    fun `each online peer is asked at its Tailscale IPv4 address, never this machine or one offline`() {
        assertEquals(listOf("100.104.52.116" to PORT, "100.90.0.3" to PORT), hosts(STATUS))
    }

    @Test
    fun `a tailscale that fails, or a status that does not parse, finds no peer`() {
        assertEquals(emptyList<Pair<String, Int>>(), hosts(null))
        assertEquals(emptyList<Pair<String, Int>>(), hosts("tailscale: not running"))
        assertEquals(emptyList<Pair<String, Int>>(), hosts("{\"Self\": {}}"))
    }
}
