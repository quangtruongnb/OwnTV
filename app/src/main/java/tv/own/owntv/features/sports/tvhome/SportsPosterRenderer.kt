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

        // League, top centre (with a red dot while live).
        val small = fit(m.leagueName, 30f, 22f, W - 120f, MUTED)
        val league = TextUtils.ellipsize(m.leagueName, small, W - 120f, TextUtils.TruncateAt.END).toString()
        val leagueW = small.measureText(league)
        val leagueX = (W - leagueW) / 2f
        c.drawText(league, leagueX, 62f, small)
        if (m.isLive) {
            p.color = LIVE
            c.drawCircle(leagueX - 18f, 52f, 9f, p)
        }

        // Crests.
        val home = m.home ?: m.competitors.firstOrNull()
        val away = m.away ?: m.competitors.getOrNull(1)
        crest(c, home, W * 0.2f, 145f)
        crest(c, away, W * 0.8f, 145f)

        // Centre: "vs" or the score.
        val mid = text(if (centre.length > 3) 68f else 60f, Typeface.BOLD, Color.WHITE)
        c.drawText(centre, (W - mid.measureText(centre)) / 2f, 166f, mid)

        // Title and kick-off.
        val big = fit(title, 50f, 34f, W - 24f, Color.WHITE)
        val t = TextUtils.ellipsize(title, big, W - 24f, TextUtils.TruncateAt.END).toString()
        c.drawText(t, (W - big.measureText(t)) / 2f, 268f, big)
        val foot = fit(footer, 40f, 30f, W - 24f, if (m.isLive) LIVE else ACCENT)
        val f = TextUtils.ellipsize(footer, foot, W - 24f, TextUtils.TruncateAt.END).toString()
        c.drawText(f, (W - foot.measureText(f)) / 2f, 318f, foot)
        return bmp
    }

    /** A bold paint at [max] size, shrunk (not below [min]) until [s] fits [width]. */
    private fun fit(s: String, max: Float, min: Float, width: Float, color: Int): TextPaint {
        val paint = text(max, Typeface.BOLD, color)
        while (paint.textSize > min && paint.measureText(s) > width) paint.textSize -= 2f
        return paint
    }

    private fun crest(c: Canvas, who: Competitor?, cx: Float, cy: Float) {
        val size = 120f
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
            val t = text(36f, Typeface.BOLD, Color.WHITE)
            c.drawText(label, cx - t.measureText(label) / 2, cy + 13f, t)
        }
    }

    /** A crest from the disk cache, downloaded once. */
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
        return BitmapFactory.decodeFile(file.path)
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
        private val MUTED = Color.rgb(0xD5, 0xDC, 0xE6)
        private val ACCENT = Color.rgb(0x5E, 0xE6, 0xCC)
        private val LIVE = Color.rgb(0xFF, 0x5A, 0x5C)
        private val CREST_BG = Color.argb(0xCC, 0x2C, 0x3A, 0x4D)
        private val SHADOW = Color.argb(0xE6, 0, 0, 0)
        private const val SHADOW_RADIUS = 8f
        private const val SHADOW_DY = 2f
    }
}
