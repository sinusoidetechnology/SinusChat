package pl.sinusoide.sinuschat.ui

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import pl.sinusoide.sinuschat.R
import pl.sinusoide.sinuschat.util.AvatarHelper

object ProfilePhotoSheet {

    fun show(
        activity: Activity,
        name: String,
        originalColor: String,
        currentPhoto: Any?,
        pickPhoto: (onPicked: (Uri) -> Unit) -> Unit,
        onDismiss: () -> Unit = {},
        onSave: (color: String, photo: Uri?, removePhoto: Boolean) -> Unit
    ) {
        val ctx = activity
        val d = ctx.resources.displayMetrics.density
        val accent = ThemeUi.accent(ctx)
        val red = androidx.core.content.ContextCompat.getColor(ctx, R.color.sinuschat_error)
        val secondary = ThemeUi.textSecondary(ctx)
        val hadPhoto = currentPhoto != null

        var pickedColor = originalColor
        var pendingPhoto: Uri? = null
        var removePhoto = false
        fun hasPhoto() = pendingPhoto != null || (hadPhoto && !removePhoto)

        val sheet = BottomSheetDialog(ctx, R.style.Theme_SinusChat_BottomSheet)
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding((24 * d).toInt(), (12 * d).toInt(), (24 * d).toInt(), (20 * d).toInt())
            clipChildren = false
        }
        root.addView(View(ctx).apply {
            background = GradientDrawable().apply { cornerRadius = 3 * d; setColor(ThemeUi.handle(context)) }
        }, LinearLayout.LayoutParams((36 * d).toInt(), (4 * d).toInt()).apply {
            bottomMargin = (18 * d).toInt()
        })
        root.addView(TextView(ctx).apply {
            text = ctx.getString(R.string.profile_photo_title)
            textSize = 20f; setTextColor(ThemeUi.textPrimary(context))
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        })
        root.addView(TextView(ctx).apply {
            text = ctx.getString(R.string.profile_photo_desc)
            textSize = 13.5f; setTextColor(secondary); gravity = Gravity.CENTER
            setPadding(0, (6 * d).toInt(), 0, (20 * d).toInt())
        })

