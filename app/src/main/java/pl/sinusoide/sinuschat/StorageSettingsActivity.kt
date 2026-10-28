package pl.sinusoide.sinuschat
import pl.sinusoide.sinuschat.util.optStringOrNull

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.json.JSONArray
import pl.sinusoide.sinuschat.messenger.MediaGalleryActivity
import pl.sinusoide.sinuschat.util.AppExecutor
import pl.sinusoide.sinuschat.util.AvatarHelper
import pl.sinusoide.sinuschat.util.ConversationCache
import pl.sinusoide.sinuschat.util.MediaManager
import pl.sinusoide.sinuschat.util.PopupHelper
import pl.sinusoide.sinuschat.util.StorageUsage
import pl.sinusoide.sinuschat.util.ThemeManager

class StorageSettingsActivity : AppCompatActivity() {
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(pl.sinusoide.sinuschat.util.LocaleManager.wrap(newBase))
    }

    private val dp get() = resources.displayMetrics.density

    private var cBg = 0; private var cSurface = 0; private var cCard = 0
    private var cPrimary = 0; private var cSecondary = 0; private var cTrack = 0
    private var cAccent = 0; private var cGreen = 0; private var cRed = 0
    private var cOrange = 0; private var cPurple = 0; private var cOther = 0

    private lateinit var content: LinearLayout
    private var loading = false
    private var firstLoad = true

    private fun attr(id: Int, fallback: Int): Int {
        val tv = android.util.TypedValue()
        return pl.sinusoide.sinuschat.ui.ThemeUi.attrOrNull(this, id) ?: fallback
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyTheme(this)
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        pl.sinusoide.sinuschat.util.SettingsHighlight.apply(this)

        cBg = attr(R.attr.sinuschat_background, 0xFF0E1621.toInt())
        cSurface = attr(R.attr.sinuschat_surface, 0xFF17212B.toInt())
        cCard = attr(R.attr.sinuschat_surface_elevated, 0xFF1D2A38.toInt())
        cPrimary = attr(R.attr.sinuschat_text_primary, Color.WHITE)
        cSecondary = attr(R.attr.sinuschat_text_secondary, 0xFF7F91A4.toInt())
        cAccent = pl.sinusoide.sinuschat.ui.ThemeUi.accent(this)
        cGreen = ContextCompat.getColor(this, R.color.sinuschat_success)
        cRed = ContextCompat.getColor(this, R.color.sinuschat_error)
        cOrange = 0xFFE67E22.toInt()
        cPurple = 0xFF9B6BE0.toInt()
        cOther = (cSecondary and 0x00FFFFFF) or 0x99000000.toInt()
        cTrack = (cSecondary and 0x00FFFFFF) or 0x26000000

        buildShell()
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    private fun buildShell() {
        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(cBg)
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = (56 * dp).toInt()
            setBackgroundColor(cSurface)
            elevation = 2 * dp
        }
        header.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_back); setColorFilter(cPrimary)
            val p = (10 * dp).toInt(); setPadding(p, p, p, p)
            background = ContextCompat.getDrawable(this@StorageSettingsActivity, R.drawable.bg_menu_ripple)
            contentDescription = getString(R.string.back)
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams((44 * dp).toInt(), (44 * dp).toInt()))
        header.addView(TextView(this).apply {
            text = getString(R.string.device_storage_title); textSize = 20f
            setTextColor(cPrimary); typeface = Typeface.DEFAULT_BOLD
            setPadding((8 * dp).toInt(), 0, 0, 0)
        })
        outer.addView(header)
        ViewCompat.setOnApplyWindowInsetsListener(header) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding((12 * dp).toInt(), bars.top, (12 * dp).toInt(), 0)
            insets
        }

        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((16 * dp).toInt(), (16 * dp).toInt(), (16 * dp).toInt(), (28 * dp).toInt())
        }
        val scroll = ScrollView(this).apply { addView(content) }
        outer.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { v, insets ->
            v.setPadding(0, 0, 0, insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom); insets
        }
        setContentView(outer)
    }

    private fun load() {
        if (loading) return
        loading = true
        val showCleanup = firstLoad
        firstLoad = false
        AppExecutor.io {
            val freedOrphans = try { MediaManager.purgeOrphans(this) } catch (_: Exception) { 0L }
            val snap = StorageUsage.snapshot(this)
            val free = StorageUsage.deviceFreeBytes(this)
            val total = StorageUsage.deviceTotalBytes(this)
            val names = buildNameMap()
            AppExecutor.main {
                loading = false
                if (isFinishing || isDestroyed) return@main
                render(snap, free, total, names)
                if (showCleanup && freedOrphans > 0) {
                    PopupHelper.show(this, getString(R.string.storage_orphans_freed,
                        StorageUsage.formatSize(freedOrphans)))
                }
            }
        }
    }

    private fun render(
        snap: StorageUsage.Snapshot, freeBytes: Long, totalBytes: Long,
        names: Map<String, ChatInfo>
    ) {
        content.removeAllViews()
        content.addView(heroCard(snap.totalBytes + snap.cacheBytes, freeBytes, totalBytes))

        content.addView(sectionTitle(getString(R.string.storage_section_types)))
        content.addView(card().apply {
            val maxBytes = snap.kinds.maxOf { it.bytes }.coerceAtLeast(1L)
            snap.kinds.forEachIndexed { i, k ->
                if (i > 0) addView(divider())
                addView(kindRow(k, maxBytes))
            }
        })

        content.addView(sectionTitle(getString(R.string.storage_section_manage)))
        content.addView(card().apply {
            addView(actionRow(R.drawable.ic_image, cAccent, getString(R.string.larger_than_5mb),
                getString(R.string.largest_media_desc), StorageUsage.formatSize(snap.totalBytes)) {
                startActivity(Intent(this@StorageSettingsActivity, MediaGalleryActivity::class.java)
                    .putExtra("conversationId", "ALL"))
            })
            addView(divider())
            addView(actionRow(R.drawable.ic_delete, cRed, getString(R.string.storage_cache_title),
                getString(R.string.storage_cache_desc), StorageUsage.formatSize(snap.cacheBytes)) {
                if (snap.cacheBytes <= 0L) PopupHelper.show(this@StorageSettingsActivity, getString(R.string.storage_cache_empty))
                else confirmClearCache(snap.cacheBytes)
            })
        })

        content.addView(sectionTitle(getString(R.string.storage_section_downloads)))
        content.addView(card().apply {
            val langs = pl.sinusoide.sinuschat.util.Translations.downloadedLangs(this@StorageSettingsActivity)
            addView(actionRow(R.drawable.ic_translate, 0xFF2FA88F.toInt(), getString(R.string.storage_translate_title),
                if (langs.isEmpty()) getString(R.string.storage_nothing_downloaded)
                else getString(R.string.storage_downloaded_list, langs.joinToString(", ") {
                    pl.sinusoide.sinuschat.util.Translations.name(this@StorageSettingsActivity, it) }),
                StorageUsage.formatSize(pl.sinusoide.sinuschat.util.Translations.storedBytes(this@StorageSettingsActivity))) {
                startActivity(Intent(this@StorageSettingsActivity, TranslationSettingsActivity::class.java))
            })
            addView(divider())
            val models = SiniaOfflineFragment.MODELS.filter { m ->
                java.io.File(filesDir, m.filename).let { it.isFile && it.length() >= m.minValidBytes }
            }
            val modelBytes = filesDir.listFiles()?.filter { it.isFile && it.name.contains(".litertlm") }?.sumOf { it.length() } ?: 0L
            addView(actionRow(R.drawable.ic_sinia, cPurple, getString(R.string.ai_models_title),
                if (models.isEmpty()) getString(R.string.storage_nothing_downloaded)
                else getString(R.string.storage_downloaded_list, models.joinToString(", ") { getString(it.nameResId) }),
                StorageUsage.formatSize(modelBytes)) {
                startActivity(Intent(this@StorageSettingsActivity, AiModelsActivity::class.java))
            })
        })

        content.addView(sectionTitle(getString(R.string.chats_chats_caps)))
        if (snap.conversations.isEmpty()) {
            content.addView(emptyCard())
        } else {
            content.addView(card().apply {
                val maxBytes = snap.conversations.first().bytes.coerceAtLeast(1L)
                snap.conversations.forEachIndexed { i, conv ->
                    if (i > 0) addView(divider())
                    addView(chatRow(conv, names[conv.conversationId], maxBytes))
                }
            })
        }

        content.addView(TextView(this).apply {
            text = getString(R.string.storage_footer)
            textSize = 12.5f; setTextColor(cSecondary); gravity = Gravity.CENTER
            setLineSpacing(0f, 1.2f)
            setPadding((12 * dp).toInt(), (20 * dp).toInt(), (12 * dp).toInt(), 0)
        })
    }

    private fun heroCard(appBytes: Long, freeBytes: Long, totalBytes: Long): View {
        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(cCard, 20f)
            setPadding((20 * dp).toInt(), (20 * dp).toInt(), (20 * dp).toInt(), (18 * dp).toInt())
        }

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        }
        top.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_storage); setColorFilter(cAccent)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor((cAccent and 0x00FFFFFF) or 0x26000000)
            }
            val p = (12 * dp).toInt(); setPadding(p, p, p, p)
        }, LinearLayout.LayoutParams((52 * dp).toInt(), (52 * dp).toInt()).apply {
            marginEnd = (14 * dp).toInt()
        })
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val parts = StorageUsage.formatSize(appBytes).split(" ")
        texts.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM
            addView(TextView(this@StorageSettingsActivity).apply {
                text = parts.getOrElse(0) { "0" }; textSize = 30f
                setTextColor(cPrimary); typeface = Typeface.DEFAULT_BOLD
                includeFontPadding = false
            })
            addView(TextView(this@StorageSettingsActivity).apply {
                text = parts.getOrElse(1) { "B" }; textSize = 15f
                setTextColor(cSecondary); typeface = Typeface.DEFAULT_BOLD
                setPadding((5 * dp).toInt(), 0, 0, (3 * dp).toInt())
            })
        })
        texts.addView(TextView(this).apply {
            text = getString(R.string.storage_hero_label); textSize = 13f; setTextColor(cSecondary)
            setPadding(0, (2 * dp).toInt(), 0, 0)
        })
        top.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
        hero.addView(top)

        val otherBytes = (totalBytes - freeBytes - appBytes).coerceAtLeast(0L)
        val safeTotal = totalBytes.coerceAtLeast(1L).toFloat()
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = rounded(cTrack, 6f)
            clipToOutline = true
        }
        fun segment(weight: Float, color: Int) = View(this).apply {
            setBackgroundColor(color)
            layoutParams = LinearLayout.LayoutParams(0, -1, weight)
        }
        val appWeight = maxOf(appBytes / safeTotal, 0.02f)
        val otherWeight = otherBytes / safeTotal
        bar.addView(segment(appWeight, cAccent))
        bar.addView(View(this).apply { setBackgroundColor(cCard) },
            LinearLayout.LayoutParams((2 * dp).toInt(), -1))
        bar.addView(segment(otherWeight, cOther))
        bar.addView(segment((1f - appWeight - otherWeight).coerceAtLeast(0f), Color.TRANSPARENT))
        hero.addView(bar, LinearLayout.LayoutParams(-1, (12 * dp).toInt()).apply {
            topMargin = (18 * dp).toInt()
        })

        val legend = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, (12 * dp).toInt(), 0, 0)
        }
        legend.addView(legendItem("SinusChat", cAccent))
        legend.addView(legendItem(getString(R.string.storage_legend_other), cOther),
            LinearLayout.LayoutParams(-2, -2).apply { marginStart = (14 * dp).toInt() })
        legend.addView(legendItem(getString(R.string.free), cTrack),
            LinearLayout.LayoutParams(-2, -2).apply { marginStart = (14 * dp).toInt() })
        hero.addView(legend)

        hero.addView(View(this).apply { setBackgroundColor(pl.sinusoide.sinuschat.ui.ThemeUi.divider(context)) },
            LinearLayout.LayoutParams(-1, (1 * dp).toInt().coerceAtLeast(1)).apply {
                topMargin = (14 * dp).toInt(); bottomMargin = (12 * dp).toInt()
            })
        hero.addView(TextView(this).apply {
            text = getString(R.string.storage_phone_summary,
                StorageUsage.formatSize(freeBytes), StorageUsage.formatSize(totalBytes))
            textSize = 13f; setTextColor(cSecondary)
        })
        return hero
    }

    private fun legendItem(label: String, color: Int) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        addView(View(this@StorageSettingsActivity).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }
        }, LinearLayout.LayoutParams((9 * dp).toInt(), (9 * dp).toInt()).apply {
            marginEnd = (6 * dp).toInt()
        })
        addView(TextView(this@StorageSettingsActivity).apply {
            text = label; textSize = 12f; setTextColor(cSecondary)
        })
    }

    private fun kindRow(k: StorageUsage.KindUsage, maxBytes: Long): View {
        val (icon, color, label) = when (k.kind) {
            StorageUsage.Kind.IMAGE -> Triple(R.drawable.ic_image, cAccent, R.string.storage_kind_images)
            StorageUsage.Kind.VIDEO -> Triple(R.drawable.ic_video, cPurple, R.string.storage_kind_videos)
            StorageUsage.Kind.VOICE -> Triple(R.drawable.ic_mic, cGreen, R.string.storage_kind_voice)
            StorageUsage.Kind.DOCUMENT -> Triple(R.drawable.ic_document, cOrange, R.string.storage_kind_docs)
        }
        return usageRow(
            leading = iconTile(icon, color),
            title = getString(label),
            subtitle = resources.getQuantityString(R.plurals.storage_files, k.count, k.count),
            size = StorageUsage.formatSize(k.bytes),
            fraction = k.bytes.toFloat() / maxBytes,
            barColor = color,
            chevron = k.count > 0,
            onClick = if (k.count > 0) {
                {
                    val key = when (k.kind) {
                        StorageUsage.Kind.IMAGE -> "image"
                        StorageUsage.Kind.VIDEO -> "video"
                        StorageUsage.Kind.VOICE -> "voice"
                        StorageUsage.Kind.DOCUMENT -> "document"
                    }
                    startActivity(Intent(this, MediaGalleryActivity::class.java)
                        .putExtra("conversationId", "ALL")
                        .putExtra("kind", key))
                }
            } else null
        )
    }

    private fun chatRow(conv: StorageUsage.ConvDetail, info: ChatInfo?, maxBytes: Long): View {
        val name = info?.name ?: getString(R.string.unknown_chat)
        val sz = (40 * dp).toInt()
        val avatar = ImageView(this).apply {
            clipToOutline = true
            background = ContextCompat.getDrawable(this@StorageSettingsActivity, R.drawable.bg_avatar_circle)
            layoutParams = LinearLayout.LayoutParams(sz, sz).apply { marginEnd = (14 * dp).toInt() }
        }
        when {
            info == null -> AvatarHelper.setLetterAvatar(this, avatar, name, "#5288C1")
            info.isGroup -> AvatarHelper.loadGroupAvatar(this, avatar, name, info.groupColor, info.avatarUrl, sz, info.id)
            !info.avatarUrl.isNullOrEmpty() -> {
                val fallback = android.graphics.drawable.BitmapDrawable(resources,
                    AvatarHelper.createLetterBitmap(AvatarHelper.initialOf(name),
                        AvatarHelper.getContactColor(this, info.otherHash ?: ""), sz))
                com.bumptech.glide.Glide.with(this).load(info.avatarUrl).circleCrop()
                    .placeholder(fallback).error(fallback).into(avatar)
            }
            else -> AvatarHelper.setLetterAvatar(this, avatar, name,
                AvatarHelper.getContactColor(this, info.otherHash ?: ""))
        }
        return usageRow(
            leading = avatar,
            title = name,
            subtitle = resources.getQuantityString(R.plurals.storage_files, conv.count, conv.count),
            size = StorageUsage.formatSize(conv.bytes),
            fraction = conv.bytes.toFloat() / maxBytes,
            barColor = cAccent,
            chevron = true
        ) {
            startActivity(Intent(this, MediaGalleryActivity::class.java)
                .putExtra("conversationId", info?.id ?: conv.conversationId)
                .putExtra("otherUserName", name))
        }
    }

    private fun usageRow(
        leading: View, title: String, subtitle: String, size: String,
        fraction: Float, barColor: Int, chevron: Boolean, onClick: (() -> Unit)?
    ): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((14 * dp).toInt(), (12 * dp).toInt(), (12 * dp).toInt(), (12 * dp).toInt())
            if (onClick != null) {
                background = ContextCompat.getDrawable(this@StorageSettingsActivity, R.drawable.bg_menu_ripple)
                isClickable = true
                setOnClickListener { onClick() }
            }
        }
        row.addView(leading)

        val middle = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val line = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        }
        line.addView(TextView(this).apply {
            text = title; textSize = 15.5f; setTextColor(cPrimary)
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, -2, 1f))
        line.addView(TextView(this).apply {
            text = size; textSize = 14f; setTextColor(cPrimary); typeface = Typeface.DEFAULT_BOLD
            setPadding((8 * dp).toInt(), 0, 0, 0)
        })
        middle.addView(line)
        middle.addView(TextView(this).apply {
            text = subtitle; textSize = 12.5f; setTextColor(cSecondary)
            setPadding(0, (1 * dp).toInt(), 0, (7 * dp).toInt())
        })

        val track = FrameLayout(this).apply { background = rounded(cTrack, 3f) }
        val f = fraction.coerceIn(0f, 1f)
        val fillRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        if (f > 0f) {
            fillRow.addView(View(this).apply { background = rounded(barColor, 3f) },
                LinearLayout.LayoutParams(0, -1, maxOf(f, 0.02f)))
        }
        fillRow.addView(View(this), LinearLayout.LayoutParams(0, -1, (1f - maxOf(f, 0.02f)).coerceAtLeast(0.0001f)))
        track.addView(fillRow, FrameLayout.LayoutParams(-1, -1))
        middle.addView(track, LinearLayout.LayoutParams(-1, (5 * dp).toInt()))

        row.addView(middle, LinearLayout.LayoutParams(0, -2, 1f))
        if (chevron) {
            row.addView(ImageView(this).apply {
                setImageResource(R.drawable.ic_chevron_right); setColorFilter(cSecondary)
            }, LinearLayout.LayoutParams((20 * dp).toInt(), (20 * dp).toInt()).apply {
                marginStart = (8 * dp).toInt()
            })
        }
        return row
    }

    private fun actionRow(
        icon: Int, color: Int, title: String, subtitle: String, value: String, onClick: () -> Unit
    ) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding((14 * dp).toInt(), (13 * dp).toInt(), (12 * dp).toInt(), (13 * dp).toInt())
        background = ContextCompat.getDrawable(this@StorageSettingsActivity, R.drawable.bg_menu_ripple)
        isClickable = true
        setOnClickListener { onClick() }
        addView(iconTile(icon, color))
        addView(LinearLayout(this@StorageSettingsActivity).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@StorageSettingsActivity).apply {
                text = title; textSize = 15.5f; setTextColor(cPrimary)
            })
            addView(TextView(this@StorageSettingsActivity).apply {
                text = subtitle; textSize = 12.5f; setTextColor(cSecondary)
                setPadding(0, (2 * dp).toInt(), 0, 0)
            })
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = (8 * dp).toInt() })
        addView(TextView(this@StorageSettingsActivity).apply {
            text = value; textSize = 13.5f; setTextColor(cSecondary)
        })
        addView(ImageView(this@StorageSettingsActivity).apply {
            setImageResource(R.drawable.ic_chevron_right); setColorFilter(cSecondary)
        }, LinearLayout.LayoutParams((20 * dp).toInt(), (20 * dp).toInt()).apply {
            marginStart = (6 * dp).toInt()
        })
    }

    private fun emptyCard() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        background = rounded(cCard, 18f)
        setPadding((20 * dp).toInt(), (26 * dp).toInt(), (20 * dp).toInt(), (24 * dp).toInt())
        addView(ImageView(this@StorageSettingsActivity).apply {
            setImageResource(R.drawable.ic_image); setColorFilter(cSecondary)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL; setColor(cTrack)
            }
            val p = (13 * dp).toInt(); setPadding(p, p, p, p)
        }, LinearLayout.LayoutParams((52 * dp).toInt(), (52 * dp).toInt()).apply {
            bottomMargin = (12 * dp).toInt()
        })
        addView(TextView(this@StorageSettingsActivity).apply {
            text = getString(R.string.no_saved_media); textSize = 15.5f
            setTextColor(cPrimary); typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
        })
        addView(TextView(this@StorageSettingsActivity).apply {
            text = getString(R.string.storage_empty_desc); textSize = 13f
            setTextColor(cSecondary); gravity = Gravity.CENTER
            setPadding(0, (4 * dp).toInt(), 0, 0)
        })
    }

    private fun confirmClearCache(bytes: Long) {
        val sheet = com.google.android.material.bottomsheet.BottomSheetDialog(this, R.style.Theme_SinusChat_BottomSheet)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding((24 * dp).toInt(), (12 * dp).toInt(), (24 * dp).toInt(), (20 * dp).toInt())
        }
        root.addView(View(this).apply {
            background = rounded(pl.sinusoide.sinuschat.ui.ThemeUi.handle(context), 3f)
        }, LinearLayout.LayoutParams((36 * dp).toInt(), (4 * dp).toInt()).apply {
            bottomMargin = (20 * dp).toInt()
        })
        root.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_delete); setColorFilter(cRed)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor((cRed and 0x00FFFFFF) or 0x26000000)
            }
            val p = (16 * dp).toInt(); setPadding(p, p, p, p)
        }, LinearLayout.LayoutParams((64 * dp).toInt(), (64 * dp).toInt()).apply {
            bottomMargin = (16 * dp).toInt()
        })
        root.addView(TextView(this).apply {
            text = getString(R.string.storage_cache_clear_q)
            textSize = 20f; setTextColor(cPrimary)
            typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
        })
        root.addView(TextView(this).apply {
            text = getString(R.string.storage_cache_clear_desc)
            textSize = 14f; setTextColor(cSecondary); gravity = Gravity.CENTER
            setPadding(0, (8 * dp).toInt(), 0, (22 * dp).toInt())
        })
        root.addView(TextView(this).apply {
            text = getString(R.string.storage_clear) + " · " + StorageUsage.formatSize(bytes)
            textSize = 16f; setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
            background = rounded(cRed, 26f)
            setPadding(0, (14 * dp).toInt(), 0, (14 * dp).toInt())
            isClickable = true
            setOnClickListener {
                sheet.dismiss()
                AppExecutor.io {
                    val freed = StorageUsage.clearCache(applicationContext)
                    AppExecutor.main {
                        if (isFinishing || isDestroyed) return@main
                        PopupHelper.show(this@StorageSettingsActivity,
                            if (freed > 0) getString(R.string.storage_freed, StorageUsage.formatSize(freed))
                            else getString(R.string.storage_cache_empty))
                        load()
                    }
                }
            }
        }, LinearLayout.LayoutParams(-1, -2))
        root.addView(TextView(this).apply {
            text = getString(R.string.cancel)
            textSize = 15f; setTextColor(cSecondary); gravity = Gravity.CENTER
            setPadding(0, (14 * dp).toInt(), 0, (4 * dp).toInt())
            isClickable = true
            setOnClickListener { sheet.dismiss() }
        }, LinearLayout.LayoutParams(-1, -2))
        sheet.setContentView(root)
        pl.sinusoide.sinuschat.ui.FlowGlass.show(sheet)
    }

    private fun rounded(color: Int, radiusDp: Float) = pl.sinusoide.sinuschat.ui.ThemeUi.rounded(this, color, radiusDp)

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(cCard, 18f)
        clipToOutline = true
    }

    private fun divider() = View(this).apply {
        setBackgroundColor(pl.sinusoide.sinuschat.ui.ThemeUi.divider(context))
        layoutParams = LinearLayout.LayoutParams(-1, (1 * dp).toInt().coerceAtLeast(1)).apply {
            marginStart = (68 * dp).toInt()
        }
    }

    private fun sectionTitle(text: String) = TextView(this).apply {
        this.text = text.uppercase()
        textSize = 12f; setTextColor(cAccent); typeface = Typeface.DEFAULT_BOLD
        letterSpacing = 0.06f
        setPadding((6 * dp).toInt(), (22 * dp).toInt(), 0, (8 * dp).toInt())
    }

    private fun iconTile(icon: Int, color: Int) = ImageView(this).apply {
        setImageResource(icon); setColorFilter(color)
        background = GradientDrawable().apply {
            cornerRadius = 12 * dp
            setColor((color and 0x00FFFFFF) or 0x24000000)
        }
        val p = (9 * dp).toInt(); setPadding(p, p, p, p)
        layoutParams = LinearLayout.LayoutParams((40 * dp).toInt(), (40 * dp).toInt()).apply {
            marginEnd = (14 * dp).toInt()
        }
    }

    private data class ChatInfo(
        val id: String,
        val name: String,
        val isGroup: Boolean,
        val otherHash: String?,
        val avatarUrl: String?,
        val groupColor: String
    )

    private fun buildNameMap(): Map<String, ChatInfo> {
        val map = HashMap<String, ChatInfo>()
        val arr: JSONArray = ConversationCache.load(this) ?: return map
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id")
            if (id.isEmpty()) continue
            val isGroup = o.optBoolean("is_group", false)
            val name = if (isGroup)
                (o.optStringOrNull("group_name") ?: "").ifEmpty { getString(R.string.group) }
            else
                pl.sinusoide.sinuschat.util.ContactCache.bestName(this, o.optString("other_hash"), o.optStringOrNull("other_name") ?: "")
                    .ifEmpty { o.optString("other_hash").take(8) }
            val info = ChatInfo(
                id = id,
                name = name,
                isGroup = isGroup,
                otherHash = if (isGroup) null else o.optString("other_hash"),
                avatarUrl = if (isGroup) o.optStringOrNull("avatar_url")
                    else pl.sinusoide.sinuschat.util.ContactCache.getAvatarUrl(this, o.optString("other_hash"))
                        ?: o.optStringOrNull("avatar_url")?.takeIf { !pl.sinusoide.sinuschat.crypto.ProfileCrypto.isEncryptedAvatar(it) },
                groupColor = o.optStringOrNull("group_avatar_color") ?: "#5288C1"
            )
            map[id.replace(Regex("[^A-Za-z0-9_-]"), "_")] = info
        }
        return map
    }
}
