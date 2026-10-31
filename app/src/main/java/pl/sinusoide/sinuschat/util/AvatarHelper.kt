package pl.sinusoide.sinuschat.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.util.Log
import android.view.View
import android.widget.GridLayout
import androidx.appcompat.app.AlertDialog
import pl.sinusoide.sinuschat.setup.SetupActivity
import java.io.File
import java.io.FileOutputStream

object AvatarHelper {

    private const val TAG   = "AvatarHelper"
    private const val PREFS = "sinuschat_avatar_prefs"
    private const val AVATAR_FILENAME = "my_avatar.jpg"
    private const val LOCAL_AVATAR_MAX_PX = 1024

    val PALETTE = listOf(
        "#5288C1",
        "#E74C3C",
        "#2ECC71",
        "#9B59B6",
        "#E67E22",
        "#1ABC9C",
        "#E91E63",
        "#FF5722",
        "#607D8B",
        "#795548"
    )

    fun getMyColor(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("my_color", PALETTE[0]) ?: PALETTE[0]

    fun hasMyColor(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).contains("my_color")

    fun setMyColor(context: Context, color: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString("my_color", color).apply()
    }

    fun colorForHash(hash: String): String =
        PALETTE[Math.abs(hash.hashCode()) % PALETTE.size]

    fun getContactColor(context: Context, hash: String): String {
        val myHash = SetupActivity.getUserPhoneHash(context)
        if (hash == myHash) return getMyColor(context)

        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("color_$hash", colorForHash(hash)) ?: colorForHash(hash)
    }

    fun setContactColor(context: Context, hash: String, color: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString("color_$hash", color).apply()
    }

    fun versionedUrl(url: String, versionMs: Long = System.currentTimeMillis()): String {
        val clean = url.substringBefore("?v=").substringBefore("&v=")
        val sep = if (clean.contains("?")) "&" else "?"
        return "$clean${sep}v=$versionMs"
    }

    fun saveAvatarLocally(context: Context, uri: Uri): String? = try {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
        val bitmap = ImagePrep.decodeSampled(bytes, LOCAL_AVATAR_MAX_PX) ?: return null
        val file = File(context.filesDir, AVATAR_FILENAME)
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        bitmap.recycle()
        file.absolutePath
    } catch (e: Exception) { Log.e(TAG, "Failed to save avatar", e); null }

    fun downloadAvatar(context: Context, url: String): String? = try {
        val bytes = if (pl.sinusoide.sinuschat.crypto.ProfileCrypto.isEncryptedAvatar(url)) {
            pl.sinusoide.sinuschat.crypto.ProfileCrypto.fetchAvatar(context, "me", url, pl.sinusoide.sinuschat.crypto.ProfileCrypto.myKey(context))
                ?.let { File(it).readBytes() } ?: ByteArray(0)
        } else {
            pl.sinusoide.sinuschat.network.ApiClient.rawGet(context, url)
                ?.takeIf { it.first in 200..299 }?.second ?: ByteArray(0)
        }
        val bitmap = ImagePrep.decodeSampled(bytes, LOCAL_AVATAR_MAX_PX)
        if (bitmap == null) null else {
            val file = File(context.filesDir, AVATAR_FILENAME)
            FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }
            bitmap.recycle()
            file.absolutePath
        }
    } catch (e: Exception) {
        Log.e(TAG, "Nie udało się pobrać awatara", e)
        null
    }

    fun getLocalAvatarPath(context: Context): String? {
        val file = File(context.filesDir, AVATAR_FILENAME)
        return if (file.exists()) file.absolutePath else null
    }

    fun loadAvatarInto(context: Context, imageView: android.widget.ImageView): Boolean {
        val path = getLocalAvatarPath(context) ?: return false
        return try {
            val bitmap = ImagePrep.decodeSampledFile(path, LOCAL_AVATAR_MAX_PX)
            if (bitmap != null) { imageView.setImageBitmap(bitmap); true } else false
        } catch (e: Exception) { Log.e(TAG, "Failed to load avatar", e); false }
    }

    fun deleteLocalAvatar(context: Context): Boolean {
        val file = File(context.filesDir, AVATAR_FILENAME)
        return file.exists() && file.delete()
    }

