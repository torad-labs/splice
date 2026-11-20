// NEW: the two files a mode's jar carries for the catalog to find it (splice.mode.gradle.kts): the mode's
// java.util.ServiceLoader entry, written from its manifest's handler, and its line in the mode index, which names its
// folder so the catalog can read a mode whose handler does not load. app's shadow jar merges the service files and
// appends the index, so the one jar carries every mode's.
package splice.modes

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/** The service a mode implements, as its ServiceLoader file is named. */
private const val SERVICE = "META-INF/services/splice.modes.api.Mode"

/** The mode index: one folder name a line, one line a mode. */
private const val INDEX = "splice-modes/index"

/** Writes a mode's service entry and index line. A manifest it cannot read a handler from gives no service entry and
 *  fails nothing: the catalog reports the mode's handler. */
@CacheableTask
abstract class ModeServiceFiles : DefaultTask() {
    /** The mode's mode.toml. Absent is allowed: then no handler is read. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val manifest: RegularFileProperty

    /** The mode's folder name, which is its name. */
    @get:Input
    abstract val folder: Property<String>

    @get:OutputDirectory
    abstract val output: DirectoryProperty

    private val handlerLine = Regex("""^\s*handler\s*=\s*"([^"\\]*)"\s*(#.*)?$""", RegexOption.MULTILINE)
    private val className = Regex("""[A-Za-z_$][\w$]*(\.[A-Za-z_$][\w$]*)*""")

    @TaskAction
    fun write() {
        val out = output.get().asFile
        out.deleteRecursively()
        out.resolve(INDEX).apply { parentFile.mkdirs() }.writeText("${folder.get()}\n")
        val file = manifest.get().asFile
        val text = if (file.isFile) file.readText() else ""
        val handler = handlerLine.find(text)?.groupValues?.get(1)?.trim()
        if (handler != null && className.matches(handler)) {
            out.resolve(SERVICE).apply { parentFile.mkdirs() }.writeText("$handler\n")
        }
    }
}
