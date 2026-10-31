package splice.app.mcp

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.system.exitProcess

/** Codex refuses an explicit CODEX_HOME that does not exist before its config API starts. */
class NativeCodexMcpHomeTest {
    @Test
    fun `a fresh registration can reach its private native configuration`(@TempDir directory: Path) = runBlocking {
        val home = directory.resolve("new-account").resolve(".codex")
        connect(home)
        assertTrue(Files.isDirectory(home))
        if ("posix" in home.fileSystem.supportedFileAttributeViews()) {
            assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(home)))
        }
    }

    @Test
    fun `an existing native home keeps its configuration`(@TempDir directory: Path) = runBlocking {
        val home = Files.createDirectory(directory.resolve(".codex"))
        val config = home.resolve("config.toml")
        Files.writeString(config, "model = \"keep-this-model\"\n")
        connect(home)
        assertEquals("model = \"keep-this-model\"\n", Files.readString(config))
    }

    @Test
    fun `a file at the native home still refuses registration`(@TempDir directory: Path) = runBlocking {
        val home = directory.resolve(".codex")
        Files.writeString(home, "keep-this-file")
        val session = NativeCodexMcpRpc {}.connect(home, launch(), 15_000)
        try {
            assertNull(session)
            assertEquals("keep-this-file", Files.readString(home))
        } finally {
            session?.close()
        }
    }

    private suspend fun connect(home: Path) {
        val session = NativeCodexMcpRpc {}.connect(home, launch(), 15_000)
        assertNotNull(session, "a newly selected native home must initialize the config API")
        try {
            val response = checkNotNull(session).request("config/read") as JsonObject
            assertEquals(home.toString(), response["codexHome"]?.jsonPrimitive?.content)
        } finally {
            session?.close()
        }
    }

    private fun launch(): NativeCodexMcpRpc.LaunchContext = NativeCodexMcpRpc.LaunchContext(
        listOf(
            ProcessHandle.current().info().command().orElse("java"),
            "-cp",
            System.getProperty("codeMode.testClasspath", System.getProperty("java.class.path")),
            NativeCodexHomeProcess::class.java.name,
        ),
    )
}

/** An isolated config API child with the real native runtime's explicit-home precondition. */
object NativeCodexHomeProcess {
    @JvmStatic
    fun main(args: Array<String>) {
        val home = Path.of(checkNotNull(System.getenv("CODEX_HOME")))
        if (!Files.isDirectory(home)) exitProcess(2)
        generateSequence(::readlnOrNull).forEach { line ->
            val request = Json.parseToJsonElement(line) as JsonObject
            request["id"]?.let { id ->
                println(
                    buildJsonObject {
                        put("id", id)
                        put("result", buildJsonObject { put("codexHome", home.toString()) })
                    },
                )
            }
        }
    }
}
