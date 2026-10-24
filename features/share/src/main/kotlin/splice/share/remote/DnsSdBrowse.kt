// NEW: LAN share, phase 2, milestone 5 (docs/specs/lan-share.md). The `_splice._tcp` services on a Mac's networks, for
// MdnsMachines, through the system's own mDNSResponder: macOS gives a process of ours no multicast of its own (a send to
// 224.0.0.251 answers "no route to host" from a terminal and from launchd alike), and `dns-sd` is how a tool asks the
// system to browse. `dns-sd -Z` runs for DNS_SD_BROWSE_MS and its SRV records name each service's host and port; each
// host, a `.local` name, is then resolved by the system resolver, which answers it over mDNS the same way.
package splice.share.remote

import splice.core.util.Cancellables
import java.net.InetAddress
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

internal class DnsSdBrowse : MdnsBrowse {
    private val runner = Executor { Thread.ofVirtual().name("splice-dns-sd").start(it) }

    override fun services(): List<MdnsService> = DnsSdRecords().targets(listing().orEmpty())
        .map { target ->
            CompletableFuture.supplyAsync({ MdnsService(addresses(target.host), target.port) }, runner)
        }
        .mapNotNull { waited(it, DNS_SD_RESOLVE_MS) }

    /** What `dns-sd -Z` printed while it browsed; it never exits on its own, so the browse is stopped. */
    private fun listing(): String? {
        val browse = ProcessBuilder(DNS_SD, "-Z", SPLICE_DNS_SD_TYPE, "local.")
            .redirectError(ProcessBuilder.Redirect.DISCARD)
        val process = Cancellables.runCatchingCancellable { browse.start() }
            .fold(onSuccess = { it }, onFailure = { null }) ?: return null
        val printed = CompletableFuture.supplyAsync({ process.inputReader().use { it.readText() } }, runner)
        process.waitFor(DNS_SD_BROWSE_MS, TimeUnit.MILLISECONDS)
        process.destroy()
        return waited(printed, DNS_SD_RESOLVE_MS)
    }

    private fun addresses(host: String): List<String> =
        Cancellables.runCatchingCancellable { InetAddress.getAllByName(host).map { it.hostAddress } }
            .fold(onSuccess = { it }, onFailure = { emptyList() })

    private fun <T> waited(future: CompletableFuture<T>, ms: Long): T? =
        try {
            future.get(ms, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            null
        } catch (_: ExecutionException) {
            null
        }
}

/** The services a `dns-sd -Z` listing names: the host and port of each SRV record, each once. Every other line (the
 *  header, PTR and TXT records) is passed over, a TXT record that reads like an SRV one included. */
internal class DnsSdRecords {
    private val srv = Regex("""^\S+\s+SRV\s+\d+\s+\d+\s+(\d+)\s+(\S+?)\.?(?:\s|$)""")

    fun targets(listing: String): List<RemoteCandidate> = listing.lineSequence()
        .mapNotNull { line -> srv.find(line) }
        .mapNotNull { match ->
            // A TCP port is sixteen bits, and none listens at 0.
            val port = match.groupValues[1].toIntOrNull()?.takeIf { it in 1..UShort.MAX_VALUE.toInt() }
            port?.let { RemoteCandidate(match.groupValues[2], it) }
        }
        .distinct()
        .toList()
}

// why: where macOS ships the mDNSResponder client tool.
private const val DNS_SD = "/usr/bin/dns-sd"

// why: the service type as dns-sd takes it, without the domain it is given apart.
private const val SPLICE_DNS_SD_TYPE = "_splice._tcp"

// why: mDNSResponder answers from its cache at once and from the network within a few hundred milliseconds; a second
// leaves the scan's probes time to reach what it names within the scan's two and a half.
private const val DNS_SD_BROWSE_MS = 1_000L

// why: a `.local` name the system resolver cannot answer in half a second belongs to a machine that is gone.
private const val DNS_SD_RESOLVE_MS = 500L
