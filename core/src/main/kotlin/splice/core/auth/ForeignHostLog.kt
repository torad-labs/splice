// NEW: v0.4.0 review — the DNS-rebinding guard ([LoopbackHost]) refused in silence on both listeners,
// so a page probing the daemon from the operator's browser left no trace while ClientAuth's own
// refusals were logged. One line per DISTINCT name, bounded: the refused caller controls the Host
// and can loop requests, and a log it can flood is a log nobody reads.
package splice.core.auth

import splice.core.util.LogSafe
import splice.core.util.LogSink
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

// why: a real rebinding attack names one host, a few at most; sixteen names every plausible probe and
// bounds the log at seventeen lines for the daemon's lifetime whatever a page sends.
private const val MAX_NAMED_HOSTS = 16

/** Says in [log] that [listener] refused a request for its Host, once per name. */
public class ForeignHostLog(private val listener: String, private val log: LogSink) {
    private val named: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val capped = AtomicBoolean(false)

    public fun refused(host: String) {
        val name = LogSafe.str(host)
        if (name in named) return
        if (named.size < MAX_NAMED_HOSTS) {
            if (named.add(name)) {
                log(
                    "[security] $listener refused a request naming Host '$name' — not a loopback name: a web " +
                        "page that rebound its name to this machine (DNS rebinding), or a client reaching " +
                        "splice by another name; nothing ran\n",
                )
            }
        } else if (capped.compareAndSet(false, true)) {
            log(
                "[security] $listener has refused $MAX_NAMED_HOSTS foreign Hosts; more are refused " +
                    "without a line each\n",
            )
        }
    }
}
