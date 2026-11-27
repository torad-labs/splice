package splice.app.mods.run

/** The saved owner is never authority to signal whatever later takes its numeric group ID. */
internal sealed class ModGroupClaim {
    data object Gone : ModGroupClaim()
    data object Reused : ModGroupClaim()
    data object Draining : ModGroupClaim()
    data object Held : ModGroupClaim()
    data object Unreadable : ModGroupClaim()
}

internal class ModGroupOwnership(private val inspector: ModGroupInspector) {
    val supported: Boolean get() = inspector.supported

    fun claim(owner: ModRunOwner): ModGroupClaim {
        val identity = owner.leader
        val live = ModProcessIdentity.open(identity)
        if (!owner.grouped) return if (live == null) ModGroupClaim.Gone else ModGroupClaim.Held
        val groups = inspector.groups() ?: return ModGroupClaim.Unreadable
        return when {
            live != null && identity.pid in groups -> ModGroupClaim.Held
            identity.pid !in groups.values -> ModGroupClaim.Gone
            else -> occupied(identity, groups)
        }
    }

    private fun occupied(identity: ModIdentity, groups: Map<Long, Long>): ModGroupClaim {
        // If the reused leader already exited, its helpers cannot be told from a draining old group.
        if (groups[identity.pid] != identity.pid) return ModGroupClaim.Draining
        val replacement = ProcessHandle.of(identity.pid).orElse(null)?.let(ModProcessIdentity::of)
        return if (replacement != null && replacement != identity) {
            ModGroupClaim.Reused
        } else {
            ModGroupClaim.Draining
        }
    }
}
