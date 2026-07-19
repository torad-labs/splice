// NEW (discipline L4): the wall itself. Fires on any FirFunctionCall whose result is annotated
// @MustConsume (on the callee, or on the return type's class — the Rust #[must_use]-on-a-struct
// shape) AND is discarded. A call is "discarded" when it sits directly in a block's statement list:
// a used result would be nested inside another node (an argument, a receiver, the initializer of a
// `val`, or a `return`'s expression) and never appear as a bare block statement. Block-bodied
// functions never implicitly return their last statement (only expression bodies `= call()` do, and
// those desugar the call under a FirReturnExpression), so even a last-statement discard is caught.
package splice.firchecks

import org.jetbrains.kotlin.com.intellij.psi.PsiElement
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
import org.jetbrains.kotlin.fir.analysis.checkers.expression.FirFunctionCallChecker
import org.jetbrains.kotlin.fir.declarations.hasAnnotation
import org.jetbrains.kotlin.fir.expressions.FirBlock
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.references.toResolvedNamedFunctionSymbol
import org.jetbrains.kotlin.fir.resolve.toRegularClassSymbol
import org.jetbrains.kotlin.fir.types.resolvedType
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName

/** The plugin's diagnostics. Registered with the compiler in [MustConsumeFirExtensionRegistrar]. */
internal object MustConsumeErrors : KtDiagnosticsContainer() {
    val DISCARDED_MUST_CONSUME_VALUE: KtDiagnosticFactory0 by error0<PsiElement>()

    override fun getRendererFactory(): BaseDiagnosticRendererFactory = MustConsumeErrorMessages
}

/** Human-readable text for [MustConsumeErrors]. The `KtDiagnosticFactoryToRendererMap(name){}` factory
 *  returns a `Lazy`, which breaks the object init cycle: the factory above references this renderer,
 *  and this map references the factory back — resolved only at render time, after both objects init. */
private object MustConsumeErrorMessages : BaseDiagnosticRendererFactory() {
    override val MAP: KtDiagnosticFactoryToRendererMap by KtDiagnosticFactoryToRendererMap("MustConsume") { map ->
        map.put(
            MustConsumeErrors.DISCARDED_MUST_CONSUME_VALUE,
            "The result of this call is annotated @MustConsume and must be used, not discarded.",
        )
    }
}

internal class MustConsumeDiscardChecker : FirFunctionCallChecker(MppCheckerKind.Common) {
    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(expression: FirFunctionCall) {
        if (!expression.hasMustConsumeResult(context.session)) return
        if (!expression.isDiscarded(context)) return
        reporter.reportOn(expression.source, MustConsumeErrors.DISCARDED_MUST_CONSUME_VALUE, context)
    }

    private fun FirFunctionCall.hasMustConsumeResult(session: FirSession): Boolean {
        val callee = calleeReference.toResolvedNamedFunctionSymbol()
        if (callee != null && callee.hasAnnotation(MUST_CONSUME_ID, session)) return true
        val returnClass = resolvedType.toRegularClassSymbol(session)
        return returnClass != null && returnClass.hasAnnotation(MUST_CONSUME_ID, session)
    }

    private fun FirFunctionCall.isDiscarded(context: CheckerContext): Boolean =
        context.containingElements.any { element ->
            element is FirBlock && element.statements.any { it === this }
        }

    private companion object {
        val MUST_CONSUME_ID: ClassId = ClassId.topLevel(FqName("splice.core.annotation.MustConsume"))
    }
}
