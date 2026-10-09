package splice.launch

/** How the launched client reaches this head's gateway: the port, the bearer, the request timeout and
 *  the status line that posts back, plus whether the client keeps its own credentials. */
public data class LaunchGateway(
    val port: Int,
    /** Per-install local gateway credential; shared with the head's inbound verifier. */
    val inferenceToken: String,
    /** Claude Code's per-request timeout (API_TIMEOUT_MS) for THIS head: the daemon's whole-turn
     *  cap plus a grace, so the client always outlives the proxy's own wall and receives its honest
     *  verdict instead of aborting first. 2026-09-01: the daemon allowed a compaction 900s while
     *  Claude Code's 600s default gave up — every compaction over ten minutes ended as client_abort
     *  with the summary still streaming, and the ones that survived had 20-100s to spare. */
    val apiTimeoutMs: Long,
    val statuslineCommand: String, // per-head statusline command (…/statusline/<head>)
    /**
     * TRUE for a client-auth head: the client keeps its OWN Anthropic credentials and its own
     * /login (campaign claude-head). Every other head serves a FOREIGN vendor, so the recipe must
     * strip the client's Anthropic session and plant the gateway bearer instead — here that would
     * replace exactly the credential the head forwards upstream, and disabling /login would nail
     * shut the only door that can heal a 401.
     */
    val forwardClientAuth: Boolean = false,
)
