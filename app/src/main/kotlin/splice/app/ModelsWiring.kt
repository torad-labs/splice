// NEW: compose model listing with the application's configuration, credential readers, and terminal;
// V4-239: and the same comparison for the console's GET /api/models/upstream.
package splice.app

import splice.app.auth.StoredModelCredentials
import splice.app.provider.JdkLocalHttp
import splice.core.terminal.TerminalOutput
import splice.core.util.EnvReader
import splice.models.list.LocalListAuthority
import splice.models.list.ModelConfiguration
import splice.models.list.ModelConfigurationSource
import splice.models.list.ModelsCommand
import splice.models.list.ModelsReporter
import splice.topology.TopologyLoader
import splice.upstream.local.LocalRuntimeKind
import splice.upstream.local.LocalRuntimeProbe
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
            listAuthority = AuthoritativeLocalList(),
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
        AuthoritativeLocalList(),
    )
}

/** Boot's rule for an unlisted local row, asked of the runtime itself (LocalRuntimeProbe.detect): Ollama, LM Studio and
 *  vLLM serve from their list, so a row they do not list is refused; a generic OpenAI-compatible server's list is not. */
internal class AuthoritativeLocalList : LocalListAuthority {
    override fun invoke(baseUrl: String): Boolean =
        LocalRuntimeProbe(baseUrl, JdkLocalHttp()).detect()?.kind
            ?.let { kind -> kind != LocalRuntimeKind.OPENAI_COMPATIBLE } == true
}
