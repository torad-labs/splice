// NEW: LAN share, phase 2, milestone 5 (docs/specs/lan-share.md). Finding the sharing machines from every source at
// once (mDNS, Tailscale, the subnet probe): what `splice remote` and the Sharing page list. One machine reached by two
// sources is one entry, at the first source's address; machines are told apart by the certificate each serves, so two
// with one name stay two; a machine is kept at the port its source found it on; a source that fails leaves the others'
// machines found. Scanning is concurrent, so the rule is also held as a property over generated sources and answers.
package splice.share.remote

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import splice.share.ShareInfo
import java.io.IOException
import java.util.concurrent.CompletableFuture
import kotlin.random.Random

// why: the default share port, which the subnet and Tailscale sources ask at; mDNS says its own.
private const val PORT = 3097

// why: a port a machine advertised over mDNS instead of the default.
private const val OTHER_PORT = 4443

// why: fixed seeds, so a failure names the scan that broke and reruns the same way.
private const val DISCOVERY_SEEDS = 300

// An answer that is a failure, not a machine: the probe completes it exceptionally.
private val FAILED = ShareInfo("failed", "0", emptyList(), null)

class RemoteDiscoveryTest {

    private fun info(server: String, fingerprint: String?) = ShareInfo(server, "0.4.0", emptyList(), fingerprint)

    private fun discovery(answers: Map<String, ShareInfo>, vararg sources: RemoteSource) = RemoteDiscovery(
        RemoteInfoProbe { host, _ -> CompletableFuture.completedFuture(answers[host]) },
        sources.toList(),
    )

    private fun at(vararg hosts: String) = RemoteSource { port -> hosts.map { RemoteCandidate(it, port) } }

    @Test
    fun `one machine found by two sources is one entry, at the address the first source gave`() {
        val answers = mapOf(
            "192.168.1.20" to info("torad-server", "sha256:aa"),
            "100.104.52.116" to info("torad-server", "sha256:aa"),
            "192.168.1.30" to info("studio", "sha256:bb"),
        )
        val found = discovery(answers, at("100.104.52.116"), at("192.168.1.20", "192.168.1.30", "192.168.1.40"))
            .scan(PORT)
        assertEquals(
            listOf("100.104.52.116" to "torad-server", "192.168.1.30" to "studio"),
            found.map { it.address to it.info.server },
        )
    }

    @Test
    fun `two machines with one name stay two, told apart by their certificates`() {
        val answers = mapOf("192.168.1.20" to info("mac", "sha256:aa"), "192.168.1.21" to info("mac", "sha256:bb"))
        assertEquals(2, discovery(answers, at("192.168.1.20", "192.168.1.21")).scan(PORT).size)
    }

    @Test
    fun `a machine that states no certificate is one entry by its name`() {
        val answers = mapOf("192.168.1.20" to info("old", null), "192.168.1.21" to info("old", null))
        assertEquals(
            listOf("192.168.1.20"),
            discovery(answers, at("192.168.1.20", "192.168.1.21")).scan(PORT).map {
                it.address
            },
        )
    }

    @Test
    fun `a machine found on its own port is kept at that port`() {
        // Only an answer on the port the source named counts: the same host on the default port says nothing.
        val probe = RemoteInfoProbe { _, port ->
            CompletableFuture.completedFuture(info("torad-server", "sha256:aa").takeIf { port == 4443 })
        }
        val found = RemoteDiscovery(probe, listOf(RemoteSource { listOf(RemoteCandidate("192.168.1.20", 4443)) }))
            .scan(PORT)
        assertEquals(listOf("192.168.1.20" to 4443), found.map { it.address to it.port })
    }

    @Test
    fun `a machine that has not answered by the deadline is left out, and the ones that answered are found`() {
        val probe = RemoteInfoProbe { host, _ ->
            if (host == "192.168.1.20") {
                CompletableFuture.completedFuture(info("torad-server", "sha256:aa"))
            } else {
                CompletableFuture()
            }
        }
        val found = RemoteDiscovery(probe, listOf(at("192.168.1.99", "192.168.1.20"))).scan(PORT)
        assertEquals(listOf("torad-server"), found.map { it.info.server })
    }

    @Test
    fun `whatever the sources name and the machines answer, each machine is found once, where it first answered`() {
        val hosts = List(6) { "192.168.1.${it + 20}" }
        val machines = listOf(
            info("torad-server", "sha256:aa"),
            info("torad-server", "sha256:bb"),
            info("mac", "sha256:cc"),
            info("old", null),
            info("mac", null),
        )
        repeat(DISCOVERY_SEEDS) { seed ->
            val random = Random(seed)
            val answers = hosts.flatMap { host -> listOf(PORT, OTHER_PORT).map { RemoteCandidate(host, it) } }
                .associateWith { listOf<ShareInfo?>(null, null, FAILED).plus(machines).random(random) }
            val named = List(random.nextInt(1, 5)) {
                if (random.nextInt(5) == 0) {
                    null
                } else {
                    List(random.nextInt(0, 7)) {
                        RemoteCandidate(hosts.random(random), listOf(PORT, OTHER_PORT).random(random))
                    }
                }
            }
            val sources = named.map { candidates -> RemoteSource { candidates ?: throw IOException("could not look") } }
            val probe = RemoteInfoProbe { host, port ->
                when (val answer = answers.getValue(RemoteCandidate(host, port))) {
                    FAILED -> CompletableFuture.failedFuture(IOException("refused"))
                    else -> CompletableFuture.completedFuture(answer)
                }
            }
            // The rule, read straight off the goal: in source order, then the order each source named them, the first
            // address where each machine answered; a machine is its certificate, or its name when it states none.
            val expected = named.filterNotNull().flatten()
                .mapNotNull { candidate ->
                    answers.getValue(candidate)?.takeIf { it !== FAILED }?.let { candidate to it }
                }
                .distinctBy { (_, answer) -> answer.fingerprint ?: "name ${answer.server}" }
                .map { (candidate, answer) -> Triple(candidate.host, candidate.port, answer) }
            val found = RemoteDiscovery(probe, sources).scan(PORT).map { Triple(it.address, it.port, it.info) }
            assertEquals(expected, found, "seed $seed")
        }
    }

    @Test
    fun `a source that fails leaves the other sources' machines found`() {
        val answers = mapOf("192.168.1.20" to info("torad-server", "sha256:aa"))
        val broken = RemoteSource { throw IOException("tailscale is not installed") }
        assertEquals(
            listOf("torad-server"),
            discovery(answers, broken, at("192.168.1.20")).scan(PORT).map {
                it.info.server
            },
        )
    }
}
