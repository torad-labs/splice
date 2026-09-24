// NEW: v0.4.0 review — where a head's SessionStart resume hook calls back, as ONE value: the two halves
// are meaningless apart (a port with no credential 401s; a credential with no port calls nothing).
package splice.client.resume

import java.nio.file.Path

/** The daemon's control port, and the source of the 0600 file holding the turn key as one
 *  `Authorization` header line (TurnKey.headerFile), which the hook hands to `curl -H @file`. The
 *  file, not the session's ANTHROPIC_AUTH_TOKEN: a client-auth head plants no such variable, so a
 *  hook reading it never called, and that head's sessions were never recorded. */
public data class ResumeHookTarget(public val controlPort: Int, public val authHeader: AuthHeaderFile)

/** The header file, written current and returned by path. Asked on EVERY install, inside the install's
 *  guarded leg (v0.4.0 review round 2): resolved once at daemon start, a failed write threw out of
 *  the control plane's start with no hook to blame, and a file removed after boot stayed gone — every
 *  statusline tick and resume hook 401ing — until the daemon restarted. */
public fun interface AuthHeaderFile {
    public fun current(): Path
}
