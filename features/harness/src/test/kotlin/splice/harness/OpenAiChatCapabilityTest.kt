// NEW: a capability's text on a head that speaks OpenAI's Chat Completions (docs/specs/harness.md, decision 5),
// through a real head on OpenAiChatProvider and the bodies the provider got.
package splice.harness

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.dialect.chat.ChatQuirks
import splice.provider.openai.OpenAiChatProvider
import java.nio.file.Path

class OpenAiChatCapabilityTest(@param:TempDir private val tmp: Path) {
    private val rig = CapabilityRig(tmp)
    private val checks = CapabilityDialectChecks(rig) { tuning ->
        OpenAiChatProvider(tuning, ChatQuirks(providerTag = HEAD))
    }

    @AfterEach
    fun close() = rig.close()

    @Test
    fun `the text follows what the person typed in the opening user message, and stays there`() =
        checks.textFollowsWhatWasTypedAndStays()

    @Test
    fun `a head the capability is not on for sends what it sends today`() = checks.aHeadItIsNotOnForSendsTodaysBytes()

    @Test
    fun `a dialect that cannot place the text leaves the body alone, and the turn's row says so`() =
        checks.aDialectThatCannotPlaceLeavesTheBodyAlone()
}
