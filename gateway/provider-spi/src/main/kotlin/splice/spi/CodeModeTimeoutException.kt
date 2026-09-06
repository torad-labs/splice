// NEW: local worker deadlines are ordinary failures, distinct from parent cancellation.
package splice.spi

import java.io.IOException

/** A worker exchange exceeded its local deadline; the message never contains source or results. */
public class CodeModeTimeoutException(public val timeoutMillis: Long) : IOException("Code-mode worker timed out")
