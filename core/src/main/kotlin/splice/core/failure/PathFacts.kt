// NEW: failure-handling slice 1 (2026-10-26). How a path leaf of FailureCause broke.
package splice.core.failure

public enum class Unreach { REFUSED, DNS, TLS, RESET_BEFORE_REPLY, WRITE_STALLED }

public enum class Tear { RESET, NO_TERMINAL, NO_EVENTS, OVERSIZED_EVENT, SOCKET_CLOSED }

public enum class Stall { FIRST_BYTE, IDLE }
