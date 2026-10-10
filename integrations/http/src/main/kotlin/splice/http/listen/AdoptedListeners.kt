// NEW: listening sockets a socket manager (systemd) opened and handed this process, turned into Netty server channels
// so a head or the control plane serves on a port that stays open across the daemon's restart. The manager holds the
// listening socket, so a client that connects while the daemon is down waits in the kernel's accept queue and is
// answered by the next daemon instead of being refused.
//
// EVERY LINE HERE IS PUBLIC NETTY API, and that is the point: the first version reached into sun.nio.ch for a channel
// over an inherited descriptor, which the no-reflection wall refuses. The native epoll transport has a public
// constructor for exactly this, and the two things it cannot do by itself are handled by one parent-channel handler
// (see [InheritedSocketHandler]). Validation comes first: a descriptor is wrapped only once it is known to be a
// listening loopback socket on the port the boot parse resolved for that listener.
package splice.http.listen

import io.netty.bootstrap.ServerBootstrap
import io.netty.channel.ChannelFactory
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelOutboundHandlerAdapter
import io.netty.channel.ChannelPromise
import io.netty.channel.ServerChannel
import io.netty.channel.epoll.Epoll
import io.netty.channel.epoll.EpollServerSocketChannel
import java.net.SocketAddress

/** Every address splice listens on is loopback; a hand-off naming anything wider is refused. */
private const val LOOPBACK = "127.0.0.1"

/** What a socket manager handed over, once it has been checked, or why it was refused. */
public sealed class Adoption {
    public data class Ready(val listeners: AdoptedListeners) : Adoption()

    public data class Refused(val reasons: List<String>) : Adoption()
}

/** Everything an engine needs to serve on a socket it did not open, applied inside Ktor's `configureBootstrap`. */
public fun interface AdoptedBootstrap {
    public fun applyTo(bootstrap: ServerBootstrap)
}

/**
 * The adopted listeners by name. The descriptors outlive every engine built over them: a head restarted from the
 * control plane serves on the same socket, because the manager still holds it and a second bind would be refused.
 */
public class AdoptedListeners internal constructor(private val fds: Map<String, Int>) {
    // NO EVENT LOOP GROUP IS SET HERE, and that is deliberate: the engine keeps the groups it builds itself. Measured
    // 2026-10-10: handing one shared epoll group to the engine as its parent and child group failed the SECOND
    // engine over the same descriptor inside register, and left the manager socket reading `Bad file descriptor`;
    // with the groups left alone the descriptor is still listening after a stop and the next engine serves on it.
    // Ktor picks the epoll transport for its own groups whenever the native library is loadable, which adoption
    // already requires one line below (refused as `native_unavailable`), so an epoll channel registers on them.

    public fun has(name: String): Boolean = name in fds

    /** The bootstrap setup for [name]'s descriptor. Call it per engine start; each start gets its own channel. */
    public fun bootstrapFor(name: String): AdoptedBootstrap {
        val fd = fds.getValue(name)
        return AdoptedBootstrap { bootstrap ->
            bootstrap
                .channelFactory(ChannelFactory<ServerChannel> { EpollServerSocketChannel(fd) })
                .handler(InheritedSocketHandler())
        }
    }
}

/**
 * The two things an already-listening socket needs from its engine, both measured against a live systemd hand-off:
 *  - BIND is completed here and never propagated. The socket is bound and listening before this process started, so a
 *    second bind fails outright; the channel is active from the moment it registers, so it is already serving.
 *  - CLOSE becomes a DEREGISTER. The socket is the manager's, not this engine's: deregistering takes the descriptor
 *    out of the epoll set and leaves it listening, so the next engine over it registers cleanly. Closing it would
 *    leave the manager's port unanswered until the whole daemon restarted, and completing the close without
 *    deregistering fails the next start with `epoll_ctl: File exists`.
 */
private class InheritedSocketHandler : ChannelOutboundHandlerAdapter() {
    override fun bind(ctx: ChannelHandlerContext, localAddress: SocketAddress, promise: ChannelPromise) {
        promise.setSuccess()
    }

    override fun close(ctx: ChannelHandlerContext, promise: ChannelPromise) {
        ctx.deregister(promise)
    }
}

public class DescriptorAdoption {
    private val inspector: DescriptorInspector = ProcDescriptors()

    /**
     * [fds] is listener name to descriptor number and [ports] is listener name to the port the boot parse resolved for
     * it. Every defect is listed at once, and no descriptor is wrapped unless all of them pass.
     */
    public fun adopt(fds: Map<String, Int>, ports: Map<String, Int>): Adoption {
        val reasons = mutableListOf<String>()
        if (!Epoll.isAvailable()) reasons += nativeUnavailable()
        fds.forEach { (name, fd) ->
            val facts = inspector.inspect(fd)
            when {
                facts == null -> reasons += "malformed: descriptor $fd for '$name' is not an IPv4 TCP socket"
                facts.address != LOOPBACK || facts.port != ports[name] ->
                    reasons += "wrong_endpoint: '$name' is ${facts.address}:${facts.port}, " +
                        "expected $LOOPBACK:${ports[name]}"
                !facts.listening -> reasons += "non_listening: '$name' on port ${facts.port} is not listening"
            }
        }
        return if (reasons.isEmpty()) Adoption.Ready(AdoptedListeners(fds)) else Adoption.Refused(reasons)
    }

    /** Named the way a boot finding is: what is missing, and what to do about it. */
    private fun nativeUnavailable(): String {
        val cause = Epoll.unavailabilityCause()?.toString()?.lineSequence()?.firstOrNull().orEmpty()
        return "native_unavailable: the netty epoll transport did not load ($cause), and a socket this daemon " +
            "did not open can only be served through it. Install the netty-transport-native-epoll linux-x86_64 " +
            "artifact, or stop passing descriptors to this daemon so every listener binds its own port"
    }
}
