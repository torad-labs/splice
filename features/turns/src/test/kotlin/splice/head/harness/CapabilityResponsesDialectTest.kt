// NEW: a capability's text on a head that speaks the Responses API (docs/specs/harness.md, decision 5), through a
// real head on the dialect's own provider and the bodies the provider got.
package splice.head.harness

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.turn.ReasoningDisplay
import splice.dialect.responses.ReasoningSettings
import splice.head.TestResponsesProvider
import java.nio.file.Path

class CapabilityResponsesDialectTest(@param:TempDir private val tmp: Path) {
    private val rig = CapabilityRig(tmp)
    private val checks = CapabilityDialectChecks(rig) { tuning ->
        TestResponsesProvider(tuning, ReasoningSettings(ReasoningDisplay.TEXT, false, "high", "detailed"))
    }

    @AfterEach
    fun close() = rig.close()

    @Test
    fun `the text follows what the person typed in the opening user items, and stays there`() =
        checks.textFollowsWhatWasTypedAndStays()

    @Test
    fun `a head the capability is not on for sends what it sends today`() = checks.aHeadItIsNotOnForSendsTodaysBytes()

    @Test
    fun `a dialect that cannot place the text leaves the body alone, and the turn's row says so`() =
        checks.aDialectThatCannotPlaceLeavesTheBodyAlone()
}
