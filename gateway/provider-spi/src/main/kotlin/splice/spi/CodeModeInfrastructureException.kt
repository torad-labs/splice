// NEW: bounded worker infrastructure diagnostics cross the isolated process boundary without source or stderr.
package splice.spi

import java.io.IOException

/** Safe categories emitted by the bundled code-mode worker. */
public enum class CodeModeInfrastructureCategory {
    /** Framed input or output did not satisfy the worker protocol. */
    PROTOCOL,

    /** The isolated host runtime failed outside guest JavaScript execution. */
    HOST,
}

/** Safe fault classes emitted by the bundled code-mode worker. */
public enum class CodeModeInfrastructureClass {
    /** An I/O or framing failure interrupted the worker protocol. */
    IO,

    /** An unexpected host runtime failure interrupted the worker. */
    RUNTIME,
}

/** A bounded worker failure; its message deliberately excludes source, results, and stderr. */
public class CodeModeInfrastructureException(
    public val category: CodeModeInfrastructureCategory,
    public val faultClass: CodeModeInfrastructureClass,
) : IOException("Code-mode worker infrastructure failure: $category/$faultClass")
