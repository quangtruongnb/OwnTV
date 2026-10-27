package tv.own.owntv.features.sports.tvhome

import android.util.Log
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import tv.own.owntv.core.sports.SportsCatalog
import tv.own.owntv.core.sports.SportsConfig
import tv.own.owntv.core.sports.SportsGate
import tv.own.owntv.core.sports.SportsSource
import tv.own.owntv.core.sports.model.League
import tv.own.owntv.core.sports.model.Match
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap

/**
 * Fetches the "Sports" channel's matches: each followed league's scoreboard for every day of the window.
 *
 * Core's board only holds today and tomorrow, so the days are asked of [SportsSource] directly, through
 * core's [SportsGate] (its parallel limit and its pause after ESPN refuses). Days close to now are asked
 * every run (scores, live state); the far days at most every [FAR_DAY_TTL_MS]. A day that fails keeps
 * what it last returned.
 */
class SportsChannelPlanner(
    private val source: SportsSource,
    private val gate: SportsGate,
) {
    private class Day(val matches: List<Match>, val fetchedAt: Long)

    private val days = ConcurrentHashMap<Pair<String, LocalDate>, Day>()

    suspend fun plan(config: SportsConfig, now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): List<Match> {
        // The leagues the profile chose — never followedLeagues(), which under "All sports" is the whole catalogue.
        val leagues = config.leagues.distinct().mapNotNull { SportsCatalog.league(it) }
        if (leagues.isEmpty()) return emptyList()
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        // One day before: ESPN files a match under its own (US) date, which can be "yesterday" here.
        val dates = (-1..SportsChannelRules.WINDOW_DAYS).map { today.plusDays(it.toLong()) }
        val all = coroutineScope {
            leagues.flatMap { league -> dates.map { date -> async { day(league, date, today, now) } } }.awaitAll()
        }.flatten()
        // Days no longer in the window are dropped from memory.
        days.keys.removeAll { it.second < today.minusDays(1) }
        return SportsChannelRules.select(all, config, now)
    }

    /** [key]'s match if a run has seen it (a home screen card's own match, opened before the board has it). */
    fun cached(key: String): Match? = days.values.firstNotNullOfOrNull { d -> d.matches.firstOrNull { it.key == key } }

    /** [link]'s match fetched afresh from its league's scoreboard, around its kick-off date. */
    suspend fun fetch(link: SportsMatchLink, zone: ZoneId = ZoneId.systemDefault()): Match? {
        val league = SportsCatalog.league(link.leagueKey) ?: return null
        val date = Instant.ofEpochMilli(link.startMs.takeIf { it > 0 } ?: System.currentTimeMillis()).atZone(zone).toLocalDate()
        for (d in listOf(date, date.minusDays(1), date.plusDays(1))) {
            val found = runCatching { gate.call { source.scoreboard(league, d) } }.getOrNull()
                ?.firstOrNull { it.key == link.key || it.id == link.id }
            if (found != null) return found
        }
        return null
    }

    private suspend fun day(league: League, date: LocalDate, today: LocalDate, now: Long): List<Match> {
        val k = league.key to date
        val held = days[k]
        val near = date <= today.plusDays(1)
        if (held != null && !near && now - held.fetchedAt < FAR_DAY_TTL_MS) return held.matches
        return try {
            val fresh = gate.call { source.scoreboard(league, date) }
            days[k] = Day(fresh, now)
            fresh
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "scoreboard ${league.key} $date failed: ${e.javaClass.simpleName}")
            held?.matches.orEmpty()
        }
    }

    companion object {
        private const val TAG = "SportsChannel"
        const val FAR_DAY_TTL_MS = 3L * 60 * 60 * 1000
    }
}
