// NEW: compose model listing with the application's configuration, credential readers, and terminal.
package splice.app

import splice.app.auth.StoredModelCredentials
import splice.app.daemon.TopologyLoader
import splice.core.util.EnvReader
import splice.models.list.ModelConfiguration
import splice.models.list.ModelConfigurationSource
import splice.models.list.ModelReportOutput
import splice.models.list.ModelsCommand

internal object ModelsWiring {
    fun run(args: List<String>): Boolean {
        val command = ModelsCommand(
            configuration = ModelConfigurationSource {
                val path = TopologyLoader.configPath()
                ModelConfiguration(path.toString(), TopologyLoader.loadOrMaterialize(path).providers)
            },
            credentials = StoredModelCredentials(),
            output = ModelReportOutput(::println),
        )
        return command.models(args, EnvReader(System::getenv))
    }
}
