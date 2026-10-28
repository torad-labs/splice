// NEW: failure-handling slice 5 (2026-10-27). The header a launched session names its own client timeout in, so a held
// turn fits inside it (final-design.md section 9, "When a hold does not fit"). LaunchService plants it with the timeout
// it plants; a request without it comes from a session launched before slice 5, whose timeout was 960 s.
package splice.core.client

/** The client timeout of the session that sent a request, in milliseconds (ANTHROPIC_CUSTOM_HEADERS carries it). */
public const val CLIENT_WALL_HEADER: String = "x-splice-client-wall-ms"
