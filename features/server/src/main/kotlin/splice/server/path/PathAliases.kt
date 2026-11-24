// NEW: #791 (server mode, routing). `[server.aliases]`: a plain base path, such as `/v1`, that stands for a served
// command's API root, `/<command>/v1`, or the team's door, `/door/v1`, for an OpenAI- or Anthropic-style client that
// cannot change its base path. The listener rewrites a request's segments once, as it reads them (RequestPaths), so
// every route after that judges the path the alias stands for: the key, the caps, the minimum client and the
// allowlist hold on `/v1/chat/completions` exactly as on `/claude-team/v1/chat/completions`.
package splice.server.path

import splice.core.topology.ServerConfig
import splice.share.HEAD_API_ROOT

internal class PathAliases(server: ServerConfig) {
    /** Each alias's one segment, to the command or door it stands for. */
    private val aliases = server.aliasSegments

    /** [path] with an alias as its first segment put as the API root it stands for; any other path as it is. */
    fun rewrite(path: List<String>): List<String> {
        val target = path.firstOrNull()?.let(aliases::get) ?: return path
        return listOf(target, HEAD_API_ROOT) + path.drop(1)
    }
}
