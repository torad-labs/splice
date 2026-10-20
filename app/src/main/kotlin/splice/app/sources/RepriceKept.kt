// NEW: Oct 20, 2026 — the hours EconomicsReprice has settled, kept across a restart while the cards they were priced at
// are still the ones in force.
//
// why: its answers lived only in memory, and every hour recorded before the plan split asks to be priced again, so each
// start decoded every archived perf generation back to the oldest of them: about 57% of the allocation in the first
// 40 s of the 08:32 CT boot on Oct 20, 12 s of claudex's start and 20 s of claude-splice's. A price is only as good as
// the cards behind it, so the kept answers carry a key over the build, the card of every model their hours ran and the
// plan answer for every account that carried them, and are all dropped when that key differs (splice-lead, Oct 20).
package splice.app.sources

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import splice.core.model.TurnPrice
import splice.core.util.SecureFile
import splice.head.usage.AccountOnPlan
import splice.head.usage.BucketCost
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.MessageDigest

/** One settled hour: the rollup figures it answered, and its repriced cost (null when its rows are not its turns). */
internal data class RepriceSettled(val recorded: List<Long>, val cost: BucketCost?)

/** What a previous run kept, when its key still holds: the hours, and the models and accounts they ran on. */
internal data class RepriceKeptHours(
    val settled: Map<Long, RepriceSettled>,
    val models: Set<String>,
    val accounts: Set<String?>,
)

/** A repriced cost as it is kept on disk. */
@Serializable
internal data class SavedRepriceCost(
    val costUsd: Double? = null,
    val unpricedTurns: Long = 0,
    val failedTurns: Long? = null,
    val planTurns: Long? = null,
    val planValueUsd: Double? = null,
    val planUnpricedTurns: Long? = null,
) {
    fun cost(): BucketCost = BucketCost(costUsd, unpricedTurns, failedTurns, planTurns, planValueUsd, planUnpricedTurns)
}

/** One settled hour as it is kept on disk; no cost is the answer that its rows are not its turns. */
@Serializable
internal data class SavedRepriceHour(val hour: Long, val recorded: List<Long>, val cost: SavedRepriceCost? = null)

/** Every kept hour, the models and accounts they ran on, and the key their prices were made under. */
@Serializable
internal data class SavedReprice(
    val key: String,
    val models: List<String>,
    val accounts: List<String?>,
    val hours: List<SavedRepriceHour>,
)

/** The build this daemon runs: each class path entry's identity, size and time, so every install reads as a new one. */
internal class RunningBuild {
    fun id(): String = System.getProperty("java.class.path").orEmpty().split(File.pathSeparator)
        .filter(String::isNotEmpty)
        .joinToString("|") { entry -> "$entry:${mark(Path.of(entry))}" }

    private fun mark(path: Path): String = try {
        val key = Files.getAttribute(path, "fileKey", LinkOption.NOFOLLOW_LINKS)
        "$key:${Files.size(path)}:${Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS).toMillis()}"
    } catch (_: IOException) {
        "unreadable"
    }
}

/** The key a price is good under: the build, and the card and plan answer in force for the models and accounts. */
internal class RepriceCards(
    private val price: TurnPrice,
    private val plans: AccountOnPlan,
    private val build: String = RunningBuild().id(),
) {
    fun key(models: Set<String>, accounts: Set<String?>): String {
        val text = buildString {
            append(build).append('\n')
            models.sorted().forEach { append(it).append('=').append(price.cardOf(it)).append('\n') }
            accounts.sortedBy { it.orEmpty() }.forEach { append(it).append('=').append(plans(it)).append('\n') }
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(StandardCharsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}

/** Where one head's settled repricing is kept: `<head>-perf-reprice.json`, beside the live request log. */
internal class RepriceKept(files: PerfReadFiles, private val cards: RepriceCards) {
    private val path = files.stateFile("reprice")
    private val json = Json { ignoreUnknownKeys = true }

    /** The kept hours when the key they were priced under is the key now; otherwise none, so every hour is priced
     *  again. */
    fun load(): RepriceKeptHours? {
        val kept = decoded() ?: return null
        val models = kept.models.toSet()
        val accounts = kept.accounts.toSet()
        if (cards.key(models, accounts) != kept.key) return null
        val settled = kept.hours.associate { it.hour to RepriceSettled(it.recorded, it.cost?.cost()) }
        return RepriceKeptHours(settled, models, accounts)
    }

    /** Best effort: hours that cannot be kept are priced again after the next restart, as before. */
    fun save(kept: RepriceKeptHours) {
        try {
            val hours = kept.settled.entries.sortedBy { it.key }.map { (hour, answer) ->
                SavedRepriceHour(hour, answer.recorded, answer.cost?.let(::saved))
            }
            val saved = SavedReprice(
                key = cards.key(kept.models, kept.accounts),
                models = kept.models.sorted(),
                accounts = kept.accounts.sortedBy { it.orEmpty() },
                hours = hours,
            )
            SecureFile.writeAtomic0600(path, json.encodeToString(SavedReprice.serializer(), saved))
        } catch (_: IOException) {
            // The hours still stand in memory for this run.
        }
    }

    private fun decoded(): SavedReprice? = try {
        json.decodeFromString(SavedReprice.serializer(), Files.readString(path))
    } catch (_: IOException) {
        null
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun saved(cost: BucketCost): SavedRepriceCost = SavedRepriceCost(
        costUsd = cost.costUsd,
        unpricedTurns = cost.unpricedTurns,
        failedTurns = cost.failedTurns,
        planTurns = cost.planTurns,
        planValueUsd = cost.planValueUsd,
        planUnpricedTurns = cost.planUnpricedTurns,
    )
}
