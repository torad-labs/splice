// NEW: LAN share, phase 2, milestone 5 (docs/specs/lan-share.md). Reading what `dns-sd -Z _splice._tcp local.` prints
// on a Mac, where the system's mDNSResponder browses for splice (a process of ours is given no multicast there): every
// service it lists is read at the host and port its SRV record names, whatever the service is called, however often it
// is listed, and whatever its TXT record says. A property over generated listings, not a list of examples: the names
// carry dns-sd's escapes, and the TXT records carry text shaped like an SRV record.
package splice.share.remote

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import kotlin.random.Random

// why: fixed seeds, so a failure names the listing that broke and reruns the same way.
private const val SEEDS = 300

private const val HEADER = """Browsing for _splice._tcp.local.
DATE: ---Fri 23 Oct 2026---
 7:22:50.420  ...STARTING...

; To direct clients to browse a different domain, substitute that domain in place of '@'
lb._dns-sd._udp                                 PTR     @

; In the list of services below, the SRV records will typically reference dot-local Multicast DNS names.
"""

class DnsSdRecordsTest {

    private fun word(random: Random, alphabet: String, length: IntRange): String =
        List(random.nextInt(length.first, length.last + 1)) { alphabet[random.nextInt(alphabet.length)] }
            .joinToString("")

    /** An instance name as dns-sd prints one: escapes for a space, a dot and the bytes of a curly apostrophe. */
    private fun instance(random: Random): String = List(random.nextInt(1, 4)) {
        word(random, "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-", 1..12)
    }.joinToString(listOf("\\032", "\\.", "\\226\\128\\153", "-").random(random))

    private fun host(random: Random): String =
        word(random, "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-", 1..24) + ".local"

    private fun records(name: String, target: RemoteCandidate, random: Random): String {
        val pad = " ".repeat(random.nextInt(1, 30))
        val txt = listOf("\"path=/share/v1\"", "\"note=SRV 0 0 9 decoy.local.\"", "\"\"").random(random)
        val srv = "${random.nextInt(0, 9)} ${random.nextInt(0, 9)} ${target.port} ${target.host}."
        return """
            |_splice._tcp$pad PTR     $name._splice._tcp
            |$name._splice._tcp$pad SRV     $srv ; Replace with unicast FQDN of target host
            |$name._splice._tcp$pad TXT     $txt
            |
        """.trimMargin()
    }

    @Test
    fun `every service dns-sd lists is read at its host and port, whatever its name, however often it is listed`() {
        repeat(SEEDS) { seed ->
            val random = Random(seed)
            val services = List(random.nextInt(0, 6)) {
                instance(random) to RemoteCandidate(host(random), random.nextInt(1, 65_536))
            }
            val listed = (services + List(random.nextInt(0, 4)) { services.randomOrNull(random) }.filterNotNull())
                .shuffled(random)
            val zone = HEADER + listed.joinToString("") { (name, target) -> records(name, target, random) }
            assertEquals(services.map { it.second }.toSet(), DnsSdRecords().targets(zone).toSet(), "seed $seed:\n$zone")
        }
    }

    @Test
    fun `a listing with no service, or no listing at all, names none`() {
        assertEquals(emptyList<RemoteCandidate>(), DnsSdRecords().targets(HEADER))
        assertEquals(emptyList<RemoteCandidate>(), DnsSdRecords().targets(""))
    }
}
