// NEW: LAN share, the gap Marcos hit on 2026-10-23 (docs/specs/lan-share.md, "Trust the LAN"). The goal: two
// machines on one LAN reach each other whatever address family they pick, and joining, which takes no code, stays
// closed to anyone who is not on that LAN. A home router hands out an ISP-delegated IPv6 prefix, so this Mac reached
// torad-server from 2605:a601:aa80:1500:4c5b:… to its 2605:a601:aa80:1500:… /64, one hop apart, and neither address is
// loopback, RFC 1918, link-local, 100.64/10 or fc00::/7: the listener refused it and every imported command read
// unreachable. A house's interface holds that /64 beside an RFC 1918 address its router NATs. A datacenter server's
// interface holds a public IPv4 address, and some providers (Linode) put a /64 every customer in the datacenter shares
// on it: those neighbours are strangers, not the LAN (round 7).
package splice.share

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.InetAddress

internal class ShareNetworkTest {

    private fun prefix(address: String, bits: Int, link: String) =
        SharePrefix(InetAddress.getByName(address), bits, link)

    /** torad-server as it is: enp8s0 holds the router's /24, a DHCPv6 /128 and a SLAAC address on the house's /64;
     *  tailscale0 holds its 100.64/10 and ULA addresses. */
    private fun home() = ShareNetwork(
        "torad-server",
        emptyList(),
        AttachedPrefixes {
            listOf(
                prefix("192.168.1.236", 24, "enp8s0"),
                prefix("2605:a601:aa80:1500::2", 128, "enp8s0"),
                prefix("2605:a601:aa80:1500:d347:d258:75ac:12c5", 64, "enp8s0"),
                prefix("100.104.52.116", 32, "tailscale0"),
                prefix("fd7a:115c:a1e0::3d01:34da", 128, "tailscale0"),
            )
        },
    )

    /** A Linode: eth0 holds its public IPv4 and an EUI-64 address on the /64 the datacenter's customers share. */
    private fun linode(vararg more: SharePrefix) = ShareNetwork(
        "linode",
        emptyList(),
        AttachedPrefixes {
            listOf(
                prefix("198.51.100.4", 24, "eth0"),
                prefix("2600:3c02::f03c:91ff:fe24:3a2f", 64, "eth0"),
            ) + more
        },
    )

    /** A machine whose interfaces splice cannot read: the named private ranges are all it has left to go on. */
    private fun bare() = ShareNetwork("torad-server", emptyList(), AttachedPrefixes { emptyList() })

    @Test
    fun `a machine on the house's IPv6 prefix is on this LAN, globally routable though that prefix is`() {
        assertTrue(
            home().admitsSource("2605:a601:aa80:1500:4c5b:1f4e:1040:deb2"),
            "the Mac on the same /64 as torad-server, one hop away",
        )
    }

    @Test
    fun `a machine one prefix past this machine's own is not on this LAN`() {
        assertFalse(home().admitsSource("2605:a601:aa80:1501::9"), "a neighbouring /64, a router away")
        assertFalse(home().admitsSource("203.0.113.7"), "a public IPv4 address on no interface here")
    }

    @Test
    fun `another customer on a datacenter's shared IPv6 prefix is not on this LAN`() {
        assertFalse(linode().admitsSource("2600:3c02::f03c:91ff:fe99:1"), "a neighbour on the datacenter's /64")
    }

    @Test
    fun `a neighbour on a public IPv4 subnet this machine holds is not on this LAN`() {
        assertFalse(linode().admitsSource("198.51.100.19"), "another customer's server in the same /24")
    }

    @Test
    fun `an interface with no IPv4 address says nothing about whose prefix it holds, so it admits no one`() {
        val v6only = ShareNetwork(
            "v6only",
            emptyList(),
            AttachedPrefixes { listOf(prefix("2001:db8:7::10", 64, "eth0")) },
        )
        assertFalse(v6only.admitsSource("2001:db8:7::20"), "an IPv6-only interface could be a datacenter's")
    }

    @Test
    fun `a house interface admits its own prefix while a datacenter interface on the same machine admits none`() {
        val both = linode(
            prefix("192.168.7.2", 24, "wlan0"),
            prefix("2001:db8:aa::5", 64, "wlan0"),
        )
        assertTrue(both.admitsSource("2001:db8:aa::77"), "a machine on the house's /64, through wlan0")
        assertFalse(both.admitsSource("2600:3c02::f03c:91ff:fe99:1"), "still not the datacenter's neighbour")
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
