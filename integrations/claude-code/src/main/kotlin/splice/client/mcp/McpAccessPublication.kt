package splice.client.mcp

import splice.core.util.SecureFile
import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission

private const val MCP_KEY_LENGTH = 64
private const val ROTATED_MARKER = "access-key-rotated"

/** The ONE active MCP bearer, also read per HTTP request by the native front. Only a genuinely
 *  absent publication at bootstrap adopts v1. Reads after bootstrap never derive a fallback. */
internal class McpAccessPublication(stateDir: Path) {
    val file: Path = stateDir.resolve("mcp-hub").resolve("access-key")
    private val owner = file.fileSystem.userPrincipalLookupService
        .lookupPrincipalByName(System.getProperty("user.name"))
    private val shape = Regex("[0-9a-f]{$MCP_KEY_LENGTH}")
    private val foreignBits = setOf(
        PosixFilePermission.GROUP_READ,
        PosixFilePermission.GROUP_WRITE,
        PosixFilePermission.GROUP_EXECUTE,
        PosixFilePermission.OTHERS_READ,
        PosixFilePermission.OTHERS_WRITE,
        PosixFilePermission.OTHERS_EXECUTE,
    )

    fun bootstrap(legacy: McpBearer) {
        val dir = file.parent
        try {
            val before = attributes(dir)
            if (before["isDirectory"] != true || before["owner"] != owner) {
                throw IOException("MCP key directory is not owner-held")
            }
        } catch (_: NoSuchFileException) {
            // SecureFile creates the new directory owner-only, before publishing any bearer.
        }
        SecureFile.ownerOnlyDirectory(dir)?.let { throw IOException("MCP key directory is not owner-only") }
        try {
            attributes(file)
        } catch (_: NoSuchFileException) {
            bootstrapKey(legacy)
        }
        read()
    }

    private fun bootstrapKey(legacy: McpBearer) {
        try {
            attributes(file.resolveSibling(ROTATED_MARKER))
        } catch (_: NoSuchFileException) {
            try {
                SecureFile.createNew0600(file, (legacy() + "\n").toByteArray(Charsets.UTF_8))
            } catch (_: FileAlreadyExistsException) {
                // Another bootstrap won. Read its complete publication, never overwrite it.
            }
            return
        }
        throw IOException("Rotated MCP key publication is missing; refusing to restore the bootstrap bearer")
    }

    fun read(): String {
        requireOwnerHeld(attributes(file.parent), "isDirectory")
        val held = attributes(file)
        requireOwnerHeld(held, "isRegularFile")
        requireSize(held)
        val key = Files.readString(file).trim()
        if (!shape.matches(key)) throw IOException("MCP key publication is malformed")
        return key
    }

    fun replace(expected: String, replacement: String) {
        if (read() != expected) throw IOException("MCP key changed during rotation; retry with its current publication")
        // Publish the no-bootstrap fence first. Even a failed replacement must never let later
        // loss of the active publication resurrect the exposed v1 capability.
        SecureFile.writeAtomic0600(file.resolveSibling(ROTATED_MARKER), "splice:mcp-revocation:v1\n")
        SecureFile.writeAtomic0600(file, replacement + "\n")
    }

    private fun requireOwnerHeld(held: Map<String, Any>, kind: String) {
        if (held[kind] != true || held["owner"] != owner) throw IOException("MCP key state is not owner-held")
        requirePrivateMode(held)
    }

    private fun requirePrivateMode(held: Map<String, Any>) {
        val mode = held["permissions"] as? Set<*> ?: throw IOException("MCP key state has no owner-only mode")
        if (mode.any { it in foreignBits }) throw IOException("MCP key state is not owner-only")
    }

    private fun requireSize(held: Map<String, Any>) {
        val size = held["size"] as? Long ?: throw IOException("MCP key publication has no size")
        if (size !in MCP_KEY_LENGTH.toLong()..(MCP_KEY_LENGTH + 1).toLong()) {
            throw IOException("MCP key publication is malformed")
        }
    }

    private fun attributes(path: Path): Map<String, Any> =
        Files.readAttributes(path, "posix:isDirectory,isRegularFile,owner,permissions,size", NOFOLLOW_LINKS)
}
