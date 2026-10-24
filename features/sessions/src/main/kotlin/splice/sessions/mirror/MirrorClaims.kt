// NEW: LAN share, round 17 of PR #419's review (docs/specs/lan-share.md). What each way another machine's sessions
// reach this one by names now: this machine's look at it, and its offers and notes here. Two machines that each joined
// the other reach each other both ways, and a machine this one keys twice (`b` and `b-2`) names its sessions under both
// keys, so a session is wanted once, by id, whichever ways name it, and each way's naming keeps its own time.
package splice.sessions.mirror

/** What each way names now, by the machine's key here and the way. SessionMirrors holds its lock around every call. */
internal class MirrorClaims {
    private val claims = mutableMapOf<Pair<String, MirrorWay>, MirrorClaim>()

    /** How many namings came before the latest, which orders two ways' namings in time. */
    private var namings = 0L

    /** The machines some way names now. */
    fun machines(): Set<String> = claims.keys.mapTo(mutableSetOf()) { it.first }

    /** The ids of the sessions [machine]'s ways name. */
    fun named(machine: String): Set<String> =
        claims.filterKeys { it.first == machine }.values.flatMapTo(mutableSetOf()) { it.sessions.keys }

    /** [machine] names [sessions] by [link]'s way now, through its carrier, for at least its time from [now]. */
    fun name(machine: String, sessions: List<MirroredSession>, link: MirrorLink, now: Long) {
        val key = machine to link.way
        val ends = maxOf(claims[key]?.ends ?: 0L, now + link.lastsMs)
        claims[key] = MirrorClaim(machine, sessions.associateBy { it.id }, link.carrier, ends, ++namings)
    }

    /** [machine] names [from] by [link]'s way too, as a note from it does: a way that names nothing yet takes [link]'s
     *  carrier, and one that does keeps its own; either lasts at least [link]'s time from [now], one whose time ran
     *  out before the sweep withdrew it included (round 18 of PR #419's review). */
    fun add(machine: String, from: MirroredSession, link: MirrorLink, now: Long) {
        val key = machine to link.way
        val ends = now + link.lastsMs
        val claim = claims[key] ?: MirrorClaim(machine, emptyMap(), link.carrier, ends, ++namings)
        claims[key] = claim.copy(sessions = claim.sessions + (from.id to from), ends = maxOf(claim.ends, ends))
    }

    /** [machine]'s [way] names nothing any more. */
    fun drop(machine: String, way: MirrorWay) {
        claims.remove(machine to way)
    }

    /** Drops each way whose time is before [now], and answers the machines they were of. */
    fun expire(now: Long): Set<String> {
        val past = claims.filterValues { it.ends < now }.keys
        claims.keys.removeAll(past)
        return past.mapTo(mutableSetOf()) { it.first }
    }

    fun clear() {
        claims.clear()
    }

    /** Each session the ways name, once, by id: under the machine [mirrors] lists it under while that machine still
     *  names it, else the machine that named it last; as that machine named it last, named apart from that machine's
     *  others while keeping the names [mirrors] gave them; and handing a message to the way that named it. */
    fun wanted(mirrors: Map<String, Mirror>): Map<String, MirrorWant> {
        val named = claims.values.flatMap { claim -> claim.sessions.values.map { claim to it } }
        val chosen = named.groupBy { (_, session) -> session.id }.map { (id, all) ->
            val listed = mirrors[id]?.machine?.takeIf { under -> all.any { (claim, _) -> claim.machine == under } }
            val under = listed ?: all.maxBy { (claim, _) -> claim.stamp }.first.machine
            all.filter { (claim, _) -> claim.machine == under }.maxBy { (claim, _) -> claim.stamp }
        }
        return chosen.groupBy { (claim, _) -> claim.machine }.flatMap { (machine, picked) ->
            val kept = mirrors.values.filter { it.machine == machine }.associate { it.session.id to it.session.name }
            val carriers = picked.associate { (claim, session) -> session.id to claim.carrier }
            MirrorNames.of(picked.map { it.second }, kept).map { MirrorWant(machine, it, carriers.getValue(it.id)) }
        }.associateBy { it.session.id }
    }
}

/** What one way a machine's sessions reach this one by named last: its sessions by id, the carrier a message to one
 *  goes by, when it ends by the mirrors' clock, and how many namings came before it. */
private data class MirrorClaim(
    val machine: String,
    val sessions: Map<String, MirroredSession>,
    val carrier: MirrorCarrier,
    val ends: Long,
    val stamp: Long,
)

/** One session as it is mirrored: the machine it is listed under, the session as that machine named it, named apart
 *  from that machine's others, and the carrier a message to it goes by. */
internal data class MirrorWant(val machine: String, val session: MirroredSession, val carrier: MirrorCarrier) {
    /** Whether [mirror] is the one this asks for: listed under this machine, by this name, its process running. */
    fun keeps(mirror: Mirror): Boolean =
        mirror.machine == machine && mirror.session.name == session.name && mirror.alive()
}
