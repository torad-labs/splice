// NEW: door-default (docs/specs/door-default.md), the ruling of Nov 17, 2026: Claude's models run on the door on
// the Claude accounts added to splice, and never on the person's own Claude login. The logins a turn through the door
// may run on, by their labels in a head's pool: the added accounts (ClaudeAccountFolders), and no login of the
// person's own, neither the sign-in a head forwards nor one read from the person's own Claude folder.
package splice.app.auth.claude

import splice.head.HeldLogins

/** The logins splice holds itself: every pool login but the caller's own sign-in a head forwards
 *  ([OWN_SIGN_IN_LABEL]) and a login read from one of the person's own Claude folders (a native place). */
internal class AddedClaudeLogins : HeldLogins {
    override fun held(label: String): Boolean = label != OWN_SIGN_IN_LABEL && !label.startsWith(NATIVE_PLACE_PREFIX)
}

// The label of a native place's login, before the place's name (ClaudeNativeAccountWiring).
private const val NATIVE_PLACE_PREFIX = "native:"
