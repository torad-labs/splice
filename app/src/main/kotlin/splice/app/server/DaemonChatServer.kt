// NEW: server mode (docs/specs/server-mode.md, F4, openai-route). The daemon's side of the server listener's OpenAI
// route: one provider that speaks openai-chat, as the listener relays an OpenAI-style client's requests to it. Where
// the provider's server is comes from splice.toml; what a request to it carries of the provider's own is what its
// head's turns and health checks carry (UpstreamPools); and what the relay does to a chat request and to its stream
// is the chat dialect's (ChatPassthrough): the listener's module knows neither the provider nor its wire. Which of a
// caller's headers ride on to it (forward_headers) the daemon says of every head's provider (DaemonServer).
package splice.app.server

import splice.core.topology.ProviderConfig
import splice.dialect.chat.ChatPassthrough
import splice.dialect.chat.PassedChatStream
import splice.server.ChatAsk
import splice.server.ChatServer
import splice.share.StreamEdit
import splice.share.WholeAnswer
import splice.share.WholeEdit

internal class DaemonChatServer(
    /** The provider's key in splice.toml. */
    private val key: String,
    private val provider: ProviderConfig,
    private val pools: UpstreamPools,
) : ChatServer {
    private val passthrough = ChatPassthrough()

    override val base: String = provider.baseUrl.trimEnd('/')

    override fun headers(): Map<String, String> = pools.headers(key)

    override fun chat(sent: ByteArray): ChatAsk {
        val passed = passthrough.request(sent)
        val meter = passed.meter
        if (passed.streamed) {
            return ChatAsk(passed.body, PassedEdit(passed.stream()), reading = meter, model = passed.model)
        }
        // An answer that is no stream goes back as the server gave it, read on its way for the ledger.
        val whole = WholeEdit { body -> WholeAnswer.Given(body).also { meter.whole(body) } }
        return ChatAsk(passed.body, stream = null, whole, meter, passed.model)
    }
}

/** The chat dialect's reading of one relayed stream, as the relay asks for it. */
private class PassedEdit(private val stream: PassedChatStream) : StreamEdit {
    override fun event(event: String): String? = stream.event(event)

    override fun ending(): String? = stream.ending()
}
