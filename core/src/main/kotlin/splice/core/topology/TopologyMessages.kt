// PORT-OF: splice/core/topology/Topology.kt (TopologyMessages, MIN_TCP_PORT, MAX_TCP_PORT,
// VALID_PORT_RANGE) @ a941c17 — invariants unchanged: every operator-facing message string is
// byte-for-byte what it was, and the port range still spans 1..65535, so the interpolated
// "(must be 1..65535)" is identical.
//
// Split out 2026-08-18 (HD-25). The header the object already carried made the case: these
// are pure text over values the caller ALREADY HOLDS, which is why they were a named object rather
// than members of Topology (HD-M8, migration pattern 5). None of the nine production call sites
// — app/head/HeadBoot.kt, app/cli/{DoctorCommand,InstallCommand,LoginCommand}.kt,
// control/api/{HeadResolver,LaunchRoutes}.kt — holds a Topology at the point it formats one, so the
// object was a passenger in the schema file rather than part of it.
//
// [validPortRange] travels with them and is `internal` rather than file-private because it now has
// two readers in two files: [TopologyMessages.invalidPortMessage] here and Topology.invalidPortHeads
// there. It is an IntRange, so `const val` is not available to it; MIN/MAX stay const. The name
// went camelCase with the visibility, which is a detekt requirement rather than a preference —
// TopLevelPropertyNaming's SCREAMING_SNAKE `constantPattern` covers `const val` only, and a
// non-private top-level val is graded by `propertyPattern` ([a-z][A-Za-z0-9]*), the same reason
// app/RefreshRetry.kt spells its `internal val refreshRetryableStatus` that way.
package splice.core.topology

private const val MIN_TCP_PORT = 1
private const val MAX_TCP_PORT = 65535
internal val validPortRange = MIN_TCP_PORT..MAX_TCP_PORT

/** The operator-facing topology diagnostics — pure text over values the caller already holds, which
 *  is why they are a named object rather than members of [Topology]: every call site has the port,
 *  the key and the head list in hand but not always the topology (HD-M8, migration pattern 5). */
public object TopologyMessages {

    /** Names both heads and the port so the operator sees the collision, not a phantom bind error. */
    public fun portCollisionMessage(port: Int, keys: List<String>): String =
        "port $port is claimed by ${keys.joinToString(" and ")} — give each head its own port"

    /** Names the head and its out-of-range port so the operator sees the config problem, not a
     *  phantom bind error (CTL-005). */
    public fun invalidPortMessage(key: String, port: Int): String =
        "head '$key' has an invalid port $port (must be $validPortRange) — fix [heads.$key] port in splice.toml"

    /** Distinct-from-"unknown-head" message for the ambiguous case: [keys] heads all map to [command].
     *  Naming both heads points the operator at the topology collision instead of a phantom head. */
    public fun ambiguousHeadMessage(command: String, keys: List<String>): String =
        "ambiguous head '$command' — heads ${keys.joinToString(" and ")} both use that command; fix the topology"
}
