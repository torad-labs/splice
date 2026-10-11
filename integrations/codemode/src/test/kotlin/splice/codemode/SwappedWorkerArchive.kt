// Test-only archive made from the same worker bytes as the module or packaged runtime suite.
package splice.codemode

import java.io.File
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import java.util.zip.Deflater

internal class SwappedWorkerArchive(root: Path, classpath: String, archiveName: String = "worker.jar") {
    val jar: Path = root.resolve(archiveName)
    val classpath: String

    init {
        val entries = classpath.split(File.pathSeparator).map(Path::of)
        val source = Path.of(CodeModeWorker::class.java.protectionDomain.codeSource.location.toURI())
        val archive = entries.singleOrNull()?.takeIf(Files::isRegularFile) ?: source.takeIf(Files::isRegularFile)
        if (archive != null) {
            Files.copy(archive, jar)
            this.classpath = (listOf(jar) + entries.filterNot { it == archive })
                .joinToString(File.pathSeparator)
        } else {
            JarOutputStream(Files.newOutputStream(jar)).use { output ->
                Files.walk(source).use { files ->
                    files.filter(Files::isRegularFile).forEach { file ->
                        val name = source.relativize(file).toString().replace(File.separatorChar, '/')
                        output.putNextEntry(JarEntry(name))
                        Files.copy(file, output)
                        output.closeEntry()
                    }
                }
            }
            this.classpath = (listOf(jar) + entries.filterNot { it == source })
                .joinToString(File.pathSeparator)
        }
    }

    fun hash(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        DigestInputStream(Files.newInputStream(path), digest).use { it.transferTo(OutputStream.nullOutputStream()) }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun replaceWorkerClass() {
        val replacement = jar.resolveSibling("replacement.jar")
        JarFile(jar.toFile()).use { original ->
            JarOutputStream(Files.newOutputStream(replacement)).use { output ->
                // This fixture changes one class, not compression: re-deflating the fat jar dominates CI's boot budget.
                output.setLevel(Deflater.NO_COMPRESSION)
                for (entry in original.entries()) {
                    output.putNextEntry(JarEntry(entry.name))
                    copyEntry(original, entry, output)
                }
            }
        }
        Files.move(replacement, jar, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    private fun copyEntry(original: JarFile, entry: JarEntry, output: JarOutputStream) {
        original.getInputStream(entry).use { it.copyTo(output) }
        if (entry.name == "splice/codemode/CodeModeWorker.class") output.write(0)
        output.closeEntry()
    }

    fun replace() {
        val replacement = jar.resolveSibling("replacement.jar")
        Files.writeString(replacement, "a different installed build")
        Files.move(replacement, jar, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }
}
