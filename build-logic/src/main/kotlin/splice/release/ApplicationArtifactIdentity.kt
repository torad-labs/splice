package splice.release

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.security.MessageDigest
import java.util.HexFormat

/** Fingerprints compiled application inputs without introducing a compileKotlin/compileJava cycle. */
@CacheableTask
abstract class ApplicationArtifactIdentity : DefaultTask() {
    @get:Classpath
    abstract val classes: DirectoryProperty

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val resources: DirectoryProperty

    @get:Classpath
    abstract val runtimeArtifacts: ConfigurableFileCollection

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val javaSources: ConfigurableFileCollection

    @get:OutputFile
    abstract val source: RegularFileProperty

    @TaskAction
    fun generate() {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(BUFFER_BYTES)
        tree(digest, buffer, "classes", classes.get().asFile)
        tree(digest, buffer, "resources", resources.get().asFile)
        runtimeArtifacts.files.sortedBy { it.name }.forEach { file ->
            if (file.isDirectory) {
                tree(digest, buffer, "runtime/${file.name}", file)
            } else {
                append(digest, buffer, "runtime/${file.name}", file)
            }
        }
        javaSources.files.sortedBy { it.name }.forEach { file ->
            tree(digest, buffer, "java/${file.name}", file)
        }
        val fingerprint = HexFormat.of().formatHex(digest.digest())
        val output = source.get().asFile
        output.parentFile.mkdirs()
        output.writeText(
            "package splice.app;\nfinal class ApplicationArtifactFingerprint {\n" +
                "    static final String VALUE = \"$fingerprint\";\n}\n",
        )
    }

    private fun tree(digest: MessageDigest, buffer: ByteArray, prefix: String, root: File) {
        if (!root.exists()) return
        root.walkTopDown().filter { it.isFile }.sortedBy { it.relativeTo(root).invariantSeparatorsPath }
            .forEach { append(digest, buffer, "$prefix/${it.relativeTo(root).invariantSeparatorsPath}", it) }
    }

    private fun append(digest: MessageDigest, buffer: ByteArray, name: String, file: File) {
        digest.update(name.toByteArray(Charsets.UTF_8))
        digest.update(0)
        digest.update(file.length().toString().toByteArray(Charsets.US_ASCII))
        digest.update(0)
        file.inputStream().use { input ->
            var count = input.read(buffer)
            while (count >= 0) {
                digest.update(buffer, 0, count)
                count = input.read(buffer)
            }
        }
    }
}

private const val BUFFER_BYTES = 16 * 1_024
