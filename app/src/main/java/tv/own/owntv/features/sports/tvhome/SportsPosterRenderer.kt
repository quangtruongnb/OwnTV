package tv.own.owntv.features.sports.tvhome

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import android.text.TextPaint
import android.text.TextUtils
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import android.text.format.DateUtils
import tv.own.owntv.core.sports.model.Competitor
import tv.own.owntv.core.sports.model.Match
import java.io.File
import java.security.MessageDigest

/**
 * Draws a card's poster: both crests, "Home vs Away" (or the live score) and the kick-off time.
 *
 * Some launchers (ATV Launcher, many OEM homes) show only a card's image and never its title, so the
 * match has to be readable from the picture itself. Posters are PNGs in the app's cache, served to the
 * launcher by [SportsPosterProvider]; a poster's file name carries its content fingerprint, so a changed
 * score is a new URI the launcher cannot serve from its own cache.
 */
class SportsPosterRenderer(
    private val context: Context,
    private val http: OkHttpClient,
) {
    private val logoDir get() = File(context.cacheDir, LOGO_DIR).apply { mkdirs() }

    /** The poster's content URI, or null if it could not be drawn. [signature] identifies what it shows. */
    fun render(m: Match, title: String, centre: String, footer: String, signature: Long): Uri? = try {
        val name = "${hash(m.key)}_${java.lang.Long.toHexString(signature)}.png"
        val file = File(SportsPosterProvider.dir(context), name)
        if (!file.exists()) {
            val bmp = draw(m, title, centre, footer)
            file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bmp.recycle()
        }
        SportsPosterProvider.uriFor(context, name)
    } catch (e: Exception) {
        Log.w(TAG, "poster failed for ${m.key}: ${e.javaClass.simpleName}")
        null
    }

    /** Removes posters no card points at any more. */
    fun prune(keep: Set<Uri>) {
        val names = keep.mapNotNull { it.lastPathSegment }.toSet()
        SportsPosterProvider.dir(context).listFiles()?.forEach { if (it.name !in names) it.delete() }
    }

    private fun draw(m: Match, title: String, centre: String, footer: String): Bitmap {
        val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        // Transparent background: the launcher's own card shows through. Text carries a shadow so it
        // reads on any wallpaper. Everything sits inside the band launchers keep when they crop to ~2:1.

        // League header across top centre (with red LIVE indicator dot).
        val small = fit(m.leagueName, 26f, 20f, W - 120f, MUTED)
        val league = TextUtils.ellipsize(m.leagueName, small, W - 120f, TextUtils.TruncateAt.END).toString()
        val leagueW = small.measureText(league)
        val leagueX = (W - leagueW) / 2f
        c.drawText(league, leagueX, 48f, small)
        if (m.isLive) {
            p.color = LIVE
            c.drawCircle(leagueX - 22f, 38f, 7f, p)
        }

        val home = m.home ?: m.competitors.firstOrNull()
        val away = m.away ?: m.competitors.getOrNull(1)

        if (home != null && away != null) {
            // Two-team match: enlarged side crests (~185px) with team names below each logo.
            val homeX = W * 0.19f
            val awayX = W * 0.81f
            val crestY = 152f

            crest(c, home, homeX, crestY, CREST_SIZE)
            crest(c, away, awayX, crestY, CREST_SIZE)

            // Team names directly below crests
            val homeName = home.team.shortName.ifBlank { home.team.name }
            val awayName = away.team.shortName.ifBlank { away.team.name }
            drawTeamName(c, homeName, homeX, 285f)
            drawTeamName(c, awayName, awayX, 285f)

            // Centre column:
            if (m.isLive) {
                // Large live score in centre
                val mid = fit(centre, 68f, 48f, 200f, Color.WHITE)
                c.drawText(centre, (W - mid.measureText(centre)) / 2f, 166f, mid)
                // Match status below score
                val foot = fit(footer, 40f, 26f, 200f, LIVE)
                c.drawText(footer, (W - foot.measureText(footer)) / 2f, 235f, foot)
            } else {
                // "vs" placed above kickoff time
                val mid = text(42f, Typeface.BOLD, Color.WHITE)
                c.drawText(centre, (W - mid.measureText(centre)) / 2f, 126f, mid)
                // Kick-off time (enlarged to 52px) and date (enlarged to 30px, white for contrast)
                if (m.startMs > 0L) {
                    val timeStr = DateUtils.formatDateTime(context, m.startMs, DateUtils.FORMAT_SHOW_TIME)
                    val dateStr = DateUtils.formatDateTime(
                        context, m.startMs,
                        DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_ABBREV_WEEKDAY or
                            DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH or DateUtils.FORMAT_NO_YEAR,
                    )
                    val timePaint = fit(timeStr, 52f, 36f, 200f, ACCENT)
                    c.drawText(timeStr, (W - timePaint.measureText(timeStr)) / 2f, 192f, timePaint)
                    val datePaint = fit(dateStr, 30f, 20f, 200f, Color.WHITE)
                    c.drawText(dateStr, (W - datePaint.measureText(dateStr)) / 2f, 248f, datePaint)
                } else if (footer.isNotBlank()) {
                    val foot = fit(footer, 42f, 26f, 200f, ACCENT)
                    c.drawText(footer, (W - foot.measureText(footer)) / 2f, 220f, foot)
                }
            }
        } else {
            // Single competitor or field event: centered crest + full title below.
            crest(c, home, W / 2f, 150f, CREST_SIZE)
            val big = fit(title, 46f, 30f, W - 48f, Color.WHITE)
            val t = TextUtils.ellipsize(title, big, W - 48f, TextUtils.TruncateAt.END).toString()
            c.drawText(t, (W - big.measureText(t)) / 2f, 270f, big)
            val foot = fit(footer, 34f, 24f, W - 48f, if (m.isLive) LIVE else ACCENT)
            val f = TextUtils.ellipsize(footer, foot, W - 48f, TextUtils.TruncateAt.END).toString()
            c.drawText(f, (W - foot.measureText(f)) / 2f, 315f, foot)
        }
        return bmp
    }

    private fun drawTeamName(c: Canvas, name: String, cx: Float, y: Float) {
        if (name.isBlank()) return
        val paint = fit(name, 34f, 22f, MAX_TEAM_NAME_W, Color.WHITE)
        val text = TextUtils.ellipsize(name, paint, MAX_TEAM_NAME_W, TextUtils.TruncateAt.END).toString()
        c.drawText(text, cx - paint.measureText(text) / 2f, y, paint)
    }

    /** A bold paint at [max] size, shrunk (not below [min]) until [s] fits [width]. */
    private fun fit(s: String, max: Float, min: Float, width: Float, color: Int): TextPaint {
        val paint = text(max, Typeface.BOLD, color)
        while (paint.textSize > min && paint.measureText(s) > width) paint.textSize -= 2f
        return paint
    }

    private fun crest(c: Canvas, who: Competitor?, cx: Float, cy: Float, size: Float = CREST_SIZE) {
        val box = RectF(cx - size / 2, cy - size / 2, cx + size / 2, cy + size / 2)
        val logo = who?.team?.logoUrl?.takeIf { it.isNotBlank() }?.let { logo(it) }
        if (logo != null) {
            // Fit inside the box, keeping its proportions.
            val s = minOf(box.width() / logo.width, box.height() / logo.height)
            val w = logo.width * s
            val h = logo.height * s
            c.drawBitmap(logo, null, RectF(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2), Paint(Paint.FILTER_BITMAP_FLAG))
            logo.recycle()
        } else {
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = CREST_BG }
            c.drawCircle(cx, cy, size / 2, p)
            val label = who?.team?.abbreviation?.ifBlank { null } ?: who?.team?.name?.take(3).orEmpty()
            val t = text(size * 0.28f, Typeface.BOLD, Color.WHITE)
            c.drawText(label, cx - t.measureText(label) / 2, cy + (size * 0.10f), t)
        }
    }

    /** Crops transparent border pixels around the logo so it fills its bounding box. */
    private fun trimTransparent(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        var minX = w
        var minY = h
        var maxX = -1
        var maxY = -1

        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                if (((pixels[row + x] ushr 24) and 0xff) > 10) {
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
        }
        if (minX > maxX || minY > maxY) return src
        val tw = maxX - minX + 1
        val th = maxY - minY + 1
        if (tw == w && th == h) return src
        return Bitmap.createBitmap(src, minX, minY, tw, th)
    }

    /** A crest from the disk cache, downloaded once and auto-trimmed of transparent borders. */
    private fun logo(url: String): Bitmap? {
        val file = File(logoDir, hash(url))
        if (!file.exists() || file.length() == 0L) {
            runCatching {
                http.newCall(Request.Builder().url(url).build()).execute().use { r ->
                    if (!r.isSuccessful) return null
                    val bytes = r.body.bytes()
                    file.writeBytes(bytes)
                }
            }.onFailure { return null }
        }
        val raw = BitmapFactory.decodeFile(file.path) ?: return null
        val trimmed = trimTransparent(raw)
        if (trimmed !== raw) raw.recycle()
        return trimmed
    }

    private fun text(size: Float, style: Int, color: Int) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size
        typeface = Typeface.create(Typeface.SANS_SERIF, style)
        this.color = color
        setShadowLayer(SHADOW_RADIUS, 0f, SHADOW_DY, SHADOW)
    }

    private fun hash(s: String): String =
        MessageDigest.getInstance(SHA).digest(s.toByteArray()).take(12).joinToString(separator = "") { "%02x".format(it) }

    companion object {
        private const val TAG = "SportsChannel"
        private const val LOGO_DIR = "sports_logos"
        private const val SHA = "SHA-256"
        const val W = 640
        const val H = 360
        private const val CREST_SIZE = 185f
        private const val MAX_TEAM_NAME_W = 190f
        private val MUTED = Color.rgb(0xD5, 0xDC, 0xE6)
        private val ACCENT = Color.rgb(0x5E, 0xE6, 0xCC)
        private val LIVE = Color.rgb(0xFF, 0x5A, 0x5C)
        private val CREST_BG = Color.argb(0xCC, 0x2C, 0x3A, 0x4D)
        private val SHADOW = Color.argb(0xE6, 0, 0, 0)
        private const val SHADOW_RADIUS = 8f
        private const val SHADOW_DY = 2f
    }
}
