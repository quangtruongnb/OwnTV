package tv.own.owntv.features.sports.tvhome

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.sports.FollowedTeam
import tv.own.owntv.core.sports.SportsConfig
import tv.own.owntv.core.sports.model.Competitor
import tv.own.owntv.core.sports.model.League
import tv.own.owntv.core.sports.model.Match
import tv.own.owntv.core.sports.model.MatchState
import tv.own.owntv.core.sports.model.Sport
import tv.own.owntv.core.sports.model.Team

class SportsChannelRulesTest {

    private val now = 1_800_000_000_000L
    private val hour = 60 * 60 * 1000L
    private val epl = League.keyOf(Sport.SOCCER, "eng.1")
    private val liga = League.keyOf(Sport.SOCCER, "esp.1")

    private fun match(
        id: String,
        start: Long,
        state: MatchState = MatchState.PRE,
        feed: String = epl,
        slug: String = "eng.1",
        teams: List<String> = listOf("h$id", "a$id"),
    ) = Match(
        id = id, sport = Sport.SOCCER, feed = feed, leagueSlug = slug, leagueName = "League",
        startMs = start, state = state, statusText = "", title = id,
        competitors = teams.mapIndexed { i, t -> Competitor(Team(t, t), home = i == 0, winner = null, score = null) },
    )

    private val config = SportsConfig(leagues = listOf(epl))

    @Test
    fun keepsLiveAndUpcomingWithinSevenDays() {
        val shown = SportsChannelRules.select(
            listOf(
                match("live", now - hour, MatchState.IN),
                match("soon", now + hour),
                match("day6", now + 6 * 24 * hour),
                match("day8", now + 8 * 24 * hour),
                match("done", now - 2 * hour, MatchState.POST),
                match("off", now + hour, MatchState.POSTPONED),
                match("stale", now - 5 * hour),
            ),
            config, now,
        ).map { it.id }
        assertEquals(listOf("live", "soon", "day6"), shown)
    }

    @Test
    fun liveFirstThenKickOff() {
        val shown = SportsChannelRules.select(
            listOf(match("b", now + 2 * hour), match("a", now + hour), match("live", now - hour, MatchState.IN)),
            config, now,
        ).map { it.id }
        assertEquals(listOf("live", "a", "b"), shown)
    }

    @Test
    fun onlyChosenLeaguesOrFollowedTeams() {
        val withTeam = config.copy(teams = listOf(FollowedTeam(Sport.SOCCER, "rm", "Real Madrid")))
        val shown = SportsChannelRules.select(
            listOf(
                match("epl", now + hour),
                match("liga", now + hour, feed = liga, slug = "esp.1"),
                match("rm", now + hour, feed = liga, slug = "esp.1", teams = listOf("rm", "fcb")),
            ),
            withTeam, now,
        ).map { it.id }
        assertEquals(setOf("epl", "rm"), shown.toSet())
        assertTrue("liga" !in shown)
    }

    @Test
    fun capsAndDeduplicates() {
        val many = (0 until 40).map { match("m$it", now + it * hour) }
        val shown = SportsChannelRules.select(many + many, config, now)
        assertEquals(SportsChannelRules.MAX_CARDS, shown.size)
        assertEquals(shown.size, shown.map { it.key }.toSet().size)
    }
}
