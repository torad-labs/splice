// PORT-OF: splice/app/Daemon.kt (Wired) @ ed5c868 — invariants unchanged: provider + its auth,
// chosen by (dialect, auth.kind) — the multi-provider dispatch's return contract.
package splice.app.provider

import splice.core.auth.RefreshableAuthProvider
import splice.spi.Provider

/** Provider + its auth, chosen by (dialect, auth.kind) — the multi-provider dispatch. */
internal data class Wired(val provider: Provider, val auth: RefreshableAuthProvider)
