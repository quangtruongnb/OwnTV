package tv.own.owntv.features.sports.tvhome

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileNotFoundException

/**
 * Serves the Sports channel's poster PNGs to the home screen launcher, read-only.
 *
 * Exported because the launcher is another app and reads the URIs straight from TvProvider. It only
 * ever opens a plain file name inside [dir]; anything else (paths, writes, queries) is refused.
 */
class SportsPosterProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != READ_MODE) throw SecurityException(mode)
        val ctx = context ?: throw FileNotFoundException()
        val segments = uri.pathSegments
        val name = segments.singleOrNull()?.takeIf { NAME.matches(it) } ?: throw FileNotFoundException()
        val file = File(dir(ctx), name)
        if (!file.isFile) throw FileNotFoundException()
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri): String = MIME
    override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<out String>?): Int = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?): Int = 0

    companion object {
        private const val DIR = "sports_posters"
        private const val AUTHORITY_SUFFIX = ".sportsposter"
        private const val MIME = "image/png"
        private const val READ_MODE = "r"
        private val NAME = Regex("[0-9a-f]+_[0-9a-f-]+\\.png")

        fun dir(context: Context): File = File(context.cacheDir, DIR).apply { mkdirs() }

        fun uriFor(context: Context, name: String): Uri = Uri.Builder()
            .scheme(android.content.ContentResolver.SCHEME_CONTENT)
            .authority(context.packageName + AUTHORITY_SUFFIX)
            .appendPath(name)
            .build()
    }
}
