package splice.core.topology

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** `[server.state]`: one external source the operator's own automation keeps current, saying whether the upstream
 *  behind this server is up, starting or off, read every [everySeconds]. Exactly one of [file], [url] and [command]
 *  names the source; a table naming none reads nothing and refuses boot, tuning keys alone included. [messageStarting]
 *  and [messageOff] are the words a refused turn carries (a source may bring its own); `{until}` in [messageOff] is
 *  where the time the upstream is off until goes. [retryAfterStarting] is the `Retry-After` a turn refused while the
 *  upstream is starting carries, and the one an off signal with no future `until` falls back to. */
@Serializable
public data class ServerState(
    /** A file holding the state, as plain text (`up`, `starting`, `off`, `off until <RFC3339>`) or JSON. */
    val file: String? = null,
    /** An http(s) URL a GET reads the state from, in the same plain-text or JSON form as [file]. */
    val url: String? = null,
    /** A command run as its argv stands, its standard output read as the state, in the same form as [file]. */
    val command: List<String>? = null,
    @SerialName("every_seconds") val everySeconds: Int = 5,
    @SerialName("message_starting") val messageStarting: String = "Upstream is starting; retry shortly.",
    @SerialName("message_off") val messageOff: String = "Upstream is off until {until}.",
    @SerialName("retry_after_starting") val retryAfterStarting: Int = 30,
)

/** `[server.probe]`: what a pooled upstream is asked before its health is believed. [kind] `health` asks its
 *  `/health` as before; `completion` asks it to really complete a minimal request for [model] (required then, and
 *  only for the chat-completions dialect pools allow today), which must answer within [timeoutSeconds].
 *  [everySeconds] is how often the completion probe runs; a health probe keeps its provider's `health_seconds`.
 *  [atStart] gates a completion probe's members at start: true (the default) holds each out until its first probe
 *  passes, false serves it from the start as a health probe always does. */
@Serializable
public data class ServerProbe(
    val kind: String = "health",
    val model: String? = null,
    @SerialName("timeout_seconds") val timeoutSeconds: Int = 30,
    @SerialName("every_seconds") val everySeconds: Int = 60,
    @SerialName("at_start") val atStart: Boolean = true,
)
