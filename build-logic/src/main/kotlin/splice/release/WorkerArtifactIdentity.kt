// The fingerprint of the compiled worker classes, generated as a Java constant compiled after the Kotlin bytes it identifies.
package splice.release

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import java.security.MessageDigest

/** SHA-256 over every compiled class's relative path and bytes, in path order, written as `WorkerArchiveFingerprint.java`. */
@CacheableTask
abstract class WorkerArtifactIdentity : DefaultTask() {
    @get:Classpath
    abstract val classes: DirectoryProperty

    @get:OutputFile
    abstract val source: RegularFileProperty

    @TaskAction
    fun generate() {
        val directory = classes.get().asFile
        val entries = directory.walkTopDown().filter { it.isFile && it.extension == "class" }
            .map { it.relativeTo(directory).invariantSeparatorsPath }.sorted().toList()
        check(entries.isNotEmpty()) { "Compiled worker identity cannot be empty" }
        val digest = MessageDigest.getInstance("SHA-256")
        for (entry in entries) {
            digest.update(entry.toByteArray(Charsets.UTF_8))
            digest.update(0.toByte())
            digest.update(directory.resolve(entry).readBytes())
        }
        val fingerprint = digest.digest().joinToString("") { "%02x".format(it) }
        val target = source.get().asFile
        target.parentFile.mkdirs()
        target.writeText(
            "package splice.codemode;\nfinal class WorkerArchiveFingerprint {\n" +
                "    static final String VALUE = \"$fingerprint\";\n" +
                "    static final String[] ENTRIES = {" +
                entries.joinToString(",") { "\"$it\"" } + "};\n}\n",
        )
    }
}
