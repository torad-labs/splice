// NEW: LAN share, phase 2, milestone 5 (docs/specs/lan-share.md). The advert (ShareAdvert) on a Mac: `dns-sd -R` asks the
// system's mDNSResponder to answer for this machine for as long as it runs, so the daemon holds one while it shares and
// ends it when it stops. A small shell around it ends it too when the daemon is gone without stopping (a crash, a kill),
// so no advert outlives the listener it names.
package splice.share

import splice.core.util.Cancellables
import splice.core.util.LogSink
import splice.core.util.SafeFailureText

internal class DnsSdAdvert(private val log: LogSink) : ShareAdvert {

    @Volatile
    private var running: Process? = null

    @Synchronized
    override fun on(name: String, port: Int) {
        off()
        val daemon = ProcessHandle.current().pid().toString()
        val advertise = ProcessBuilder("/bin/sh", "-c", DNS_SD_WATCHED, "splice-share-advert", name, "$port", daemon)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
        running = Cancellables.runCatchingCancellable { advertise.start() }
            .onFailure { log("[share] not advertised on this network: ${SafeFailureText.render(it)}\n") }
            .getOrNull()
        if (running != null) log("[share] advertised on this network as $name, port $port\n")
    }

    @Synchronized
    override fun off() {
        running?.let { shell ->
            shell.descendants().forEach { it.destroy() }
            shell.destroy()
        }
        running = null
    }
}

// why: `dns-sd -R <name> <type> <domain> <port> <txt>` registers until it is killed; the loop watches the daemon ($3)
// every five seconds and ends the registration once it is gone.
private const val DNS_SD_WATCHED = "/usr/bin/dns-sd -R \"$1\" _splice._tcp local. \"$2\" " + SHARE_MDNS_TEXT +
    " & while kill -0 \"$3\" 2>/dev/null; do sleep 5; done; kill $!"
