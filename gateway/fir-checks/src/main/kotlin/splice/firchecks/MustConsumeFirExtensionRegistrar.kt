// NEW (discipline L4): binds the checker into FIR analysis and registers its diagnostics container so
// the compiler can render the error message.
package splice.firchecks

import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.analysis.checkers.expression.ExpressionCheckers
import org.jetbrains.kotlin.fir.analysis.checkers.expression.FirFunctionCallChecker
import org.jetbrains.kotlin.fir.analysis.extensions.FirAdditionalCheckersExtension
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrar

internal class MustConsumeFirExtensionRegistrar : FirExtensionRegistrar() {
    override fun ExtensionRegistrarContext.configurePlugin() {
        +::MustConsumeAdditionalCheckersExtension
        registerDiagnosticContainers(MustConsumeErrors)
    }
}

private class MustConsumeAdditionalCheckersExtension(
    session: FirSession,
) : FirAdditionalCheckersExtension(session) {
    override val expressionCheckers: ExpressionCheckers = object : ExpressionCheckers() {
        override val functionCallCheckers: Set<FirFunctionCallChecker> = setOf(MustConsumeDiscardChecker())
    }
}