        val previewSize = (112 * d).toInt()
        val halo = FrameLayout(ctx).apply { val p = (8 * d).toInt(); setPadding(p, p, p, p) }
        val preview = ImageView(ctx)
        halo.addView(preview, FrameLayout.LayoutParams(previewSize, previewSize))
        root.addView(halo, LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = (10 * d).toInt() })

        fun chip(label: String, icon: Int, tint: Int) = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply { cornerRadius = 20 * d; setColor(ThemeUi.card(ctx)) }
            setPadding((14 * d).toInt(), (8 * d).toInt(), (16 * d).toInt(), (8 * d).toInt())
            isClickable = true
            addView(ImageView(ctx).apply { setImageResource(icon); setColorFilter(tint) },
                LinearLayout.LayoutParams((18 * d).toInt(), (18 * d).toInt()).apply { marginEnd = (8 * d).toInt() })
            addView(TextView(ctx).apply { text = label; textSize = 13.5f; setTextColor(tint) })
        }
        val chooseChip = chip(ctx.getString(R.string.group_photo_choose), R.drawable.ic_image, accent)
        val removeChip = chip(ctx.getString(R.string.group_photo_remove), R.drawable.ic_delete, red)
        val downloadChip = chip(ctx.getString(R.string.avatar_download), R.drawable.ic_download, accent)
        root.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            addView(chooseChip)
            addView(removeChip, LinearLayout.LayoutParams(-2, -2).apply { marginStart = (10 * d).toInt() })
        }, LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = (10 * d).toInt() })
        root.addView(downloadChip, LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = (20 * d).toInt() })
        downloadChip.setOnClickListener {
            val photo: Any? = pendingPhoto ?: currentPhoto?.takeIf { !removePhoto }
            val color = pickedColor
            pl.sinusoide.sinuschat.util.AvatarSaver.saveFrom(activity, photo,
                { pl.sinusoide.sinuschat.util.AvatarSaver.letter(name, color) }, name.ifBlank { "SinusChat" })
        }

        root.addView(TextView(ctx).apply {
            text = ctx.getString(R.string.profile_initial_color)
            textSize = 12f; setTextColor(secondary)
        }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = (6 * d).toInt() })
        val swatches = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((6 * d).toInt(), (8 * d).toInt(), (6 * d).toInt(), (8 * d).toInt())
            clipChildren = false; clipToPadding = false
        }
        val swatchViews = mutableListOf<Pair<String, ImageView>>()
        val saveBtn = TextView(ctx)

        fun render() {
            val letterBmp = AvatarHelper.createLetterBitmap(AvatarHelper.initialOf(name), pickedColor, previewSize)
            val photo: Any? = pendingPhoto ?: currentPhoto?.takeIf { !removePhoto }
            if (photo != null) {
                val ph = BitmapDrawable(ctx.resources, letterBmp)
                val req = com.bumptech.glide.Glide.with(preview).load(photo).circleCrop().placeholder(ph).error(ph)
                (photo as? java.io.File)?.let { req.signature(com.bumptech.glide.signature.ObjectKey(it.lastModified())) }
                    ?.into(preview) ?: req.into(preview)
            } else {
                com.bumptech.glide.Glide.with(preview).clear(preview)
                preview.setImageBitmap(letterBmp)
            }
            halo.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                val c = try { Color.parseColor(pickedColor) } catch (_: Exception) { accent }
                setColor((c and 0x00FFFFFF) or 0x33000000)
            }
            removeChip.visibility = if (hasPhoto()) View.VISIBLE else View.GONE
            (chooseChip.getChildAt(1) as? TextView)?.text =
                ctx.getString(if (hasPhoto()) R.string.group_photo_change else R.string.group_photo_choose)
            for ((hex, v) in swatchViews) {
                val selected = hex.equals(pickedColor, ignoreCase = true)
                v.setImageResource(if (selected) R.drawable.ic_check else 0)
                v.scaleX = if (selected) 1.08f else 1f
                v.scaleY = if (selected) 1.08f else 1f
                (v.background as? GradientDrawable)?.setStroke(
                    (2.5f * d).toInt(), if (selected) (if (ThemeUi.isDark(ctx)) Color.WHITE else ThemeUi.textPrimary(ctx)) else 0x00000000)
            }
            val changed = !pickedColor.equals(originalColor, ignoreCase = true) ||
                pendingPhoto != null || (removePhoto && hadPhoto)
            saveBtn.isEnabled = changed
            saveBtn.alpha = if (changed) 1f else 0.45f
        }

        val swatchSize = (40 * d).toInt()
        for (hex in AvatarHelper.PALETTE) {
            val c = try { Color.parseColor(hex) } catch (_: Exception) { continue }
            val v = ImageView(ctx).apply {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(c) }
                setColorFilter(Color.WHITE)
                val p = (10 * d).toInt(); setPadding(p, p, p, p)
                isClickable = true
                contentDescription = hex
                setOnClickListener { pickedColor = hex; render() }
            }
            v.setTag(R.id.flow_glass_keep, true)
            swatchViews += hex to v
            swatches.addView(v, LinearLayout.LayoutParams(swatchSize, swatchSize).apply { marginEnd = (10 * d).toInt() })
        }
        root.addView(HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            clipToPadding = false; clipChildren = false
            addView(swatches)
        }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = (16 * d).toInt() })

        saveBtn.apply {
            text = ctx.getString(R.string.save)
            textSize = 16f; setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = GradientDrawable().apply { cornerRadius = 26 * d; setColor(accent) }
            setPadding(0, (14 * d).toInt(), 0, (14 * d).toInt())
            isClickable = true
        }
        root.addView(saveBtn, LinearLayout.LayoutParams(-1, -2))
        root.addView(TextView(ctx).apply {
            text = ctx.getString(R.string.cancel)
            textSize = 15f; setTextColor(secondary); gravity = Gravity.CENTER
            setPadding(0, (14 * d).toInt(), 0, (4 * d).toInt())
            isClickable = true
            setOnClickListener { sheet.dismiss() }
        }, LinearLayout.LayoutParams(-1, -2))

        chooseChip.setOnClickListener {
            pickPhoto { uri -> pendingPhoto = uri; removePhoto = false; render() }
        }
        removeChip.setOnClickListener { pendingPhoto = null; removePhoto = true; render() }

        saveBtn.setOnClickListener {
            onSave(pickedColor, pendingPhoto, removePhoto && hadPhoto)
            sheet.dismiss()
        }

        sheet.setOnDismissListener { onDismiss() }
        render()
        sheet.setContentView(root)
        sheet.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        sheet.behavior.skipCollapsed = true
        FlowGlass.show(sheet)
    }
}
