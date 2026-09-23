// NEW: a finished sign-in's persistence step, for consumers' tests (LAYOUT-01). The CLI's receipt
// tests in :app assert the label a login actually wrote; LoginIo is internal to this module, so they
// reach the real write path through this fixture rather than a widened production API.
package splice.oauth

import splice.core.terminal.TerminalOutput
import java.nio.file.Path

class SignInPersistence {
    /** Persists [authJson] for [account] exactly as a completed sign-in does, recording its label. */
    fun persist(path: Path, authJson: String, account: OAuthLoginAccount?): Boolean =
        LoginIo(TerminalOutput {}).persistIfSignedIn(path, authJson, account)
}
