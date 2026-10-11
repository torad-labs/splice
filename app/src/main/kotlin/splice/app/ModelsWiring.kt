// NEW: compose model listing with the application's configuration, credential readers, and terminal;
// V4-239: and the same comparison for the console's GET /api/models/upstream.
package splice.app

import splice.app.auth.StoredModelCredentials
import splice.core.terminal.TerminalOutput
import splice.core.util.EnvReader
import splice.models.list.ModelConfiguration
import splice.models.list.ModelConfigurationSource
import splice.models.list.ModelsCommand
import splice.models.list.ModelsReporter
import splice.topology.TopologyLoader
import java.nio.file.Files
import java.nio.file.Path

internal object ModelsWiring {
    fun run(args: List<String>): Boolean {
        val command = ModelsCommand(
            configuration = ModelConfigurationSource {
                val path = TopologyLoader.configPath()
                ModelConfiguration(path.toString(), TopologyLoader.loadOrMaterialize(path).providers)
            },
            credentials = StoredModelCredentials(),
            output = TerminalOutput(::println),
        )
        return command.models(args, EnvReader(System::getenv))
    }

    /** V4-239: the verb's comparison for GET /api/models/upstream, over the file the daemon booted from
     *  ([path]) and the same stored credentials. The file is read on every Compare, so an edit shows
     *  without a restart, and only read: a console read never writes a starter file the way the
     *  verb's first run does. */
    fun reporter(path: Path): ModelsReporter = ModelsReporter(
        ModelConfigurationSource {
            ModelConfiguration(path.toString(), TopologyLoader.parse(Files.readString(path)).providers)
        },
        StoredModelCredentials(),
    )
}
