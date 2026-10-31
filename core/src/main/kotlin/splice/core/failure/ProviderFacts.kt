// NEW: failure-handling slice 1 (2026-10-26). The facts a provider leaf of FailureCause carries.
package splice.core.failure

public enum class RefusedBy { MODEL, POLICY, FILTER }

public enum class LimitWindow { FIVE_HOUR, WEEKLY, MODEL_WEEKLY, OTHER }

public enum class ServerFault { INTERNAL, BAD_GATEWAY, GATEWAY_TIMEOUT, FOREIGN_KEY }

public enum class LoginOwner { CLIENT, SPLICE }

/** What an account's owner does before its provider serves the account again. */
public enum class OwnerStep {
    /** Accept the provider's updated terms. */
    ACCEPT_TERMS,
}

/**
 * The provider refuses an account itself, not the request: every request on any login of that account meets the same
 * answer until the account's owner takes [step], where the provider says to take it ([where], a place its owner
 * knows, such as claude.ai). Only a provider's own reader names one, from the provider's own words (a vendor fact).
 * [login] is the login that met it as a person knows it, once the turn names it; null where the head holds one login
 * and the line names the head's account.
 */
public data class AccountBlock(val step: OwnerStep, val where: String, val login: String? = null)
