// NEW: what a check of `splice add` reports, moved out of AddChecks.kt, which runs the checks: one check's row
// (AddCheck), what an endpoint did when a check asked it (AddFinding, AddCause), and what GET /models yielded
// (ListedModels).
package splice.configuration.add

/** [detail] is the sentence the CLI prints and the log keeps. [finding] is what a check that asked an endpoint
 *  found, by name, for a page that words it itself and never matches the sentence. */
internal data class AddCheck(val name: String, val ok: Boolean, val detail: String, val finding: AddFinding? = null)

/** What an endpoint did when a check asked it: [cause] names it, [status] is the HTTP status it answered with and
 *  [at] the address asked. On the wire: `cause`, `http_status` and `at`, each absent when the check has none. */
internal data class AddFinding(val cause: AddCause, val status: Int? = null, val at: String? = null)

internal enum class AddCause(val wire: String) {
    /** Nothing answered at the address. */
    NO_ANSWER("no_answer"),

    /** The endpoint answered with a status other than 200. */
    HTTP_STATUS("http_status"),

    /** The endpoint answered with something that is no model list. */
    NO_LIST("no_list"),

    /** The endpoint's list leaves out a model the add names. */
    NOT_LISTED("not_listed"),
}

/** What GET /models yielded: the dialect has no list, the endpoint could not serve it, or the ids. */
internal sealed class ListedModels {
    data object Absent : ListedModels()

    /** [blocking] false is a list the add could not read but goes on without (the models the person entered stand). */
    data class Unreadable(
        val detail: String,
        val blocking: Boolean = true,
        val finding: AddFinding? = null,
    ) : ListedModels() {
        fun asCheck(): AddCheck = if (blocking) {
            AddCheck(MODELS_CHECK, false, "$detail; the model list could not be checked", finding)
        } else {
            AddCheck(MODELS_CHECK, true, "WARNING: $detail; the models you entered are used as given", finding)
        }
    }

    /** The dialect has a list but splice holds no credential of its own to ask for it with. */
    data object NoCredential : ListedModels()
    data class Listed(val ids: List<String>, val windows: Map<String, Long> = emptyMap()) : ListedModels()

    /** These windows with a local runtime's own [served] ones over them: Ollama and LM Studio leave the window out of
     *  /v1/models, so without them every local row read "unchecked" (splice-providers' spec pass, Oct 12). */
    fun withServed(served: Map<String, Long>): ListedModels = if (served.isEmpty()) {
        this
    } else {
        val known = this as? Listed ?: Listed(emptyList())
        known.copy(windows = known.windows + served)
    }
}
