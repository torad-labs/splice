// NEW: (discipline L4) binds the checkers into FIR analysis and registers their diagnostics containers so
// the compiler can render the error messages. The closed-when wall is bound only when the compilation asks for it.
package splice.firchecks

import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.analysis.checkers.expression.ExpressionCheckers
import org.jetbrains.kotlin.fir.analysis.checkers.expression.FirFunctionCallChecker
import org.jetbrains.kotlin.fir.analysis.checkers.expression.FirWhenExpressionChecker
import org.jetbrains.kotlin.fir.analysis.extensions.FirAdditionalCheckersExtension
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrar

internal class MustConsumeFirExtensionRegistrar(private val closedWhen: Boolean) : FirExtensionRegistrar() {
    override fun ExtensionRegistrarContext.configurePlugin() {
        val checkers = { session: FirSession -> MustConsumeAdditionalCheckersExtension(session, closedWhen) }
        +checkers
        registerDiagnosticContainers(MustConsumeErrors, ClosedWhenErrors)
    }
}

private class MustConsumeAdditionalCheckersExtension(
    session: FirSession,
    closedWhen: Boolean,
) : FirAdditionalCheckersExtension(session) {
    override val expressionCheckers: ExpressionCheckers = object : ExpressionCheckers() {
        override val functionCallCheckers: Set<FirFunctionCallChecker> = setOf(MustConsumeDiscardChecker())
        override val whenExpressionCheckers: Set<FirWhenExpressionChecker> =
            if (closedWhen) setOf(ClosedWhenElseChecker()) else emptySet()
    }
}
