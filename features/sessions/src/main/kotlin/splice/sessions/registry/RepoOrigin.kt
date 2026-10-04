// NEW: V4-444 — cached credential-free git origins for the sessions and projects wire.
package splice.sessions.registry

import splice.core.util.Cancellables
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime

// why: match RepoResolver's bound so polling many sessions cannot retain unbounded roots.
private const val ORIGIN_CACHE_LIMIT = 64

// why: local git metadata is bounded to 1 MiB; a larger file is not read into a polling route.
private const val MAX_CONFIG_BYTES = 1_048_576L
private val ORIGIN_SECTION = Regex("""^\[(?i:remote)\s+"origin"\]\s*(?:[#;].*)?$""")
private val ORIGIN_URL = Regex("""^(?i:url)\s*=\s*(.*)$""")
private data class OriginStamp(val key: Any?, val bytes: Long, val modified: Long)
private data class CachedOrigin(val stamp: OriginStamp, val remote: String?)

/** Reads only the known root's local git config. The cache holds sanitized values, never userinfo. */
internal object RepoOrigin {
    private val cache = LinkedHashMap<Path, CachedOrigin>()

    fun of(root: String): String? =
        // ast-grep-ignore: kt-no-silent-result-collapse -- an absent or unreadable local origin is omitted from the wire; neither the config nor exception text is exposed
        Cancellables.runCatchingCancellable { read(Path.of(root)) }.getOrNull()

    private fun read(root: Path): String? {
        val config = configAt(root)
        if (!Files.isRegularFile(config)) return null
        val attrs = Files.readAttributes(config, "basic:fileKey,size,lastModifiedTime")
        val stamp = OriginStamp(
            attrs["fileKey"],
            attrs.getValue("size") as Long,
            (attrs.getValue("lastModifiedTime") as FileTime).toMillis(),
        )
        synchronized(cache) { cache[config]?.takeIf { it.stamp == stamp }?.let { return it.remote } }
        val remote = if (stamp.bytes <= MAX_CONFIG_BYTES) origin(Files.readAllLines(config)) else null
        synchronized(cache) {
            cache[config] = CachedOrigin(stamp, remote)
            while (cache.size > ORIGIN_CACHE_LIMIT) cache.remove(cache.keys.first())
        }
        return remote
    }

    private fun configAt(root: Path): Path {
        val dotGit = root.resolve(".git")
        if (Files.isDirectory(dotGit)) return dotGit.resolve("config")
        if (!Files.isRegularFile(dotGit) || Files.size(dotGit) > MAX_CONFIG_BYTES) return dotGit.resolve("config")
        val pointer = Files.readString(dotGit).trim().removePrefix("gitdir:").trim()
        val git = root.resolve(pointer).normalize()
        val common = git.resolve("commondir")
        val shared = if (Files.isRegularFile(common)) git.resolve(Files.readString(common).trim()).normalize() else git
        return shared.resolve("config")
    }

    private fun origin(lines: List<String>): String? {
        var inOrigin = false
        var remote: String? = null
        for (line in lines) {
            val value = line.trim()
            if (value.startsWith("[")) {
                inOrigin = ORIGIN_SECTION.matches(value)
            } else if (inOrigin) {
                ORIGIN_URL.matchEntire(value)?.groupValues?.get(1)?.let { remote = sanitized(unquote(it)) }
            }
        }
        return remote
    }

    private fun unquote(raw: String): String = buildString {
        var quoted = false
        var escaped = false
        for (char in raw) {
            when {
                escaped -> {
                    append(unescaped(char))
                    escaped = false
                }
                char == '\\' -> escaped = true
                char == '"' -> quoted = !quoted
                !quoted && char in "#;" -> break
                else -> append(char)
            }
        }
    }.trim()

    private fun unescaped(char: Char): Char = when (char) {
        'n' -> '\n'
        't' -> '\t'
        'b' -> '\b'
        else -> char
    }

    private fun sanitized(raw: String): String? {
        if (raw.isBlank() || raw.any { it.isISOControl() }) return null
        // git's scp-like ssh syntax has a public user, not URL credentials.
        return if ("://" in raw) uriRemote(raw) else if (raw.startsWith("git@")) raw else raw.substringAfterLast('@')
    }

    private fun uriRemote(raw: String): String {
        val authority = URI(raw).rawAuthority ?: return raw
        if ('@' !in authority) return raw
        val separator = "://"
        val start = raw.indexOf(separator) + separator.length
        return raw.replaceRange(start, start + authority.length, authority.substringAfterLast('@'))
    }
}
