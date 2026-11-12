// NEW: LAN share (docs/specs/lan-share.md). The address another machine reaches this one's share port by, as
// `splice share status` prints it. A name is printed only once this machine has checked that it resolves to one of its
// own addresses on the LAN, the ones the listener admits a request from: on a Mac on Tailscale, the bare host name
// resolves to its Tailscale address, which the listener turns away, while the name under `.lan` resolves to the LAN.
// With no such name, the LAN address itself is printed, which works until the router hands out another.
package splice.share

import splice.core.topology.ShareConfig
import splice.core.util.Cancellables
import splice.core.util.LogSink
import splice.core.util.SafeFailureText
import java.net.Inet4Address
import java.net.InetAddress
import java.net.UnknownHostException

/** This machine's share port as another machine on its LAN reaches it: [server] by the name that resolves there, or by
 *  its LAN address when no name does, or null when this machine has no address on a LAN at all; and [addresses], the
 *  same port by each of its LAN IPv4 addresses. */
public data class ShareReach(val server: String?, val addresses: List<String>)

public class ShareAddresses(private val share: ShareConfig, private val machine: String, log: LogSink) {
    private val prefixes = HostPrefixes(log)

    // The domains a house router, mDNS or a home DNS puts a machine's short name under, in the order a house most
    // often answers them.
    private val lanDomains = listOf("lan", "local", "home", "internal")

    public fun reach(): ShareReach {
        val admission = LanAdmission(machine, share.hostnames, prefixes)
        val lan = prefixes.now().orEmpty().map { it.address }
            .filter { !it.isLoopbackAddress && admission.refusal(it.hostAddress) == null }
        val named = names().firstOrNull { name -> resolved(name).any { it in lan } }
        val addresses = lan.filterIsInstance<Inet4Address>().map { it.hostAddress }.distinct()
        val host = named ?: addresses.firstOrNull()
        return ShareReach(host?.let(::at), addresses.map(::at))
    }

    private fun at(host: String): String = "http://$host:${share.port}"

    /** This machine's names, the person's own first: `[share] hostnames`, its full host name when that has a domain,
     *  then its short name under each domain a house router or mDNS puts it in, then the short name bare. */
    private fun names(): List<String> {
        val full = machine.lowercase().trimEnd('.')
        val short = full.substringBefore('.')
        val domained = listOf(full).filter { '.' in it }
        return (share.hostnames + domained + lanDomains.map { "$short.$it" } + short).distinct()
    }

    private fun resolved(name: String): List<InetAddress> = try {
        InetAddress.getAllByName(name).toList()
    } catch (_: UnknownHostException) {
        emptyList()
    }
}

/** This machine's name as the OS gives it, which an address another machine adds names and the listener answers to. */
public class ShareMachine(private val log: LogSink) {
    public fun name(): String = Cancellables.runCatchingCancellable { InetAddress.getLocalHost().hostName }
        .getOrElse { failure ->
            log("[share] this machine's name could not be read (${SafeFailureText.render(failure)})\n")
            LOCALHOST
        }
}

// What this machine is called when the OS can't say: an address naming it by IP is still answered.
private const val LOCALHOST = "localhost"
