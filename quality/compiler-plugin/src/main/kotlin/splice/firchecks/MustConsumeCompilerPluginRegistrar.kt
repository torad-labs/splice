// NEW: (discipline L4) the K2 compiler-plugin entry point. Discovered via the
// META-INF/services/org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar resource. Its two -P options,
// publicSurfaceReportDir and publicSurfaceSourceRoot (FirChecksCommandLineProcessor), add the V4-92 public-surface
// reports, one per compiled source, to that compilation. Its third, closedWhen, turns the closed-when wall on.
package splice.firchecks

import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar.ExtensionStorage
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrarAdapter
import java.io.File

@OptIn(ExperimentalCompilerApi::class)
internal class MustConsumeCompilerPluginRegistrar : CompilerPluginRegistrar() {
    override val pluginId: String = PLUGIN_ID

    override val supportsK2: Boolean = true

    override fun ExtensionStorage.registerExtensions(configuration: CompilerConfiguration) {
        FirExtensionRegistrarAdapter.registerExtension(
            MustConsumeFirExtensionRegistrar(closedWhen = configuration.get(closedWhenKey) == true),
        )
        surfaceReports(configuration)?.let { IrGenerationExtension.registerExtension(it) }
    }

    /** The V4-92 report extension the two options ask for, or null when neither is given. One without the other is
     *  an error: a report directory with no root has no key to write under, and a root alone is an option no one reads. */
    private fun surfaceReports(configuration: CompilerConfiguration): PublicSurfaceReportExtension? {
        val reportDir = configuration.get(reportDirKey)
        val sourceRoot = configuration.get(sourceRootKey)
        if (reportDir == null && sourceRoot == null) return null
        require(reportDir != null && sourceRoot != null) {
            "$PLUGIN_ID: ${reportDirOption.optionName} and ${sourceRootOption.optionName} are given together or " +
                "not at all"
        }
        return PublicSurfaceReportExtension(File(reportDir), File(sourceRoot))
    }
}
