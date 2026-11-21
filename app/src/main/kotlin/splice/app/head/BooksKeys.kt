// NEW: gateway-no-heads. The persisted key each provider's books are kept under, decided in one place so the stores
// that write under it and the daemon-start sweep that keeps it agree. A legacy head keeps the key it has always had; a
// provider the gateway serves keeps its books under its own name, which is the same key as a head of that name when
// that head serves that provider (the canonical `[heads.grok] provider = "grok"`), so the two are one identity and share
// their books. Only a head that carries a provider's name and serves ANOTHER provider owns that name's files, and the
// provider's books move off it: two identities never share a file by sharing a name.
package splice.app.head

import splice.core.topology.Topology

internal class BooksKeys(private val topology: Topology) {
    /** The key the gateway keeps provider [provider]'s books under. */
    fun forProvider(provider: String): String =
        if (headNamedLikeServingAnother(provider)) "$MOVED_PREFIX$provider" else provider

    /** Every key the gateway keeps books under, one per provider. A start that sweeps the kept files of heads no longer
     *  in splice.toml (HeadKeptFiles.ofRemovedHeads) must be told these too, or it deletes a headless provider's kept
     *  state as the state of a head that is gone. */
    fun all(): Set<String> = topology.providers.keys.map(::forProvider).toSet()

    private fun headNamedLikeServingAnother(provider: String): Boolean =
        topology.heads[provider]?.let { head -> head.provider != provider } == true
}

// A provider's books that moved off a head's name: dotted, as no head of splice.toml's own is.
private const val MOVED_PREFIX = "providers."
