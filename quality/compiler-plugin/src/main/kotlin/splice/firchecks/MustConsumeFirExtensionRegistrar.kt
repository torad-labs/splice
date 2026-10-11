// NEW: (discipline L4) binds the checkers into FIR analysis and registers their diagnostics containers so
// the compiler can render the error messages.
package splice.firchecks

import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.analysis.checkers.expression.ExpressionCheckers
import org.jetbrains.kotlin.fir.analysis.checkers.expression.FirFunctionCallChecker
import org.jetbrains.kotlin.fir.analysis.checkers.expression.FirWhenExpressionChecker
import org.jetbrains.kotlin.fir.analysis.extensions.FirAdditionalCheckersExtension
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrar

internal class MustConsumeFirExtensionRegistrar : FirExtensionRegistrar() {
    override fun ExtensionRegistrarContext.configurePlugin() {
        val checkers = { session: FirSession -> MustConsumeAdditionalCheckersExtension(session) }
        +checkers
        registerDiagnosticContainers(MustConsumeErrors, ClosedWhenErrors)
    }
}

private class MustConsumeAdditionalCheckersExtension(session: FirSession) : FirAdditionalCheckersExtension(session) {
    override val expressionCheckers: ExpressionCheckers = object : ExpressionCheckers() {
        override val functionCallCheckers: Set<FirFunctionCallChecker> = setOf(MustConsumeDiscardChecker())
        override val whenExpressionCheckers: Set<FirWhenExpressionChecker> =
            setOf(ClosedWhenElseChecker())
    }
}
