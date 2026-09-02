// PORT-OF: splice/app/Daemon.kt (Wired) @ ed5c868 — invariants unchanged: provider + its auth,
// chosen after registered auth-kind/dialect compatibility validation — the dispatch return contract.
package splice.app.provider

import splice.core.auth.RefreshableAuthProvider
import splice.spi.Provider

/** Provider + auth chosen by the compatibility-checked multi-provider dispatch. */
internal data class Wired(val provider: Provider, val auth: RefreshableAuthProvider)
