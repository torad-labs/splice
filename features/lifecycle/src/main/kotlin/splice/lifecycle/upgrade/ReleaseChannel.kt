// NEW: the newest release on an update channel, read from the releases list splice.torad.ai publishes (Oct 13, moved off
// GitHub's list on Oct 31 with the downloads). One reader for the three places that ask: the daemon's updater
// (UpgradeAuto), `splice upgrade --check` and the console's Check (UpgradeChecks) on the beta channel, so they never
// disagree about what is newest. /latest/ on the site never follows a prerelease, which is why the beta channel reads
// this list instead. The list keeps the shape of GitHub's: tag_name, prerelease and draft per release.
package splice.lifecycle.upgrade

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import splice.core.util.EnvReader
import splice.core.util.JsonScalars

// why: every published release, newest first (tools/release/src/lib/downloads.ts writes it, the site never caches it).
private const val RELEASES_LIST = "$RELEASE_SITE/releases.json"

internal class ReleaseChannel(env: EnvReader, private val fetch: UpgradeFetch = JdkUpgradeFetch()) {
    private val layout = UpgradeLayout(env)
    private val json = Json { ignoreUnknownKeys = true }

    /** The highest published version on the channel: never a draft, and a prerelease only when [beta]. */
    fun newest(beta: Boolean): Upgraded<String> {
        val body = try {
            fetch(RELEASES_LIST)
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
