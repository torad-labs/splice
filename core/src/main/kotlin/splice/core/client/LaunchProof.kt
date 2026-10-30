// NEW: PR #247 review, Oct 29, 2026 (security). A head that forwards the caller's own Claude login and also holds logins
// of its own puts one of those in place of whatever the caller forwards, so its door has to know the caller is
// splice's. A session splice launched names the head's turn key in this header (LaunchService plants it through
// ANTHROPIC_CUSTOM_HEADERS), and the LAN share's relay sends it with another machine's turn. The turn key sits in
// 0600 files of the operator's account, so no other account on the machine can name it. The head never forwards it.
package splice.core.client

// The name carries no "key", "token" or "auth": Claude Code copies a custom header whose name reads as a credential onto
// requests it sends Anthropic itself (its auto-mode checks, read in the 2.1.312 binary), and the turn key must not go.
/** The header a session splice launched proves itself in, on a head that forwards the caller's own login. */
public const val LAUNCH_PROOF_HEADER: String = "x-splice-launch-proof"
