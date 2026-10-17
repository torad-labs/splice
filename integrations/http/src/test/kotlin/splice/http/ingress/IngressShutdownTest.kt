// NEW: the ingress input halves forward what they do not own and release exactly once what they drop.
package splice.http.ingress

import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.SimpleUserEventChannelHandler
import io.netty.channel.embedded.EmbeddedChannel
import io.netty.channel.socket.ChannelInputShutdownEvent
import io.netty.handler.codec.http.DefaultHttpContent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class IngressShutdownTest {

    @Test
    fun `drain releases a dropped buffer exactly once while draining`() {
        val ownership = IngressOwnership(null).apply { draining.set(true) }
        val channel = EmbeddedChannel(IngressDrain(ownership))
        val buffer = Unpooled.buffer(4)
        channel.writeInbound(buffer)
        // A second release would throw here, inside the pipeline, and EmbeddedChannel rethrows it.
        assertEquals(0, buffer.refCnt())
        assertNull(channel.readInbound<ByteBuf>())
        assertFalse(channel.finish())
    }

    @Test
    fun `drain forwards an open buffer unchanged and unreleased`() {
        val ownership = IngressOwnership(null)
        val channel = EmbeddedChannel(IngressDrain(ownership))
        val buffer = Unpooled.buffer(4)
        channel.writeInbound(buffer)
        val forwarded = channel.readInbound<ByteBuf>()
        assertSame(buffer, forwarded)
        assertEquals(1, forwarded.refCnt())
        forwarded.release()
        assertFalse(channel.finish())
    }

    @Test
    fun `drain forwards a message it does not own unchanged and unreleased, even while draining`() {
        val ownership = IngressOwnership(null).apply { draining.set(true) }
        val channel = EmbeddedChannel(IngressDrain(ownership))
        val holder = DefaultHttpContent(Unpooled.buffer(4))
        channel.writeInbound(holder)
        val forwarded = channel.readInbound<DefaultHttpContent>()
        assertSame(holder, forwarded)
        assertEquals(1, holder.refCnt())
        holder.release()
        assertFalse(channel.finish())
    }

    @Test
    fun `input shutdown closes the connection only while draining`() {
        val ownership = IngressOwnership(null)
        val tap = EventTap(Any::class.java)
        val channel = EmbeddedChannel(IngressInputShutdown(ownership), tap)
        channel.pipeline().fireUserEventTriggered(ChannelInputShutdownEvent.INSTANCE)
        assertTrue(channel.isOpen)
        assertEquals(listOf<Any>(ChannelInputShutdownEvent.INSTANCE), tap.seen)
        ownership.draining.set(true)
        channel.pipeline().fireUserEventTriggered(ChannelInputShutdownEvent.INSTANCE)
        assertFalse(channel.isOpen)
        assertEquals(1, tap.seen.size)
    }

    @Test
    fun `input shutdown forwards any other user event unchanged, even while draining`() {
        val ownership = IngressOwnership(null).apply { draining.set(true) }
        val tap = EventTap(Any::class.java)
        val channel = EmbeddedChannel(IngressInputShutdown(ownership), tap)
        val other = Any()
        channel.pipeline().fireUserEventTriggered(other)
        assertTrue(channel.isOpen)
        assertEquals(listOf(other), tap.seen)
        channel.finish()
    }
}

/** Records the user events that reach the end of the pipeline, so a test can see exactly what was forwarded. */
private class EventTap<E : Any>(type: Class<E>) : SimpleUserEventChannelHandler<E>(type, false) {
    val seen = mutableListOf<E>()

    override fun eventReceived(ctx: ChannelHandlerContext, event: E) {
        seen += event
    }
}
