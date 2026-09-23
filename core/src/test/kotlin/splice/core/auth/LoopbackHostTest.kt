// NEW: v0.4.0 — the DNS-rebinding guard's predicate, pinned from both sides: every loopback spelling
// a local client sends is served, and every name a rebinding page can carry is refused, including
// the ones that merely START like a loopback name.
package splice.core.auth

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LoopbackHostTest {

    @Test
    fun `loopback names are served on any port and in any case`() {
        listOf(
            "127.0.0.1",
            "127.0.0.1:3096",
            "localhost",
            "localhost:3100",
            "LOCALHOST:3096",
            "[::1]",
            "[::1]:3096",
            " 127.0.0.1:3096 ",
        ).forEach { assertTrue(LoopbackHost.admits(it), it) }
    }

    @Test
    fun `an absent Host is a local non-browser client and is served`() {
        assertTrue(LoopbackHost.admits(null))
    }

    @Test
    fun `a rebinding page's name is refused, even one that begins like loopback`() {
        listOf(
            "attacker.example",
            "attacker.example:3096",
            "localhost.attacker.example:3096",
            "127.0.0.1.nip.io:3096",
            "[::1].attacker.example",
            "localhost:1.attacker.example",
            "0.0.0.0:3096",
            "",
            "192.168.1.5:3096",
        ).forEach { assertFalse(LoopbackHost.admits(it), it) }
    }
}
