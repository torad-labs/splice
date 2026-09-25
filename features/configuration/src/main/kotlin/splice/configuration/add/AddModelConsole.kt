// NEW: V4-220 (2026-09-25) — `splice add-model` as the console runs it. The offers, the composition and
// the write are the CLI's (AddModelOffers, AddModelCompose, AddWrite.replace); the console only names the
// head and the ids in one request where the CLI asks two prompts.
//
// THE FILE IS READ AT WRITE TIME. What the console listed is a view: the request names ids, and they are
// checked against the offers as splice.toml stands when the add runs, under the same lock the console's
// add saves under, so neither renames over the other. The save's restart is the add's (AddDaemonRestart):
// the roster is read at start, so an added model is reachable only after it.
package splice.configuration.add

import splice.core.util.Cancellables
import splice.core.util.EnvReader
import splice.core.util.SafeFailureText
import splice.topology.TopologyLoader
import java.nio.file.Files
import java.nio.file.Path

/** What add-model can offer from splice.toml as it stands. */
internal sealed class AddModelListed {
    /** [text] is the file the [offers] were read from: the one an add composes on and compares against. */
    data class Listed(val path: Path, val text: String, val offers: List<AddModelOffer>) : AddModelListed()

    /** The file did not read or parse; [detail] is SafeFailureText's. */
    data class Unloadable(val path: Path, val detail: String) : AddModelListed()
}

internal sealed class AddModelOutcome {
    data class Added(val path: Path, val headKey: String, val ids: List<String>, val restart: AddRestartTaken) :
        AddModelOutcome()

    data class NoSuchHead(val headKey: String) : AddModelOutcome()

    /** [id] is on [headKey]'s roster already, or not in the catalogue. */
    data class NotOffered(val headKey: String, val id: String) : AddModelOutcome()

    /** Nothing written; [text] is the one sentence the console shows. */
    data class Refused(val text: String) : AddModelOutcome()
}

/** [writes] is the console add's own lock (AddConsole), so an add's save and an add-model never interleave. */
internal class AddModelConsole(private val env: EnvReader, private val writes: Any) {
    private val offers = AddModelOffers()
    private val compose = AddModelCompose(RosterEditor(HeadModelArray()::withAdded))
    private val texts = AddRefusalText()

    fun list(): AddModelListed {
        val path = TopologyLoader.configPath(env)
        return Cancellables.runCatchingCancellable {
            val text = Files.readString(path)
            AddModelListed.Listed(path, text, offers.of(TopologyLoader.parse(text)))
        }.getOrElse { AddModelListed.Unloadable(path, SafeFailureText.render(it)) }
    }

    /** The ids added to [headKey]'s roster, then the restart that makes them reachable. Nothing restarts
     *  after a refusal. take() answers at once (a wait runs on its own), so it is taken under the lock. */
    fun add(headKey: String, ids: List<String>, restart: AddDaemonRestart): AddModelOutcome = synchronized(writes) {
        val listed = when (val read = list()) {
            is AddModelListed.Unloadable ->
                return AddModelOutcome.Refused("${read.path} does not load (${read.detail}), so nothing was saved.")
            is AddModelListed.Listed -> read
        }
        val offer = listed.offers.firstOrNull { it.headKey == headKey } ?: return AddModelOutcome.NoSuchHead(headKey)
        val offered = offer.remaining.map { it.id }.toSet()
        ids.firstOrNull { it !in offered }?.let { return AddModelOutcome.NotOffered(headKey, it) }
        val plan = AddModelPlan(offer, offer.remaining.filter { it.id in ids })
        val path = listed.path
        Cancellables.runCatchingCancellable { AddWrite().replace(path, listed.text, compose(listed.text, plan)) }.fold(
            onSuccess = { written ->
                when (written) {
                    AddWritten.Written ->
                        AddModelOutcome.Added(path, headKey, plan.models.map { it.id }, restart.take())
                    is AddWritten.Refused -> AddModelOutcome.Refused(texts.modelStale(path.toString(), written))
                }
            },
            onFailure = { AddModelOutcome.Refused(refusal(path, it)) },
        )
    }

    /** A roster edit AddModelCompose refused says why in its own words; anything else names the file. */
    private fun refusal(path: Path, failure: Throwable): String =
        (failure as? AddRefused)?.message
            ?: "$path could not be written (${SafeFailureText.render(failure)}), so nothing was saved."
}
