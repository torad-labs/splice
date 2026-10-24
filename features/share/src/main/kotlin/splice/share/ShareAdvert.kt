// NEW: LAN share, phase 2, milestone 5 (docs/specs/lan-share.md). What this machine tells its private networks while it
// shares: `_splice._tcp` under its name, at the port it listens on, so `splice remote` and the Sharing page on his other
// machines find it at once, on its own port, with no subnet sweep. On a Mac through the system's own mDNSResponder
// (DnsSdAdvert), which is the only multicast macOS gives a process of ours; everywhere else through JmDNS (JmdnsAdvert).
package splice.share

import splice.core.util.LogSink

internal interface ShareAdvert {

    /** Advertises this machine as [name] at [port], in place of what it advertised before. */
    fun on(name: String, port: Int)

    /** Stops advertising. */
    fun off()
}

/** Which multicast DNS this machine uses: the system's on a Mac, JmDNS everywhere else. */
internal class SystemMdns {
    val mac: Boolean = System.getProperty("os.name").orEmpty().startsWith("Mac")

    fun advert(log: LogSink): ShareAdvert = if (mac) DnsSdAdvert(log) else JmdnsAdvert(log)
}

/** The multicast DNS service type sharing machines advertise and `splice remote` browses for. */
internal const val SHARE_MDNS_TYPE = "_splice._tcp.local."

/** The TXT record every advert carries; what a machine offers is read from /share/v1/info, never from here. */
internal const val SHARE_MDNS_TEXT = "path=/share/v1"
