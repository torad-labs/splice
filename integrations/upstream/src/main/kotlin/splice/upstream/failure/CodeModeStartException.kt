// NEW: only a proven pre-dispatch failure permits the saved source to start on retry.
package splice.upstream.failure

import java.io.IOException

/** The runtime failed before dispatching source. The cause is diagnostic, never client-visible. */
public class CodeModeStartException(cause: Exception) :
    IOException("Code-mode host failed before source dispatch", cause)
