// NEW: v0.4.0 FEATURES.md §10 — the one network seam the local-runtime probe speaks through.
// Moved from dialect-openai-chat to provider-spi (V4-103): probing a user-managed runtime is a
// provider-contract concern, not a chat-dialect fact. The JDK implementation (JdkLocalHttp) stays
// in :app, which owns the network.
package splice.spi

public data class LocalHttpReply(val status: Int, val body: String)

/** The one seam to the network: GET or POST a URL, or null when the runtime is unreachable. */
public fun interface LocalHttp {
    public operator fun invoke(method: String, url: String, body: String?): LocalHttpReply?
}
