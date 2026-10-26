// NEW: failure-handling slice 1 (2026-10-26). The facts a provider leaf of FailureCause carries.
package splice.core.failure

public enum class RefusedBy { MODEL, POLICY, FILTER }

public enum class LimitWindow { FIVE_HOUR, WEEKLY, MODEL_WEEKLY, OTHER }

public enum class ServerFault { INTERNAL, BAD_GATEWAY, GATEWAY_TIMEOUT, FOREIGN_KEY }

public enum class LoginOwner { CLIENT, SPLICE }
