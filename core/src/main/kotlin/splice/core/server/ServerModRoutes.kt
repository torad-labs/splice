// NEW: server mode (docs/specs/server-mode.md, "Mode routes"). What a team server's listener hands a mode under
// `/server/mod/<name>/...` once a key was admitted, and what the mode answers. The listener knows the key; a mode never
// does: it is told who is asking (their email, their team, whether they are one of the server's admins) and nothing a
// caller could present again. The daemon decides which modes serve here (`[modes.<name>] server_routes`) and which
// only for admins (`server_admin_only`); the listener says each refusal in its own voice.
package splice.core.server

import splice.core.topology.DOOR_SEGMENT

/** Who is asking: the person a key was issued to (a lowercased email), their team when one is known, and whether
 *  `[server] admins` names them. Never the key, nor anything derived from it. */
public data class ModCaller(val person: String, val team: String?, val admin: Boolean)

/** One request to a mode: its [method], the [path] after `/server/mod/<name>`, segment by segment and decoded, the
 *  [caller], the few [headers] a mode may read (lowercased names, from [modHeaders]), the [query] (first value of each
 *  name), and its [body], read under the listener's cap. */
public class ModRequest(
    public val method: String,
    public val path: List<String>,
    public val caller: ModCaller,
    public val headers: Map<String, String>,
    public val query: Map<String, String>,
    public val body: ByteArray,
) {
    /** The body as UTF-8 text. */
    public fun text(): String = body.decodeToString()
}

/** A mode's answer: its HTTP [status], the [contentType] of its [body], and the body itself. */
public class ModResponse(public val status: Int, public val contentType: String, public val body: ByteArray) {
    /** The body as UTF-8 text. */
    public fun text(): String = body.decodeToString()
}

/** What became of a request for one mode. */
public sealed class ModAnswer {
    /** No mode of that name serves the listener: none is carried, or splice.toml does not set its `server_routes`. */
    public data object NoSuchMod : ModAnswer()

    /** The mode serves only the server's admins (`server_admin_only`), and the caller is not one. */
    public data object AdminsOnly : ModAnswer()

    /** The mode's answer; null when it has no such path. */
    public class Answered(public val response: ModResponse?) : ModAnswer()

    /** The mode's handler threw: [said] is what the caller is told, naming the exception's class and nothing it
     *  held. */
    public data class Failed(val said: String) : ModAnswer()
}

/** The modes a team server's listener reaches, as the daemon holds them now. */
public interface ServerModRoutes {
    /** [request] to the mode named [name]. */
    public fun answer(name: String, request: ModRequest): ModAnswer
}

/** The path segment after `/server` that a mode's routes are under: `/server/mod/<name>/...`. */
public const val SERVER_MOD_SEGMENT: String = "mod"

/** The headers a mode is handed, by lowercased name: what it needs to read a body and answer in kind. A credential
 *  (`authorization`, `x-api-key`, `cookie`) is never among them. */
public val modHeaders: Set<String> = setOf("accept", "content-type", "user-agent")

/** The first path segment of a team server's own routes; no command may be named it. */
public const val SERVER_ROOT: String = "server"

/** The path a team server's listener answers, with no key, that it is up. */
public const val SERVER_HEALTH_ROUTE: String = "health"

/** The first path segments a team server's listener answers itself: its own routes, its door and its health. A mode
 *  that serves the listener may not take one of these names, nor a served command's. */
internal val serverRoots: Set<String> = setOf(SERVER_ROOT, DOOR_SEGMENT, SERVER_HEALTH_ROUTE)
