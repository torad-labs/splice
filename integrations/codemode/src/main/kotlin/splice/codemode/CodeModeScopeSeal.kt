// NEW: source-owned intrinsic admission is enforced against every subsequently parsed root declaration.
package splice.codemode

import splice.upstream.codemode.CodeModeManual
import splice.upstream.codemode.CodeModeSealedSource
import splice.upstream.codemode.CodeModeSource

internal object CodeModeScopeSeal {
    val globals: Set<String> = CodeModeManual.streamingSealedGlobals
    val conversions: Set<String> = globals - "Promise"
    private val hostNames = setOf("tools", "console", "text", "exit", "ALL_TOOLS")

    fun names(source: CodeModeSource): Set<String> {
        val names = (source as? CodeModeSealedSource)?.sealedGlobals.orEmpty().toSet()
        require(names.all { it in globals }) { "Only declared streaming intrinsics can be sealed" }
        return names
    }

    fun violation(bindings: List<StatementBinding>, sealed: Set<String>): String? =
        bindings.firstOrNull { it.name in sealed || it.name in hostNames }?.let {
            "SyntaxError: Streaming source cannot declare sealed binding '${it.name}'"
        }
}
