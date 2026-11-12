package splice.upstream.codemode.availability

/** Cheap provider readiness, before a session or execution is opened. Startup failures stay distinct. */
public sealed class CodeModeAvailability {
    public data object Available : CodeModeAvailability()
    public data class Unavailable(val reason: String) : CodeModeAvailability()
}

/** The model's requested tool mode; an unavailable preferred mode retains direct tools. */
public enum class CodeModeToolMode { DIRECT, CODE_MODE, CODE_MODE_ONLY }

public const val CODE_MODE_HOST_DISABLED: String = "code-mode host is disabled"

/** Source-equivalent availability decisions without starting a runtime or manufacturing model content. */
public data class CodeModeAvailabilityPlan(
    private val availability: CodeModeAvailability,
    private val mode: CodeModeToolMode,
) {
    public val codeMode: Boolean
        get() = mode != CodeModeToolMode.DIRECT &&
            (availability is CodeModeAvailability.Available || mode == CodeModeToolMode.CODE_MODE_ONLY)

    public val rejection: String?
        get() {
            if (mode == CodeModeToolMode.DIRECT) return "unsupported custom tool call: exec"
            return when (availability) {
                CodeModeAvailability.Available -> null
                is CodeModeAvailability.Unavailable -> if (mode == CodeModeToolMode.CODE_MODE_ONLY) {
                    availability.reason
                } else {
                    "unsupported custom tool call: exec"
                }
            }
        }

    public val warning: String?
        get() {
            if (mode == CodeModeToolMode.DIRECT) return null
            val absent = availability as? CodeModeAvailability.Unavailable ?: return null
            val behavior = when (mode) {
                CodeModeToolMode.DIRECT -> return null
                CodeModeToolMode.CODE_MODE -> "Falling back to direct tools"
                CodeModeToolMode.CODE_MODE_ONLY -> "Code mode will fail closed"
            }
            return "Code Mode is unavailable because ${absent.reason}. $behavior; " +
                "enable `features.code_mode_host` and install `codex-code-mode-host`."
        }
}
