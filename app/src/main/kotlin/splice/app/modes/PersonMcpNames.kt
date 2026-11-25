// NEW: modes-mcp name clash (Nov 25, 2026). The names of the person's own MCP servers, as the daemon's MCP host reads
// them (McpGlobalRead: what they imported into splice, `~/.claude.json`'s and the Temple's), for the mcp modes to yield
// a name the person already uses. The modes ask at every request to their address, so the servers are read again only
// when one of those three files has changed since the last reading, as McpHarnessAutoSync watches them: three stat
// calls a request, never a read of `~/.claude.json` each time.
package splice.app.modes

import splice.app.McpGlobalRead
import splice.app.auth.claude.CLAUDE_JSON
import splice.client.mcp.TempleMcpEntry
import splice.core.config.StatePaths
import splice.core.mcp.McpUserRegistry
import splice.core.util.LogSink
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.FileTime

/** The person's own MCP server names for [home] and [paths], read again only when their files change. */
internal class PersonMcpNames(home: Path, paths: StatePaths) {
    private val temple = TempleMcpEntry(home, paths = paths)
    private val registry = McpUserRegistry(paths)
    private val read = McpGlobalRead(home, LogSink {}, temple, registry)
    private val files = listOf(home.resolve(CLAUDE_JSON), registry.file, temple.registrationFile)

    @Volatile
    private var last: Reading? = null

    fun names(): Set<String> {
        val stamps = files.map(::stamp)
        last?.takeIf { it.stamps == stamps }?.let { return it.names }
        val names = read.inputs().map { it.name }.toSet()
        last = Reading(stamps, names)
        return names
    }

    /** [path]'s modification time and size, or null when it is no file or can't be asked. */
    private fun stamp(path: Path): Pair<FileTime, Long>? = try {
        if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            Files.getLastModifiedTime(path) to Files.size(path)
        } else {
            null
        }
    } catch (_: IOException) {
        null
    }

    private data class Reading(val stamps: List<Pair<FileTime, Long>?>, val names: Set<String>)
}
