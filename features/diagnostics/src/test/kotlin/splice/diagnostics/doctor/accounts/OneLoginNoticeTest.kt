package splice.diagnostics.doctor.accounts

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.util.WallClock

// why: a moment, in seconds, that every reading of these tests is taken at.
private const val ONE_LOGIN_AT = 1_800_000_000L

// why: a day after that moment, so a window that resets then is still running.
private const val ONE_LOGIN_RESET = ONE_LOGIN_AT + 86_400L

/** What a person is told of a command that has one login: the notice's own words. */
class OneLoginNoticeTest {
    private val notices = LoginNotices(WallClock { ONE_LOGIN_AT * 1_000L })

    /** The roster of a command "solo" with one login a person named Work, its windows as [fields] say. */
    private fun roster(fields: String): String =
        """{"accounts":[{"heads":["solo"],"label":"work","account_labels":{"solo":null},"display_name":"Work",""" +
            """"credential_present":true,"single_login":true$fields}]}"""

    private fun week(percent: Int): String =
        ""","seven_day_used_percent":$percent,"seven_day_reset_epoch_seconds":$ONE_LOGIN_RESET"""

    @Test
    fun `a command with one login says when that login nears its weekly limit, and again at the second mark`() {
        val near = notices.of(roster(week(82))).single()
        assertTrue(
            near.text.startsWith("solo has one login, and Work has used 82% of its weekly limit, which resets "),
            near.text,
        )
        assertTrue(near.text.endsWith(". When it runs out, sessions on solo wait."), near.text)

        val last = notices.of(roster(week(96))).single()
        assertTrue(last.text.startsWith("solo has one login, and Work has used 96% of its weekly limit"), last.text)
        assertTrue(near.key != last.key, "the second mark is a notice of its own, so it is said too")
        assertEquals(emptyList<LoginNotice>(), notices.of(roster(week(79))), "below the first mark nothing is said")
    }

    @Test
    fun `a command whose one login has used up its week says its sessions wait, and says when it is back`() {
        val out = notices.of(roster(week(100))).single()
        assertTrue(
            out.text.startsWith(
                "solo has one login, and Work has used up its weekly limit, so sessions on solo are waiting. " +
                    "It comes back ",
            ),
            out.text,
        )
        assertEquals(emptyList<LoginNotice>(), notices.back(roster(week(100))), "it is not back while it is out")

        val back = notices.back(roster(week(3))).single()
        assertEquals("solo's login can serve again: Work. Sessions that waited for it go on.", back.text)
        assertEquals(out.key.removeSuffix(LOGIN_NOTICE_NONE), back.key.removeSuffix(LOGIN_NOTICE_BACK))
    }

    @Test
    fun `a week that has already reset is not read, and a login with no window says nothing`() {
        val past = ""","seven_day_used_percent":100,"seven_day_reset_epoch_seconds":${ONE_LOGIN_AT - 60L}"""
        assertEquals(emptyList<LoginNotice>(), notices.of(roster(past)))
        assertEquals(emptyList<LoginNotice>(), notices.of(roster("")), "an API key has no plan window")
    }
}
