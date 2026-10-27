package tv.own.owntv.features.sports.tvhome

import android.net.Uri
import tv.own.owntv.core.sports.model.League
import tv.own.owntv.core.sports.model.Match

/**
 * A card on the Android TV home screen's "Sports" channel: `owntv://open/sports?key=…&league=…&id=…&start=…`.
 *
 * Uses the `open` host the launcher activities already accept. Core's `LauncherDeepLink.parse` only
 * knows `open/live`, so it returns null for this link and the two never collide.
 */
data class SportsMatchLink(
    /** [Match.key]. */
    val key: String,
    /** The league the match is fetched under ([League.key] form), to fetch it again if it is not stored. */
    val leagueKey: String,
    val id: String,
    val startMs: Long,
) {
    /** False for the channel's own link (`owntv://open/sports`): that one opens the Sports hub. */
    val opensMatch: Boolean get() = key.isNotBlank()

    fun toUri(): Uri = Uri.Builder()
        .scheme(SCHEME)
        .authority(HOST)
        .appendPath(PATH)
        .appendQueryParameter(Q_KEY, key)
        .appendQueryParameter(Q_LEAGUE, leagueKey)
        .appendQueryParameter(Q_ID, id)
        .appendQueryParameter(Q_START, startMs.toString())
        .build()

    companion object {
        private const val SCHEME = "owntv"
        private const val HOST = "open"
        private const val PATH = "sports"
        private const val Q_KEY = "key"
        private const val Q_LEAGUE = "league"
        private const val Q_ID = "id"
        private const val Q_START = "start"

        fun of(match: Match): SportsMatchLink =
            SportsMatchLink(match.key, match.feed, match.id, match.startMs)

        fun parse(uri: Uri?): SportsMatchLink? {
            if (uri == null || uri.scheme != SCHEME || uri.host != HOST) return null
            if (uri.pathSegments.firstOrNull() != PATH) return null
            return SportsMatchLink(
                key = uri.getQueryParameter(Q_KEY).orEmpty(),
                leagueKey = uri.getQueryParameter(Q_LEAGUE).orEmpty(),
                id = uri.getQueryParameter(Q_ID).orEmpty(),
                startMs = uri.getQueryParameter(Q_START)?.toLongOrNull() ?: 0L,
            )
        }
    }
}
