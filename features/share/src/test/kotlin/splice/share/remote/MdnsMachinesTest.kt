// NEW: LAN share, phase 2, milestone 5 (docs/specs/lan-share.md). The sharing machines that advertise `_splice._tcp`
// on this network: each is asked at its IPv4 address and at the port it advertised, which need not be the default. A
// service that resolved no IPv4 address is left out.
package splice.share.remote

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

// why: the default share port; an advertised machine is asked at its own instead.
private const val PORT = 3097

class MdnsMachinesTest {

    private fun hosts(vararg services: MdnsService) =
        MdnsMachines { services.toList() }.candidates(PORT).map { it.host to it.port }

    @Test
    fun `each machine advertised is asked at its IPv4 address and the port it advertised`() {
        assertEquals(
            listOf("192.168.1.20" to 3097, "192.168.1.30" to 4443),
            hosts(MdnsService(listOf("fe80::1", "192.168.1.20"), 3097), MdnsService(listOf("192.168.1.30"), 4443)),
        )
    }

    @Test
    fun `a service that resolved no IPv4 address is left out`() {
        val none = hosts(MdnsService(emptyList(), 3097), MdnsService(listOf("fe80::1"), 3097))
        assertEquals(emptyList<Pair<String, Int>>(), none)
    }
}
