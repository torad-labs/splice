// NEW: failure-handling slice 1 (2026-10-26). How a path leaf of FailureCause broke.
package splice.core.failure

public enum class Unreach { REFUSED, DNS, TLS, RESET_BEFORE_REPLY, WRITE_STALLED }

/** [ROTATED]: the provider retired the socket at its planned lifetime (the Responses websocket's 60-minute
 *  websocket_connection_limit_reached), a rotation the turn continues across on a fresh socket, not a failure of the
 *  request or of the answer (2026-11-10). */
public enum class Tear { RESET, NO_TERMINAL, NO_EVENTS, OVERSIZED_EVENT, SOCKET_CLOSED, ROTATED }

public enum class Stall { FIRST_BYTE, IDLE }
