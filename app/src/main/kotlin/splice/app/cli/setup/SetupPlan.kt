// NEW: what `splice setup`'s questions end in (WizardAnswer) and the answers beyond plans and launchers (SetupExtras).
// Split from SetupCommand.kt (Nov 23, 2026, concentration).
package splice.app.cli.setup

import java.nio.file.Path

/** What the wizard's questions end in: a plan the operator said go to, or the reason it ends without installing. */
internal sealed class WizardAnswer {
    class Cancelled(val reason: String) : WizardAnswer()

    class Plan(
        val path: Path,
        val launchers: SetupLaunchers,
        val launcherChoice: LauncherChoice,
        val picker: SetupHeads,
        val heads: List<String>,
        val extras: SetupExtras,
    ) : WizardAnswer()
}

/** The wizard's answers beyond plans and launchers: the [local] model step, [share] (`[daemon] mcp_share` and the
 *  optional MCP import and harness sync), the [modes] turned on and whether [autoUpdate] was turned on. */
internal data class SetupExtras(
    val local: Boolean,
    val share: McpSetupAnswer,
    val modes: List<String>,
    val autoUpdate: Boolean,
)
