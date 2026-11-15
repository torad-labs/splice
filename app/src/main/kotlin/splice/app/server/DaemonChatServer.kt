// NEW: server mode (docs/specs/server-mode.md, F4, openai-route). The daemon's side of the server listener's OpenAI
// route: one provider that speaks openai-chat, as the listener relays an OpenAI-style client's requests to it. Where
// the provider's server is comes from splice.toml, what a request to it carries is what its head's turns and health
// checks carry (UpstreamPools), and what the relay does to a chat request and to its stream is the chat dialect's
// (ChatPassthrough): the listener's module knows neither the provider nor its wire.
package splice.app.server

import splice.app.provider.UpstreamPools
import splice.dialect.chat.ChatPassthrough
import splice.dialect.chat.PassedChatStream
import splice.server.ChatAsk
import splice.server.ChatServer
import splice.share.StreamEdit

internal class DaemonChatServer(
    override val base: String,
    /** The provider's key in splice.toml. */
    private val provider: String,
    private val pools: UpstreamPools,
) : ChatServer {
    private val passthrough = ChatPassthrough()

    override fun headers(): Map<String, String> = pools.headers(provider)

    override fun chat(sent: ByteArray): ChatAsk {
        val passed = passthrough.request(sent)
        return ChatAsk(passed.body, if (passed.streamed) PassedEdit(passed.stream()) else null)
    }
}

/** The chat dialect's reading of one relayed stream, as the relay asks for it. */
private class PassedEdit(private val stream: PassedChatStream) : StreamEdit {
    override fun event(event: String): String? = stream.event(event)

    override fun ending(): String? = stream.ending()
}
