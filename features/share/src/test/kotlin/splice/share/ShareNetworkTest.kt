// NEW: LAN share, the gap Marcos hit on 2026-10-23 (docs/specs/lan-share.md, Bounds). The goal: two machines on one
// LAN reach each other whatever address family they pick. A home router hands out an ISP-delegated IPv6 prefix, so
// this Mac reached torad-server from 2605:a601:aa80:1500:4c5b:… to 2605:a601:aa80:1500::2 — one hop, no router
// between them — and neither address is loopback, RFC 1918, link-local, 100.64/10 or fc00::/7, so the listener
// refused it as "not from a private network" and every imported command read unreachable. A machine on an IPv6 prefix
// one of this machine's own interfaces holds is on this LAN, as SLAAC makes a house's /64 the link itself. Anything
// past those prefixes is not, and neither is a public IPv4 subnet's neighbour.
package splice.share

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.InetAddress

internal class ShareNetworkTest {

    /** This machine as Marcos's LAN has it: the ISP's globally routable /64 and the router's /24. */
    private fun home() = ShareNetwork(
        "torad-server",
        emptyList(),
        AttachedPrefixes {
            listOf(
                SharePrefix(InetAddress.getByName("2605:a601:aa80:1500::2"), 64),
                SharePrefix(InetAddress.getByName("192.168.1.236"), 24),
            )
        },
    )

    /** A machine whose interfaces splice cannot read: the named private ranges are all it has left to go on. */
    private fun bare() = ShareNetwork("torad-server", emptyList(), AttachedPrefixes { emptyList() })

    @Test
    fun `a machine on this machine's own IPv6 prefix is on this LAN, globally routable though that prefix is`() {
        assertTrue(
            home().admitsSource("2605:a601:aa80:1500:4c5b:1f4e:1040:deb2"),
            "the Mac on the same /64 as this machine, one hop away",
        )
    }

    @Test
    fun `a machine one prefix past this machine's own is not on this LAN`() {
        assertFalse(home().admitsSource("2605:a601:aa80:1501::9"), "a neighbouring /64, a router away")
        assertFalse(home().admitsSource("203.0.113.7"), "a public IPv4 address on no interface here")
    }

    // A house's IPv4 is RFC 1918 behind its router, which the private ranges already admit. A public IPv4 subnet on an
    // interface is a datacenter's, shared with other customers' servers, and joining takes no code: its neighbours are
    // strangers, not the LAN.
    @Test
    fun `a neighbour on a public IPv4 subnet this machine holds is not on this LAN`() {
        val datacenter = ShareNetwork(
            "torad-server",
            emptyList(),
            AttachedPrefixes { listOf(SharePrefix(InetAddress.getByName("198.51.100.4"), 26)) },
        )
        assertFalse(datacenter.admitsSource("198.51.100.19"), "another customer's server in the same /26")
    }

    @Test
    fun `the private ranges are admitted with no interface read at all`() {
        listOf("127.0.0.1", "::1", "192.168.1.5", "10.3.2.1", "fe80::1", "100.64.0.3", "fd00::1").forEach { source ->
            assertTrue(bare().admitsSource(source), "$source is private on any machine")
        }
    }

    @Test
    fun `a name is never a source, and neither is an address outside every private range`() {
        assertFalse(bare().admitsSource("torad-server.lan"), "a name is not a literal")
        assertFalse(bare().admitsSource("203.0.113.7"), "a public address with nothing to put it on this LAN")
    }
}
