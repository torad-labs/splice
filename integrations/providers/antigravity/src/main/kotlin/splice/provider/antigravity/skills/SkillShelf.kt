// NEW: desktop-antigravity (ACP skills). The session's local skills as they stand now: loaded when the session starts,
// before agy opens, and again on Desktop's `reload_skills`, so autocomplete, `/name` expansion and the reload answer
// all read one list.
package splice.provider.antigravity.skills

/** Holds [catalog]'s current skills and commands for one ACP session. */
internal class SkillShelf(private val catalog: ClaudeSkillCatalog) {
    /** The skills and commands found when the session started or at the last reload. */
    var current: LocalSkillCatalog = catalog.load()
        private set

    /** Scans the skill folders again and keeps what it finds. */
    fun reload(): LocalSkillCatalog = catalog.load().also { current = it }

    /** The index of the current skills that agy cannot find by itself, for its system prompt; null when none
     *  qualify. Read when agy opens, so a reload before then is what it lists. */
    fun index(): String? = SkillIndex().of(current, catalog.nativeRoots(), catalog.nativeSkills())
}
