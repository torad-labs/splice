// NEW: server mode (docs/specs/server-mode.md, F4, openai-route). The daemon's side of the server listener's OpenAI
// route: one provider that speaks openai-chat, as the listener relays an OpenAI-style client's requests to it. Where
// the provider's server is, and which of a caller's headers ride on to it (forward_headers), come from splice.toml;
// what a request to it carries of the provider's own is what its head's turns and health checks carry
// (UpstreamPools); and what the relay does to a chat request and to its stream is the chat dialect's
// (ChatPassthrough): the listener's module knows neither the provider nor its wire.
package splice.app.server

import splice.app.provider.UpstreamPools
import splice.core.topology.ProviderConfig
import splice.dialect.chat.ChatPassthrough
import splice.dialect.chat.PassedChatStream
import splice.server.ChatAsk
import splice.server.ChatServer
import splice.share.StreamEdit

internal class DaemonChatServer(
    /** The provider's key in splice.toml. */
    private val key: String,
    private val provider: ProviderConfig,
    private val pools: UpstreamPools,
) : ChatServer {
    private val passthrough = ChatPassthrough()

    override val base: String = provider.baseUrl.trimEnd('/')

    override val forwarded: List<String> = provider.forwarded.carried

    override fun headers(): Map<String, String> = pools.headers(key)

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
