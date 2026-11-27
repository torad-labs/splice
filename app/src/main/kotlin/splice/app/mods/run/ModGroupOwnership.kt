package splice.app.mods.run

import java.io.IOException

/** The successor only observes. A saved identity is never authority to send a signal. */
internal class ModGroupOwnership(private val table: ModProcessTable) {
    val supported: Boolean get() = table.supported

    fun stillRunning(owner: ModRunOwner): Long? {
        val guardian = owner.guardian
        if (ModProcessIdentity.matches(guardian)) return guardian.pid
        val group = owner.group ?: return null
        val groups = table.groups() ?: throw IOException("the previous mod group cannot be verified")
        return member(groups, group)
    }

    private fun member(groups: Map<Long, Long>, group: ModIdentity): Long? {
        val held = groups.entries.firstOrNull { it.value == group.pid } ?: return null
        // Another leader with the same PID proves reuse. Without that witness,
        // keep ownership rather than starting alongside a possibly draining group.
        if (groups[group.pid] != group.pid) return held.key
        val leader = ModProcessIdentity.of(group.pid)
        return if (leader != null && leader != group) null else held.key
    }
}
