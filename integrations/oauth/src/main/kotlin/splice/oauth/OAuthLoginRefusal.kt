// NEW: what refused a sign-in after its exchange. It was one flag, on disk or not, so the surface that words a
// refusal had only "Config error" for a sign-in that came back as an account the command already holds.
package splice.oauth

/** What refused a sign-in once its exchange had answered, for the surface that words it. */
public enum class OAuthLoginRefusal {
    /** The account or its label, by a rule of splice's: it fails the same way until what it names changes. */
    ACCOUNT,

    /** The credential did not reach disk, which fails again until the disk is fixed. */
    DISK,

    /** The sign-in came back as an account the command already holds, so nothing was added. */
    ALREADY_THERE,
}