    fun createLetterBitmap(letter: String, colorHex: String, sizePx: Int): android.graphics.Bitmap {
        val size = sizePx.coerceIn(24, 2048)
        val bmp = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bmp)
        val paint  = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            isFilterBitmap = true
            isDither = true
            isSubpixelText = true
        }

        val color = parseColorSafe(colorHex)
        val radius = size / 2f

        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(color, hsv)
        val colorDark = android.graphics.Color.HSVToColor(floatArrayOf(hsv[0], hsv[1], hsv[2] * 0.9f))
        val colorLight = android.graphics.Color.HSVToColor(floatArrayOf(hsv[0], hsv[1], Math.min(1.0f, hsv[2] * 1.3f)))
        
        paint.shader = android.graphics.LinearGradient(
            0f, 0f, 0f, size.toFloat(),
            colorLight, colorDark,
            android.graphics.Shader.TileMode.CLAMP
        )
        canvas.drawCircle(radius, radius, radius, paint)
        paint.shader = null

        paint.style = android.graphics.Paint.Style.STROKE
        paint.strokeWidth = 1f
        paint.color = colorDark
        paint.alpha = 150
        canvas.drawCircle(radius, radius, radius - 0.5f, paint)

        paint.strokeWidth = size * 0.012f
        paint.color = android.graphics.Color.WHITE
        paint.alpha = 40 
        canvas.drawCircle(radius, radius, radius - (paint.strokeWidth / 2f) - 1f, paint)

        paint.style = android.graphics.Paint.Style.FILL
        paint.alpha = 255
        paint.color = android.graphics.Color.WHITE
        paint.textSize = size * 0.46f
        paint.textAlign = android.graphics.Paint.Align.CENTER
        paint.typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        
        val fontMetrics = paint.fontMetrics
        val y = radius - (fontMetrics.ascent + fontMetrics.descent) / 2f
        
        paint.setShadowLayer(size * 0.015f, 0f, size * 0.008f, 0x50000000)
        
        canvas.drawText(letter, radius, y, paint)
        return bmp
    }

    fun initialOf(name: String): String {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return "?"
        val cp = trimmed.codePointAt(0)
        return String(Character.toChars(cp)).uppercase()
    }

    private fun parseColorSafe(colorHex: String?): Int = try {
        Color.parseColor(colorHex ?: PALETTE[0])
    } catch (_: IllegalArgumentException) {
        Color.parseColor(PALETTE[0])
    }

    private fun isDestroyed(context: android.content.Context): Boolean {
        var c: android.content.Context? = context
        while (c is android.content.ContextWrapper) {
            if (c is android.app.Activity) return c.isDestroyed || c.isFinishing
            c = c.baseContext
        }
        return false
    }

    private val pendingGroupAvatars = java.util.WeakHashMap<android.widget.ImageView, (android.widget.ImageView) -> Unit>()

    fun onAvatarDecrypted() {
        val pending = pendingGroupAvatars.entries.map { it.key to it.value }
        pendingGroupAvatars.clear()
        pending.forEach { (view, reload) -> runCatching { reload(view) } }
    }

    fun loadGroupAvatar(
        context: android.content.Context,
        imageView: android.widget.ImageView,
        name: String,
        colorHex: String,
        rawUrl: String?,
        sizePx: Int = 0,
        groupId: String? = null
    ) {
        val safeName = name.ifEmpty { "G" }
        pendingGroupAvatars.remove(imageView)
        if (isDestroyed(imageView.context) || isDestroyed(context)) return
        com.bumptech.glide.Glide.with(imageView).clear(imageView)
        val url = if (pl.sinusoide.sinuschat.crypto.ProfileCrypto.isEncryptedAvatar(rawUrl)) {
            groupId?.let { pl.sinusoide.sinuschat.crypto.ProfileCrypto.displayAvatar(context, it, rawUrl) }.also {
                if (it == null && groupId != null) pendingGroupAvatars[imageView] = { v ->
                    loadGroupAvatar(v.context, v, name, colorHex, rawUrl, sizePx, groupId)
                }
            }
        } else rawUrl
        if (url.isNullOrEmpty()) {
            setLetterAvatar(context, imageView, safeName, colorHex)
            return
        }
        val dp = context.resources.displayMetrics.density
        val px = sizePx.takeIf { it > 0 }
            ?: maxOf(imageView.width, imageView.height).takeIf { it > 0 }
            ?: (44 * dp).toInt()
        val letter = initialOf(safeName).takeIf { it != "?" } ?: "G"
        val fallback = android.graphics.drawable.BitmapDrawable(
            context.resources, createLetterBitmap(letter, colorHex, px))
        imageView.visibility = android.view.View.VISIBLE
        com.bumptech.glide.Glide.with(imageView)
            .load(url)
            .signature(com.bumptech.glide.signature.ObjectKey(url))
            .circleCrop()
            .placeholder(fallback)
            .error(fallback)
            .into(imageView)
    }

    fun setLetterAvatar(
        context: android.content.Context,
        imageView: android.widget.ImageView,
        name: String,
        colorHex: String
    ) {
        val letter = initialOf(name)
        val dp = context.resources.displayMetrics.density

        fun render(px: Int) {
            imageView.setImageBitmap(createLetterBitmap(letter, colorHex, px))
            imageView.visibility = android.view.View.VISIBLE
        }

        val measured = maxOf(imageView.width, imageView.height)
        if (measured > 0) {
            render(measured)
        } else {
            render((44 * dp).toInt())
            imageView.post {
                val px = maxOf(imageView.width, imageView.height)
                if (px > 0) render(px)
            }
        }
    }

    fun showColorPicker(
        context: Context,
        currentColor: String,
        title: String = context.getString(pl.sinusoide.sinuschat.R.string.initial_color),
        onPicked: (String) -> Unit
    ) {
        val dp = context.resources.displayMetrics.density
        val grid = GridLayout(context).apply {
            columnCount = 5
            setPadding((16 * dp).toInt(), (12 * dp).toInt(), (16 * dp).toInt(), (8 * dp).toInt())
        }

        for (color in PALETTE) {
            val btn = View(context).apply {
                val size = (44 * dp).toInt()
                val margin = (6 * dp).toInt()
                layoutParams = GridLayout.LayoutParams().apply {
                    width = size; height = size
                    setMargins(margin, margin, margin, margin)
                }
                val shape = GradientDrawable()
                shape.shape = GradientDrawable.OVAL
                shape.setColor(Color.parseColor(color))
                if (color == currentColor) {
                    shape.setStroke((3 * dp).toInt(), Color.WHITE)
                }
                background = shape
                setOnClickListener { onPicked(color) }
            }
            grid.addView(btn)
        }

        AlertDialog.Builder(context, pl.sinusoide.sinuschat.R.style.Theme_SinusChat_Dialog)
            .setTitle(title)
            .setView(grid)
            .setPositiveButton(context.getString(pl.sinusoide.sinuschat.R.string.done), null)
            .show()
    }
}
