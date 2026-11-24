// NEW: terminal launch profiles (issue #791, comment §4). The links a profile's folder carries into `~/.claude`, each
// made in the profile and none ever written in `~/.claude`: one per skill, agent and command the profile keeps (a name
// starting with a `drop_names` prefix is left out), and `projects` and `sessions`, so `--resume` and `/resume` see the
// person's sessions from any profile. Each launch keeps the links that are still right and takes away the rest, so a
// dropped or removed entry leaves no link behind and a running session keeps every link it still has. Only links
// are ever taken away: a file or folder of the profile's own is kept, and said. A link is removed as a link, never
// followed.
package splice.client.profile

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

internal class ProfileLinks(
    private val claudeDir: Path,
    private val dropNames: List<String>,
    private val maxEntries: Int = PROFILE_MAX_ENTRIES,
) {
    /** Links [folder]'s skills, agents, commands, projects and sessions; what the person should know of what was kept
     *  as the profile's own. */
    fun link(folder: Path): List<String> =
        LISTED.flatMap { listed(folder, it) } + SHARED.mapNotNull { shared(folder, it) }

    /** `<folder>/<kind>/`, a folder of the profile's own holding one link per kept entry of `~/.claude/<kind>`. A link
     *  that already points where it should is left in place, so a session running in the profile never sees it go;
     *  only a link to an entry no longer kept, or pointing elsewhere, is taken away. */
    private fun listed(folder: Path, kind: String): List<String> {
        val target = folder.resolve(kind)
        if (Files.isSymbolicLink(target)) Files.delete(target)
        Files.createDirectories(target)
        val source = claudeDir.resolve(kind)
        val wanted = if (Files.isDirectory(source)) entries(source).filter(::kept) else emptyList()
        val byName = wanted.associateBy { it.fileName.toString() }
        val stale = entries(target).filter { link ->
            Files.isSymbolicLink(link) && Files.readSymbolicLink(link) != byName[link.fileName.toString()]
        }
        stale.forEach(Files::delete)
        return byName.mapNotNull { (name, entry) ->
            val link = target.resolve(name)
            when {
                Files.isSymbolicLink(link) -> null
                Files.exists(link, LinkOption.NOFOLLOW_LINKS) -> "$kind/$name is the profile's own, kept over yours"
                else -> {
                    Files.createSymbolicLink(link, entry)
                    null
                }
            }
        }
    }

    /** Whether [entry] of `~/.claude` gets a link: not hidden, and no `drop_names` prefix starts its name. */
    private fun kept(entry: Path): Boolean {
        val name = entry.fileName.toString()
        return !name.startsWith(HIDDEN_PREFIX) && dropNames.none { it.isNotBlank() && name.startsWith(it) }
    }

    /** `<folder>/<name>` as a link to `~/.claude/<name>` while that is there; no link while it is not. */
    private fun shared(folder: Path, name: String): String? {
        val link = folder.resolve(name)
        val source = claudeDir.resolve(name)
        val present = Files.isDirectory(source)
        val current = Files.isSymbolicLink(link) && present && Files.readSymbolicLink(link) == source
        if (Files.isSymbolicLink(link) && !current) Files.delete(link)
        return when {
            !present || current -> null
            Files.exists(link, LinkOption.NOFOLLOW_LINKS) ->
                "$name is the profile's own folder, so --resume there sees only this profile's sessions"
            else -> {
                Files.createSymbolicLink(link, source)
                null
            }
        }
    }

    /** [dir]'s entries, at most [maxEntries] of them, never through a link. */
    private fun entries(dir: Path): List<Path> =
        Files.list(dir).use { stream -> stream.limit(maxEntries.toLong()).sorted().toList() }
}

// The folders whose entries Claude Code lists one by one, and the ones a profile shares whole with ~/.claude.
private val LISTED = listOf("skills", "agents", "commands")
private val SHARED = listOf("projects", "sessions")

private const val HIDDEN_PREFIX = "."

// why: a Claude folder holds tens of skills, agents or commands; 4096 bounds one listing far above that, so a folder
// that is not what it seems can't hold a launch up.
internal const val PROFILE_MAX_ENTRIES: Int = 4096
