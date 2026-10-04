// NEW: only the executing GraalJS context can certify source as a complete function-body fragment.
package splice.codemode

internal fun interface CodeModeStatementSyntax {
    operator fun invoke(source: String): Boolean
}
