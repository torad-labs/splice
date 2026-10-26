// NEW: failure-handling slice 1 (2026-10-26). The provider's own words about a failure, once filtered. Only core
// constructs one: slice 2's ProviderWordsFilter is the one author, so until it lands no leaf carries words, and the
// renderers keep today's text from the failure's evidence.
package splice.core.failure

/** Words the provider said about a failure, cut to what a person may read. */
@JvmInline
public value class ProviderWords internal constructor(public val text: String)
