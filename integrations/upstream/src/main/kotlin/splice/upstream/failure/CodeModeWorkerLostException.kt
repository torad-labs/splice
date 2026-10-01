// NEW: a vanished local worker is an infrastructure interruption, never a deterministic protocol verdict.
package splice.upstream.failure

import java.io.IOException

/** Safe fixed text separates a forced host cut from malformed protocol or guest-script failures. */
public class CodeModeWorkerLostException(cause: Throwable? = null) :
    IOException("Code-mode worker was stopped; source was not rerun", cause)
