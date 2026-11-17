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
    fun `a command with one login says once that the login has passed 80 percent of its weekly limit`() {
        val near = notices.of(roster(week(82))).single()
        assertTrue(
            near.text.startsWith("solo has one login, and Work has used 82% of its weekly limit, which resets "),
            near.text,
        )
        assertTrue(near.text.endsWith(". When it runs out, sessions on solo wait."), near.text)

        // Nov 17, 2026: there is no second mark. Past 95% it is the notice already said, so nothing more is sent.
        assertEquals(near.key, notices.of(roster(week(96))).single().key)
        assertEquals(emptyList<LoginNotice>(), notices.of(roster(week(79))), "below the mark nothing is said")
    }

    @Test
    fun `a command whose one login has used up its week says its sessions wait and when it comes back`() {
        val out = notices.of(roster(week(100))).single()
        assertTrue(
            out.text.startsWith(
                "solo has one login, and Work has used up its weekly limit, so sessions on solo are waiting. " +
                    "It comes back ",
            ),
            out.text,
        )
        // Nov 17, 2026: that the login serves again is no longer a notice. Once it does, nothing is said.
        assertEquals(emptyList<LoginNotice>(), notices.of(roster(week(3))))
    }

    @Test
    fun `below the mark a login that fills fast says nothing`() {
        var at = ONE_LOGIN_AT
        val read = LoginNotices(WallClock { at * 1_000L })
        // Seven points in ten minutes was a notice of its own until Nov 17, 2026. The mark is the one warning now.
        assertEquals(emptyList<LoginNotice>(), read.of(roster(week(40))))
        at += 600L
        assertEquals(emptyList<LoginNotice>(), read.of(roster(week(47))))
    }

    @Test
    fun `a notice an earlier build said at its second mark is the one notice of this build`() {
        val near = notices.of(roster(week(96))).single()
        assertEquals(near.key, notices.carried("solo|login|weekly limit|95"))
        assertEquals(near.key, notices.carried(near.key), "a key of this build is itself")
    }

    @Test
    fun `a week that has already reset is not read, and a login with no window says nothing`() {
        val past = ""","seven_day_used_percent":100,"seven_day_reset_epoch_seconds":${ONE_LOGIN_AT - 60L}"""
        assertEquals(emptyList<LoginNotice>(), notices.of(roster(past)))
        assertEquals(emptyList<LoginNotice>(), notices.of(roster("")), "an API key has no plan window")
    }
}
