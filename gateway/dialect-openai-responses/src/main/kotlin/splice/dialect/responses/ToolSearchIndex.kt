// NEW: deterministic field-weighted lexical ranking over the deferred
// tool set. Split from ResponsesToolSearch.kt so the per-turn controller
// is not billed for the corpus + score table (concentration, 2026-08-19).
// Same-package — callers keep splice.dialect.responses.ToolSearchIndex.
package splice.dialect.responses

import kotlinx.serialization.json.JsonObject
import splice.core.util.JsonScalars
import splice.core.wire.ToolDefinition

/** Deterministic field-weighted lexical ranking over the deferred set. NOT BM25: over ≤100
 *  two-line documents its saturation and length-normalisation terms are noise and pure tuning
 *  surface. The CORPUS is the parity-relevant part and is a direct port of codex-rs
 *  default_tool_search_text (tools/src/tool_search.rs:67-94): name, name with '_'→space,
 *  description, top-level schema property names and their descriptions. */
internal class ToolSearchIndex(private val deferred: List<ToolDefinition>) {

    private data class Doc(val tool: ToolDefinition, val name: String, val description: String, val properties: String)

    private val docs: List<Doc> = deferred.map { t ->
        Doc(
            tool = t,
            name = "${t.name} ${t.name.replace('_', ' ')}".lowercase(),
            description = t.description.orEmpty().lowercase(),
            properties = propertyCorpus(t).lowercase(),
        )
    }

    /** The full deferred set, in input order — the exhaustive answer for the final permitted round. */
    fun all(): List<ToolDefinition> = deferred

    /** Terms are whitespace-split, matched by substring per field, weighted, summed; ties break by
     *  name so [limit] and equal-score ordering are both deterministic across identical calls. */
    fun search(query: String, limit: Int): List<ToolDefinition> {
        val terms = query.lowercase().split(QUERY_SPLIT).filter { it.isNotEmpty() }
        if (terms.isEmpty()) return emptyList()
        return docs.asSequence()
            .map { it to score(it, terms) }
            .filter { (_, s) -> s > 0 }
            .sortedWith(compareByDescending<Pair<Doc, Int>> { it.second }.thenBy { it.first.tool.name })
            .take(limit)
            .map { it.first.tool }
            .toList()
    }

    private fun score(doc: Doc, terms: List<String>): Int = terms.sumOf { term ->
        fieldHit(doc.name, term, NAME_WEIGHT) + fieldHit(doc.description, term, DESCRIPTION_WEIGHT) +
            fieldHit(doc.properties, term, PROPERTY_WEIGHT)
    }

    private fun fieldHit(field: String, term: String, weight: Int): Int = if (field.contains(term)) weight else 0

    /** Top-level schema property names + their descriptions, space-joined — the "structured schema
     *  property names and their descriptions" leg of the codex-rs corpus (tool_search.rs:67-94). */
    private fun propertyCorpus(t: ToolDefinition): String {
        val props = t.inputSchema?.get(FIELD_PROPERTIES) as? JsonObject ?: return ""
        return props.entries.joinToString(" ") { (name, schema) ->
            "$name ${JsonScalars.str((schema as? JsonObject)?.get(FIELD_DESCRIPTION)).orEmpty()}"
        }
    }
}

// FILE SCOPE ON PURPOSE: one compiled Regex, split against every search query.
private val QUERY_SPLIT = Regex("\\s+")
private const val NAME_WEIGHT = 3
private const val DESCRIPTION_WEIGHT = 2
private const val PROPERTY_WEIGHT = 1
private const val FIELD_PROPERTIES = "properties"
private const val FIELD_DESCRIPTION = "description"
