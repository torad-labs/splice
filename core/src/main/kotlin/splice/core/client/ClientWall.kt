// NEW: failure-handling slice 5 (2026-10-27). The header a launched session names its own client timeout in, so a held
// turn fits inside it (final-design.md section 9, "When a hold does not fit"). LaunchService plants it with the timeout
// it plants; a request without it comes from a session launched before slice 5, whose timeout was 960 s.
package splice.core.client

import splice.core.wire.HttpStatus

/** The client timeout of the session that sent a request, in milliseconds (ANTHROPIC_CUSTOM_HEADERS carries it). */
public const val CLIENT_WALL_HEADER: String = "x-splice-client-wall-ms"

/** The header each launch of a laptop names its splice's version in, as in `0.10.0`, which a team server holds against
 *  its `[server.client] minimum` (server mode, F9). */
public const val SPLICE_CLIENT_HEADER: String = "x-splice-client"

/** The `[server.client] outdated_status` that answers an older laptop's turn in the conversation, as a 200. */
public const val OUTDATED_IN_CONVERSATION: Int = HttpStatus.OK

/** custom-provider (issue #680, cp-identity): the header a team's wrapper names itself in, as in `acme-ai/1.4.2`, set in
 *  Claude Code's ANTHROPIC_CUSTOM_HEADERS. A provider with `send_version = true` is sent it as the session sent it; any
 *  other provider never is. */
internal const val SPLICE_WRAPPER_HEADER: String = "x-splice-wrapper"
