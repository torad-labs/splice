// The advertised model and window checks of splice add (failure-handling slice 8).
package splice.configuration.add

import splice.core.model.ModelEntry

// why: an unlisted-model diagnostic shows at most ten advertised alternatives so large catalogs do not flood the CLI.
private const val LISTED_SHOWN = 10

/** The single name used for model-list checks on the CLI and console. */
internal const val MODELS_CHECK = "models"

// why: said in place of a model count and a window check on a head that lists no model of its own.
private const val OWN_MODELS = "Claude Code's own: splice forwards whichever model it asks for"

internal object AddModelChecks {
    /** The model lines of an add's report: the [listed] and [windows] checks, or, where the client shows its own
     *  models ([own]) and the head names none, one line saying so. Such an add read "4 model(s) as entered" and
     *  named four rows as unchecked, for a head that lists no model (Nov 11, 2026). */
    fun rows(own: Boolean, listed: AddCheck, windows: AddCheck): List<AddCheck> =
        if (own) listOf(AddCheck(MODELS_CHECK, true, OWN_MODELS)) else listOf(listed, windows)

    /** [authoritative] false is a server that answers any model id: its list still has to ANSWER
     *  (an unreadable one fails as before), but an unlisted row is trusted and said to be. */
    fun listed(models: List<String>, listed: ListedModels, authoritative: Boolean = true): AddCheck =
        when (listed) {
            // V4-267: in the user's terms, not the dialect's: where the rows came from and why nothing checked them.
            ListedModels.Absent -> {
                val count = if (models.size == 1) "1 model" else "${models.size} models"
                val why = "this provider publishes no list to check them against"
                AddCheck(MODELS_CHECK, true, "$count from splice's catalog; $why")
            }
            ListedModels.NoCredential -> {
                val why = "this provider forwards your own login, " +
                    "so splice holds no credential to read its model list with"
                AddCheck(MODELS_CHECK, true, "${models.size} model(s) as entered; $why")
            }
            is ListedModels.Unreadable -> listed.asCheck()
            is ListedModels.Listed -> {
                val missing = models.filterNot { it in listed.ids }
                val shown = listed.ids.take(LISTED_SHOWN)
                when {
                    missing.isEmpty() ->
                        AddCheck(MODELS_CHECK, true, "all ${models.size} row(s) listed by the endpoint")
                    authoritative -> AddCheck(
                        MODELS_CHECK,
                        false,
                        "not listed by the endpoint: $missing (it lists $shown)",
                        AddFinding(AddCause.NOT_LISTED),
                    )
                    else ->
                        AddCheck(MODELS_CHECK, true, "$missing trusted: the server answers any model id (lists $shown)")
                }
            }
        }

    /** Check advertised limits and name every declared row whose limit could not be checked: by the local runtime's
     *  [unchecked] reason when it never answered, never as the provider listing no size (splice-usage, Oct 13). */
    fun windows(models: List<ModelEntry>, listed: ListedModels, unchecked: String? = null): AddCheck {
        val windows = (listed as? ListedModels.Listed)?.windows.orEmpty()
        val oversized = models.any { row -> windows[row.id]?.let { row.contextWindow > it } == true }
        // The rows no size could be checked for share one reason, so it is said once, after their names: four Claude
        // rows said "unchecked: provider lists no window size" four times on one line (Nov 11, 2026).
        val blind = models.filter { windows[it.id] == null }.map { it.id }
        val why = unchecked ?: "provider lists no window size"
        val detail = models.mapNotNull { row ->
            val served = windows[row.id]
            when {
                served == null -> "${blind.joinToString(", ")} unchecked: $why".takeIf { row.id == blind.first() }
                row.contextWindow > served -> "${row.id} declares ${row.contextWindow}, provider serves $served"
                else -> "${row.id} fits: declares ${row.contextWindow}, provider serves $served"
            }
        }.joinToString("; ").ifEmpty { "no declared model windows to check" }
        return AddCheck("windows", !oversized, detail)
    }
}
