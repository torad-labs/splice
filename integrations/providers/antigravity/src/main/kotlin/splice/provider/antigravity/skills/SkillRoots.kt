package splice.provider.antigravity.skills

import splice.core.config.UserHome
import splice.core.util.EnvReader
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

internal class SkillRoots(private val cwd: Path, private val env: EnvReader) {
    fun config(): Path = namedDir("CLAUDE_CONFIG_DIR")?.let(::canonical) ?: claudeUnder(home())

    fun pluginDir(): Path? = namedDir("CLAUDE_CODE_DESKTOP_SKILLS_PLUGIN_DIR")?.let(::canonical)

    /** A git root is part of the workspace; the user's home is handled by the user sources instead. */
    fun workspace(): List<Path> {
        val home = home()
        return generateSequence(canonical(cwd)) { dir -> dir.parent.takeUnless { gitRoot(dir) } }
            .takeWhile { it != home }.toList()
    }

    private fun home(): Path = canonical(UserHome.dir(env))

    private fun canonical(path: Path): Path = SkillPaths.real(path) ?: path.toAbsolutePath().normalize()

    fun nativeUnder(dir: Path): Path = dir.resolve(AGENTS_DIR).resolve(LOCAL_SKILLS_DIR)

    /** The skill folders, nearest first: each [workspace] folder's, which belong to the project (the repository root
     *  that ends the walk, or outside a repository the folder holding them), then the person's own under [config]
     *  and home. */
    fun skillDirs(workspace: List<Path>, config: Path): List<SkillRoot> {
        val repository = workspace.lastOrNull()?.takeIf(::gitRoot)
        val project = workspace.flatMap { dir ->
            listOf(claudeUnder(dir).resolve(LOCAL_SKILLS_DIR), nativeUnder(dir))
                .map { SkillRoot(it, project = repository ?: dir) }
        }
        return project + listOf(config.resolve(LOCAL_SKILLS_DIR), nativeUnder(home())).map { SkillRoot(it) }
    }

    fun commandDirs(workspace: List<Path>, config: Path): List<Path> =
        workspace.map { claudeUnder(it).resolve(LOCAL_COMMANDS_DIR) } + listOf(config.resolve(LOCAL_COMMANDS_DIR))

    private fun namedDir(name: String): Path? = env(name)?.takeIf(String::isNotBlank)?.let(Path::of)

    private fun gitRoot(dir: Path): Boolean {
        val marker = dir.resolve(GIT_MARKER)
        return Files.isDirectory(marker) || Files.isRegularFile(marker)
    }

    private fun claudeUnder(dir: Path): Path = dir.resolve(UserHome.CLAUDE_DIR)
}

internal object SkillPaths {
    fun directory(path: Path): Path? = real(path)?.takeIf { Files.isDirectory(it) }

    fun file(path: Path): Path? = real(path)?.takeIf { Files.isRegularFile(it) }

    fun real(path: Path): Path? = try {
        path.toRealPath()
    } catch (_: IOException) {
        null
    }
}

internal const val LOCAL_SKILLS_DIR = "skills"
private const val AGENTS_DIR = ".agents"
private const val LOCAL_COMMANDS_DIR = "commands"
private const val GIT_MARKER = ".git"
