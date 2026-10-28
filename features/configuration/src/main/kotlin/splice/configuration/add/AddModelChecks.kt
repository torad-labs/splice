// The advertised model and window checks of splice add (failure-handling slice 8).
package splice.configuration.add

import splice.core.model.ModelEntry

private const val LISTED_SHOWN = 10

/** The single name used for model-list checks on the CLI and console. */
internal const val MODELS_CHECK = "models"

internal object AddModelChecks {
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
                    authoritative ->
                        AddCheck(MODELS_CHECK, false, "not listed by the endpoint: $missing (it lists $shown)")
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
        val detail = models.joinToString("; ") { row ->
            val served = windows[row.id]
            when {
                served == null -> "${row.id} unchecked: ${unchecked ?: "provider lists no window size"}"
                row.contextWindow > served -> "${row.id} declares ${row.contextWindow}, provider serves $served"
                else -> "${row.id} fits: declares ${row.contextWindow}, provider serves $served"
            }
        }.ifEmpty { "no declared model windows to check" }
        return AddCheck("windows", !oversized, detail)
    }
}
