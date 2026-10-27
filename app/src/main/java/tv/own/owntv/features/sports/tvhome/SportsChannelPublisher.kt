package tv.own.owntv.features.sports.tvhome

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.text.format.DateUtils
import android.util.Log
import androidx.core.content.edit
import androidx.core.graphics.drawable.toBitmap
import androidx.tvprovider.media.tv.PreviewChannel
import androidx.tvprovider.media.tv.PreviewChannelHelper
import androidx.tvprovider.media.tv.PreviewProgram
import androidx.tvprovider.media.tv.TvContractCompat
import tv.own.owntv.R
import tv.own.owntv.core.sports.model.Match

/**
 * The Android TV home screen's "Sports" preview channel: created once, its cards kept in step with the
 * planned matches (inserted, updated when they change, deleted when gone), keyed by [Match.key].
 *
 * Every provider call is guarded: a launcher without TvProvider (phone, Fire TV) or one that refuses a
 * write leaves the app as it was.
 */
class SportsChannelPublisher(
    private val context: Context,
    private val posters: SportsPosterRenderer,
) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val helper by lazy { PreviewChannelHelper(context) }

    val isSupported: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)

    /** The channel id, if it exists and the system has not shown it yet (the app asks once, in front). */
    fun channelAwaitingBrowsable(): Long? {
        if (!isSupported || !prefs.getBoolean(KEY_ASK_BROWSABLE, false)) return null
        val id = prefs.getLong(KEY_CHANNEL_ID, -1L).takeIf { it > 0 } ?: return null
        val channel = runCatching { helper.getPreviewChannel(id) }.getOrNull() ?: return null
        return if (channel.isBrowsable) { markBrowsableAsked(); null } else id
    }

    fun markBrowsableAsked() = prefs.edit { putBoolean(KEY_ASK_BROWSABLE, false) }

    fun publish(matches: List<Match>, spoilerFree: Boolean) {
        if (!isSupported) return
        try {
            val channelId = ensureChannel() ?: return
            val existing = programs(channelId)
            val wanted = HashSet<String>()
            val posterUris = HashSet<Uri>()
            matches.forEachIndexed { i, m ->
                wanted += m.key
                val program = program(channelId, m, weight = matches.size - i, spoilerFree = spoilerFree)
                program.posterArtUri?.let { posterUris += it }
                val held = existing[m.key]
                when {
                    held == null -> helper.publishPreviewProgram(program)
                    held.internalProviderFlag1 != program.internalProviderFlag1 -> helper.updatePreviewProgram(held.id, program)
                }
            }
            existing.filterKeys { it !in wanted }.values.forEach { helper.deletePreviewProgram(it.id) }
            posters.prune(posterUris)
            Log.d(TAG, "published ${matches.size} cards (removed ${existing.keys.count { it !in wanted }})")
        } catch (e: Exception) {
            Log.w(TAG, "publish failed: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /** The channel and its cards gone (Android TV Home turned off). */
    fun clear() {
        val id = prefs.getLong(KEY_CHANNEL_ID, -1L)
        if (id <= 0) return
        if (isSupported) runCatching { helper.deletePreviewChannel(id) }
        prefs.edit { remove(KEY_CHANNEL_ID); remove(KEY_ASK_BROWSABLE) }
    }

    private fun ensureChannel(): Long? {
        val held = prefs.getLong(KEY_CHANNEL_ID, -1L)
        if (held > 0 && runCatching { helper.getPreviewChannel(held) }.getOrNull() != null) return held
        val channel = PreviewChannel.Builder()
            .setDisplayName(context.getString(R.string.sports_title))
            .setAppLinkIntentUri(Uri.parse(APP_LINK))
            .setInternalProviderId(CHANNEL_PROVIDER_ID)
            .setLogo(context.packageManager.getApplicationIcon(context.packageName).toBitmap(LOGO_PX, LOGO_PX))
            .build()
        val id = helper.publishChannel(channel)
        if (id <= 0) return null
        prefs.edit { putLong(KEY_CHANNEL_ID, id); putBoolean(KEY_ASK_BROWSABLE, true) }
        // Granted at once for an app's first channel; otherwise the app asks the user when next in front.
        runCatching { TvContractCompat.requestChannelBrowsable(context, id) }
        Log.d(TAG, "channel created id=$id")
        return id
    }

    @SuppressLint("RestrictedApi")
    private fun programs(channelId: Long): Map<String, PreviewProgram> {
        val out = HashMap<String, PreviewProgram>()
        context.contentResolver.query(
            TvContractCompat.buildPreviewProgramsUriForChannel(channelId), PreviewProgram.PROJECTION, null, null, null,
        )?.use { c ->
            while (c.moveToNext()) {
                val p = PreviewProgram.fromCursor(c)
                val key = p.internalProviderId
                // A duplicate (an interrupted earlier run) is removed; the first copy is kept.
                if (key == null || out.containsKey(key)) helper.deletePreviewProgram(p.id) else out[key] = p
            }
        }
        return out
    }

    private fun program(channelId: Long, m: Match, weight: Int, spoilerFree: Boolean): PreviewProgram {
        val names = names(m)
        val score = if (m.isLive && !spoilerFree) score(m) else null
        val title = names?.let { (h, a) -> if (score != null) "$h $score $a" else "$h $VS $a" } ?: m.title
        val time = if (m.isLive) m.statusText.takeIf { it.isNotBlank() } else kickOff(m.startMs)
        val description = listOfNotNull(m.leagueName.takeIf { it.isNotBlank() }, time).joinToString(SEPARATOR)
        val posterTitle = names?.let { (h, a) -> "$h $VS $a" } ?: m.title
        // Flag 1 fingerprints what a card shows, so an unchanged card is not rewritten every run.
        val signature = listOf(POSTER_VERSION, title, description, m.isLive, m.startMs, weight).hashCode().toLong()
        val poster = posters.render(m, posterTitle, centre = score ?: VS, footer = time.orEmpty(), signature = signature)
            ?: (m.home ?: m.competitors.firstOrNull())?.team?.logoUrl?.takeIf { it.isNotBlank() }?.let(Uri::parse)
        return PreviewProgram.Builder()
            .setChannelId(channelId)
            .setType(TvContractCompat.PreviewPrograms.TYPE_EVENT)
            .setTitle(title)
            .setDescription(description)
            .apply { poster?.let { setPosterArtUri(it) } }
            .setPosterArtAspectRatio(TvContractCompat.PreviewProgramColumns.ASPECT_RATIO_16_9)
            .setLive(m.isLive)
            .setStartTimeUtcMillis(m.startMs)
            .setIntentUri(SportsMatchLink.of(m).toUri())
            .setInternalProviderId(m.key)
            .setInternalProviderFlag1(signature)
            .setWeight(weight)
            .build()
    }

    /** Home and away names, for a two-sided match; null for a field event (racing, golf). */
    private fun names(m: Match): Pair<String, String>? {
        val home = m.home
        val away = m.away
        if (home == null || away == null || m.competitors.size != 2) return null
        return home.team.shortName.ifBlank { home.team.name } to away.team.shortName.ifBlank { away.team.name }
    }

    private fun score(m: Match): String? {
        val hs = m.home?.score?.text
        val aws = m.away?.score?.text
        return if (hs.isNullOrBlank() || aws.isNullOrBlank()) null else "$hs - $aws"
    }

    private fun kickOff(startMs: Long): String = DateUtils.formatDateTime(
        context, startMs,
        DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_ABBREV_WEEKDAY or DateUtils.FORMAT_SHOW_DATE or
            DateUtils.FORMAT_ABBREV_MONTH or DateUtils.FORMAT_NO_YEAR or DateUtils.FORMAT_SHOW_TIME,
    )

    companion object {
        private const val TAG = "SportsChannel"
        private const val PREFS = "sports_tv_channel"
        private const val KEY_CHANNEL_ID = "channel_id"
        private const val KEY_ASK_BROWSABLE = "ask_browsable"
        private const val CHANNEL_PROVIDER_ID = "owntv-sports"
        private const val APP_LINK = "owntv://open/sports"
        private const val SEPARATOR = " · "
        private const val VS = "vs"
        /** Bump when the poster's design changes, so every card is drawn again. */
        private const val POSTER_VERSION = 2
        private const val LOGO_PX = 160
    }
}
