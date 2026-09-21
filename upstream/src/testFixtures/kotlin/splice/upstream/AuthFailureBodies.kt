// NEW: the one auth-body corpus FailureRules and MuseOAuth both pin against. Test fixture,
// not production ABI — nothing in main references it.
package splice.upstream

/** Positives and negatives for [FailureRules.isAuthFailureBody]; one list, two modules. */
public class AuthFailureBodies {
    public val positives: List<String> = listOf(
        "unauthenticated",
        "unauthenticated:bad-credentials",
        "bad-credentials",
        "token invalid",
        "token is invalid",
        "token expired",
        "token is expired",
        "TOKEN EXPIRED",
        "access token could not be validated",
        "oauth token could not be validated",
        "oauth2 token could not be validated",
    )

    public val negatives: List<String> = listOf(
        "",
        "plan limit exceeded",
        "permission denied",
        "quota exceeded",
        "tokens invalid",
        "oauth3 token could not be validated",
    )
}
