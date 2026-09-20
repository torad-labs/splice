// NEW: V4-156 — DaemonMaterializer moved verbatim out of ControlPlane.kt, which carried it as a
// second declared type. The concentration metric counts every type a file declares as a concern,
// and ControlPlane was band HIGH on concerns; the object has no state and no caller other than
// ControlPlane's one call, so it stands alone without a seam. The call site, and the wiring pin that
// reads it (DaemonMaterializerTest), stay in ControlPlane.kt.
package splice.app

import splice.core.launch.ClaudeConfigMaterializer
import splice.core.launch.HookExec
import splice.core.launch.McpRewrite
import java.nio.file.Path

/** Builds the daemon's [ClaudeConfigMaterializer]. The caller NAMES the exec so the noexec
 *  capture-hook guard cannot be silently unwired — a materializer built without one skips the
 *  exec-probe and a pasted credential would reach the model on a noexec mount (law 19).
 *
 *  [hookExec] has NO DEFAULT, deliberately. A default is the shape this law exists to forbid: the
 *  omission that disables a guard compiles, runs, and looks exactly like the wiring that enables it,
 *  so no test can tell the two apart. Without one, omitting the argument is a compile error — the
 *  strongest pin available, because it cannot be satisfied by accident. The wiring pin in
 *  DaemonMaterializerTest guards the other half (the caller passing the REAL exec rather than any
 *  exec), which the type system cannot state. */
internal object DaemonMaterializer {
    internal fun build(
        home: Path,
        rewrite: McpRewrite?,
        hookExec: HookExec,
        controlPort: Int,
    ): ClaudeConfigMaterializer =
        ClaudeConfigMaterializer(home, mcpRewrite = rewrite, hookExec = hookExec, resumeHookPort = controlPort)
}
