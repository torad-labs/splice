package splice.client.mcp

import splice.core.util.SecureFile
import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission

// why: HMAC-SHA256 publishes its 32-byte capability as 64 lowercase hex characters.
private const val MCP_KEY_LENGTH = 64
private const val ROTATED_MARKER = "access-key-rotated"

// why: beside the management key, so wiping mcp-hub cannot erase the fence that keeps a revoked v1 bearer revoked.
private const val STATE_ROTATED_MARKER = "mcp-access-rotated"
private const val BINDING_FILE = "access-key-binding"
private const val REVOCATION_TEXT = "splice:mcp-revocation:v1\n"

/** The ONE active MCP bearer, also read per HTTP request by the native front, and its binding to the management key
 *  that issued it. File mechanics only: which bearer to adopt, keep or replace is McpAccessKey's decision. */
internal class McpAccessPublication(stateDir: Path) {
    val file: Path = stateDir.resolve("mcp-hub").resolve("access-key")
    private val binding: Path = file.resolveSibling(BINDING_FILE)
    private val fences = listOf(stateDir.resolve(STATE_ROTATED_MARKER), file.resolveSibling(ROTATED_MARKER))

    // Looked up at first use, never construction: a user the system cannot name turns MCP off, not the daemon.
    private val owner by lazy {
        file.fileSystem.userPrincipalLookupService.lookupPrincipalByName(System.getProperty("user.name"))
    }
    private val shape = Regex("[0-9a-f]{$MCP_KEY_LENGTH}")
    private val foreignBits = setOf(
        PosixFilePermission.GROUP_READ,
        PosixFilePermission.GROUP_WRITE,
        PosixFilePermission.GROUP_EXECUTE,
        PosixFilePermission.OTHERS_READ,
        PosixFilePermission.OTHERS_WRITE,
        PosixFilePermission.OTHERS_EXECUTE,
    )

    /** The owner-only directory every MCP key file lives in, created owner-only before any bearer is published. */
    fun prepareDirectory() {
        val dir = file.parent
        try {
            val before = attributes(dir)
            if (before["isDirectory"] != true || before["owner"] != owner) {
                throw IOException("MCP key directory is not owner-held")
            }
        } catch (_: NoSuchFileException) {
            // SecureFile creates the new directory owner-only, before publishing any bearer.
        }
        SecureFile.ownerOnlyDirectory(dir)?.let { throw IOException("MCP key directory is not owner-only: $it") }
    }

    fun present(): Boolean = try {
        attributes(file)
        true
    } catch (_: NoSuchFileException) {
        false
    }

    /** Whether an explicit rotation ever revoked the bootstrap bearer: boot must never restore it. A fence only the old
     *  location inside mcp-hub holds is copied beside the management key, so clearing mcp-hub cannot drop it. */
    fun carryFence(): Boolean {
        val held = fences.map { fence ->
            try {
                attributes(fence)
                true
            } catch (_: NoSuchFileException) {
                false
            }
        }
        if (!held.first() && held.last()) SecureFile.writeAtomic0600(fences.first(), REVOCATION_TEXT)
        return held.any { it }
    }

    /** First-run publication. Another bootstrap that already won keeps its complete publication. */
    fun create(key: String, bound: String) {
        try {
            SecureFile.createNew0600(file, (key + "\n").toByteArray(Charsets.UTF_8))
        } catch (_: FileAlreadyExistsException) {
            return
        }
        bind(bound)
    }

    fun read(): String = readKey(file, "MCP key publication")

    /** The stored binding, or null when the publication predates bindings. */
    fun binding(): String? = if (Files.notExists(binding, NOFOLLOW_LINKS)) null else readKey(binding, "MCP key binding")

    fun bind(bound: String) = SecureFile.writeAtomic0600(binding, bound + "\n")

    /** Publishes a new incarnation. The no-bootstrap fence goes first: even a failed replacement must never let a later
     *  loss of the active publication resurrect the exposed v1 capability. The binding follows the bearer, so a crash
     *  between them leaves the old binding beside the new bearer, and the next adoption publishes a fresh one. */
    fun publish(key: String, bound: String) {
        SecureFile.writeAtomic0600(fences.first(), REVOCATION_TEXT)
        SecureFile.writeAtomic0600(file, key + "\n")
        bind(bound)
    }

    fun replace(expected: String, key: String, bound: String) {
        if (read() != expected) throw IOException("MCP key changed during rotation; retry with its current publication")
        publish(key, bound)
    }

    private fun readKey(path: Path, what: String): String {
        requireOwnerHeld(attributes(path.parent), "isDirectory")
        val held = attributes(path)
        requireOwnerHeld(held, "isRegularFile")
        requireSize(held, what)
        val key = Files.readString(path).trim()
        if (!shape.matches(key)) throw IOException("$what is malformed")
        return key
    }

    private fun requireOwnerHeld(held: Map<String, Any>, kind: String) {
        if (held[kind] != true || held["owner"] != owner) throw IOException("MCP key state is not owner-held")
        requirePrivateMode(held)
    }

    private fun requirePrivateMode(held: Map<String, Any>) {
        val mode = held["permissions"] as? Set<*> ?: throw IOException("MCP key state has no owner-only mode")
        if (mode.any { it in foreignBits }) throw IOException("MCP key state is not owner-only")
    }

    private fun requireSize(held: Map<String, Any>, what: String) {
        val size = held["size"] as? Long ?: throw IOException("$what has no size")
        if (size !in MCP_KEY_LENGTH.toLong()..(MCP_KEY_LENGTH + 1).toLong()) {
            throw IOException("$what is malformed")
        }
    }

    private fun attributes(path: Path): Map<String, Any> =
        Files.readAttributes(path, "posix:isDirectory,isRegularFile,owner,permissions,size", NOFOLLOW_LINKS)
}
