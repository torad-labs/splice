// WALLS for reading a session's choices off its screen. Every arm here is a screen a person could be
// looking at, written as the client draws it, because the whole point of reading the screen instead of
// keeping a list is that the client's drawing is the only authority on what the choices are.
package splice.sessions.prompt

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.session.SessionKey

/** A permission prompt as Claude Code draws one, pointer and all. */
private val PERMISSION = """
    ⏺ Bash(rm -rf build/)
      ⎿  Running…

    Do you want to proceed?
    ❯ 1. Yes
      2. Yes, and don't ask again for Bash commands in this project
      3. No, and tell Claude what to do differently (esc)
""".trimIndent()

class ScreenChoicesTest {
    private val choices = ScreenChoices()

    @Test
    fun `the choices are the ones the client drew, in order, each with the key that answers it`() {
        val offer = choices.on(PERMISSION)

        assertTrue(offer.offering, "a screen showing a permission is offering something")
        assertEquals(
            listOf(SessionKey.CHOICE_1, SessionKey.CHOICE_2, SessionKey.CHOICE_3),
            offer.choices.map { it.key },
        )
        assertEquals(
            listOf(
                "Yes",
                "Yes, and don't ask again for Bash commands in this project",
                "No, and tell Claude what to do differently (esc)",
            ),
            offer.choices.map { it.label },
            "the words are the client's own, kept whole, with the number and the pointer taken off",
        )
    }

    @Test
    fun `the option the client is pointing at is the one marked here`() {
        val offer = choices.on(PERMISSION)

        assertEquals(
            listOf(true, false, false),
            offer.choices.map { it.here },
            "ACCEPT answers the option the person is on, so the card has to know which one that is",
        )
    }

    /** Claude Code 2.1.296's Bash permission, as captured off a real pane on Oct 10, 2026. */
    @Test
    fun `the whole prompt above the choices comes back, with what it runs framed, so nothing is approved blind`() {
        val screen = """
              Running the tax tests
              ⎿  $ npm test -- tax.spec.ts

            ────────────────────────────────────────
             Bash command
             Run the tax tests
            ╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌
             npm test -- tax.spec.ts
            ╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌
             This command requires approval

             Do you want to proceed?
             ❯ 1. Yes
               2. Yes, and don’t ask again for: npm test *
               3. No
        """.trimIndent()

        assertEquals(
            listOf(
                ScreenLine("Bash command", framed = false),
                ScreenLine("Run the tax tests", framed = false),
                ScreenLine("npm test -- tax.spec.ts", framed = true),
                ScreenLine("This command requires approval", framed = false),
                ScreenLine("Do you want to proceed?", framed = false),
            ),
            choices.on(screen).panel,
        )
        assertEquals(emptyList<ScreenLine>(), choices.on(PERMISSION).panel, "no opening rule, no panel")
    }

    @Test
    fun `the question above the choices comes back as the client wrote it`() {
        assertEquals("Do you want to proceed?", choices.on(PERMISSION).asked)
    }

    /** NOTHING FOUND IS AN ANSWER, and it does not mean nothing is pending. */
    @Test
    fun `a screen with no numbered choices offers nothing`() {
        val working = "⏺ Reading the file…\n  ⎿  412 lines"

        val offer = choices.on(working)

        assertFalse(offer.offering, "a working session is not offering a choice")
        assertEquals(emptyList<ScreenChoice>(), offer.choices)
        assertEquals("", offer.asked, "and there is no question to show above choices that are not there")
    }

    @Test
    fun `prose that opens with a number is prose`() {
        val offer = choices.on("⏺ Edited src/main.kt\n  ⎿  2 files changed, 14 insertions\n\n3 tests passed")

        assertFalse(
            offer.offering,
            "a number needs its own dot or bracket to be a choice, or every count on screen becomes a button",
        )
    }

    /** A pane that has scrolled still holds the tail of an older prompt above the live one. Pressing its
     *  "1." would answer something the person is not looking at. */
    @Test
    fun `when two prompts are on screen the choices are the last one's`() {
        val scrolled = """
            Do you want to proceed?
            ❯ 1. Yes
              2. No

            ⏺ Bash(git push)

            Do you want to proceed?
              1. Allow once
            ❯ 2. Always allow
              3. Deny
        """.trimIndent()

        val offer = choices.on(scrolled)

        assertEquals(
            listOf("Allow once", "Always allow", "Deny"),
            offer.choices.map { it.label },
            "the live prompt's words, not the one that scrolled past",
        )
        assertEquals(
            listOf(false, true, false),
            offer.choices.map { it.here },
            "and the pointer is the live prompt's too",
        )
    }

    /** The arm that caught the first rule. Keeping the lowest line per NUMBER left the scrolled prompt's
     *  third option behind as a button pressing a digit the live prompt does not offer. */
    @Test
    fun `a scrolled prompt with more options leaves none of them behind`() {
        val scrolled = """
            Do you want to proceed?
              1. Yes
              2. Yes, and don't ask again
            ❯ 3. No, and tell Claude what to do differently

            ⏺ Bash(git status)
              ⎿  clean

            Continue?
            ❯ 1. Keep going
              2. Stop
        """.trimIndent()

        val offer = choices.on(scrolled)

        assertEquals(listOf("Keep going", "Stop"), offer.choices.map { it.label })
        assertTrue(
            offer.choices.none { it.key == SessionKey.CHOICE_3 },
            "the old prompt's third option is not a button: the live prompt has no 3 to press",
        )
        assertEquals("Continue?", offer.asked, "and the question is the live prompt's")
    }

    /** SessionKey presses 1 to 9. A tenth option would be a button nothing can press, so it is not drawn
     *  as one; the person answers that screen in the terminal. */
    @Test
    fun `a tenth option is not offered as a choice`() {
        val many = (1..10).joinToString("\n") { "  $it. option $it" }

        val offer = choices.on(many)

        assertEquals(9, offer.choices.size, "nine is every key there is")
        assertEquals(SessionKey.CHOICE_9, offer.choices.last().key)
        assertTrue(offer.choices.none { it.label == "option 10" }, "the tenth is left for the terminal")
    }

    @Test
    fun `a choice written with a bracket is a choice`() {
        val offer = choices.on("Pick one\n 1) Keep going\n 2) Stop here")

        assertEquals(listOf("Keep going", "Stop here"), offer.choices.map { it.label })
    }
}
