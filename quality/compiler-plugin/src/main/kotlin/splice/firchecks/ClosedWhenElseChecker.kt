// NEW: (discipline L4) the second wall. A `when` over an enum, a sealed type or a Boolean lists every case, and an `else` arm
// there is a compile error: it silently absorbs the next case somebody adds, which is exactly the case the exhaustiveness
// check exists to point at. A `when` over an open type (String, Int, a non-sealed class) needs its `else` and is untouched,
// as is a subjectless `when`. The subject's type comes from the compiler's own FIR, so it is as exact as the build is.
package splice.firchecks

import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.diagnostics.KtDiagnosticFactory0
import org.jetbrains.kotlin.diagnostics.KtDiagnosticFactoryToRendererMap
import org.jetbrains.kotlin.diagnostics.KtDiagnosticsContainer
import org.jetbrains.kotlin.diagnostics.error0
import org.jetbrains.kotlin.diagnostics.rendering.BaseDiagnosticRendererFactory
import org.jetbrains.kotlin.diagnostics.reportOn
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.expression.FirWhenExpressionChecker
import org.jetbrains.kotlin.fir.expressions.FirWhenExpression
import org.jetbrains.kotlin.fir.expressions.impl.FirElseIfTrueCondition
import org.jetbrains.kotlin.fir.resolve.toRegularClassSymbol
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.fir.types.coneType
import org.jetbrains.kotlin.fir.types.isBooleanOrNullableBoolean

/** The wall's diagnostics. Registered with the compiler in [MustConsumeFirExtensionRegistrar]. */
internal object ClosedWhenErrors : KtDiagnosticsContainer() {
    val ELSE_ON_CLOSED_WHEN: KtDiagnosticFactory0 by error0<PsiElement>()

    override fun getRendererFactory(): BaseDiagnosticRendererFactory = ClosedWhenErrorMessages
}

private object ClosedWhenErrorMessages : BaseDiagnosticRendererFactory() {
    override val MAP: KtDiagnosticFactoryToRendererMap by KtDiagnosticFactoryToRendererMap("ClosedWhen") { map ->
        map.put(
            ClosedWhenErrors.ELSE_ON_CLOSED_WHEN,
            "ClosedWhen: this `when` is over an enum, a sealed type or a Boolean; list every case instead of `else`.",
        )
    }
}

internal class ClosedWhenElseChecker : FirWhenExpressionChecker(MppCheckerKind.Common) {
    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(expression: FirWhenExpression) {
        val subject = expression.subjectVariable?.returnTypeRef?.coneType ?: return
        if (!isClosed(subject, context.session)) return
        expression.branches
            .filter { it.condition is FirElseIfTrueCondition }
            .forEach { reporter.reportOn(it.source, ClosedWhenErrors.ELSE_ON_CLOSED_WHEN, context) }
    }

    private fun isClosed(type: ConeKotlinType, session: FirSession): Boolean {
        if (type.isBooleanOrNullableBoolean) return true
        val symbol = type.toRegularClassSymbol(session) ?: return false
        return symbol.classKind == ClassKind.ENUM_CLASS || symbol.rawStatus.modality == Modality.SEALED
    }
}
