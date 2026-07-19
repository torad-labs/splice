// NEW (discipline L4): the K2 compiler-plugin entry point. Discovered via the
// META-INF/services/org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar resource; no
// CommandLineProcessor / -P options in v1 (the plugin has no configuration).
package splice.firchecks

import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar.ExtensionStorage
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrarAdapter

@OptIn(ExperimentalCompilerApi::class)
internal class MustConsumeCompilerPluginRegistrar : CompilerPluginRegistrar() {
    override val pluginId: String = "splice.fir-checks"

    override val supportsK2: Boolean = true

    override fun ExtensionStorage.registerExtensions(configuration: CompilerConfiguration) {
        FirExtensionRegistrarAdapter.registerExtension(MustConsumeFirExtensionRegistrar())
    }
}
