// NEW (discipline L4): the wall itself. Fires on any FirFunctionCall whose result is annotated
// @MustConsume (on the callee, or on the return type's class — the Rust #[must_use]-on-a-struct
// shape) AND is discarded. A call is "discarded" when its value provably flows nowhere: the walk in
// isDiscarded() climbs from the call through value-transparent wrappers — `?.` selectors, `?:`
// operands, when/if branch tails (every `if` is a FirWhenExpression in FIR), try/catch arm tails,
// block tails — until it reaches either a consumer (an argument, a receiver, a `val` initializer, a
// `return`; NOT discarded) or a statement position (a non-tail block statement, a function/init body
// tail, a loop body tail, a `finally` tail; discarded). So a branch tail fires only when the
// ENCLOSING expression's own value is unused (`val x = if (c) produce() else 0` is clean, a bare
// `if (c) produce() else other()` statement is not), and a bare `x?.produce()` statement fires even
// though the call is nested under the safe-call node rather than sitting in the block itself.
// Lambda body tails are never flagged: their consumption belongs to the called function (a `map`
// consumes, a `forEach` does not) and is not decidable from the call site — conservative by design.
package splice.firchecks

import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.diagnostics.KtDiagnosticFactory0
import org.jetbrains.kotlin.diagnostics.KtDiagnosticFactoryToRendererMap
import org.jetbrains.kotlin.diagnostics.KtDiagnosticsContainer
import org.jetbrains.kotlin.diagnostics.error0
import org.jetbrains.kotlin.diagnostics.rendering.BaseDiagnosticRendererFactory
import org.jetbrains.kotlin.diagnostics.reportOn
import org.jetbrains.kotlin.fir.FirElement
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.expression.FirFunctionCallChecker
import org.jetbrains.kotlin.fir.declarations.FirAnonymousFunction
import org.jetbrains.kotlin.fir.declarations.FirAnonymousInitializer
import org.jetbrains.kotlin.fir.declarations.FirFunction
import org.jetbrains.kotlin.fir.declarations.hasAnnotation
import org.jetbrains.kotlin.fir.expressions.FirBlock
import org.jetbrains.kotlin.fir.expressions.FirCatch
import org.jetbrains.kotlin.fir.expressions.FirElvisExpression
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.expressions.FirLoop
import org.jetbrains.kotlin.fir.expressions.FirSafeCallExpression
import org.jetbrains.kotlin.fir.expressions.FirTryExpression
import org.jetbrains.kotlin.fir.expressions.FirWhenBranch
import org.jetbrains.kotlin.fir.expressions.FirWhenExpression
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

    /** Walks outward from the call, [child] carrying the node whose value is the call's value so far.
     *  Each step either settles the verdict ([stepVerdict] non-null) or dissolves one transparent
     *  wrapper and climbs on. Runs on `containingElements`, ordered outermost-first; the walk starts
     *  just above the call itself (which the visitor pushes as the innermost element). */
    private fun FirFunctionCall.isDiscarded(context: CheckerContext): Boolean {
        val path = context.containingElements
        val start = path.indexOfLast { it === this }.let { if (it < 0) path.size else it } - 1
        var child: FirElement = this
        for (index in start downTo 0) {
            stepVerdict(path[index], child)?.let { return it }
            child = path[index]
        }
        return false
    }

    /** Verdict for [child]'s value as seen from [parent]: `true` = discarded, `false` = consumed (or
     *  a position the walk does not model — conservative, never a false positive), `null` = [parent]
     *  is transparent and the verdict is [parent]'s own consumption. Lambda tails are never flagged
     *  ([FirAnonymousFunction]): whether the called function consumes the lambda's value is not
     *  decidable here. Named-function/init body tails and loop body tails yield no value: discarded. */
    private fun stepVerdict(parent: FirElement, child: FirElement): Boolean? = when (parent) {
        is FirBlock -> blockVerdict(parent, child)
        is FirTryExpression -> tryVerdict(parent, child)
        is FirLoop -> parent.block === child
        is FirAnonymousFunction -> false
        is FirFunction -> parent.body === child
        is FirAnonymousInitializer -> parent.body === child
        else -> if (carriesValue(parent, child)) null else false
    }

    /** A non-tail block statement is a discard; a tail statement's fate is the block's own. */
    private fun blockVerdict(parent: FirBlock, child: FirElement): Boolean? {
        val index = parent.statements.indexOfLast { it === child }
        return when {
            index < 0 -> false
            index < parent.statements.lastIndex -> true
            else -> null
        }
    }

    /** `finally` never yields a value, so a tail discard there fires even under a consumed `try`. */
    private fun tryVerdict(parent: FirTryExpression, child: FirElement): Boolean? = when {
        parent.finallyBlock === child -> true
        parent.tryBlock === child || parent.catches.any { it === child || it.block === child } -> null
        else -> false
    }

    /** Wrappers whose own value IS [child]'s value — the walk sees through them. Branch/catch nodes
     *  are matched both directly and via their result/block: the diagnostic visitor pushes blocks
     *  and expressions onto the context path, but plain holders like [FirWhenBranch]/[FirCatch] are
     *  not guaranteed there, so [child] may be either the holder or its block. Everything else
     *  (a when subject, a branch condition, a `?.` receiver) genuinely consumes the value. */
    private fun carriesValue(parent: FirElement, child: FirElement): Boolean = when (parent) {
        is FirWhenBranch -> parent.result === child
        is FirWhenExpression -> parent.branches.any { it === child || it.result === child }
        is FirCatch -> parent.block === child
        is FirSafeCallExpression -> parent.selector === child
        is FirElvisExpression -> parent.lhs === child || parent.rhs === child
        else -> false
    }

    private companion object {
        val MUST_CONSUME_ID: ClassId = ClassId.topLevel(FqName("splice.core.annotation.MustConsume"))
    }
}
