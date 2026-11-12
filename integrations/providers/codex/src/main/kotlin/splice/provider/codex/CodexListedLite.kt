// NEW: fix 5 of the Oct 27 Responses fixes (artifacts/responses-gaps-2026-10-27/goals.md). The lite request shape, the
// lite header and the lite verbosity followed a gpt-5.6 / gpt-6 name pattern, so models the backend marks
// use_responses_lite under other names (gpt-reserve, codex-auto-review) went out in the full shape, and code mode's
// lite check refused them. The backend's own list now decides, read the way Codex reads it.
package splice.provider.codex

import splice.core.model.DiscoveredModel
import splice.core.model.HeadDiscoveredModels
import splice.dialect.responses.ResponsesListedLite

/**
 * [head]'s model list, asked whether a model speaks responses-lite, as Codex asks its own: the model takes the
 * `use_responses_lite` of the listed model whose id or alias is the longest prefix of its id (codex-rs models-manager
 * manager.rs, find_model_by_longest_prefix), and a model no listed id begins is not lite, as Codex's fallback metadata
 * is not (model_info.rs, model_info_from_slug). Hidden models count: the list marks them too. With no list discovered
 * or kept it answers null, and the name pattern on [CodexQuirks] decides, as Codex's bundled catalog stands in for a
 * list it could not fetch.
 *
 * The list is read on every ask, so a refresh while the daemon runs reaches the next turn. One turn asks once for every
 * message it builds, so the answer for the last model asked is kept against the list it came from.
 */
public class CodexListedLite(private val roster: HeadDiscoveredModels, private val head: String) : ResponsesListedLite {
    @Volatile private var last: Answer? = null

    override fun marks(model: String): Boolean? {
        val listed = roster.metadataForHead(head)
        if (listed.isEmpty()) return null
        val kept = last?.takeIf { it.listed === listed && it.model == model }
        return kept?.lite ?: lookUp(listed, model).also { last = Answer(listed, model, it) }
    }

    private fun lookUp(listed: List<DiscoveredModel>, model: String): Boolean {
        val id = model.trim().lowercase()
        return listed.flatMap { row -> row.spellings.map { it.trim().lowercase() to row } }
            .filter { (spelling, _) -> spelling.isNotEmpty() && id.startsWith(spelling) }
            .maxByOrNull { (spelling, _) -> spelling.length }
            ?.second?.useResponsesLite ?: false
    }

    private data class Answer(val listed: List<DiscoveredModel>, val model: String, val lite: Boolean)
}
