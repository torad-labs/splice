package splice.provider.codex.diagnostics.current

import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import splice.core.util.JsonScalars
import splice.upstream.codemode.ProcessDispatchers
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.TimeUnit

/** Run the pinned official patch tool only for the reviewed disposable one-file effect. */
internal class CodeModeGatewayPatchEffect(
    private val directory: Path,
    specification: JsonObject,
) {
    private val executable = specification.getValue("freeform").jsonObject.getValue("executable").jsonObject
    private val workspace = directory.resolve("workspace")
    private val effects = LinkedHashMap<String, JsonObject>()

    suspend fun apply(callback: JsonObject): Unit = runInterruptible(ProcessDispatchers().io()) {
        val id = checkNotNull(JsonScalars.str(callback, "id"))
        check(JsonScalars.str(callback, "name") == "apply_patch") { "Freeform effect has no advertised tool" }
        check(callback.getValue("arguments") == buildJsonObject { put("input", PATCH_EFFECT_SOURCE) })
        if (id !in effects) {
            check(effects.isEmpty()) { "This disposable patch fixture owns only one permission callback" }
            effects[id] = execute(id)
        }
    }

    private fun execute(id: String): JsonObject {
        Files.createDirectory(workspace)
        val binary = Path.of(checkNotNull(JsonScalars.str(executable, "path")))
        check(binary.isAbsolute && Files.isExecutable(binary))
        check(artifact(binary) == executable) { "Freeform executable changed after its build receipt" }
        val profiles = Files.createDirectory(directory.resolve("freeform-tool-profile"))
        val stdout = directory.resolve("freeform-tool-stdout.txt")
        val stderr = directory.resolve("freeform-tool-stderr.txt")
        val builder = ProcessBuilder(binary.toString(), PATCH_EFFECT_SOURCE).directory(workspace.toFile())
            .redirectOutput(stdout.toFile()).redirectError(stderr.toFile())
        builder.environment()["LLVM_PROFILE_FILE"] = profiles.resolve("%p.profraw").toString()
        val process = builder.start()
        try {
            check(
                process.waitFor(PATCH_EFFECT_TIMEOUT_SECONDS, TimeUnit.SECONDS),
            ) { "Freeform patch process timed out" }
            check(process.exitValue() == 0) { "Freeform patch process failed" }
            check(artifact(binary) == executable) { "Freeform executable changed during execution" }
            return receipt(id, process, stdout, stderr, profiles)
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }

    private fun receipt(id: String, process: Process, stdout: Path, stderr: Path, profiles: Path): JsonObject {
        val file = workspace.resolve(PATCH_EFFECT_FILE)
        check(file.toRealPath() == file && Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
        Files.list(workspace).use { check(it.toList() == listOf(file)) { "Freeform patch has unbound file effects" } }
        val bytes = bounded(file, PATCH_EFFECT_BYTES)
        val profile = profiles.resolve("${process.pid()}.profraw")
        Files.list(
            profiles,
        ).use { check(it.toList() == listOf(profile)) { "Freeform tool counters are not PID-isolated" } }
        return buildJsonObject {
            put("callbackId", id); put("executable", executable)
            put("argv", JsonArray(listOf(executable.getValue("path"), JsonPrimitive(PATCH_EFFECT_SOURCE))))
            put("inputSha256", digest(PATCH_EFFECT_SOURCE.toByteArray()))
            put("pid", process.pid()); put("exit", process.exitValue())
            put("stdout", bounded(stdout, PATCH_EFFECT_OUTPUT_BOUND).decodeToString())
            put("stderr", bounded(stderr, PATCH_EFFECT_OUTPUT_BOUND).decodeToString())
            put(
                "files",
                JsonArray(
                    listOf(
                        buildJsonObject {
                            put("path", PATCH_EFFECT_FILE); put("sha256", digest(bytes)); put("bytes", bytes.size)
                            put("base64", Base64.getEncoder().encodeToString(bytes))
                        },
                    ),
                ),
            )
            put("profiles", JsonArray(listOf(artifact(profile))))
        }
    }

    fun report(): JsonArray = JsonArray(effects.values.toList())

    private fun artifact(path: Path): JsonObject {
        check(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && Files.size(path) <= PATCH_EFFECT_BINARY_BOUND)
        val hash = MessageDigest.getInstance("SHA-256")
        var bytes = 0L
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var count = input.read(buffer)
            while (count >= 0) {
                bytes += count
                check(bytes <= PATCH_EFFECT_BINARY_BOUND)
                hash.update(buffer, 0, count)
                count = input.read(buffer)
            }
        }
        return buildJsonObject {
            put("path", path.toString()); put("sha256", hash.digest().joinToString("") { "%02x".format(it) })
            put("bytes", bytes)
        }
    }

    private fun bounded(path: Path, limit: Long): ByteArray {
        check(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && Files.size(path) <= limit)
        return Files.readAllBytes(path).also { check(it.size.toLong() <= limit) }
    }

    private fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }
}

// why: the source witness measures one exact add-file patch; this runner cannot edit an arbitrary path.
private const val PATCH_EFFECT_SOURCE = "*** Begin Patch\n*** Add File: codemode-freeform.txt\n" +
    "+CodeMode freeform λ fixture.\n*** End Patch"
private const val PATCH_EFFECT_FILE = "codemode-freeform.txt"

// why: the measured UTF-8 fixture is exactly thirty bytes, including its final newline.
private const val PATCH_EFFECT_BYTES = 30L

// why: a fixed single-file local patch must finish within five seconds of the outer thirty-second fixture bound.
private const val PATCH_EFFECT_TIMEOUT_SECONDS = 5L

// why: retain bounded diagnostics without allowing a tool to fill the qualification directory.
private const val PATCH_EFFECT_OUTPUT_BOUND = 64L * 1024

// why: debug coverage binaries and their profiles fit below one GiB; oversize evidence fails closed.
private const val PATCH_EFFECT_BINARY_BOUND = 1024L * 1024 * 1024
