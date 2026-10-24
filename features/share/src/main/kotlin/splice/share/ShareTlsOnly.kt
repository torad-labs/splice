// NEW: LAN share, phase 2, milestone 2 (docs/specs/lan-share.md). The first handler on every share connection: a
// connection that opens with a TLS record goes on to the TLS handler with its bytes untouched, and one that opens with
// anything else, a phase-1 client's plain HTTP, is answered in plain HTTP with [refusal] (the Anthropic error shape,
// so Claude Code on that machine prints it) and closed. Without it that client would read a TLS alert as garbage.
package splice.share

import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import io.netty.channel.ChannelFutureListener
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.ByteToMessageDecoder
import io.netty.handler.ssl.SslHandler

internal class ShareTlsOnly(private val refusal: String) : ByteToMessageDecoder() {
    override fun decode(ctx: ChannelHandlerContext, input: ByteBuf, out: MutableList<Any>) {
        if (input.readableBytes() < TLS_RECORD_HEADER_BYTES) return
        if (SslHandler.isEncrypted(input, false)) {
            // Leaving the pipeline hands the bytes read so far to the TLS handler behind this one.
            ctx.pipeline().remove(this)
            return
        }
        input.skipBytes(input.readableBytes())
        val body = refusal.toByteArray(Charsets.UTF_8)
        val head = "HTTP/1.1 426 Upgrade Required\r\nContent-Type: application/json\r\n" +
            "Content-Length: ${body.size}\r\nConnection: close\r\n\r\n"
        ctx.writeAndFlush(Unpooled.wrappedBuffer(head.toByteArray(Charsets.US_ASCII), body))
            .addListener(ChannelFutureListener.CLOSE)
    }
}

internal const val SHARE_PLAIN_HANDLER: String = "share-tls-only"

// why: a TLS record header is five bytes (type, version, length); fewer cannot yet tell TLS from plain HTTP.
private const val TLS_RECORD_HEADER_BYTES = 5
