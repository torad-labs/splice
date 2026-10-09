// NEW (V4-92, v3): MustConsumeCompilerPluginRegistrar with one thing removed, the lookups SurfaceLookups records.
// PublicSurfaceIncrementalTest loads it as a plugin to show that without them an incremental compile leaves a
// public-surface report stale.
package splice.firchecks

import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar.ExtensionStorage
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrarAdapter
import java.io.File

@OptIn(ExperimentalCompilerApi::class)
internal class UnregisteredLookupsRegistrar : CompilerPluginRegistrar() {
    override val pluginId: String = PLUGIN_ID

    override val supportsK2: Boolean = true

    override fun ExtensionStorage.registerExtensions(configuration: CompilerConfiguration) {
        FirExtensionRegistrarAdapter.registerExtension(MustConsumeFirExtensionRegistrar())
        val reportDir = checkNotNull(configuration.get(reportDirKey)) {
            "the test passes ${reportDirOption.optionName}"
        }
        val sourceRoot = checkNotNull(configuration.get(sourceRootKey)) {
            "the test passes ${sourceRootOption.optionName}"
        }
        IrGenerationExtension.registerExtension(
            PublicSurfaceReportExtension(File(reportDir), File(sourceRoot)) { SurfaceLookups { } },
        )
    }
}
