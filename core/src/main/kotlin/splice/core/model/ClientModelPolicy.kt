package splice.core.model

/** What a [ModelCatalog] declares to the client about which models it may pick. */
public data class ClientModelPolicy(
    /** Declared model id to Claude tier, kept even when a listing omits the model. */
    val tierSlots: Map<String, String> = emptyMap(),
    /** The head forwards the client's own Claude login, so the client picks its models (2026-09-30): any id
     *  is admitted and nothing about models is declared to the client. Counts ride raw except for
     *  Claude sessions whose actual divisor was learned from a status-line post. [ModelCatalog.models] holds
     *  only labels and rate cards, and may be empty. */
    val open: Boolean = false,
)
