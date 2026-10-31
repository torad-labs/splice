// NEW: isolates the pinned Ktor HTTP/1 JVM accessors used to bind each original request.
package splice.http.ingress;

import io.ktor.server.application.ApplicationCall;
import io.ktor.server.netty.http1.NettyHttp1ApplicationRequest;
import io.netty.channel.Channel;
import io.netty.handler.codec.http.HttpRequest;

/**
 * Ktor exposes these accessors in its JVM ABI, while marking the implementation Kotlin-internal.
 * Keep that version-specific seam here. No reflection, wire header or FIFO call matching is used.
 */
final class NettyIngressRequest {
    public HttpRequest request(ApplicationCall call) {
        if (!(call.getRequest() instanceof NettyHttp1ApplicationRequest request)) return null;
        return request.getHttpRequest();
    }

    public Channel channel(ApplicationCall call) {
        if (!(call.getRequest() instanceof NettyHttp1ApplicationRequest request)) return null;
        return request.getContext().channel();
    }
}
