// NEW: compose model listing with the application's configuration, credential readers, and terminal.
package splice.app

import splice.app.auth.StoredCredential
import splice.core.terminal.TerminalOutput
import splice.core.util.EnvReader
import splice.models.list.ModelConfiguration
import splice.models.list.ModelConfigurationSource
import splice.models.list.ModelCredentialSource
import splice.models.list.ModelsCommand
import splice.topology.TopologyLoader

internal object ModelsWiring {
    fun run(args: List<String>): Boolean {
        val command = ModelsCommand(
            configuration = ModelConfigurationSource {
                val path = TopologyLoader.configPath()
                ModelConfiguration(path.toString(), TopologyLoader.loadOrMaterialize(path).providers)
            },
            credentials = ModelCredentialSource(StoredCredential()::bearer),
            output = TerminalOutput(::println),
        )
        return command.models(args, EnvReader(System::getenv))
    }
}
