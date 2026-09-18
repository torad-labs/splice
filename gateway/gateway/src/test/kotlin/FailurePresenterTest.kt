// NEW: V4-107 — the non-JSON pass-through in FailurePresenter.sentence is capped at ERR_SNIPPET,
// so a huge non-JSON vendor body cannot render whole into the operator's transcript. JSON-field
// behaviour (detail/error/message lifting) is deliberately untouched.
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import splice.gateway.pipeline.FailurePresenter

class FailurePresenterTest {

    private val presenter = FailurePresenter()

    @Test
    fun `a huge non-JSON vendor body is capped at the error snippet`() {
        // A space makes the body unparseable as JSON (a bare x-string would parse as a JsonLiteral
        // under the lenient parser), so this rides the non-JSON pass-through — the real prose/HTML
        // shape the cap exists for.
        val huge = "x ".repeat(10_000)

        val sentence = presenter.sentence(huge)

        assertEquals(200, sentence.length, sentence)
        assertEquals(huge.take(200), sentence)
    }

    @Test
    fun `a short non-JSON body rides through untouched`() {
        assertEquals("plain prose", presenter.sentence("plain prose"))
    }

    @Test
    fun `a blank non-JSON body is described, not echoed`() {
        assertEquals(
            "the upstream returned an error that could not be read",
            presenter.sentence("   "),
        )
    }
}
