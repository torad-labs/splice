package splice.sessions.teams

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.client.DOOR_HOOK_KEY
import java.nio.file.Files
import java.nio.file.Path

class DoorTeamRuleBindingTest {
    @Test
    fun `door SessionStart joins the first free rule slot in that team repo while named heads stay exact`(
        @TempDir tmp: Path,
    ) {
        val repo = Files.createDirectories(tmp.resolve("repo"))
        val store = TeamStore(tmp.resolve("teams.json"))
        val (team, _) = store.create(
            team = Team(
                name = "Backend",
                repo = repo.toString(),
                slots = listOf(
                    TeamSlot(id = "lead", role = "Lead", head = "claude", bindByRule = true),
                    TeamSlot(id = "review", role = "Review", head = "codex", bindByRule = true),
                ),
            ),
            key = "door-rule-team-1",
        )
        val binding = TeamRuleBinding(store, sessions = null)

        // Named head mismatch still refuses.
        assertNull(binding.started("grok", "sess-grok", repo.toString()))

        // Door session binds the first free rule slot in the repo.
        val joined = binding.started(DOOR_HOOK_KEY, "sess-door-1", repo.toString())
        assertNotNull(joined)
        assertEquals("sess-door-1", store.team(team.id)?.slots?.first { it.id == "lead" }?.session)

        // Second door session binds the next free rule slot.
        val joined2 = binding.started(DOOR_HOOK_KEY, "sess-door-2", repo.toString())
        assertNotNull(joined2)
        assertEquals("sess-door-2", store.team(team.id)?.slots?.first { it.id == "review" }?.session)
    }
}
