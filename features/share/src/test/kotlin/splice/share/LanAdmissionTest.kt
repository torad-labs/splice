// Who the share listener answers (docs/specs/lan-share.md, "Who is on the LAN"): a source on this machine's own
// networks, naming this machine in its Host. Properties over generated addresses and names, against three machines:
// torad-server as it is (a house: its router's /24 and the ISP's /64 on one interface, Tailscale on another), a
// datacenter server (a public IPv4 address and the provider's shared networks on one interface), and a machine whose
// interfaces cannot be read, which cannot tell the two apart and so answers loopback alone.
package splice.share

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.InetAddress
import kotlin.random.Random

internal class LanAdmissionTest {
    private val random = Random(SEED)

    private fun prefix(address: String, bits: Int, link: String) =
        SharePrefix(InetAddress.getByName(address), bits, link)

    private val house = LanAdmission(
        "torad-server",
        listOf("splice.example.net"),
        AttachedPrefixes {
            listOf(
                prefix("192.168.1.236", 24, "enp8s0"),
                prefix("2605:a601:aa80:1500:d347:d258:75ac:12c5", 64, "enp8s0"),
                prefix("100.104.52.116", 32, "tailscale0"),
                prefix("fd7a:115c:a1e0::3d01:34da", 128, "tailscale0"),
            )
        },
    )

    private val datacenter = LanAdmission(
        "linode",
        emptyList(),
        AttachedPrefixes {
            listOf(
                prefix("198.51.100.4", 24, "eth0"),
                prefix("192.168.130.5", 17, "eth0"),
                prefix("2600:3c02::f03c:91ff:fe24:3a2f", 64, "eth0"),
            )
        },
    )

    private val unread = LanAdmission("torad-server", emptyList(), AttachedPrefixes { null })

    private fun octet() = random.nextInt(256)

    private fun hex() = random.nextInt(0x10000).toString(16)

    private fun loopback() = listOf("127.${octet()}.${octet()}.${random.nextInt(1, 255)}", "::1").random(random)

    private fun rfc1918() = listOf(
        "10.${octet()}.${octet()}.${octet()}",
        "172.${random.nextInt(16, 32)}.${octet()}.${octet()}",
        "192.168.${octet()}.${octet()}",
    ).random(random)

    private fun linkLocal() = listOf("169.254.${octet()}.${octet()}", "fe80::${hex()}:${hex()}").random(random)

    private fun uniqueLocal() = "fd${random.nextInt(256).toString(16).padStart(2, '0')}::${hex()}:${hex()}"

    // The first two octets of every range that is not the public internet: 10/8, loopback, 172.16/12, 192.168/16,
    // link-local and 100.64/10.
    private val notPublic = listOf(10 to 0..255, 127 to 0..255, 172 to 16..31, 192 to 168..168, 169 to 254..254)
        .plus(100 to 64..127)

    /** A public IPv4 address: none of loopback, the private ranges, link-local, 100.64/10, or the reserved top. */
    private fun publicIpv4(): String = generateSequence { random.nextInt(1, 224) to octet() }
        .first { (a, b) -> notPublic.none { (first, seconds) -> a == first && b in seconds } }
        .let { (a, b) -> "$a.$b.${octet()}.${octet()}" }

    private fun carrierGrade() = "100.${random.nextInt(64, 128)}.${octet()}.${octet()}"

    private fun onHouseSixtyFour() = "2605:a601:aa80:1500:${hex()}:${hex()}:${hex()}:${hex()}"

    /** A global IPv6 address off the house's /64: another /64 under the same /48, or anywhere else. */
    private fun offHouseSixtyFour() = listOf(
        "2605:a601:aa80:${random.nextInt(0x1501, 0x10000).toString(16)}::${hex()}",
        "2a00:${hex()}:${hex()}::${hex()}",
    ).random(random)

    @Test
    fun `loopback is answered on every machine, whatever its networks`() = repeat(CASES) {
        val source = loopback()
        listOf(house, datacenter, unread).forEach { machine -> assertNull(machine.refusal(source), source) }
    }

    @Test
    fun `a house answers its private ranges, link-local, unique local, and its own IPv6 prefix`() = repeat(CASES) {
        val source = listOf(rfc1918(), linkLocal(), uniqueLocal(), onHouseSixtyFour()).random(random)
        assertNull(house.refusal(source), source)
    }

    @Test
    fun `a house turns away the internet, Tailscale's range, and a prefix beside its own`() = repeat(CASES) {
        val source = listOf(publicIpv4(), carrierGrade(), offHouseSixtyFour()).random(random)
        val said = house.refusal(source)
        assertNotNull(said, source)
        assertTrue(said!!.contains("not from this machine's LAN"), said)
    }

    @Test
    fun `a datacenter turns away its provider's shared networks and every link-local source`() = repeat(CASES) {
        val shared = listOf(
            "192.168.${random.nextInt(128, 256)}.${octet()}",
            "2600:3c02::${hex()}:${hex()}",
            "198.51.100.${octet()}",
        ).random(random)
        assertNotNull(datacenter.refusal(shared), shared)
        val local = linkLocal()
        assertNotNull(datacenter.refusal(local), local)
    }

    @Test
    fun `a machine that cannot read its networks answers loopback alone, and says why`() = repeat(CASES) {
        val source = listOf(rfc1918(), linkLocal(), uniqueLocal(), publicIpv4()).random(random)
        val said = unread.refusal(source)
        assertNotNull(said, source)
        assertTrue(said!!.contains("could not read its own networks"), said)
    }

    @Test
    fun `a Host naming this machine, by address or by name, is answered`() = repeat(CASES) {
        val port = if (random.nextBoolean()) ":${random.nextInt(1, 65536)}" else ""
        val name = listOf(
            "torad-server",
            "torad-server.local",
            "torad-server.lan",
            "torad-server.home",
            "torad-server.internal",
            "torad-server.lan.",
            "splice.example.net",
        ).random(random).let { if (random.nextBoolean()) it.uppercase() else it }
        val host = listOf(
            name + port,
            rfc1918() + port,
            "[fd00::${hex()}]$port",
            "[2605:a601:aa80:1500::${hex()}]$port",
        ).random(random)
        assertTrue(house.admitsHost(host), host)
    }

    @Test
    fun `a Host naming any other machine is turned away, so a rebound web page cannot reach the heads`() =
        repeat(CASES) {
            val letters = "abcdefghijklmnopqrstuvwxyz"
            val label = (1..random.nextInt(1, 12)).map { letters.random(random) }.joinToString("")
            val host = listOf(
                "$label.example.com",
                "torad-server.$label.com",
                "$label-torad-server",
                "[zz:${label.take(2)}]",
            ).random(random)
            if (host.substringBefore(':').trimEnd('.').lowercase() != "torad-server") {
                assertFalse(house.admitsHost(host), host)
            }
        }

    @Test
    fun `a request with no Host is answered, as a plain HTTP 1 0 client sends none`() {
        assertEquals(true, house.admitsHost(null))
    }
}

private const val SEED = 20261024
private const val CASES = 400
