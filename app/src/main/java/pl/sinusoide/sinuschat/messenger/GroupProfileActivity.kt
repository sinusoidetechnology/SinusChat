package pl.sinusoide.sinuschat.messenger

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.bumptech.glide.Glide
import com.bumptech.glide.signature.ObjectKey
import pl.sinusoide.sinuschat.R
import pl.sinusoide.sinuschat.ScreenLockActivity
import pl.sinusoide.sinuschat.crypto.DbKeyManager
import pl.sinusoide.sinuschat.setup.SetupActivity
import pl.sinusoide.sinuschat.util.AppExecutor
import pl.sinusoide.sinuschat.util.AvatarHelper
import pl.sinusoide.sinuschat.util.DisappearingMessages
import pl.sinusoide.sinuschat.util.PopupHelper
import androidx.activity.enableEdgeToEdge

class GroupProfileActivity : AppCompatActivity() {
    private val tc by lazy { pl.sinusoide.sinuschat.ui.ThemeColors(this) }
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(pl.sinusoide.sinuschat.util.LocaleManager.wrap(newBase))
    }


    private lateinit var groupId: String
    private lateinit var groupName: String
    private lateinit var myHash: String
    private lateinit var membersContainer: LinearLayout
    private lateinit var subtitleView: TextView
    private lateinit var avatarView: ImageView
    private lateinit var nameView: TextView
    private val currentMemberHashes = ArrayList<String>()
    private val dp get() = resources.displayMetrics.density

    override fun onCreate(savedInstanceState: Bundle?) {
        if (DbKeyManager.needsPinUnlock(this)) {
            ScreenLockActivity.start(this, ScreenLockActivity.MODE_UNLOCK)
            super.onCreate(null)
            finish()
            return
        }
        
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        groupId   = intent.getStringExtra("conversationId") ?: ""
        groupName = intent.getStringExtra("groupName") ?: getString(R.string.group)
        myHash    = SetupActivity.getUserPhoneHash(this)
        buildUI()
        loadGroupInfo()
    }

    private fun buildUI() {
        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(tc.bg)
        }
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(outer) { v, insets ->
            val b = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            val top = maxOf(b.top, insets.getInsetsIgnoringVisibility(androidx.core.view.WindowInsetsCompat.Type.statusBars()).top)
            v.setPadding(0, top, 0, b.bottom); insets
        }
        androidx.core.view.ViewCompat.requestApplyInsets(outer)

        outer.addView(ImageButton(this).apply {
            setImageResource(R.drawable.ic_back)
            setBackgroundResource(0); setColorFilter(tc.text)
            setPadding((14*dp).toInt(), (14*dp).toInt(), (14*dp).toInt(), (14*dp).toInt())
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams((52*dp).toInt(), (52*dp).toInt()))

        val root = ScrollView(this)
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(content)
        outer.addView(root, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(outer)

        val center = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            setPadding(0, (8*dp).toInt(), 0, (20*dp).toInt())
        }
        avatarView = ImageView(this).apply {
            setImageBitmap(AvatarHelper.createLetterBitmap(
                AvatarHelper.initialOf(groupName).takeIf { it != "?" } ?: "G", "#5288C1", (110*dp).toInt()))
        }
        center.addView(avatarView, LinearLayout.LayoutParams((110*dp).toInt(), (110*dp).toInt()).apply {
            bottomMargin = (14*dp).toInt()
        })
        nameView = TextView(this).apply {
            text = groupName; textSize = 24f; setTextColor(tc.text)
            gravity = Gravity.CENTER; typeface = Typeface.DEFAULT_BOLD
        }
        center.addView(nameView)
        avatarView.setOnClickListener { showFullScreenGroupIcon() }
        subtitleView = TextView(this).apply {
            text = getString(R.string.group); textSize = 14f; setTextColor(tc.text2)
            gravity = Gravity.CENTER; setPadding(0, (4*dp).toInt(), 0, 0)
        }
        center.addView(subtitleView)
        content.addView(center)

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            setPadding(0, 0, 0, (20*dp).toInt())
        }
        actionAudio = actionButton(getString(R.string.call_audio), R.drawable.ic_call_answer) { startGroupCall(false) }
            .also { actions.addView(it) }
        actionVideo = actionButton(getString(R.string.action_video), R.drawable.ic_call_video_on) { startGroupCall(true) }
            .also { actions.addView(it) }
        actionAdd = actionButton(getString(R.string.add), R.drawable.ic_person_add) { showAddMemberDialog() }
            .also { actions.addView(it) }
        actions.addView(actionButton(getString(R.string.search), R.drawable.ic_search) { openGroupSearch() })
        content.addView(actions)

        content.addView(separator())

        editRow = bottomRow(getString(R.string.edit_group_name_icon), R.drawable.ic_edit, false,
            tc.accent) { showEditGroupDialog() }.also {
            it.visibility = View.GONE
            content.addView(it)
        }

        val disRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding((16*dp).toInt(), (14*dp).toInt(), (16*dp).toInt(), (14*dp).toInt())
            isClickable = true
            setOnClickListener { showDisappearingPicker() }
        }
        disRow.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_timer); setColorFilter(tc.accent)
        }, LinearLayout.LayoutParams((24*dp).toInt(), (24*dp).toInt()).apply { marginEnd = (18*dp).toInt() })
        val disCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        disCol.addView(TextView(this).apply {
            text = getString(R.string.disappearing_messages); textSize = 16f; setTextColor(tc.text)
        })
        disappearingSubtitle = TextView(this).apply {
            text = getString(R.string.disabled); textSize = 13f; setTextColor(tc.text2)
        }
        disCol.addView(disappearingSubtitle)
        disRow.addView(disCol)
        disRow.visibility = View.GONE
        disappearingRow = disRow
        content.addView(disRow)

        val permRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding((16*dp).toInt(), (14*dp).toInt(), (16*dp).toInt(), (14*dp).toInt())
            isClickable = true
            setOnClickListener {
                if (amIAdmin) showPermissionsDialog()
                else PopupHelper.show(this@GroupProfileActivity,
                    getString(R.string.only_admin_change))
            }
        }
        permRow.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_settings); setColorFilter(tc.accent)
        }, LinearLayout.LayoutParams((24*dp).toInt(), (24*dp).toInt()).apply { marginEnd = (18*dp).toInt() })
        val permCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        permCol.addView(TextView(this).apply {
            text = getString(R.string.group_permissions); textSize = 16f; setTextColor(tc.text)
        })
        permissionsSubtitle = TextView(this).apply {
            text = getString(R.string.who_can_edit); textSize = 13f; setTextColor(tc.text2)
        }
        permCol.addView(permissionsSubtitle)
        permRow.addView(permCol)
        permRow.visibility = View.GONE
        permissionsRow = permRow
        content.addView(permRow)

        val invRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding((16*dp).toInt(), (14*dp).toInt(), (16*dp).toInt(), (14*dp).toInt())
            isClickable = true
            setOnClickListener {
                GroupInviteSheet(this@GroupProfileActivity, groupId, currentName.ifEmpty { groupName }) { active ->
                    hasInviteLink = active
                    updateInviteSubtitle()
                }.show()
            }
        }
        invRow.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_link); setColorFilter(0xFF2ECC71.toInt())
        }, LinearLayout.LayoutParams((24*dp).toInt(), (24*dp).toInt()).apply { marginEnd = (18*dp).toInt() })
        val invCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        invCol.addView(TextView(this).apply {
            text = getString(R.string.group_invite_title); textSize = 16f; setTextColor(tc.text)
        })
        inviteSubtitle = TextView(this).apply {
            text = getString(R.string.group_invite_sub_off); textSize = 13f; setTextColor(tc.text2)
        }
        invCol.addView(inviteSubtitle)
        invRow.addView(invCol)
        invRow.visibility = View.GONE
        inviteRow = invRow
        content.addView(invRow)

        val muteRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding((16*dp).toInt(), (14*dp).toInt(), (16*dp).toInt(), (14*dp).toInt())
            isClickable = true
            setOnClickListener { showMuteOptions() }
        }
        muteRow.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_notifications_off); setColorFilter(tc.accent)
        }, LinearLayout.LayoutParams((24*dp).toInt(), (24*dp).toInt()).apply { marginEnd = (18*dp).toInt() })
        val muteCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        muteCol.addView(TextView(this).apply {
            text = getString(R.string.notifications); textSize = 16f; setTextColor(tc.text)
        })
        muteSubtitle = TextView(this).apply {
            text = pl.sinusoide.sinuschat.util.MuteSettings.label(this@GroupProfileActivity, 
                pl.sinusoide.sinuschat.util.MuteSettings.getMutedUntil(this@GroupProfileActivity, groupId))
            textSize = 13f; setTextColor(tc.text2)
        }
        muteCol.addView(muteSubtitle)
        muteRow.addView(muteCol)
        content.addView(muteRow)

        pl.sinusoide.sinuschat.ui.TranslateBlockRow.create(this, groupId, true, "",
            { currentName.ifEmpty { groupName } }, tc.text, tc.text2, tc.accent)?.let { content.addView(it) }

        content.addView(bottomRow(getString(R.string.media_links_docs), R.drawable.ic_image, false, tc.accent) {
            startActivity(android.content.Intent(this, MediaGalleryActivity::class.java).apply {
                putExtra("conversationId", groupId)
                putExtra("otherUserName", groupName)
            })
        })

        content.addView(separator())

        val membersHeader = TextView(this).apply {
            text = getString(R.string.members); textSize = 13f; setTextColor(tc.text2)
            setPadding((16*dp).toInt(), (14*dp).toInt(), (16*dp).toInt(), (8*dp).toInt())
        }
        content.addView(membersHeader)

        membersContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(membersContainer)

        content.addView(separator())

        content.addView(bottomRow(getString(R.string.clear_chat), R.drawable.ic_delete, false, 0xFFF39C12.toInt()) {
            confirmClearChat()
        })
        content.addView(bottomRow(getString(R.string.leave_group), R.drawable.ic_leave, true) {
            confirmLeave()
        })
    }

    private fun actionButton(label: String, icon: Int, onClick: () -> Unit): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            setPadding((14*dp).toInt(), 0, (14*dp).toInt(), 0)
            isClickable = true
            setOnClickListener { onClick() }
        }
        col.addView(ImageView(this).apply {
            setImageResource(icon); setColorFilter(tc.text)
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(tc.input)
            }
            setPadding((14*dp).toInt(), (14*dp).toInt(), (14*dp).toInt(), (14*dp).toInt())
        }, LinearLayout.LayoutParams((52*dp).toInt(), (52*dp).toInt()))
        col.addView(TextView(this).apply {
            text = label; textSize = 12f; setTextColor(tc.text2)
            gravity = Gravity.CENTER; setPadding(0, (6*dp).toInt(), 0, 0)
        })
        return col
    }

    private fun separator(): View = View(this).apply {
        setBackgroundColor(tc.input)
        layoutParams = LinearLayout.LayoutParams(-1, (1*dp).toInt()).apply {
            topMargin = (4*dp).toInt(); bottomMargin = (4*dp).toInt()
        }
    }

    private fun bottomRow(label: String, icon: Int, danger: Boolean, iconTint: Int? = null, onClick: () -> Unit): View {
        val color = if (danger) 0xFFFF4444.toInt() else tc.text
        val iconColor = iconTint ?: color
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding((16*dp).toInt(), (14*dp).toInt(), (16*dp).toInt(), (14*dp).toInt())
            isClickable = true
            setOnClickListener { onClick() }
            addView(ImageView(context).apply {
                setImageResource(icon); setColorFilter(iconColor)
            }, LinearLayout.LayoutParams((24*dp).toInt(), (24*dp).toInt()).apply {
                marginEnd = (18*dp).toInt()
            })
            addView(TextView(context).apply {
                text = label; textSize = 16f; setTextColor(color)
            })
        }
    }

    private fun loadGroupInfo() {
        GroupRepository.getGroupInfo(this, groupId) { info ->
            if (info == null) return@getGroupInfo
            amIAdmin = info.amIAdmin
            currentAvatarUrl = info.avatarUrl
            AvatarHelper.loadGroupAvatar(this, avatarView, info.name, info.avatarColor,
                info.avatarUrl, (110 * dp).toInt(), groupId)
            nameView.text = info.name
            subtitleView.text = getString(R.string.group_members_count, info.members.size)
            currentMemberHashes.clear()
            currentMemberHashes.addAll(info.members.map { it.userHash })

            disappearingSubtitle?.text = pl.sinusoide.sinuschat.util.DisappearingMessages.label(this@GroupProfileActivity, info.disappearingTtlMs)
            currentPerms = info.perms
            isCommunity = info.isCommunity
            hasInviteLink = info.hasInviteLink
            updateInviteSubtitle()
            currentName = info.name
            currentAvatarColor = info.avatarColor
            applyPermissions()
            currentTtlMs = info.disappearingTtlMs
            pl.sinusoide.sinuschat.util.DisappearingMessages.setTtl(this, groupId, info.disappearingTtlMs)

            permissionsSubtitle?.text = permsSummary(info.perms)

            muteSubtitle?.text = pl.sinusoide.sinuschat.util.MuteSettings.label(this, 
                pl.sinusoide.sinuschat.util.MuteSettings.getMutedUntil(this, groupId))

            membersContainer.removeAllViews()
            if (canAdd()) membersContainer.addView(addMembersRow())
            for (member in info.members) {
                membersContainer.addView(memberRow(member, info.createdBy))
            }
        }
    }

    private fun addMembersRow(): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        setPadding((16*dp).toInt(), (12*dp).toInt(), (16*dp).toInt(), (12*dp).toInt())
        isClickable = true
        setOnClickListener { showAddMemberDialog() }
        addView(ImageView(context).apply {
            setImageResource(R.drawable.ic_person_add); setColorFilter(Color.WHITE)
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(0xFF2ECC71.toInt())
            }
            setPadding((10*dp).toInt(), (10*dp).toInt(), (10*dp).toInt(), (10*dp).toInt())
        }, LinearLayout.LayoutParams((44*dp).toInt(), (44*dp).toInt()).apply {
            marginEnd = (14*dp).toInt()
        })
        addView(TextView(context).apply {
            text = getString(R.string.add_members); textSize = 16f; setTextColor(tc.text)
        })
    }

    private fun memberRow(member: GroupMember, createdBy: String): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding((16*dp).toInt(), (10*dp).toInt(), (16*dp).toInt(), (10*dp).toInt())
            background = ContextCompat.getDrawable(this@GroupProfileActivity, R.drawable.bg_menu_ripple)
            isClickable = true
        }

        val avatarImg = ImageView(this).apply {
            clipToOutline = true
            background = ContextCompat.getDrawable(
                this@GroupProfileActivity, R.drawable.bg_avatar_circle)
        }

        val avatarSize = (44 * dp).toInt()
        val color = when {
            member.userHash == myHash && AvatarHelper.hasMyColor(this) -> AvatarHelper.getMyColor(this)
            else -> member.avatarColor
        }
        
        if (!member.avatarUrl.isNullOrEmpty()) {
            val fallback = AvatarHelper.createLetterBitmap(
                AvatarHelper.initialOf(member.displayName),
                color,
                avatarSize
            ).let { BitmapDrawable(resources, it) }

            Glide.with(this)
                .load(member.avatarUrl)
                .signature(ObjectKey(member.avatarUrl))
                .circleCrop()
                .placeholder(fallback)
                .error(fallback)
                .into(avatarImg)
        } else if (member.userHash == myHash) {
            if (!AvatarHelper.loadAvatarInto(this, avatarImg)) {
                AvatarHelper.setLetterAvatar(this, avatarImg, member.displayName, color)
            }
        } else {
            AvatarHelper.setLetterAvatar(this, avatarImg, member.displayName, color)
        }

        row.addView(avatarImg, LinearLayout.LayoutParams(avatarSize, avatarSize).apply {
            marginEnd = (14*dp).toInt()
        })
        
        row.addView(TextView(this).apply {
            text = if (member.userHash == myHash) getString(R.string.you) else member.displayName
            textSize = 16f; setTextColor(tc.text)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        if (member.isAdmin) {
            row.addView(TextView(this).apply {
                text = getString(R.string.group_admin); textSize = 12f
                setTextColor(0xFF2ECC71.toInt())
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                    cornerRadius = 12f * dp; setColor(0x332ECC71)
                }
                setPadding((10*dp).toInt(), (5*dp).toInt(), (10*dp).toInt(), (5*dp).toInt())
            })
        }
        row.setOnLongClickListener {
            showMemberActions(member, createdBy); true
        }
        return row
    }

    private var amIAdmin = false
    private var isCommunity = false
    private var hasInviteLink = false
    private var inviteRow: View? = null
    private var inviteSubtitle: TextView? = null

    private fun updateInviteSubtitle() {
        inviteSubtitle?.text = getString(if (hasInviteLink) R.string.group_invite_sub_on else R.string.group_invite_sub_off)
    }

    private var actionAudio: View? = null
    private var actionVideo: View? = null
    private var actionAdd: View? = null
    private var editRow: View? = null
    private var currentName = ""
    private var currentAvatarColor = "#5288C1"
    private var currentAvatarUrl: String? = null

    private var onGroupPhotoPicked: ((android.net.Uri) -> Unit)? = null

    private var photoFlowOpen = false

    private fun launchGroupPhotoPicker() {
        if (photoFlowOpen) return
        photoFlowOpen = true
        try { pickGroupPhoto.launch("image/*") } catch (_: Exception) { photoFlowOpen = false }
    }

    private val pickGroupPhoto = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri == null) { photoFlowOpen = false; return@registerForActivityResult }
        try {
            cropGroupPhoto.launch(Intent(this, pl.sinusoide.sinuschat.util.CropActivity::class.java)
                .putExtra(pl.sinusoide.sinuschat.util.CropActivity.EXTRA_URI, uri))
        } catch (_: Exception) { photoFlowOpen = false }
    }

    private val cropGroupPhoto = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        photoFlowOpen = false
        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        val out = result.data?.getStringExtra(pl.sinusoide.sinuschat.util.CropActivity.EXTRA_OUTPUT)
            ?: return@registerForActivityResult
        onGroupPhotoPicked?.invoke(android.net.Uri.parse(out))
    }

    private fun canEdit() = amIAdmin || currentPerms.editSettings
    private fun canAdd()  = amIAdmin || currentPerms.addMembers
    private fun canCall() = amIAdmin || currentPerms.callGroup

    private fun applyPermissions() {
        actionAudio?.visibility = if (canCall()) View.VISIBLE else View.GONE
        actionVideo?.visibility = if (canCall()) View.VISIBLE else View.GONE
        actionAdd?.visibility   = if (canAdd())  View.VISIBLE else View.GONE
        editRow?.visibility     = if (canEdit()) View.VISIBLE else View.GONE
        disappearingRow?.visibility = if (canEdit()) View.VISIBLE else View.GONE
        permissionsRow?.visibility  = if (amIAdmin) View.VISIBLE else View.GONE
        inviteRow?.visibility = if (amIAdmin && isCommunity) View.VISIBLE else View.GONE
    }

    private fun showFullScreenGroupIcon() {
        val m = resources.displayMetrics
        val size = minOf(minOf(m.widthPixels, m.heightPixels), (420 * dp).toInt())
            .coerceAtLeast((160 * dp).toInt())
        val name = currentName.ifEmpty { groupName }
        val letterBmp = AvatarHelper.createLetterBitmap(
            AvatarHelper.initialOf(name).takeIf { it != "?" } ?: "G", currentAvatarColor, size)
        pl.sinusoide.sinuschat.ui.PhotoViewer.show(this) { img ->
            img.setImageBitmap(letterBmp)
            currentAvatarUrl?.takeIf { it.isNotEmpty() }?.let { url ->
                val ph = BitmapDrawable(resources, letterBmp)
                Glide.with(this).load(url).signature(ObjectKey(url)).placeholder(ph).error(ph).into(img)
            }
        }
    }

    private fun showEditGroupDialog() {
        if (!canEdit()) return
        val d = dp
        val accent = tc.accent
        val secondary = tc.text2
        val maxLen = 50
        var pickedColor = currentAvatarColor
        var pendingPhoto: android.net.Uri? = null
        var removePhoto = false
        var uploadedUrl: String? = null
        fun hasPhoto() = pendingPhoto != null || (!removePhoto && !currentAvatarUrl.isNullOrEmpty())

        val sheet = com.google.android.material.bottomsheet.BottomSheetDialog(this, R.style.Theme_SinusChat_BottomSheet_Input)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding((24 * d).toInt(), (12 * d).toInt(), (24 * d).toInt(), (20 * d).toInt())
        }

        root.addView(View(this).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 3 * d; setColor((tc.text2 and 0x00FFFFFF) or 0x55000000)
            }
        }, LinearLayout.LayoutParams((36 * d).toInt(), (4 * d).toInt()).apply {
            bottomMargin = (18 * d).toInt()
        })

        root.addView(TextView(this).apply {
            text = getString(R.string.edit_group_name_icon)
            textSize = 20f; setTextColor(tc.text); typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        })
        root.addView(TextView(this).apply {
            text = getString(R.string.edit_group_sheet_desc)
            textSize = 13.5f; setTextColor(secondary); gravity = Gravity.CENTER
            setPadding(0, (6 * d).toInt(), 0, (20 * d).toInt())
        })

        val previewSize = (104 * d).toInt()
        val halo = android.widget.FrameLayout(this).apply {
            val p = (8 * d).toInt(); setPadding(p, p, p, p)
        }
        val preview = ImageView(this)
        halo.addView(preview, android.widget.FrameLayout.LayoutParams(previewSize, previewSize))
        root.addView(halo, LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = (10 * d).toInt() })

        fun chip(label: String, icon: Int, tint: Int) = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 20 * d; setColor(pl.sinusoide.sinuschat.ui.ThemeUi.card(this@GroupProfileActivity))
            }
            setPadding((14 * d).toInt(), (8 * d).toInt(), (16 * d).toInt(), (8 * d).toInt())
            isClickable = true
            addView(ImageView(this@GroupProfileActivity).apply {
                setImageResource(icon); setColorFilter(tint)
            }, LinearLayout.LayoutParams((18 * d).toInt(), (18 * d).toInt()).apply {
                marginEnd = (8 * d).toInt()
            })
            addView(TextView(this@GroupProfileActivity).apply {
                text = label; textSize = 13.5f; setTextColor(tint)
            })
        }
        val choosePhotoChip = chip(getString(R.string.group_photo_choose), R.drawable.ic_image, accent)
        val removePhotoChip = chip(getString(R.string.group_photo_remove), R.drawable.ic_delete,
            ContextCompat.getColor(this, R.color.sinuschat_error))
        val chipRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            addView(choosePhotoChip)
            addView(removePhotoChip, LinearLayout.LayoutParams(-2, -2).apply {
                marginStart = (10 * d).toInt()
            })
        }
        root.addView(chipRow, LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = (20 * d).toInt() })

        val nameInput = EditText(this).apply {
            setText(currentName)
            setSelection(text.length)
            textSize = 16f
            setTextColor(tc.text)
            setHintTextColor(tc.muted)
            hint = getString(R.string.group_name_hint)
            isSingleLine = true
            filters = arrayOf(android.text.InputFilter.LengthFilter(maxLen))
            background = null
            setPadding(0, (4 * d).toInt(), 0, (4 * d).toInt())
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
        }
        val counter = TextView(this).apply {
            textSize = 12f; setTextColor(secondary)
        }
        val nameCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((16 * d).toInt(), (10 * d).toInt(), (16 * d).toInt(), (10 * d).toInt())
        }
        fun cardBg(focused: Boolean) = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = 16 * d
            setColor(pl.sinusoide.sinuschat.ui.ThemeUi.card(this@GroupProfileActivity))
            setStroke((1.5f * d).toInt(), if (focused) accent else 0x00000000)
        }
        nameCard.background = cardBg(false)
        val labelRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        labelRow.addView(TextView(this).apply {
            text = getString(R.string.group_name_hint)
            textSize = 12f; setTextColor(accent)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        labelRow.addView(counter)
        nameCard.addView(labelRow)
        nameCard.addView(nameInput, LinearLayout.LayoutParams(-1, -2))
        nameInput.setOnFocusChangeListener { _, has -> nameCard.background = cardBg(has) }
        root.addView(nameCard, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = (18 * d).toInt() })

        root.addView(TextView(this).apply {
            text = getString(R.string.group_icon_color)
            textSize = 12f; setTextColor(secondary)
        }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = (10 * d).toInt() })

        val swatches = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((6 * d).toInt(), (8 * d).toInt(), (6 * d).toInt(), (8 * d).toInt())
            clipChildren = false
            clipToPadding = false
        }
        val swatchViews = mutableListOf<Pair<String, ImageView>>()
        val swatchSize = (40 * d).toInt()

        val saveBtn = TextView(this)

        fun render() {
            val name = nameInput.text.toString()
            val letterBmp = AvatarHelper.createLetterBitmap(
                AvatarHelper.initialOf(name).takeIf { it != "?" } ?: "G", pickedColor, previewSize)
            val photo: Any? = pendingPhoto ?: currentAvatarUrl?.takeIf { !removePhoto && it.isNotEmpty() }
            if (photo != null) {
                val ph = BitmapDrawable(resources, letterBmp)
                Glide.with(preview).load(photo).circleCrop().placeholder(ph).error(ph).into(preview)
            } else {
                Glide.with(preview).clear(preview)
                preview.setImageBitmap(letterBmp)
            }
            removePhotoChip.visibility = if (hasPhoto()) View.VISIBLE else View.GONE
            (choosePhotoChip.getChildAt(1) as? TextView)?.text = getString(
                if (hasPhoto()) R.string.group_photo_change else R.string.group_photo_choose)
            halo.background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                val c = try { Color.parseColor(pickedColor) } catch (_: Exception) { accent }
                setColor((c and 0x00FFFFFF) or 0x33000000)
            }
            for ((hex, v) in swatchViews) {
                val selected = hex.equals(pickedColor, ignoreCase = true)
                v.setImageResource(if (selected) R.drawable.ic_check else 0)
                v.scaleX = if (selected) 1.08f else 1f
                v.scaleY = if (selected) 1.08f else 1f
                (v.background as? android.graphics.drawable.GradientDrawable)?.setStroke(
                    (2.5f * d).toInt(), if (selected) (if (tc.dark) Color.WHITE else tc.text) else 0x00000000)
            }
            counter.text = "${name.length}/$maxLen"
            val trimmed = name.trim()
            val changed = (trimmed != currentName) ||
                !pickedColor.equals(currentAvatarColor, ignoreCase = true) ||
                pendingPhoto != null || (removePhoto && !currentAvatarUrl.isNullOrEmpty())
            val enabled = trimmed.isNotEmpty() && changed
            saveBtn.isEnabled = enabled
            saveBtn.alpha = if (enabled) 1f else 0.45f
        }

        for (hex in AvatarHelper.PALETTE) {
            val c = try { Color.parseColor(hex) } catch (_: Exception) { continue }
            val v = ImageView(this).apply {
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(c)
                }
                setColorFilter(Color.WHITE)
                val p = (10 * d).toInt(); setPadding(p, p, p, p)
                isClickable = true
                contentDescription = hex
                setOnClickListener {
                    pickedColor = hex
                    render()
                }
            }
            v.setTag(R.id.flow_glass_keep, true)
            swatchViews += hex to v
            swatches.addView(v, LinearLayout.LayoutParams(swatchSize, swatchSize).apply {
                marginEnd = (10 * d).toInt()
            })
        }
        root.clipChildren = false
        root.addView(android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            clipToPadding = false
            clipChildren = false
            addView(swatches)
        }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = (16 * d).toInt() })

        saveBtn.apply {
            text = getString(R.string.save)
            textSize = 16f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 26 * d; setColor(accent)
            }
            setPadding(0, (14 * d).toInt(), 0, (14 * d).toInt())
            isClickable = true
        }
        root.addView(saveBtn, LinearLayout.LayoutParams(-1, -2))
        root.addView(TextView(this).apply {
            text = getString(R.string.cancel)
            textSize = 15f; setTextColor(secondary); gravity = Gravity.CENTER
            setPadding(0, (14 * d).toInt(), 0, (4 * d).toInt())
            isClickable = true
            setOnClickListener { sheet.dismiss() }
        }, LinearLayout.LayoutParams(-1, -2))

        onGroupPhotoPicked = { uri ->
            pendingPhoto = uri; removePhoto = false; uploadedUrl = null
            render()
        }
        choosePhotoChip.setOnClickListener { launchGroupPhotoPicker() }
        removePhotoChip.setOnClickListener {
            pendingPhoto = null; removePhoto = true
            render()
        }
        sheet.setOnDismissListener { onGroupPhotoPicked = null }

        nameInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) { render() }
        })
        render()

        saveBtn.setOnClickListener {
            val newName = nameInput.text.toString().trim()
            if (newName.isEmpty()) return@setOnClickListener
            val nameChanged = newName != currentName
            val colorChanged = !pickedColor.equals(currentAvatarColor, ignoreCase = true)
            val photoToUpload = pendingPhoto
            val clearPhoto = removePhoto && photoToUpload == null && !currentAvatarUrl.isNullOrEmpty()
            if (!nameChanged && !colorChanged && photoToUpload == null && !clearPhoto) {
                sheet.dismiss(); return@setOnClickListener
            }

            saveBtn.isEnabled = false; saveBtn.alpha = 0.6f
            saveBtn.text = getString(if (photoToUpload != null) R.string.sending_photo else R.string.save)

            fun fail() {
                saveBtn.isEnabled = true; saveBtn.alpha = 1f
                saveBtn.text = getString(R.string.save)
                PopupHelper.showError(this, getString(R.string.error_save_settings))
            }

            fun commit(newUrl: String?) {
                GroupRepository.updateGroup(this, groupId,
                    name = if (nameChanged) newName else null,
                    avatarUrl = newUrl,
                    avatarColor = if (colorChanged) pickedColor else null) { ok ->
                    if (!ok) { fail(); return@updateGroup }
                    val color = pickedColor
                    currentName = newName; currentAvatarColor = color
                    if (newUrl != null) currentAvatarUrl = newUrl.ifEmpty { null }
                    nameView.text = newName
                    AvatarHelper.loadGroupAvatar(this, avatarView, newName, color,
                        currentAvatarUrl, (110 * dp).toInt(), groupId)
                    pl.sinusoide.sinuschat.util.ContactCache.saveName(this, groupId, newName)
                    pl.sinusoide.sinuschat.util.ContactCache.saveAvatarUrl(this, groupId, currentAvatarUrl)
                    AvatarHelper.setContactColor(this, groupId, color)
                    photoToUpload?.takeIf { it.scheme == "file" }?.path?.let { f ->
                        try { java.io.File(f).delete() } catch (_: Exception) {}
                    }
                    pl.sinusoide.sinuschat.util.GroupSystemMessages.insert(this, groupId,
                        getString(R.string.group_settings_changed_by_me_sys))
                    sheet.dismiss()
                }
            }

            when {
                photoToUpload != null ->
                    uploadedUrl?.let { commit(it) }
                        ?: pl.sinusoide.sinuschat.util.FileUploader.uploadGroupAvatarAsync(this, photoToUpload,
                            pl.sinusoide.sinuschat.crypto.GroupMeta.profileKey(this, groupId)) { url ->
                            if (url.isNullOrEmpty()) { fail(); return@uploadGroupAvatarAsync }
                            uploadedUrl = url
                            commit(url)
                        }
                clearPhoto -> commit("")
                else -> commit(null)
            }
        }

        sheet.setContentView(root)
        sheet.behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
        sheet.behavior.skipCollapsed = true
        pl.sinusoide.sinuschat.ui.FlowGlass.show(sheet)
    }

    private val groupSettingsReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context, intent: Intent) {
            if (intent.getStringExtra("groupId") == groupId) loadGroupInfo()
        }
    }

    override fun onStart() {
        super.onStart()
        val f = android.content.IntentFilter(ChatActivity.GROUP_SETTINGS_CHANGED)
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            registerReceiver(groupSettingsReceiver, f, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(groupSettingsReceiver, f)
        }
    }

    override fun onStop() {
        super.onStop()
        try { unregisterReceiver(groupSettingsReceiver) } catch (_: Exception) {}
    }
    private var permissionsRow: View? = null
    private var permissionsSubtitle: TextView? = null
    private var currentPerms = GroupPerms()

    private fun permsSummary(p: GroupPerms): String {
        val on = listOf(p.editSettings, p.sendMessages, p.addMembers, p.callGroup).count { it }
        return when (on) {
            4 -> getString(R.string.perms_all)
            0 -> getString(R.string.perms_none)
            else -> getString(R.string.perms_some, on, 4)
        }
    }

    private fun showPermissionsDialog() {
        val p = currentPerms
        var editSettings = p.editSettings
        var sendMessages = p.sendMessages
        var addMembers   = p.addMembers
        var callGroup    = p.callGroup

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((8*dp).toInt(), (8*dp).toInt(), (8*dp).toInt(), 0)
        }
        fun row(title: String, desc: String?, initial: Boolean, onChange: (Boolean) -> Unit) {
            val r = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding((12*dp).toInt(), (10*dp).toInt(), (12*dp).toInt(), (10*dp).toInt())
            }
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            col.addView(TextView(this@GroupProfileActivity).apply {
                text = title; textSize = 16f; setTextColor(tc.text)
            })
            if (desc != null) col.addView(TextView(this@GroupProfileActivity).apply {
                text = desc; textSize = 13f; setTextColor(tc.text2)
            })
            r.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
            r.addView(Switch(this).apply {
                isChecked = initial
                setOnCheckedChangeListener { _, v -> onChange(v) }
            })
            root.addView(r)
        }
        row(getString(R.string.edit_group_settings), getString(R.string.group_settings_full),
            editSettings) { editSettings = it }
        row(getString(R.string.sending_new_messages), null, sendMessages) { sendMessages = it }
        row(getString(R.string.adding_members), null, addMembers) { addMembers = it }
        row(getString(R.string.calling_group), null, callGroup) { callGroup = it }

        AlertDialog.Builder(this, R.style.Theme_SinusChat_Dialog)
            .setTitle(R.string.group_permissions)
            .setView(android.widget.ScrollView(this).apply { addView(root) })
            .setPositiveButton(R.string.save) { _, _ ->
                val next = GroupPerms(editSettings, sendMessages, addMembers, callGroup)
                if (next == currentPerms) return@setPositiveButton
                GroupRepository.setPermissions(this, groupId, next) { ok ->
                    if (ok) {
                        currentPerms = next
                        permissionsSubtitle?.text = permsSummary(next)
                        applyPermissions()
                    } else {
                        PopupHelper.showError(this, getString(R.string.error_save_settings))
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private var disappearingRow: View? = null
    private var disappearingSubtitle: TextView? = null
    private var currentTtlMs = 0L

    private fun showDisappearingPicker() {
        if (!canEdit()) {
            PopupHelper.show(this, getString(R.string.only_admin_change))
            return
        }
        disappearingLauncher.launch(
            Intent(this, DisappearingSettingsActivity::class.java)
                .putExtra("conversationId", groupId)
                .putExtra("currentTtlMs", currentTtlMs)
                .putExtra("currentTrigger", DisappearingMessages.getTrigger(this, groupId))
        )
    }

    private val disappearingLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val ttl = result.data?.getLongExtra("selectedTtlMs", 0L) ?: 0L
            val trigger = result.data?.getStringExtra("selectedTrigger") ?: DisappearingMessages.TRIGGER_READ
            applyDisappearing(ttl, trigger)
        }
    }

    private fun applyDisappearing(ttl: Long, trigger: String) {
        GroupRepository.setDisappearing(this, groupId, ttl, trigger) { ok ->
            if (ok) {
                currentTtlMs = ttl
                disappearingSubtitle?.text = DisappearingMessages.fullLabel(this@GroupProfileActivity, ttl, trigger)
                DisappearingMessages.setTtl(this, groupId, ttl, trigger)
                PopupHelper.showSuccess(this, getString(R.string.group_saved))
            } else PopupHelper.showError(this, getString(R.string.error))
        }
    }

    private fun showMemberActions(member: GroupMember, createdBy: String) {
        if (member.userHash == myHash) return
        if (!amIAdmin) {
            PopupHelper.show(this, getString(R.string.only_admin_change))
            return
        }
        if (member.userHash == createdBy) {
            PopupHelper.show(this, getString(R.string.cant_manage_creator))
            return
        }
        val opcje = if (member.isAdmin) arrayOf(getString(R.string.remove_admin), getString(R.string.kick_from_group))
        else arrayOf(getString(R.string.make_group_admin), getString(R.string.kick_from_group))
        AlertDialog.Builder(this, R.style.Theme_SinusChat_Dialog)
            .setTitle(member.displayName)
            .setItems(opcje) { _, which ->
                when (which) {
                    0 -> GroupRepository.setAdmin(this, groupId, member.userHash, !member.isAdmin) { ok ->
                        PopupHelper.show(this, if (ok) getString(R.string.group_updated_ok) else getString(R.string.error))
                        if (ok) loadGroupInfo()
                    }
                    1 -> AlertDialog.Builder(this, R.style.Theme_SinusChat_Dialog)
                        .setTitle(getString(R.string.kick_from_group))
                        .setMessage(getString(R.string.kick_member_q, member.displayName))
                        .setPositiveButton(getString(R.string.kick_word)) { _, _ ->
                            GroupRepository.kickMember(this, groupId, member.userHash) { ok ->
                                PopupHelper.show(this, if (ok) getString(R.string.member_kicked_ok) else getString(R.string.error))
                                if (ok) loadGroupInfo()
                            }
                        }
                        .setNegativeButton(getString(R.string.cancel), null)
                        .show()
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private var muteSubtitle: TextView? = null

    private fun showMuteOptions() {
        val i = android.content.Intent(this, MuteSettingsActivity::class.java).apply {
            putExtra("conversationId", groupId)
            putExtra("currentMutedUntil", pl.sinusoide.sinuschat.util.MuteSettings.getMutedUntil(this@GroupProfileActivity, groupId))
        }
        muteLauncher.launch(i)
    }

    private val muteLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val until = result.data?.getLongExtra("selectedMutedUntil", 0L) ?: 0L
            val before = pl.sinusoide.sinuschat.util.MuteSettings.getMutedUntil(this, groupId)
            pl.sinusoide.sinuschat.util.MuteSettings.setMutedUntil(this, groupId, until)
            muteSubtitle?.text = pl.sinusoide.sinuschat.util.MuteSettings.label(this, until)
            pl.sinusoide.sinuschat.util.MuteSettings.changeMessage(this, before, until)?.let { PopupHelper.show(this, it) }
        }
    }

    private fun startGroupCall(video: Boolean) {
        PopupHelper.show(this, if (video) getString(R.string.group_video_call_soon)
        else getString(R.string.group_voice_call_soon))
    }

    private fun openGroupSearch() {
        ChatActivity.pendingOpenSearch = true
        finish()
    }

    private fun showAddMemberDialog() {
        val existing = ArrayList(currentMemberHashes)
        val i = android.content.Intent(this, AddGroupMembersActivity::class.java).apply {
            putExtra("conversationId", groupId)
            putStringArrayListExtra("existing", existing)
        }
        addMembersLauncher.launch(i)
    }

    private val addMembersLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) loadGroupInfo()
    }

    private fun confirmClearChat() {
        AlertDialog.Builder(this, R.style.Theme_SinusChat_Dialog)
            .setTitle(getString(R.string.clear_chat))
            .setMessage(getString(R.string.delete_all_group_local_q))
            .setPositiveButton(getString(R.string.clear_word)) { _, _ ->
                AppExecutor.io {
                    pl.sinusoide.sinuschat.data.SinusChatDatabase.getInstance(this)
                        .messageDao().clearConversation(groupId)
                    try { pl.sinusoide.sinuschat.util.MediaManager.clearConversationMedia(this, groupId) } catch (_: Exception) {}
                    AppExecutor.main {
                        PopupHelper.show(this, getString(R.string.cleared))
                    }
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun confirmLeave() {
        val d = dp
        val sheet = com.google.android.material.bottomsheet.BottomSheetDialog(this, R.style.Theme_SinusChat_BottomSheet)
        val red = ContextCompat.getColor(this, R.color.sinuschat_error)
        val secondary = tc.text2
        val accent = tc.accent

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding((24 * d).toInt(), (12 * d).toInt(), (24 * d).toInt(), (20 * d).toInt())
        }

        root.addView(View(this).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 3 * d; setColor((tc.text2 and 0x00FFFFFF) or 0x55000000)
            }
        }, LinearLayout.LayoutParams((36 * d).toInt(), (4 * d).toInt()).apply {
            bottomMargin = (20 * d).toInt()
        })

        root.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_leave)
            setColorFilter(red)
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor((red and 0x00FFFFFF) or 0x26000000)
            }
            val p = (16 * d).toInt(); setPadding(p, p, p, p)
        }, LinearLayout.LayoutParams((64 * d).toInt(), (64 * d).toInt()).apply {
            bottomMargin = (16 * d).toInt()
        })

        root.addView(TextView(this).apply {
            text = getString(R.string.leave_group_q, currentName.ifEmpty { groupName })
            textSize = 20f; setTextColor(tc.text); typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        })
        root.addView(TextView(this).apply {
            text = getString(R.string.leave_group_desc)
            textSize = 14f; setTextColor(secondary); gravity = Gravity.CENTER
            setLineSpacing(0f, 1.15f)
            setPadding(0, (8 * d).toInt(), 0, (20 * d).toInt())
        })

        val silentSwitch = com.google.android.material.materialswitch.MaterialSwitch(this).apply {
            isClickable = false
            thumbTintList = android.content.res.ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(Color.WHITE, tc.text2))
            trackTintList = android.content.res.ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(accent, tc.border))
        }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 16 * d; setColor(pl.sinusoide.sinuschat.ui.ThemeUi.card(this@GroupProfileActivity))
            }
            setPadding((16 * d).toInt(), (14 * d).toInt(), (12 * d).toInt(), (14 * d).toInt())
            isClickable = true; isFocusable = true
            foreground = ContextCompat.getDrawable(this@GroupProfileActivity, R.drawable.bg_menu_ripple)
            setOnClickListener { silentSwitch.isChecked = !silentSwitch.isChecked }
        }
        card.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_notifications_off); setColorFilter(accent)
        }, LinearLayout.LayoutParams((22 * d).toInt(), (22 * d).toInt()).apply {
            marginEnd = (14 * d).toInt()
        })
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(TextView(this).apply {
            text = getString(R.string.leave_silently); textSize = 15f; setTextColor(tc.text)
        })
        texts.addView(TextView(this).apply {
            text = getString(R.string.leave_silently_desc); textSize = 12.5f; setTextColor(secondary)
            setPadding(0, (2 * d).toInt(), 0, 0)
        })
        card.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
        card.addView(silentSwitch)
        root.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = (20 * d).toInt() })

        val leaveBtn = TextView(this).apply {
            text = getString(R.string.leave_group)
            textSize = 16f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 26 * d; setColor(red)
            }
            setPadding(0, (14 * d).toInt(), 0, (14 * d).toInt())
            isClickable = true
        }
        root.addView(leaveBtn, LinearLayout.LayoutParams(-1, -2))
        root.addView(TextView(this).apply {
            text = getString(R.string.cancel)
            textSize = 15f; setTextColor(secondary); gravity = Gravity.CENTER
            setPadding(0, (14 * d).toInt(), 0, (4 * d).toInt())
            isClickable = true
            setOnClickListener { sheet.dismiss() }
        }, LinearLayout.LayoutParams(-1, -2))

        leaveBtn.setOnClickListener {
            leaveBtn.isEnabled = false; leaveBtn.alpha = 0.6f
            GroupRepository.leaveGroup(this, groupId, silentSwitch.isChecked) { ok ->
                if (ok) {
                    sheet.dismiss()
                    PopupHelper.show(this, getString(R.string.group_left))
                    finishAffinity()
                } else {
                    leaveBtn.isEnabled = true; leaveBtn.alpha = 1f
                    PopupHelper.showError(this, getString(R.string.error))
                }
            }
        }

        sheet.setContentView(root)
        pl.sinusoide.sinuschat.ui.FlowGlass.show(sheet)
    }
}
