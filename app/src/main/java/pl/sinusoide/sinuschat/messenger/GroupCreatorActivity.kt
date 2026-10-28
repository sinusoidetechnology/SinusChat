package pl.sinusoide.sinuschat.messenger

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.bumptech.glide.Glide
import pl.sinusoide.sinuschat.R
import pl.sinusoide.sinuschat.util.*
import androidx.activity.enableEdgeToEdge

class GroupCreatorActivity : AppCompatActivity() {
    private val tc by lazy { pl.sinusoide.sinuschat.ui.ThemeColors(this) }
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(LocaleManager.wrap(newBase))
    }


    private val selected = LinkedHashMap<String, String>()
    private val selectionSnapshot = LinkedHashMap<String, String>()

    private enum class Screen { STEP1, TYPE, STEP2, PERMISSIONS }
    private var currentScreen = Screen.STEP1
    private var isReturningFromStep2 = false
    private lateinit var resultsContainer: LinearLayout
    private lateinit var selectedChips: LinearLayout
    private lateinit var nextButton: TextView
    private var membersStrip: LinearLayout? = null
    private var membersLabel: TextView? = null
    private val dp get() = resources.displayMetrics.density

    private var selectedAvatarUri: Uri? = null
    private lateinit var avatarView: ImageView

    private var photoFlowOpen = false

    private val cropImage = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        photoFlowOpen = false
        if (result.resultCode == RESULT_OK) {
            val outputUri = result.data?.getStringExtra(pl.sinusoide.sinuschat.util.CropActivity.EXTRA_OUTPUT)
            if (outputUri != null) {
                selectedAvatarUri = Uri.parse(outputUri)
                com.bumptech.glide.Glide.with(this).load(selectedAvatarUri).circleCrop().into(avatarView)
            }
        }
    }

    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            val intent = Intent(this, pl.sinusoide.sinuschat.util.CropActivity::class.java)
            intent.putExtra(pl.sinusoide.sinuschat.util.CropActivity.EXTRA_URI, uri)
            cropImage.launch(intent)
        } else {
            photoFlowOpen = false
        }
    }

    private val disappearingLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val ttl = result.data?.getLongExtra("selectedTtlMs", 0L) ?: 0L
            pendingTtlMs = ttl
            (disappearingRow?.tag as? TextView)?.text = DisappearingMessages.label(this, ttl)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(buildStep1())

        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when (currentScreen) {
                    Screen.PERMISSIONS -> goToStep2()
                    Screen.STEP2 -> showTypeScreen()
                    Screen.TYPE -> { setContentView(buildStep1()); refreshChips() }
                    Screen.STEP1 -> {
                        if (isReturningFromStep2) {
                            selected.clear()
                            selected.putAll(selectionSnapshot)
                            goToStep2()
                        } else finish()
                    }
                }
            }
        })
    }

    private fun applyInsets(root: View) {
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val b = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime())
            v.setPadding(0, b.top, 0, maxOf(b.bottom, ime.bottom))
            insets
        }
        root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                androidx.core.view.ViewCompat.requestApplyInsets(v)
                v.removeOnAttachStateChangeListener(this)
            }
            override fun onViewDetachedFromWindow(v: View) {}
        })
        androidx.core.view.ViewCompat.requestApplyInsets(root)
    }

    private fun buildStep1(): View {
        currentScreen = Screen.STEP1
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(tc.bg)
        }
        applyInsets(root)

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((8*dp).toInt(), (12*dp).toInt(), (12*dp).toInt(), (12*dp).toInt())
        }
        header.addView(android.widget.ImageButton(this).apply {
            setImageResource(R.drawable.ic_back)
            setBackgroundResource(0); setColorFilter(tc.text)
            setPadding((10*dp).toInt(), (10*dp).toInt(), (10*dp).toInt(), (10*dp).toInt())
            setOnClickListener { 
                if (isReturningFromStep2) {
                    selected.clear()
                    selected.putAll(selectionSnapshot)
                    goToStep2()
                } else finish() 
            }
        }, LinearLayout.LayoutParams((44*dp).toInt(), (44*dp).toInt()))
        header.addView(TextView(this).apply {
            text = getString(R.string.add_people); textSize = 19f; setTextColor(tc.text)
            setPadding((8*dp).toInt(), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(TextView(this).apply {
            text = getString(R.string.skip); textSize = 15f; setTextColor(tc.accent)
            setPadding((12*dp).toInt(), (8*dp).toInt(), (12*dp).toInt(), (8*dp).toInt())
            isClickable = true
            visibility = if (isReturningFromStep2) View.GONE else View.VISIBLE
            setOnClickListener { 
                selected.clear()
                showTypeScreen() 
            }
        })
        root.addView(header)

        selectedChips = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding((12*dp).toInt(), 0, (12*dp).toInt(), (8*dp).toInt())
        }
        val chipScroll = android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(selectedChips)
            visibility = View.GONE
        }
        root.addView(chipScroll)

        val search = EditText(this).apply {
            hint = getString(R.string.search_name_email_hint)
            setHintTextColor(tc.text2)
            setTextColor(tc.text)
            textSize = 15f
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = 22f * dp
                setColor(tc.input)
            }
            setPadding((16*dp).toInt(), (12*dp).toInt(), (16*dp).toInt(), (12*dp).toInt())
            addTextChangedListener(object : android.text.TextWatcher {
                override fun afterTextChanged(s: android.text.Editable?) {
                    val q = s?.toString()?.trim() ?: ""
                    currentQuery = q
                    searchHandler.removeCallbacksAndMessages(null)
                    if (q.isEmpty()) {
                        showContacts(allContacts)
                    } else {
                        showContacts(allContacts.filter { it.second.contains(q, ignoreCase = true) })
                        if (PeopleSearch.shouldQueryServer(q)) {
                            searchHandler.postDelayed({ searchUsers(q) }, 450)
                        }
                    }
                }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
        }
        root.addView(search, LinearLayout.LayoutParams(-1, -2).apply {
            setMargins((12*dp).toInt(), 0, (12*dp).toInt(), (8*dp).toInt())
        })

        resultsContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = android.widget.ScrollView(this).apply { addView(resultsContainer) }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        loadPrivateContacts()

        nextButton = TextView(this).apply {
            text = getString(R.string.next); textSize = 16f; setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = 26f * dp
                setColor(tc.accent)
            }
            setPadding(0, (14*dp).toInt(), 0, (14*dp).toInt())
            visibility = View.GONE
            setOnClickListener { 
                if (isReturningFromStep2) goToStep2()
                else showTypeScreen() 
            }
        }
        root.addView(nextButton, LinearLayout.LayoutParams(-1, -2).apply {
            setMargins((16*dp).toInt(), (8*dp).toInt(), (16*dp).toInt(), (16*dp).toInt())
        })

        this.chipScrollRef = chipScroll
        return flowStep1(root, header, search, scroll, nextButton)
    }

    private fun flowStep1(root: LinearLayout, header: LinearLayout, search: View, scroll: View, next: View): View {
        if (!pl.sinusoide.sinuschat.util.ThemeManager.isFlow(this)) return root
        val glass = pl.sinusoide.sinuschat.ui.FlowGlass
        header.setPadding((8*dp).toInt(), (6*dp).toInt(), (12*dp).toInt(), (6*dp).toInt())
        glass.bar(header, 28f)
        glass.bar(search, 24f, hMarginDp = 12f, vMarginDp = 4f)
        val L = pl.sinusoide.sinuschat.ui.FlowLens
        if (!L.supported(this)) return root
        val frame = android.widget.FrameLayout(this)
        frame.addView(root, android.widget.FrameLayout.LayoutParams(-1, -1))
        L.underBars(root, scroll, listOf(
            pl.sinusoide.sinuschat.ui.FlowLens.Lens(header, 28 * dp, magnify = 0.12f, blurDp = 0.8f),
            pl.sinusoide.sinuschat.ui.FlowLens.Lens(search, 24 * dp, magnify = 0.12f, blurDp = 0.8f),
            pl.sinusoide.sinuschat.ui.FlowLens.Lens(next, magnify = 0.12f, blurDp = 0.8f)))
        return frame
    }

    private var chipScrollRef: View? = null
    private val allContacts = ArrayList<Pair<String, String>>()

    private val searchHandler = android.os.Handler(android.os.Looper.getMainLooper())
    @Volatile private var currentQuery = ""

    private fun loadPrivateContacts() {
        AppExecutor.io {
            val people = PeopleSearch.loadKnownContacts(this)
            AppExecutor.main {
                allContacts.clear()
                allContacts.addAll(people.map { Pair(it.hash, it.name) })
                if (currentQuery.isEmpty()) showContacts(allContacts)
            }
        }
    }

    private fun showContacts(list: List<Pair<String, String>>) {
        resultsContainer.removeAllViews()
        if (list.isEmpty()) {
            resultsContainer.addView(TextView(this).apply {
                text = getString(R.string.no_contacts_search_email)
                setTextColor(tc.text2); textSize = 14f
                setPadding((16*dp).toInt(), (16*dp).toInt(), (16*dp).toInt(), (16*dp).toInt())
            })
            return
        }
        for ((h, n) in list) {
            val cachedAvatar = ContactCache.getAvatarUrl(this, h)
            val v = userRow(h, n, cachedAvatar)
            v.tag = Triple(h, n, cachedAvatar)
            resultsContainer.addView(v)
        }
    }

    private fun searchUsers(query: String) {
        AppExecutor.io {
            val results = PeopleSearch.search(this, query)
            AppExecutor.main {
                if (query != currentQuery) return@main
                var added = 0
                for (p in results) {
                    var exists = false
                    for (k in 0 until resultsContainer.childCount) {
                        val t = resultsContainer.getChildAt(k).tag as? Triple<*, *, *>
                        if (t?.first == p.hash) { exists = true; break }
                    }
                    if (exists) continue
                    if (added == 0 && resultsContainer.childCount == 1 &&
                        resultsContainer.getChildAt(0).tag == null) resultsContainer.removeAllViews()
                    val v = userRow(p.hash, p.name, p.avatarUrl)
                    v.tag = Triple(p.hash, p.name, p.avatarUrl)
                    resultsContainer.addView(v)
                    added++
                }
                if (results.isEmpty() && PeopleSearch.isEmail(query)) showNotFound(query)
            }
        }
    }

    private fun showNotFound(email: String) {
        resultsContainer.removeAllViews()
        resultsContainer.addView(TextView(this).apply {
            text = getString(R.string.no_account_for_email, email.trim())
            setTextColor(tc.text2); textSize = 14f
            setPadding((16*dp).toInt(), (16*dp).toInt(), (16*dp).toInt(), (16*dp).toInt())
        })
    }

    private fun userRow(hash: String, name: String, avatarUrl: String? = null): View {
        val isSel = selected.containsKey(hash)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((16*dp).toInt(), (12*dp).toInt(), (16*dp).toInt(), (12*dp).toInt())
            isClickable = true
            setOnClickListener { toggleUser(hash, name) }
        }
        val avatar = android.widget.ImageView(this).apply {
            clipToOutline = true
            val color = AvatarHelper.getContactColor(this@GroupCreatorActivity, hash)
            setImageBitmap(AvatarHelper.createLetterBitmap(
                AvatarHelper.initialOf(name), color, (44*dp).toInt()))
        }
        
        val bestAvatar = avatarUrl ?: ContactCache.getAvatarUrl(this, hash)
        if (!bestAvatar.isNullOrEmpty()) {
            Glide.with(this).load(bestAvatar).circleCrop()
                .into(object : com.bumptech.glide.request.target.CustomTarget<android.graphics.drawable.Drawable>() {
                    override fun onResourceReady(r: android.graphics.drawable.Drawable,
                                                 t: com.bumptech.glide.request.transition.Transition<in android.graphics.drawable.Drawable>?) {
                        avatar.setImageDrawable(r)
                    }
                    override fun onLoadCleared(p: android.graphics.drawable.Drawable?) {}
                    override fun onLoadFailed(e: android.graphics.drawable.Drawable?) {}
                })
        }
        row.addView(avatar, LinearLayout.LayoutParams((44*dp).toInt(), (44*dp).toInt()).apply {
            marginEnd = (14*dp).toInt()
        })
        row.addView(TextView(this).apply {
            text = name; textSize = 16f; setTextColor(tc.text)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        val check = android.widget.ImageView(this).apply {
            setImageResource(if (isSel) R.drawable.ic_check else 0)
            setColorFilter(Color.WHITE)
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                if (isSel) setColor(tc.accent)
                else { setColor(0x00000000); setStroke((2*dp).toInt(), tc.muted) }
            }
            setPadding((4*dp).toInt(), (4*dp).toInt(), (4*dp).toInt(), (4*dp).toInt())
        }
        pl.sinusoide.sinuschat.ui.FlowGlass.check(check, isSel, animate = hash == justToggled)
        row.addView(check, LinearLayout.LayoutParams((28*dp).toInt(), (28*dp).toInt()))
        return row
    }

    private var justToggled: String? = null

    private fun toggleUser(hash: String, name: String) {
        if (selected.containsKey(hash)) selected.remove(hash) else selected[hash] = name
        refreshChips()
        justToggled = hash
        rebuildVisibleRows()
        justToggled = null
    }

    private fun rebuildVisibleRows() {
        val snapshot = ArrayList<Triple<String, String, String?>>()
        for (i in 0 until resultsContainer.childCount) {
            (resultsContainer.getChildAt(i).tag as? Triple<*, *, *>)?.let {
                snapshot.add(Triple(it.first as String, it.second as String, it.third as? String))
            }
        }
        if (snapshot.isEmpty()) return
        resultsContainer.removeAllViews()
        for ((h, n, a) in snapshot) {
            val v = userRow(h, n, a)
            v.tag = Triple(h, n, a)
            resultsContainer.addView(v)
        }
    }

    private fun renderMembersStrip() {
        val strip = membersStrip ?: return
        strip.removeAllViews()
        membersLabel?.text = if (selected.isEmpty()) getString(R.string.group_no_members)
        else getString(R.string.members_count_label, selected.size)

        for ((hash, name) in selected) {
            val holder = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
            }
            val frame = android.widget.FrameLayout(this)
            val memberAvatar = android.widget.ImageView(this).apply {
                clipToOutline = true
                setImageBitmap(AvatarHelper.createLetterBitmap(
                    AvatarHelper.initialOf(name),
                    AvatarHelper.getContactColor(this@GroupCreatorActivity, hash), (48*dp).toInt()))
            }
            frame.addView(memberAvatar, android.widget.FrameLayout.LayoutParams((48*dp).toInt(), (48*dp).toInt()).apply {
                setMargins((6*dp).toInt(), (6*dp).toInt(), 0, 0)
            })
            val memberUrl = pl.sinusoide.sinuschat.util.ContactCache.getAvatarUrl(this, hash)
            if (!memberUrl.isNullOrEmpty()) {
                com.bumptech.glide.Glide.with(this).load(memberUrl).circleCrop()
                    .into(object : com.bumptech.glide.request.target.CustomTarget<android.graphics.drawable.Drawable>() {
                        override fun onResourceReady(r: android.graphics.drawable.Drawable,
                                                     t: com.bumptech.glide.request.transition.Transition<in android.graphics.drawable.Drawable>?) {
                            memberAvatar.setImageDrawable(r)
                        }
                        override fun onLoadCleared(p: android.graphics.drawable.Drawable?) {}
                        override fun onLoadFailed(e: android.graphics.drawable.Drawable?) {}
                    })
            }
            frame.addView(android.widget.ImageView(this).apply {
                setImageResource(R.drawable.ic_close)
                imageTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
                setColorFilter(Color.WHITE)
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(0xFFE04545.toInt())
                    setStroke((1.5*dp).toInt(), tc.input)
                }
                val p = (3*dp).toInt(); setPadding(p, p, p, p)
                setOnClickListener {
                    selected.remove(hash)
                    renderMembersStrip()
                }
            }, android.widget.FrameLayout.LayoutParams((20*dp).toInt(), (20*dp).toInt()).apply {
                gravity = Gravity.TOP or Gravity.END
            })
            holder.addView(frame)
            holder.addView(TextView(this).apply {
                text = if (name.length > 8) name.take(7) + "…" else name
                textSize = 11f; setTextColor(tc.text2)
                gravity = Gravity.CENTER; setPadding(0, (4*dp).toInt(), 0, 0)
            })
            strip.addView(holder, LinearLayout.LayoutParams(-2, -2).apply {
                marginEnd = (14*dp).toInt()
            })
        }

        val addHolder = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            isClickable = true
            setOnClickListener { goToStep1KeepingSelection() }
        }
        addHolder.addView(android.widget.ImageView(this).apply {
            setImageResource(R.drawable.ic_person_add)
            imageTintList = android.content.res.ColorStateList.valueOf(tc.accent)
            setColorFilter(tc.accent)
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(tc.input)
                setStroke((1.5*dp).toInt(), tc.accent)
            }
            val p = (12*dp).toInt(); setPadding(p, p, p, p)
        }, LinearLayout.LayoutParams((48*dp).toInt(), (48*dp).toInt()).apply {
            topMargin = (6*dp).toInt()
        })
        addHolder.addView(TextView(this).apply {
            text = getString(R.string.add)
            textSize = 11f; setTextColor(tc.accent)
            gravity = Gravity.CENTER; setPadding(0, (4*dp).toInt(), 0, 0)
        })
        strip.addView(addHolder, LinearLayout.LayoutParams(-2, -2))
    }

    private fun goToStep1KeepingSelection() {
        selectionSnapshot.clear()
        selectionSnapshot.putAll(selected)
        isReturningFromStep2 = true
        currentScreen = Screen.STEP1
        setContentView(buildStep1())
        refreshChips()
    }

    private fun refreshChips() {
        selectedChips.removeAllViews()
        chipScrollRef?.visibility = if (selected.isEmpty()) View.GONE else View.VISIBLE
        nextButton.visibility = if (selected.isEmpty()) View.GONE else View.VISIBLE
        for ((hash, name) in selected) {
            val chip = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                    cornerRadius = 20f * dp
                    setColor(tc.input)
                }
                setPadding((10*dp).toInt(), (6*dp).toInt(), (10*dp).toInt(), (6*dp).toInt())
                setOnClickListener { selected.remove(hash); refreshChips(); rebuildVisibleRows() }
            }
            chip.addView(TextView(this).apply {
                text = "$name  ✕"; textSize = 13f; setTextColor(tc.text)
            })
            selectedChips.addView(chip, LinearLayout.LayoutParams(-2, -2).apply {
                marginEnd = (8*dp).toInt()
            })
        }
    }

    private var selectedColor = AvatarHelper.PALETTE[0]
    private var groupName = ""

    private fun currentAvatarLetter(): String =
        AvatarHelper.initialOf(groupName).takeIf { it != "?" } ?: "G"

    private fun goToStep2() {
        isReturningFromStep2 = false
        currentScreen = Screen.STEP2
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(tc.bg)
        }
        applyInsets(root)
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((8*dp).toInt(), (12*dp).toInt(), (12*dp).toInt(), (12*dp).toInt())
        }
        header.addView(android.widget.ImageButton(this).apply {
            setImageResource(R.drawable.ic_back)
            setBackgroundResource(0); setColorFilter(tc.text)
            setPadding((10*dp).toInt(), (10*dp).toInt(), (10*dp).toInt(), (10*dp).toInt())
            setOnClickListener { showTypeScreen() }
        }, LinearLayout.LayoutParams((44*dp).toInt(), (44*dp).toInt()))
        header.addView(TextView(this).apply {
            text = getString(R.string.group_name); textSize = 19f; setTextColor(tc.text)
            setPadding((8*dp).toInt(), 0, 0, 0)
        })
        root.addView(header)

        avatarView = ImageView(this).apply {
            clipToOutline = true
            if (selectedAvatarUri != null) {
                com.bumptech.glide.Glide.with(this@GroupCreatorActivity).load(selectedAvatarUri).circleCrop().into(this)
            } else {
                setImageBitmap(AvatarHelper.createLetterBitmap(currentAvatarLetter(), selectedColor, (80*dp).toInt()))
            }
        }
        val avatarWrap = LinearLayout(this).apply {
            gravity = Gravity.CENTER; setPadding(0, (16*dp).toInt(), 0, (16*dp).toInt())
            addView(avatarView, LinearLayout.LayoutParams((80*dp).toInt(), (80*dp).toInt()))
        }
        avatarView.setOnClickListener { showPhotoOptions() }
        root.addView(avatarWrap)

        val etName = EditText(this).apply {
            hint = getString(R.string.enter_group_name); setHintTextColor(tc.text2)
            setTextColor(tc.text); textSize = 16f
            setText(groupName)
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = 22f * dp; setColor(tc.input)
            }
            setPadding((16*dp).toInt(), (14*dp).toInt(), (16*dp).toInt(), (14*dp).toInt())
            addTextChangedListener(object : android.text.TextWatcher {
                override fun afterTextChanged(s: android.text.Editable?) {
                    groupName = s?.toString() ?: ""
                    avatarView.setImageBitmap(
                        AvatarHelper.createLetterBitmap(currentAvatarLetter(), selectedColor, (80*dp).toInt()))
                }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
        }
        root.addView(etName, LinearLayout.LayoutParams(-1, -2).apply {
            setMargins((16*dp).toInt(), 0, (16*dp).toInt(), (12*dp).toInt())
        })

        membersLabel = TextView(this).apply {
            textSize = 13f; setTextColor(tc.text2)
            setPadding((16*dp).toInt(), 0, (16*dp).toInt(), (8*dp).toInt())
        }
        root.addView(membersLabel)

        membersStrip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding((16*dp).toInt(), 0, (16*dp).toInt(), (12*dp).toInt())
        }
        root.addView(android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(membersStrip)
        })
        renderMembersStrip()

        root.addView(settingRow(getString(R.string.disappearing_messages),
            DisappearingMessages.label(this@GroupCreatorActivity, pendingTtlMs), R.drawable.ic_timer) {
            showDisappearingPicker()
        }.also { disappearingRow = it })

        root.addView(settingRow(getString(R.string.group_permissions),
            getString(R.string.who_can_edit), R.drawable.ic_settings) {
            showPermissionsScreen()
        })

        root.addView(View(this), LinearLayout.LayoutParams(-1, 0, 1f))

        root.addView(TextView(this).apply {
            text = getString(R.string.create_group); textSize = 16f; setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = 26f * dp; setColor(tc.accent)
            }
            setPadding(0, (14*dp).toInt(), 0, (14*dp).toInt())
            setOnClickListener { createGroup(etName.text.toString().trim()) }
        }, LinearLayout.LayoutParams(-1, -2).apply {
            setMargins((16*dp).toInt(), 0, (16*dp).toInt(), (16*dp).toInt())
        })

        setContentView(root)
    }

    private var pendingTtlMs = 0L
    private var disappearingRow: View? = null

    private fun settingRow(title: String, subtitle: String, icon: Int, onClick: () -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((16*dp).toInt(), (14*dp).toInt(), (16*dp).toInt(), (14*dp).toInt())
            isClickable = true
            setOnClickListener { onClick() }
        }
        row.addView(android.widget.ImageView(this).apply {
            setImageResource(icon); setColorFilter(tc.accent)
        }, LinearLayout.LayoutParams((24*dp).toInt(), (24*dp).toInt()).apply {
            marginEnd = (16*dp).toInt()
        })
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(TextView(this@GroupCreatorActivity).apply {
            text = title; textSize = 16f; setTextColor(tc.text)
        })
        val sub = TextView(this@GroupCreatorActivity).apply {
            text = subtitle; textSize = 13f; setTextColor(tc.text2)
            tag = "subtitle"
        }
        col.addView(sub)
        row.addView(col)
        row.tag = sub
        return row
    }

    private fun showDisappearingPicker() {
        val intent = Intent(this, DisappearingSettingsActivity::class.java).apply {
            putExtra("currentTtlMs", pendingTtlMs)
        }
        disappearingLauncher.launch(intent)
    }

    private fun showPhotoOptions() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.layout_dialog_profile_options, null)
        val btnChangePhoto = dialogView.findViewById<View>(R.id.opt_change_photo)
        val btnChangeColor = dialogView.findViewById<View>(R.id.opt_change_color)
        val btnRemovePhoto = dialogView.findViewById<View>(R.id.opt_remove_photo)

        if (selectedAvatarUri != null) {
            btnRemovePhoto.visibility = View.VISIBLE
        }

        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(this, R.style.Theme_SinusChat_Dialog)
            .setView(dialogView)
            .create()

        btnChangePhoto.setOnClickListener {
            dialog.dismiss()
            if (!photoFlowOpen) {
                photoFlowOpen = true
                pickImage.launch("image/*")
            }
        }
        btnChangeColor.setOnClickListener {
            dialog.dismiss()
            pickAvatarColor()
        }
        btnRemovePhoto.setOnClickListener {
            dialog.dismiss()
            confirmRemovePhoto()
        }

        dialog.show()
    }

    private fun pickAvatarColor() {
        AvatarHelper.showColorPicker(this, selectedColor) { color ->
            selectedColor = color
            selectedAvatarUri = null
            avatarView.setImageBitmap(AvatarHelper.createLetterBitmap(currentAvatarLetter(), color, (80 * dp).toInt()))
        }
    }

    private fun confirmRemovePhoto() {
        selectedAvatarUri = null
        avatarView.setImageBitmap(AvatarHelper.createLetterBitmap(currentAvatarLetter(), selectedColor, (80 * dp).toInt()))
    }

    private var permEditSettings = true
    private var permSendMessages = true
    private var permAddMembers = true
    private var permCallGroup = true

    private var chosenType: String? = null
    private val typeTiles = mutableListOf<Pair<String, LinearLayout>>()
    private val typeIcons = HashMap<String, android.widget.ImageView>()
    private val typeLabels = HashMap<String, TextView>()


    private fun showTypeScreen() {
        currentScreen = Screen.TYPE
        chosenType = null
        typeTiles.clear(); typeIcons.clear()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(tc.bg)
        }
        applyInsets(root)

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding((8*dp).toInt(), (12*dp).toInt(), (12*dp).toInt(), (12*dp).toInt())
        }
        header.addView(android.widget.ImageButton(this).apply {
            setImageResource(R.drawable.ic_back); setBackgroundResource(0); setColorFilter(tc.text)
            setPadding((10*dp).toInt(), (10*dp).toInt(), (10*dp).toInt(), (10*dp).toInt())
            setOnClickListener { setContentView(buildStep1()); refreshChips() }
        }, LinearLayout.LayoutParams((44*dp).toInt(), (44*dp).toInt()))
        header.addView(TextView(this).apply {
            text = getString(R.string.group_type_title); textSize = 19f; setTextColor(tc.text)
            setPadding((8*dp).toInt(), 0, 0, 0)
        })
        root.addView(header)

        val caption = TextView(this).apply {
            text = getString(R.string.group_type_hint); textSize = 13f
            setTextColor(tc.text2); gravity = Gravity.CENTER
            setPadding((24*dp).toInt(), (20*dp).toInt(), (24*dp).toInt(), 0)
        }
        val nextBtn = TextView(this).apply {
            text = getString(R.string.next); textSize = 15f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 14f*dp; setColor(tc.accent)
            }
            setPadding(0, (15*dp).toInt(), 0, (15*dp).toInt())
            alpha = 0.4f; isEnabled = false
            setOnClickListener {
                val t = chosenType ?: return@setOnClickListener
                applyTypePreset(t)
                goToStep2()
            }
        }

        val tileSize = (135*dp).toInt(); val gap = (18*dp).toInt()
        val comm = makeTypeTile("comm", getString(R.string.type_community), R.drawable.ic_community, caption, nextBtn)
        val group = makeTypeTile("group", getString(R.string.type_group), R.drawable.ic_group, caption, nextBtn)
        val custom = makeTypeTile("custom", getString(R.string.type_custom), R.drawable.ic_settings, caption, nextBtn)

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            setPadding(0, (8*dp).toInt(), 0, 0)
        }
        val topRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        topRow.addView(comm, LinearLayout.LayoutParams(tileSize, tileSize).apply { rightMargin = gap/2 })
        topRow.addView(group, LinearLayout.LayoutParams(tileSize, tileSize).apply { leftMargin = gap/2 })
        container.addView(topRow)
        container.addView(custom, LinearLayout.LayoutParams(tileSize, tileSize).apply { topMargin = gap })
        
        root.addView(container, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(caption, LinearLayout.LayoutParams(-1, -2).apply {
            setMargins((32*dp).toInt(), 0, (32*dp).toInt(), (20*dp).toInt())
        })

        root.addView(nextBtn, LinearLayout.LayoutParams(-1, -2).apply {
            setMargins((16*dp).toInt(), 0, (16*dp).toInt(), (24*dp).toInt())
        })
        setContentView(root)
    }

    private fun makeTypeTile(type: String, label: String, iconRes: Int, caption: TextView, nextBtn: TextView): LinearLayout {
        val tile = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            background = tileBackground(type, false)
            elevation = 4f * dp
        }
        val icon = android.widget.ImageView(this).apply {
            setImageResource(iconRes)
            setColorFilter(tc.text)
        }
        typeIcons[type] = icon
        tile.addView(icon, LinearLayout.LayoutParams((48*dp).toInt(), (48*dp).toInt()))
        tile.addView(TextView(this).apply {
            text = label; textSize = 15f; setTextColor(tc.text); setPadding(0, (10*dp).toInt(), 0, 0)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            typeLabels[type] = this
        })
        tile.setOnClickListener { selectType(type, caption, nextBtn) }
        typeTiles.add(Pair(type, tile))
        return tile
    }

    private fun selectType(type: String, caption: TextView, nextBtn: TextView) {
        chosenType = type
        typeTiles.forEach { (t, tile) -> tile.background = tileBackground(t, t == type) }
        typeIcons.forEach { (t, v) -> v.setColorFilter(if (t == type) Color.WHITE else tc.text) }
        typeLabels.forEach { (t, v) -> v.setTextColor(if (t == type) Color.WHITE else tc.text) }
        animateTile(type)
        caption.text = when (type) {
            "comm" -> getString(R.string.type_community_desc)
            "group" -> getString(R.string.type_group_desc)
            else -> getString(R.string.type_custom_desc)
        }
        nextBtn.alpha = 1f; nextBtn.isEnabled = true
    }

    private fun animateTile(type: String) {
        val icon = typeIcons[type] ?: return
        when (type) {
            "custom" -> android.animation.ObjectAnimator.ofFloat(icon, "rotation", 0f, 360f).apply {
                duration = 700; interpolator = android.view.animation.DecelerateInterpolator(); start()
            }
            "group" -> icon.animate().translationY(-18f * dp).setDuration(180).withEndAction {
                icon.animate().translationY(0f).setDuration(280)
                    .setInterpolator(android.view.animation.OvershootInterpolator()).start()
            }.start()
            else -> icon.animate().scaleX(1.18f).scaleY(1.18f).setDuration(180).withEndAction {
                icon.animate().scaleX(1f).scaleY(1f).setDuration(240).start()
            }.start()
        }
    }

    private fun tileBackground(type: String, selected: Boolean): android.graphics.drawable.Drawable {
        if (tc.flow) {
            val tint = when (type) { "comm" -> 0xFF25A96C.toInt(); "group" -> 0xFF3E92CC.toInt(); else -> 0xFF6C7C8C.toInt() }
            return pl.sinusoide.sinuschat.ui.GlassDrawable(dp, tint, false, 24f * dp, null, emphasis = selected)
        }
        val colors = when (type) {
            "comm" -> if (selected) intArrayOf(0xFF1C8A50.toInt(), 0xFF25A96C.toInt()) else intArrayOf(0x2E25A96C, 0x2E25A96C)
            "group" -> if (selected) intArrayOf(0xFF3E92CC.toInt(), 0xFF5AB0E6.toInt()) else intArrayOf(0x2E3E92CC, 0x2E3E92CC)
            else -> if (selected) intArrayOf(0xFF526273.toInt(), 0xFF6C7C8C.toInt()) else intArrayOf(tc.input, tc.input)
        }
        return android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.TL_BR, colors
        ).apply {
            cornerRadius = 24f * dp
            if (selected) {
                setStroke((3 * dp).toInt(), if (tc.dark) Color.WHITE else 0x59000000)
            } else {
                setStroke((1 * dp).toInt(), if (tc.dark) 0x22FFFFFF else tc.border)
            }
        }
    }

    private fun applyTypePreset(type: String) {
        when (type) {
            "comm" -> { permAddMembers = true; permSendMessages = false; permEditSettings = false; permCallGroup = false }
            "group" -> { permAddMembers = false; permSendMessages = true; permEditSettings = false; permCallGroup = true }
            else -> { permAddMembers = true; permSendMessages = true; permEditSettings = true; permCallGroup = true }
        }
    }

    private fun showPermissionsScreen() {
        currentScreen = Screen.PERMISSIONS
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(tc.bg)
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding((8*dp).toInt(), (12*dp).toInt(), (12*dp).toInt(), (12*dp).toInt())
        }
        header.addView(android.widget.ImageButton(this).apply {
            setImageResource(R.drawable.ic_back); setBackgroundResource(0); setColorFilter(tc.text)
            setPadding((10*dp).toInt(), (10*dp).toInt(), (10*dp).toInt(), (10*dp).toInt())
            setOnClickListener { goToStep2() }
        }, LinearLayout.LayoutParams((44*dp).toInt(), (44*dp).toInt()))
        header.addView(TextView(this).apply {
            text = getString(R.string.group_permissions); textSize = 19f; setTextColor(tc.text)
            setPadding((8*dp).toInt(), 0, 0, 0)
        })
        root.addView(header)

        root.addView(TextView(this).apply {
            text = getString(R.string.member_permissions); textSize = 13f; setTextColor(tc.text2)
            setPadding((16*dp).toInt(), (12*dp).toInt(), (16*dp).toInt(), (8*dp).toInt())
        })

        root.addView(permRow(
            getString(R.string.edit_group_settings),
            getString(R.string.group_settings_full),
            permEditSettings) { permEditSettings = it })
        root.addView(permRow(getString(R.string.sending_new_messages), null, permSendMessages) { permSendMessages = it })
        root.addView(permRow(getString(R.string.adding_members), null, permAddMembers) { permAddMembers = it })
        root.addView(permRow(getString(R.string.calling_group), null, permCallGroup) { permCallGroup = it })

        val scroll = android.widget.ScrollView(this).apply {
            setBackgroundColor(tc.bg)
            addView(root)
        }
        applyInsets(scroll)
        setContentView(scroll)
    }

    private fun permRow(title: String, desc: String?, initial: Boolean, onChange: (Boolean) -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding((16*dp).toInt(), (12*dp).toInt(), (16*dp).toInt(), (12*dp).toInt())
        }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(TextView(this@GroupCreatorActivity).apply {
            text = title; textSize = 16f; setTextColor(tc.text)
        })
        if (desc != null) col.addView(TextView(this@GroupCreatorActivity).apply {
            text = desc; textSize = 13f; setTextColor(tc.text2)
            setPadding(0, (4*dp).toInt(), 0, 0)
        })
        row.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        
        val activeColor = when (chosenType) {
            "comm" -> 0xFF2ECC71.toInt()
            "group" -> tc.accent
            else -> if (tc.dark) 0xFF6C7C8C.toInt() else 0xFF526273.toInt()
        }
        
        val tintList = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(activeColor, tc.muted)
        )

        val sw = Switch(this).apply {
            isChecked = initial
            thumbTintList = tintList
            trackTintList = tintList
            setOnCheckedChangeListener { _, v -> onChange(v) }
        }
        row.addView(sw)
        return row
    }

    private fun createGroup(name: String) {
        if (name.isEmpty()) {
            PopupHelper.show(this, getString(R.string.enter_group_name)); return
        }

        if (selectedAvatarUri != null) PopupHelper.show(this, getString(R.string.sending_photo))
        GroupRepository.createGroup(
            context = this,
            name = name,
            memberHashes = selected.keys.toList(),
            avatarColor = selectedColor,
            kind = when (chosenType) { "comm" -> "community"; "group" -> "group"; else -> "custom" },
            avatarUri = selectedAvatarUri,
            onPhotoFailed = { PopupHelper.showError(this, getString(R.string.photo_upload_server_error)) }
        ) { groupId ->
            if (groupId == null) {
                PopupHelper.showError(this, getString(R.string.error_create_group))
                return@createGroup
            }
            if (pendingTtlMs > 0L) {
                DisappearingMessages.setTtl(this, groupId, pendingTtlMs)
                GroupRepository.setDisappearing(this, groupId, pendingTtlMs,
                    DisappearingMessages.TRIGGER_READ) {}
            }
            GroupRepository.setPermissions(this, groupId,
                GroupPerms(permEditSettings, permSendMessages, permAddMembers, permCallGroup)) {}
            startActivity(Intent(this, ChatActivity::class.java).apply {
                putExtra("conversationId", groupId)
                putExtra("otherUserName", name)
                putExtra("otherUserHash", "")
                putExtra("isGroup", true)
                putExtra("weStarted", true)
            })
            finish()
        }
    }
}
