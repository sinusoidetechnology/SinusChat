package pl.sinusoide.sinuschat.util

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import pl.sinusoide.sinuschat.R
import java.io.File

object AvatarSaver {

    private const val DEFAULT_SIZE = 1024

    fun bitmapOf(d: Drawable?): Bitmap? {
        if (d == null) return null
        (d as? BitmapDrawable)?.bitmap?.let { return it }
        val w = d.intrinsicWidth.takeIf { it > 0 } ?: DEFAULT_SIZE
        val h = d.intrinsicHeight.takeIf { it > 0 } ?: DEFAULT_SIZE
        return runCatching {
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { b ->
                val c = Canvas(b)
                d.setBounds(0, 0, w, h)
                d.draw(c)
            }
        }.getOrNull()
    }

    fun letter(name: String, color: String, size: Int = DEFAULT_SIZE): Bitmap =
        AvatarHelper.createLetterBitmap(AvatarHelper.initialOf(name), color, size)

    private fun safe(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().take(60).ifBlank { "SinusChat" }

    fun save(activity: Activity, bitmap: Bitmap, name: String) {
        AppExecutor.io {
            val tmp = File(activity.cacheDir, "avatar-save-${System.nanoTime()}.png")
            val saved = try {
                tmp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                DownloadsSaver.save(activity, tmp, "${safe(name)}_${System.currentTimeMillis()}.png", "image/png")
            } catch (_: Exception) {
                null
            } finally {
                tmp.delete()
            }
            AppExecutor.main {
                if (activity.isFinishing || activity.isDestroyed) return@main
                if (saved != null) PopupHelper.showSuccess(activity,
                    activity.getString(R.string.avatar_saved, DownloadsSaver.locationFor(activity, "image/png")))
                else PopupHelper.showError(activity, activity.getString(R.string.avatar_save_failed))
            }
        }
    }

    fun saveFrom(activity: Activity, source: Any?, fallback: () -> Bitmap, name: String) {
        AppExecutor.io {
            val bmp = source?.let {
                runCatching {
                    com.bumptech.glide.Glide.with(activity.applicationContext).asBitmap().load(it).submit().get()
                }.getOrNull()
            } ?: fallback()
            AppExecutor.main { if (!activity.isFinishing && !activity.isDestroyed) save(activity, bmp, name) }
        }
    }
}
