// NEW (discipline L4): the test-only registrar ClosedWhenElseCheckerTest packs into its throwaway plugin jar; it registers the
// closed-when wall alone, so the fixtures prove that checker and nothing else.
package splice.firchecks

import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar.ExtensionStorage
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.analysis.checkers.expression.ExpressionCheckers
import org.jetbrains.kotlin.fir.analysis.checkers.expression.FirWhenExpressionChecker
import org.jetbrains.kotlin.fir.analysis.extensions.FirAdditionalCheckersExtension
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrar
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrarAdapter

@OptIn(ExperimentalCompilerApi::class)
class TestClosedWhenRegistrar : CompilerPluginRegistrar() {
    override val pluginId: String = "splice.fir-checks.closed-when-test"

    override val supportsK2: Boolean = true

    override fun ExtensionStorage.registerExtensions(configuration: CompilerConfiguration) {
        FirExtensionRegistrarAdapter.registerExtension(TestClosedWhenFirRegistrar())
    }
}

internal class TestClosedWhenFirRegistrar : FirExtensionRegistrar() {
    override fun ExtensionRegistrarContext.configurePlugin() {
        +::TestClosedWhenCheckers
        registerDiagnosticContainers(ClosedWhenErrors)
    }
}

private class TestClosedWhenCheckers(session: FirSession) : FirAdditionalCheckersExtension(session) {
    override val expressionCheckers: ExpressionCheckers = object : ExpressionCheckers() {
        override val whenExpressionCheckers: Set<FirWhenExpressionChecker> = setOf(ClosedWhenElseChecker())
    }
}
