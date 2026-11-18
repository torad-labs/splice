// NEW: server mode (docs/specs/server-mode.md, F8 and F9, the laptop's half). What a launch of a command a team's server
// serves carries beyond any other remote command's: each launch names this laptop's splice to the server in
// `x-splice-client`, so the server can tell an older one what to upgrade to; and on a server whose profile names a
// telemetry relay, Claude Code's own metrics and logs are exported there, with the person's key sent by a headers
// helper and never put in the session's environment.
package splice.launch

/** A command linked from a team's server, and the [telemetry] relay its profile named, or null when it names none or
 *  the command's managed settings send telemetry of their own. */
public data class TeamServerLaunch(val telemetry: LaunchTelemetry? = null)

/** Where the session exports its telemetry: the relay's OTLP/HTTP [endpoint] (`<server>/server/otlp`), and the
 *  `otelHeadersHelper` command that prints the headers carrying the person's key. */
public data class LaunchTelemetry(val endpoint: String, val headersHelper: String)
