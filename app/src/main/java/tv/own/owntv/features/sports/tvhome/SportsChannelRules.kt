package tv.own.owntv.features.sports.tvhome

import tv.own.owntv.core.sports.SportsConfig
import tv.own.owntv.core.sports.model.Match
import tv.own.owntv.core.sports.model.MatchState

/**
 * Which matches the home screen's "Sports" channel shows. Pure, so it is unit-tested on its own.
 *
 * The profile's chosen leagues and its teams (as the in-app Home row, never "All sports"' every league),
 * live now or kicking off within [WINDOW_MS]. Live first, then by kick-off; at most [MAX_CARDS].
 */
object SportsChannelRules {
    const val WINDOW_DAYS = 7
    const val WINDOW_MS = WINDOW_DAYS * 24L * 60 * 60 * 1000
    const val MAX_CARDS = 30

    /** A "not started" match this far past kick-off is a stale listing, not a live one. */
    const val STALE_PRE_MS = 3L * 60 * 60 * 1000

    fun select(matches: Collection<Match>, config: SportsConfig, now: Long, max: Int = MAX_CARDS): List<Match> {
        val leagues = config.leagues.toSet()
        return matches
            .asSequence()
            .distinctBy { it.key }
            .filter { follows(config, leagues, it) }
            .filter { shown(it, now) }
            .sortedWith(compareBy<Match>({ !it.isLive }, { it.startMs }))
            .take(max)
            .toList()
    }

    fun shown(match: Match, now: Long): Boolean = when (match.state) {
        MatchState.IN -> true
        MatchState.PRE -> match.startMs in (now - STALE_PRE_MS)..(now + WINDOW_MS)
        else -> false
    }

    private fun follows(config: SportsConfig, leagues: Set<String>, match: Match): Boolean {
        if (match.feed in leagues) return true
        if (config.teams.isEmpty()) return false
        return match.competitors.any { c -> config.teams.any { t -> t.sport == match.sport && t.id == c.team.id } }
    }
}
