package splice.accounts.signin

/** What a sign-in asks the person to do. [userCode] and [verificationUri] are the device flow's announcement;
 *  [browserUrl] is the OAuth flow's, for the console to open. A flow fills only the part it has. */
public data class LoginPrompt(
    val userCode: String? = null,
    val verificationUri: String? = null,
    val browserUrl: String? = null,
)
