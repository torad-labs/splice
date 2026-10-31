// NEW: the newest release on an update channel, read from GitHub's releases list (Oct 13). One reader for the three
// places that ask: the daemon's updater (UpgradeAuto), `splice upgrade --check` and the console's Check (UpgradeChecks)
// on the beta channel, so they never disagree about what is newest. The latest-release URL the stable check reads never
// shows a prerelease, which is why the beta channel reads this list instead.
package splice.lifecycle.upgrade

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import splice.core.util.EnvReader
import splice.core.util.JsonScalars

// why: the first page of releases, newest first; a newer release than the one installed is never past the 30th.
private const val RELEASES_API = "https://api.github.com/repos/torad-labs/splice/releases?per_page=30"

internal class ReleaseChannel(env: EnvReader, private val fetch: UpgradeFetch = JdkUpgradeFetch()) {
    private val layout = UpgradeLayout(env)
    private val json = Json { ignoreUnknownKeys = true }

    /** The highest published version on the channel: never a draft, and a prerelease only when [beta]. */
    fun newest(beta: Boolean): Upgraded<String> {
        val body = try {
            fetch(RELEASES_API)
        } catch (failed: UpgradeFetchFailed) {
            return Upgraded.Refused("reading the releases list failed: ${failed.why}")
        }
        val releases = body?.let { json.parseToJsonElement(String(it)) as? JsonArray }
        val newest = releases.orEmpty().mapNotNull { it as? JsonObject }
            .filter { JsonScalars.str(it, "draft") == "false" }
            .filter { beta || JsonScalars.str(it, "prerelease") == "false" }
            .mapNotNull { JsonScalars.str(it, "tag_name")?.removePrefix("v") }
            .filter(layout::isVersion)
            .maxByOrNull(::ReleaseOrder)
        return newest?.let { Upgraded.Ok(it) } ?: Upgraded.Refused("the releases list names no release on this channel")
    }
}
