// NEW: server mode (docs/specs/server-mode.md, the readiness probe). What a dialect answers when a pooled server of its
// provider is asked to really complete (`[server.probe] kind = "completion"`): the request it is sent, and what of the
// answer proves the model behind the server can complete. Each dialect that pools implements it in its own module, so
// its wire shape stays there (the chat dialect's ChatReadinessProbe, the one dialect pools allow today); the pool owner
// in :app asks whichever one the head's arm hands it and never names a dialect.
package splice.upstream.readiness

/** One dialect's readiness question for a pooled server. */
public interface ReadinessProbe {
    /** The minimal completion of [model] asked of the server whose API root is [base], as a `base_url` spells one. */
    public fun request(base: String, model: String): ReadinessRequest

    /** What the server's answer of [status] and [body] says. */
    public fun read(status: Int, body: String): ReadinessReading
}

/** A readiness probe's request: the [url] it is posted to, and its JSON [body]. */
public data class ReadinessRequest(val url: String, val body: String)

/** What one server's answer to a readiness question came to: [ready] or not, and [said], how it answered, for the log.
 *  [said] never quotes the server's body, which is the server's own bytes. */
public data class ReadinessReading(val ready: Boolean, val said: String)
