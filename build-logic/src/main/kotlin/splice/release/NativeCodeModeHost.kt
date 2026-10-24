package splice.release

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.HexFormat
import java.util.Properties
import java.util.zip.GZIPInputStream

/** Packages only checksum-qualified native hosts. Missing supported targets are build failures. */
abstract class NativeCodeModeHost : DefaultTask() {
    @get:InputDirectory
    abstract val artifacts: DirectoryProperty

    @get:Input
    abstract val platforms: ListProperty<String>

    @get:OutputDirectory
    abstract val destination: DirectoryProperty

    @TaskAction
    fun stage() {
        val source = artifacts.get().asFile
        val output = destination.get().asFile
        output.deleteRecursively()
        platforms.get().forEach { platform ->
            val directory = source.resolve(platform)
            val manifest = directory.resolve("host.properties")
            val payload = directory.resolve("codex-code-mode-host.gz")
            check(manifest.isFile && payload.isFile) {
                "Missing pinned native code-mode host for $platform; add its verified artifact before packaging"
            }
            verify(manifest, payload)
            val target = output.resolve("splice/codemode/native/$platform")
            target.mkdirs()
            manifest.copyTo(target.resolve(manifest.name))
            payload.copyTo(target.resolve(payload.name))
        }
        val license = output.resolve("META-INF/licenses/codex-code-mode-host/LICENSE")
        license.parentFile.mkdirs()
        source.resolve("LICENSE").copyTo(license)
    }

    private fun verify(manifest: File, payload: File) {
        val metadata = Properties().apply { manifest.inputStream().use(::load) }
        check(metadata.getProperty("protocolVersion") == "1") { "Unsupported native host protocol" }
        val digest = MessageDigest.getInstance("SHA-256")
        GZIPInputStream(payload.inputStream()).use { input ->
            DigestInputStream(input, digest).use { verified ->
                verified.transferTo(java.io.OutputStream.nullOutputStream())
            }
        }
        check(HexFormat.of().formatHex(digest.digest()) == metadata.getProperty("sha256")) {
            "Native host SHA-256 mismatch: ${payload.parentFile.name}"
        }
    }
}
