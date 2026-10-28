package pl.sinusoide.sinuschat

import pl.sinusoide.sinuschat.util.SettingsSearch.Target as SettingTarget
import pl.sinusoide.sinuschat.util.optStringOrNull

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import pl.sinusoide.sinuschat.network.ApiClient
import pl.sinusoide.sinuschat.util.AppExecutor
import pl.sinusoide.sinuschat.setup.SetupActivity
import pl.sinusoide.sinuschat.util.AvatarHelper
import pl.sinusoide.sinuschat.util.FileUploader
import pl.sinusoide.sinuschat.util.PopupHelper

class AboutFragment : Fragment() {

    private lateinit var avatarLetter: ImageView
    private lateinit var avatarImage: ImageView
    private lateinit var tvName: TextView
    private lateinit var btnEditName: View
    private lateinit var btnEditPhoto: View


    private val myHash get() = SetupActivity.getUserPhoneHash(requireContext())

    private var photoFlowOpen = false
    private var onProfilePhotoPicked: ((Uri) -> Unit)? = null

    private val cropImage = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        photoFlowOpen = false
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val outputUri = result.data?.getStringExtra(pl.sinusoide.sinuschat.util.CropActivity.EXTRA_OUTPUT)
            if (outputUri != null) {
                val uri = Uri.parse(outputUri)
                onProfilePhotoPicked?.invoke(uri) ?: uploadNewAvatar(uri)
            }
        }
    }

    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            val intent = Intent(requireContext(), pl.sinusoide.sinuschat.util.CropActivity::class.java)
            intent.putExtra(pl.sinusoide.sinuschat.util.CropActivity.EXTRA_URI, uri)
            cropImage.launch(intent)
        } else {
            photoFlowOpen = false
        }
    }

    private var afterAuth: (() -> Unit)? = null

    private val pinConfirm = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val action = afterAuth
        afterAuth = null
        if (result.resultCode == android.app.Activity.RESULT_OK) action?.invoke()
    }

    private fun requireAuthThen(action: () -> Unit) {
        val ctx = requireContext()
        if (!LockManager.hasPin(ctx)) { action(); return }
        afterAuth = action
        val askPin = {
            pinConfirm.launch(Intent(ctx, ScreenLockActivity::class.java)
                .putExtra(ScreenLockActivity.EXTRA_MODE, ScreenLockActivity.MODE_CONFIRM)
                .putExtra(ScreenLockActivity.EXTRA_TITLE, getString(R.string.account_auth_title)))
        }
        if (LockManager.isBiometricEnabled(ctx) && LockManager.isBiometricAvailable(ctx)) {
            LockManager.showBiometricPrompt(requireActivity(),
                onSuccess = { afterAuth = null; action() },
                onFailed = { if (isAdded) askPin() })
        } else {
            askPin()
        }
    }

    private fun launchPhotoPicker() {
        if (photoFlowOpen) return
        photoFlowOpen = true
        try { pickImage.launch("image/*") } catch (_: Exception) { photoFlowOpen = false }
    }

    private val dp get() = resources.displayMetrics.density
    private var cBg = 0; private var cSurface = 0; private var cCard = 0
    private var cPrimary = 0; private var cSecondary = 0; private var cAccent = 0

    private var privacySubtitle: TextView? = null
    private var languageSubtitle: TextView? = null
    private var notificationsSubtitle: TextView? = null

    private fun attr(id: Int, fallback: Int): Int {
        val tv = android.util.TypedValue()
        return pl.sinusoide.sinuschat.ui.ThemeUi.attrOrNull(requireContext(), id) ?: fallback
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val ctx = requireContext()
        cBg = attr(R.attr.sinuschat_background, 0xFF0E1621.toInt())
        cSurface = attr(R.attr.sinuschat_surface, 0xFF17212B.toInt())
        cCard = attr(R.attr.sinuschat_surface_elevated, 0xFF1D2A38.toInt())
        cPrimary = attr(R.attr.sinuschat_text_primary, android.graphics.Color.WHITE)
        cSecondary = attr(R.attr.sinuschat_text_secondary, 0xFF7F91A4.toInt())
        cAccent = pl.sinusoide.sinuschat.ui.ThemeUi.accent(ctx)

        val flow = pl.sinusoide.sinuschat.util.ThemeManager.isFlow(ctx)
        val overlay = pl.sinusoide.sinuschat.ui.FlowLens.supported(ctx)
        val outer: ViewGroup = if (overlay) FrameLayout(ctx) else LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
        }
        outer.setBackgroundColor(cBg)
        val header = FrameLayout(ctx).apply {
            if (!flow) {
                setBackgroundColor(cSurface)
                elevation = 2 * dp
            }
        }
        val titleRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding((20 * dp).toInt(), 0, (8 * dp).toInt(), 0)
            addView(TextView(ctx).apply {
                text = getString(R.string.settings); textSize = 22f
                setTextColor(cPrimary); typeface = android.graphics.Typeface.DEFAULT_BOLD
            }, LinearLayout.LayoutParams(0, -2, 1f))
        }
        val searchIcon = ImageView(ctx).apply {
            setImageResource(R.drawable.ic_search); setColorFilter(cPrimary)
            val p = (10 * dp).toInt(); setPadding(p, p, p, p)
            background = androidx.core.content.ContextCompat.getDrawable(ctx, R.drawable.bg_menu_ripple)
            contentDescription = getString(R.string.settings_search_hint)
            setOnClickListener { openSearch() }
            if (!overlay) pl.sinusoide.sinuschat.ui.FlowGlass.glass(this, 22f, oval = true)
        }
        titleRow.addView(searchIcon, LinearLayout.LayoutParams((44 * dp).toInt(), (44 * dp).toInt()))
        if (overlay) {
            pl.sinusoide.sinuschat.ui.FlowGlass.glass(titleRow, 28f, press = false)
            header.addView(titleRow, FrameLayout.LayoutParams(-1, (56 * dp).toInt()).apply {
                marginStart = (10 * dp).toInt(); marginEnd = (10 * dp).toInt()
                topMargin = (4 * dp).toInt(); bottomMargin = (4 * dp).toInt()
            })
        } else {
            header.addView(titleRow, FrameLayout.LayoutParams(-1, (56 * dp).toInt()))
        }
        val pill = searchPill()
        header.addView(pill, FrameLayout.LayoutParams(-1, (46 * dp).toInt(), android.view.Gravity.CENTER_VERTICAL).apply {
            marginStart = (10 * dp).toInt(); marginEnd = (10 * dp).toInt()
        })
        headerTitleRow = titleRow
        headerSearchIcon = searchIcon
        pl.sinusoide.sinuschat.ui.PillWarmUp.prime(pill) { searchOpen }
        if (!overlay) outer.addView(header)
        if (pl.sinusoide.sinuschat.util.ThemeManager.isRetro(ctx)) pl.sinusoide.sinuschat.ui.RetroUi.styleTitleBar(header)
        ViewCompat.setOnApplyWindowInsetsListener(header) { v, insets ->
            v.setPadding(0, insets.getInsets(WindowInsetsCompat.Type.systemBars()).top, 0, 0)
            insets
        }

        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((16 * dp).toInt(), (16 * dp).toInt(), (16 * dp).toInt(), (32 * dp).toInt())
        }
        val scroll = ScrollView(ctx).apply {
            isVerticalScrollBarEnabled = false
            addView(content)
        }
        if (overlay) {
            scroll.clipToPadding = false
            outer.addView(scroll, FrameLayout.LayoutParams(-1, -1))
            outer.addView(header, FrameLayout.LayoutParams(-1, -2, android.view.Gravity.TOP))
            header.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
                if (scroll.paddingTop != v.height) scroll.post { scroll.setPadding(0, v.height, 0, 0) }
            }
            pl.sinusoide.sinuschat.ui.FlowLens.attach(scroll, listOf(
                pl.sinusoide.sinuschat.ui.FlowLens.Lens(pill, magnify = 0.12f, blurDp = 0.8f),
                pl.sinusoide.sinuschat.ui.FlowLens.Lens(titleRow, 28 * dp, magnify = 0.12f, blurDp = 0.8f)),
                topEdge = { header.height.toFloat() })
        } else {
            outer.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        }

        searchResults = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        content.addView(searchResults)
        content.addView(profileCard())
        return outer.also { settingsContent = content }
    }

    private var searchResults: LinearLayout? = null
    private var searchField: android.widget.EditText? = null
    private var searchPillView: View? = null
    private var searchBack: ImageView? = null
    private var headerTitleRow: View? = null
    private var headerSearchIcon: View? = null
    private var searchOpen = false

    private val searchBackCallback = object : androidx.activity.OnBackPressedCallback(false) {
        override fun handleOnBackPressed() { closeSearch() }
    }

    private fun searchPill(): View {
        val ctx = requireContext()
        val clear = ImageView(ctx).apply {
            setImageResource(R.drawable.ic_close); setColorFilter(cSecondary)
            val p = (8 * dp).toInt(); setPadding(p, p, p, p)
            background = androidx.core.content.ContextCompat.getDrawable(ctx, R.drawable.bg_menu_ripple)
            visibility = View.GONE
            contentDescription = getString(R.string.settings_search_clear)
        }
        val back = ImageView(ctx).apply {
            setImageResource(R.drawable.ic_back); setColorFilter(cPrimary)
            val p = (9 * dp).toInt(); setPadding(p, p, p, p)
            background = androidx.core.content.ContextCompat.getDrawable(ctx, R.drawable.bg_menu_ripple)
            contentDescription = getString(R.string.back)
            setOnClickListener { closeSearch() }
        }
        searchBack = back
        val field = android.widget.EditText(ctx).apply {
            hint = getString(R.string.settings_search_hint)
            setHintTextColor(tint(cSecondary, 0xCC)); setTextColor(cPrimary)
            textSize = 15.5f
            background = null
            setSingleLine(true)
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setPadding(0, (12 * dp).toInt(), 0, (12 * dp).toInt())
            setOnEditorActionListener { v, actionId, _ ->
                if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) { hideKeyboard(v); true } else false
            }
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) {
                    val q = s?.toString().orEmpty()
                    clear.visibility = if (q.isEmpty()) View.GONE else View.VISIBLE
                    showSearch(q)
                }
            })
        }
        searchField = field
        clear.setOnClickListener { field.setText(""); field.requestFocus() }
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            background = if (pl.sinusoide.sinuschat.util.ThemeManager.isFlow(ctx))
                pl.sinusoide.sinuschat.ui.GlassDrawable(dp, null, false, 23 * dp, null)
            else android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 23 * dp; setColor(cCard); setStroke((1 * dp).toInt(), tint(cAccent, 0x55))
            }
            setPadding((2 * dp).toInt(), 0, (4 * dp).toInt(), 0)
            visibility = View.INVISIBLE
            addView(back, LinearLayout.LayoutParams((40 * dp).toInt(), (40 * dp).toInt()).apply { marginEnd = (2 * dp).toInt() })
            addView(field, LinearLayout.LayoutParams(0, -2, 1f))
            addView(clear, LinearLayout.LayoutParams((38 * dp).toInt(), (38 * dp).toInt()))
        }.also { searchPillView = it }
    }

    override fun onAttach(context: android.content.Context) {
        super.onAttach(context)
        requireActivity().onBackPressedDispatcher.addCallback(this, searchBackCallback)
    }

    private var openingResult = false

    override fun onPause() {
        super.onPause()
        if (searchOpen && !openingResult) closeSearch(animate = false)
    }

    private fun openSearch() {
        val pill = searchPillView ?: return
        val icon = headerSearchIcon ?: return
        val field = searchField ?: return
        if (searchOpen) return
        searchOpen = true
        searchBackCallback.isEnabled = true

        headerTitleRow?.animate()?.alpha(0f)?.translationX(-16 * dp)?.setStartDelay(0)?.setDuration(170)?.start()
        pill.animate().cancel()
        pill.scaleX = 1f; pill.scaleY = 1f
        pill.alpha = 0f
        pill.visibility = View.VISIBLE
        searchBack?.apply { animate().cancel(); rotation = 180f; alpha = 0f }
        pill.postOnAnimation {
            if (!searchOpen || !isAdded) return@postOnAnimation
            pill.postOnAnimation {
                if (!searchOpen || !isAdded) return@postOnAnimation
                val duration = startPillOpen(pill, icon)
                searchBack?.animate()?.rotation(0f)?.alpha(1f)?.setStartDelay(90)?.setDuration(300)
                    ?.setInterpolator(android.view.animation.DecelerateInterpolator())?.start()
                field.postDelayed({
                    if (!searchOpen || !isAdded) return@postDelayed
                    field.requestFocus()
                    (requireContext().getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager)
                        ?.showSoftInput(field, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
                }, (duration - 80L).coerceAtLeast(0L))
            }
        }
    }

    private fun startPillOpen(pill: View, icon: View): Long {
        if (!pill.isAttachedToWindow || pill.width == 0) {
            pill.alpha = 1f
            return 0L
        }
        val cx = ((icon.left + icon.width / 2f) - pill.left).toInt().coerceIn(0, pill.width)
        val cy = pill.height / 2
        val radius = kotlin.math.hypot(pill.width.toDouble(), pill.height.toDouble()).toFloat()
        return if (pl.sinusoide.sinuschat.util.ThemeManager.isFlow(requireContext())) {
            pill.pivotX = cx.toFloat(); pill.pivotY = cy.toFloat()
            pill.scaleX = 0.12f; pill.scaleY = 0.9f; pill.alpha = 0f
            pill.animate().scaleX(1f).scaleY(1f).alpha(1f).setStartDelay(0).setDuration(440)
                .setInterpolator(android.view.animation.OvershootInterpolator(1.1f)).start()
            440L
        } else {
            pill.alpha = 1f
            android.view.ViewAnimationUtils.createCircularReveal(pill, cx, cy, 0f, radius).apply {
                duration = 340
                interpolator = android.view.animation.DecelerateInterpolator(1.6f)
                start()
            }
            340L
        }
    }

    private fun closeSearch(animate: Boolean = true) {
        val pill = searchPillView ?: return
        val icon = headerSearchIcon ?: return
        val field = searchField ?: return
        if (!searchOpen) return
        searchOpen = false
        searchBackCallback.isEnabled = false
        hideKeyboard(field)
        field.clearFocus()
        field.setText("")

        val title = headerTitleRow
        if (!animate || !pill.isAttachedToWindow || pill.width == 0) {
            pill.animate().cancel()
            pill.visibility = View.INVISIBLE
            pill.scaleX = 1f; pill.scaleY = 1f; pill.alpha = 1f
            title?.alpha = 1f; title?.translationX = 0f
            return
        }
        val cx = ((icon.left + icon.width / 2f) - pill.left).toInt().coerceIn(0, pill.width)
        val radius = kotlin.math.hypot(pill.width.toDouble(), pill.height.toDouble()).toFloat()
        if (pl.sinusoide.sinuschat.util.ThemeManager.isFlow(requireContext())) {
            pill.animate().cancel()
            pill.pivotX = cx.toFloat(); pill.pivotY = pill.height / 2f
            pill.animate().scaleX(0.12f).scaleY(0.9f).alpha(0f).setDuration(260)
                .setInterpolator(android.view.animation.AccelerateInterpolator(1.2f))
                .withEndAction {
                    if (!searchOpen) {
                        pill.visibility = View.INVISIBLE
                        pill.scaleX = 1f; pill.scaleY = 1f; pill.alpha = 1f
                    }
                }.start()
        } else android.view.ViewAnimationUtils.createCircularReveal(pill, cx, pill.height / 2, radius, 0f).apply {
            duration = 280
            interpolator = android.view.animation.AccelerateInterpolator(1.2f)
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (!searchOpen) pill.visibility = View.INVISIBLE
                }
            })
            start()
        }
        title?.animate()?.alpha(1f)?.translationX(0f)?.setStartDelay(120)?.setDuration(220)?.start()
    }

    private fun showSearch(query: String) {
        val content = settingsContent ?: return
        val results = searchResults ?: return
        val searching = query.isNotBlank()
        val wasShowing = results.visibility == View.VISIBLE
        if (searching && !wasShowing) {
            results.alpha = 0f; results.translationY = 10 * dp
            results.animate().alpha(1f).translationY(0f).setDuration(220)
                .setInterpolator(android.view.animation.DecelerateInterpolator()).start()
        }
        for (i in 0 until content.childCount) {
            val child = content.getChildAt(i)
            if (child === results) continue
            child.visibility = if (searching) View.GONE else View.VISIBLE
        }
        results.visibility = if (searching) View.VISIBLE else View.GONE
        results.removeAllViews()
        if (!searching) return

        val ctx = requireContext()
        val found = pl.sinusoide.sinuschat.util.SettingsSearch.search(ctx, query)
        if (found.isEmpty()) {
            results.addView(TextView(ctx).apply {
                text = getString(R.string.settings_search_empty, query.trim())
                textSize = 14f; setTextColor(cSecondary); gravity = android.view.Gravity.CENTER
                setPadding((16 * dp).toInt(), (36 * dp).toInt(), (16 * dp).toInt(), (36 * dp).toInt())
            })
            return
        }
        results.addView(card().apply {
            found.take(30).forEachIndexed { i, e ->
                if (i > 0) addView(divider())
                val subtitle = if (e.isScreen) getString(R.string.settings)
                    else getString(R.string.settings) + " › " + getString(e.screen)
                addView(settingsRow(e.icon, e.color, getString(e.title), subtitle) { openSearchResult(e) })
            }
        })
    }

    private fun openSearchResult(e: pl.sinusoide.sinuschat.util.SettingsSearch.Entry) {
        val ctx = requireContext()
        openingResult = true
        searchField?.let { hideKeyboard(it); it.clearFocus() }
        val cls: Class<*> = when (e.target) {
            SettingTarget.NOTIFICATIONS -> NotificationSettingsActivity::class.java
            SettingTarget.SOUND -> SoundSettingsActivity::class.java
            SettingTarget.PRIVACY -> PrivacySettingsActivity::class.java
            SettingTarget.SCREEN_LOCK -> ScreenLockSettingsActivity::class.java
            SettingTarget.ACCOUNT -> AccountSettingsActivity::class.java
            SettingTarget.THEMES -> ThemeSettingsActivity::class.java
            SettingTarget.LANGUAGE -> LanguageActivity::class.java
            SettingTarget.TRANSLATION -> TranslationSettingsActivity::class.java
            SettingTarget.STORAGE -> StorageSettingsActivity::class.java
            SettingTarget.INTERNET -> InternetSettingsActivity::class.java
            SettingTarget.EXPERIMENTAL -> ExperimentalActivity::class.java
            SettingTarget.DIAGNOSTICS -> DiagnosticsActivity::class.java
            SettingTarget.REPORT -> ReportProblemActivity::class.java
            SettingTarget.SUPPORT -> SupportActivity::class.java
        }
        val intent = if (e.isScreen) Intent(ctx, cls)
            else pl.sinusoide.sinuschat.util.SettingsHighlight.intent(ctx, cls, e.title)
        when (e.target) {
            SettingTarget.ACCOUNT -> requireAuthThen { startActivity(intent) }
            SettingTarget.SCREEN_LOCK ->
                if (LockManager.hasPin(ctx)) requireAuthThen { startActivity(intent) }
                else ScreenLockActivity.start(requireActivity(), ScreenLockActivity.MODE_SET, openSettings = true)
            else -> startActivity(intent)
        }
    }

    private fun hideKeyboard(v: View) {
        (requireContext().getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager)
            ?.hideSoftInputFromWindow(v.windowToken, 0)
    }

    private var settingsContent: LinearLayout? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val content = settingsContent ?: return

        val versionName = try {
            val pInfo = requireContext().packageManager.getPackageInfo(requireContext().packageName, 0)
            pInfo.versionName ?: "1.0"
        } catch (_: Exception) { "1.0" }

        content.addView(sectionTitle(getString(R.string.settings_section_account)))
        content.addView(card().apply {
            addView(settingsRow(R.drawable.ic_key, 0xFFE67E22.toInt(), getString(R.string.account),
                getString(R.string.account_sub)) {
                requireAuthThen { startActivity(Intent(requireContext(), AccountSettingsActivity::class.java)) }
            })
            addView(divider())
            addView(settingsRow(R.drawable.ic_lock, 0xFF9B6BE0.toInt(), getString(R.string.privacy),
                getString(R.string.privacy_sub), onSubtitle = { privacySubtitle = it }) {
                startActivity(Intent(requireContext(), PrivacySettingsActivity::class.java))
            })
            addView(divider())
            addView(settingsRow(R.drawable.ic_qr_code, 0xFF26A5B8.toInt(), getString(R.string.pclink_title),
                getString(R.string.pclink_sub)) {
                startActivity(Intent(requireContext(), pl.sinusoide.sinuschat.pclink.PcLinkActivity::class.java))
            })
        })

        content.addView(sectionTitle(getString(R.string.settings_section_app)))
        content.addView(card().apply {
            addView(settingsRow(R.drawable.ic_notifications, 0xFFE5A50A.toInt(), getString(R.string.notif_settings_title),
                "", onSubtitle = { notificationsSubtitle = it }) {
                startActivity(Intent(requireContext(), NotificationSettingsActivity::class.java))
            })
            addView(divider())
            addView(settingsRow(R.drawable.ic_call_speaker_on, 0xFFE67E22.toInt(), getString(R.string.sound_title),
                getString(R.string.sound_sub)) {
                startActivity(Intent(requireContext(), SoundSettingsActivity::class.java))
            })
            addView(divider())
            addView(settingsRow(R.drawable.ic_palette, 0xFFE0578B.toInt(), getString(R.string.themes),
                getString(R.string.theme_sub)) {
                startActivity(Intent(requireContext(), ThemeSettingsActivity::class.java))
            })
            addView(divider())
            addView(settingsRow(R.drawable.ic_language, cAccent, getString(R.string.app_language),
                "", onSubtitle = { languageSubtitle = it }) {
                startActivity(Intent(requireContext(), LanguageActivity::class.java))
            })
            addView(divider())
            addView(settingsRow(R.drawable.ic_translate, 0xFF2FA88F.toInt(), getString(R.string.translate_title),
                getString(R.string.translate_sub)) {
                startActivity(Intent(requireContext(), TranslationSettingsActivity::class.java))
            })
            addView(divider())
            addView(settingsRow(R.drawable.ic_storage, 0xFF3FB97A.toInt(), getString(R.string.device_storage),
                getString(R.string.storage_sub)) {
                startActivity(Intent(requireContext(), StorageSettingsActivity::class.java))
            })
            addView(divider())
            addView(settingsRow(R.drawable.ic_internet, 0xFF26A5B8.toInt(), getString(R.string.internet),
                getString(R.string.internet_sub)) {
                startActivity(Intent(requireContext(), InternetSettingsActivity::class.java))
            })
            addView(divider())
            addView(settingsRow(R.drawable.ic_experimental, 0xFFE5484D.toInt(), getString(R.string.experimental_title),
                getString(R.string.experimental_sub)) {
                startActivity(Intent(requireContext(), ExperimentalActivity::class.java))
            })
        })

        content.addView(sectionTitle(getString(R.string.settings_section_backup)))
        content.addView(card().apply {
            addView(settingsRow(R.drawable.ic_backup, cAccent, getString(R.string.backup_title),
                getString(R.string.backup_row_sub)) {
                startActivity(Intent(requireContext(), BackupActivity::class.java))
            })
        })

        content.addView(sectionTitle(getString(R.string.settings_section_help)))
        content.addView(card().apply {
            addView(settingsRow(R.drawable.ic_question_exchange, 0xFF3FB97A.toInt(), getString(R.string.diagnostics_title),
                getString(R.string.diagnostics_sub)) {
                startActivity(Intent(requireContext(), DiagnosticsActivity::class.java))
            })
            addView(divider())
            addView(settingsRow(R.drawable.ic_report, 0xFFE67E22.toInt(), getString(R.string.report_title),
                getString(R.string.report_sub)) {
                startActivity(Intent(requireContext(), ReportProblemActivity::class.java))
            })
        })

        content.addView(sectionTitle(getString(R.string.settings_section_about)))
        content.addView(card().apply {
            addView(settingsRow(R.drawable.ic_heart, 0xFFE5484D.toInt(), getString(R.string.support_title),
                getString(R.string.support_sub)) {
                startActivity(Intent(requireContext(), SupportActivity::class.java))
            })
            addView(divider())
            addView(settingsRow(R.drawable.ic_lightbulb, cAccent, getString(R.string.wn_settings_title),
                getString(R.string.wn_settings_sub, versionName)) {
                val lang = if (pl.sinusoide.sinuschat.util.LocaleManager.getEffective(requireContext()) == "pl") "pl" else "en"
                pl.sinusoide.sinuschat.util.SafeEngine.openLink(requireActivity(),
                    "${AppConfig.CHANGELOG_URL}?lang=$lang#v$versionName")
            })
            addView(divider())
            addView(settingsRow(R.drawable.ic_document, cSecondary, getString(R.string.docs_title),
                getString(R.string.docs_sub)) {
                val lang = if (pl.sinusoide.sinuschat.util.LocaleManager.getEffective(requireContext()) == "pl") "pl" else "en"
                pl.sinusoide.sinuschat.util.SafeEngine.openLink(requireActivity(), "${AppConfig.DOCS_URL}?lang=$lang")
            })
        })

        if (savedInstanceState == null) {
            for (i in 0 until content.childCount) {
                val child = content.getChildAt(i)
                child.alpha = 0f; child.translationY = 14 * dp
                child.animate().alpha(1f).translationY(0f)
                    .setStartDelay((i * 28L).coerceAtMost(300L)).setDuration(260)
                    .setInterpolator(android.view.animation.DecelerateInterpolator()).start()
            }
        }

        loadProfile()
        refreshStatusSubtitles()
    }

    override fun onResume() {
        super.onResume()
        openingResult = false
        if (isAdded) refreshStatusSubtitles()
    }

    private fun refreshStatusSubtitles() {
        val ctx = context ?: return
        languageSubtitle?.text = pl.sinusoide.sinuschat.util.LocaleManager.displayName(ctx,
            pl.sinusoide.sinuschat.util.LocaleManager.getCurrent(ctx))
        notificationsSubtitle?.text = getString(
            if (pl.sinusoide.sinuschat.util.NotificationPrefs.pushMode(ctx) == pl.sinusoide.sinuschat.util.NotificationPrefs.PushMode.FCM)
                R.string.notif_mode_fcm else R.string.notif_mode_no_google)
        val hasPin = LockManager.hasPin(ctx)
        privacySubtitle?.text = getString(when {
            hasPin && LockManager.isBiometricEnabled(ctx) -> R.string.priv_lock_on_bio
            hasPin -> R.string.priv_lock_on_pin
            else -> R.string.priv_lock_off
        })
    }

    private fun profileCard(): View {
        val ctx = requireContext()
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER_HORIZONTAL
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(tint(cAccent, 0x2E), cCard)).apply { cornerRadius = 22 * dp }
            setPadding((20 * dp).toInt(), (24 * dp).toInt(), (20 * dp).toInt(), (20 * dp).toInt())
        }
        val size = (96 * dp).toInt()
        val avatarFrame = FrameLayout(ctx).apply {
            clipChildren = false; clipToPadding = false
            isClickable = true
            contentDescription = getString(R.string.change_photo_cd)
        }
        avatarLetter = ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        avatarImage = ImageView(ctx).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = androidx.core.content.ContextCompat.getDrawable(ctx, R.drawable.bg_avatar_circle)
            clipToOutline = true
            visibility = View.GONE
        }
        avatarFrame.addView(avatarLetter, FrameLayout.LayoutParams(size, size))
        avatarFrame.addView(avatarImage, FrameLayout.LayoutParams(size, size))
        avatarFrame.addView(ImageView(ctx).apply {
            setImageResource(R.drawable.ic_camera); setColorFilter(android.graphics.Color.WHITE)
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(cAccent); setStroke((3 * dp).toInt(), cCard)
            }
            val p = (7 * dp).toInt(); setPadding(p, p, p, p)
        }, FrameLayout.LayoutParams((32 * dp).toInt(), (32 * dp).toInt(),
            android.view.Gravity.BOTTOM or android.view.Gravity.END))
        btnEditPhoto = avatarFrame
        avatarFrame.setOnClickListener {
            avatarFrame.animate().scaleX(0.94f).scaleY(0.94f).setDuration(80).withEndAction {
                avatarFrame.animate().scaleX(1f).scaleY(1f).setDuration(160)
                    .setInterpolator(android.view.animation.OvershootInterpolator()).start()
            }.start()
            showPhotoOptions()
        }
        card.addView(avatarFrame, LinearLayout.LayoutParams(size, size))

        tvName = TextView(ctx).apply {
            text = "—"; textSize = 23f; setTextColor(cPrimary)
            typeface = android.graphics.Typeface.DEFAULT_BOLD; gravity = android.view.Gravity.CENTER
            maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, (14 * dp).toInt(), 0, 0)
        }
        card.addView(tvName)

        val buttons = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER
            setPadding(0, (14 * dp).toInt(), 0, 0)
        }
        val nameBtn = pillButton(R.drawable.ic_edit, getString(R.string.change_name)) { showEditNameDialog() }
        btnEditName = nameBtn
        buttons.addView(nameBtn)
        buttons.addView(pillButton(R.drawable.ic_camera, getString(R.string.change_photo_cd)) { showPhotoOptions() },
            LinearLayout.LayoutParams(-2, -2).apply { marginStart = (10 * dp).toInt() })
        card.addView(buttons)
        return card
    }

    private fun pillButton(icon: Int, label: String, onClick: () -> Unit) = LinearLayout(requireContext()).apply {
        val flow = pl.sinusoide.sinuschat.util.ThemeManager.isFlow(context)
        val fg = if (flow) cPrimary else cAccent
        orientation = LinearLayout.HORIZONTAL
        gravity = android.view.Gravity.CENTER_VERTICAL
        background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = 18 * dp; setColor(tint(cAccent, 0x26))
        }
        setPadding((14 * dp).toInt(), (8 * dp).toInt(), (16 * dp).toInt(), (8 * dp).toInt())
        isClickable = true
        setOnClickListener { onClick() }
        addView(ImageView(requireContext()).apply {
            setImageResource(icon); setColorFilter(fg)
        }, LinearLayout.LayoutParams((16 * dp).toInt(), (16 * dp).toInt()).apply { marginEnd = (6 * dp).toInt() })
        addView(TextView(requireContext()).apply {
            text = label; textSize = 13.5f; setTextColor(fg); typeface = android.graphics.Typeface.DEFAULT_BOLD
        })
        if (flow) {
            pl.sinusoide.sinuschat.ui.FlowGlass.glass(this, 18f)
            setTag(R.id.flow_glass_keep, true)
        }
    }

    private fun settingsRow(
        icon: Int, color: Int, title: String, subtitle: String,
        badge: String? = null, chevron: Boolean = true,
        onSubtitle: ((TextView) -> Unit)? = null, onClick: () -> Unit
    ): View {
        val ctx = requireContext()
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding((14 * dp).toInt(), (12 * dp).toInt(), (12 * dp).toInt(), (12 * dp).toInt())
            background = androidx.core.content.ContextCompat.getDrawable(ctx, R.drawable.bg_menu_ripple)
            isClickable = true
            setOnClickListener { onClick() }
            addView(ImageView(ctx).apply {
                setImageResource(icon); setColorFilter(color)
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = 12 * dp; setColor(tint(color, 0x24))
                }
                val p = (9 * dp).toInt(); setPadding(p, p, p, p)
            }, LinearLayout.LayoutParams((40 * dp).toInt(), (40 * dp).toInt()).apply { marginEnd = (14 * dp).toInt() })
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                addView(LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    addView(TextView(ctx).apply {
                        text = title; textSize = 15.5f; setTextColor(cPrimary)
                    })
                    if (badge != null) addView(TextView(ctx).apply {
                        text = badge; textSize = 10f; letterSpacing = 0.08f
                        typeface = android.graphics.Typeface.DEFAULT_BOLD; setTextColor(0xFFE67E22.toInt())
                        background = android.graphics.drawable.GradientDrawable().apply {
                            cornerRadius = 8 * dp; setColor(0x26E67E22)
                        }
                        setPadding((7 * dp).toInt(), (2 * dp).toInt(), (7 * dp).toInt(), (2 * dp).toInt())
                    }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = (8 * dp).toInt() })
                })
                val sub = TextView(ctx).apply {
                    text = subtitle; textSize = 12.5f; setTextColor(cSecondary)
                    maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                    setPadding(0, (2 * dp).toInt(), 0, 0)
                }
                addView(sub)
                onSubtitle?.invoke(sub)
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = (8 * dp).toInt() })
            if (chevron) addView(ImageView(ctx).apply {
                setImageResource(R.drawable.ic_chevron_right); setColorFilter(tint(cSecondary, 0xAA))
            }, LinearLayout.LayoutParams((20 * dp).toInt(), (20 * dp).toInt()))
        }
    }

    private fun tint(color: Int, alpha: Int) = (color and 0x00FFFFFF) or (alpha shl 24)

    private fun card() = LinearLayout(requireContext()).apply {
        orientation = LinearLayout.VERTICAL
        background = pl.sinusoide.sinuschat.ui.ThemeUi.rounded(context, cCard, 18f)
        clipToOutline = true
    }

    private fun divider() = View(requireContext()).apply {
        setBackgroundColor(pl.sinusoide.sinuschat.ui.ThemeUi.divider(context))
        layoutParams = LinearLayout.LayoutParams(-1, (1 * dp).toInt().coerceAtLeast(1)).apply {
            marginStart = (68 * dp).toInt()
        }
    }

    private fun sectionTitle(text: String) = TextView(requireContext()).apply {
        this.text = text.uppercase()
        textSize = 12f; setTextColor(cAccent); typeface = android.graphics.Typeface.DEFAULT_BOLD
        letterSpacing = 0.06f
        setPadding((6 * dp).toInt(), (22 * dp).toInt(), 0, (8 * dp).toInt())
    }

    private fun loadProfile() {
        val name = SetupActivity.getUserDisplayName(requireContext())
        tvName.text = name.ifEmpty { getString(R.string.not_set) }
        
        val color = AvatarHelper.getMyColor(requireContext())
        val dp = resources.displayMetrics.density
        val size = (96 * dp).toInt()
        val letter = pl.sinusoide.sinuschat.util.AvatarHelper.initialOf(name)
        avatarLetter.setImageBitmap(AvatarHelper.createLetterBitmap(letter, color, size))

        if (AvatarHelper.loadAvatarInto(requireContext(), avatarImage)) {
            avatarImage.visibility = View.VISIBLE
            avatarLetter.visibility = View.GONE
            loadAvatarFromServer()
        } else {
            pl.sinusoide.sinuschat.util.ProfileSync.pullMissing(requireContext()) { changed ->
                if (changed && isAdded) loadProfile()
            }
        }
    }

    private fun loadAvatarFromServer() {
        val myHash = myHash
        if (myHash.isEmpty()) return
        AppExecutor.io {
            try {
                val user = ApiClient.getObject("/api/user/$myHash", requireContext()) ?: return@io
                val url = user.optStringOrNull("avatar_url") ?: return@io
                AvatarHelper.downloadAvatar(requireContext(), url) ?: return@io
                AppExecutor.main {
                    if (isAdded && AvatarHelper.loadAvatarInto(requireContext(), avatarImage)) {
                        avatarImage.visibility = View.VISIBLE
                        avatarLetter.visibility = View.GONE
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w("AboutFragment", "Avatar download: ${e.message}")
            }
        }
    }

    private fun showPhotoOptions() {
        val ctx = requireContext()
        val originalColor = AvatarHelper.getMyColor(ctx)
        pl.sinusoide.sinuschat.ui.ProfilePhotoSheet.show(
            requireActivity(), SetupActivity.getUserDisplayName(ctx), originalColor,
            AvatarHelper.getLocalAvatarPath(ctx)?.let { java.io.File(it) },
            pickPhoto = { onPicked -> onProfilePhotoPicked = onPicked; launchPhotoPicker() },
            onDismiss = { onProfilePhotoPicked = null }
        ) { color, photo, remove ->
            val colorChanged = !color.equals(originalColor, ignoreCase = true)
            if (colorChanged) applyAvatarColor(color)
            when {
                photo != null -> uploadNewAvatar(photo)
                remove -> removeAvatarPhoto()
                colorChanged -> PopupHelper.showSuccess(requireActivity(), getString(R.string.color_changed))
            }
        }
    }

    private fun applyAvatarColor(color: String) {
        val ctx = requireContext()
        AvatarHelper.setMyColor(ctx, color)
        val size = (96 * resources.displayMetrics.density).toInt()
        val letter = AvatarHelper.initialOf(SetupActivity.getUserDisplayName(ctx))
        avatarLetter.setImageBitmap(AvatarHelper.createLetterBitmap(letter, color, size))
        val app = ctx.applicationContext
        AppExecutor.io {
            ApiClient.post("/api/user/update", org.json.JSONObject().put("avatarColor", color), app)
        }
    }

    private fun removeAvatarPhoto() {
        val ctx = requireContext()
        AvatarHelper.deleteLocalAvatar(ctx)
        avatarImage.visibility = View.GONE
        avatarLetter.visibility = View.VISIBLE
        val size = (96 * resources.displayMetrics.density).toInt()
        val letter = AvatarHelper.initialOf(SetupActivity.getUserDisplayName(ctx))
        avatarLetter.setImageBitmap(AvatarHelper.createLetterBitmap(letter, AvatarHelper.getMyColor(ctx), size))

        val app = ctx.applicationContext
        AppExecutor.io {
            val ok = ApiClient.post("/api/user/update", org.json.JSONObject().put("avatarUrl", ""), app)
            pl.sinusoide.sinuschat.util.ContactCache.saveAvatarUrl(app, SetupActivity.getUserPhoneHash(app), null)
            pl.sinusoide.sinuschat.util.ConversationCache.clear(app)
            if (ok != null) pl.sinusoide.sinuschat.crypto.ProfileCrypto.announceProfileChange(app)
        }
        PopupHelper.show(requireActivity(), getString(R.string.photo_deleted))
    }

    private fun showEditNameDialog() {
        val ctx = requireContext()
        val d = resources.displayMetrics.density
        val accent = pl.sinusoide.sinuschat.ui.ThemeUi.accent(ctx)
        val red = androidx.core.content.ContextCompat.getColor(ctx, R.color.sinuschat_error)
        val secondary = pl.sinusoide.sinuschat.ui.ThemeUi.textSecondary(ctx)
        val currentName = SetupActivity.getUserDisplayName(ctx)
        val color = AvatarHelper.getMyColor(ctx)
        val minLen = 2
        val maxLen = 32

        val sheet = com.google.android.material.bottomsheet.BottomSheetDialog(ctx, R.style.Theme_SinusChat_BottomSheet_Input)

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER_HORIZONTAL
            setPadding((24 * d).toInt(), (12 * d).toInt(), (24 * d).toInt(), (20 * d).toInt())
        }
        root.addView(View(ctx).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 3 * d; setColor(pl.sinusoide.sinuschat.ui.ThemeUi.handle(context))
            }
        }, LinearLayout.LayoutParams((36 * d).toInt(), (4 * d).toInt()).apply {
            bottomMargin = (18 * d).toInt()
        })
        root.addView(TextView(ctx).apply {
            text = getString(R.string.rename)
            textSize = 20f; setTextColor(pl.sinusoide.sinuschat.ui.ThemeUi.textPrimary(context))
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = android.view.Gravity.CENTER
        })
        root.addView(TextView(ctx).apply {
            text = getString(R.string.rename_desc)
            textSize = 13.5f; setTextColor(secondary); gravity = android.view.Gravity.CENTER
            setPadding(0, (6 * d).toInt(), 0, (20 * d).toInt())
        })

        val previewSize = (88 * d).toInt()
        val preview = ImageView(ctx)
        root.addView(preview, LinearLayout.LayoutParams(previewSize, previewSize).apply {
            bottomMargin = (20 * d).toInt()
        })
        val localPhoto = AvatarHelper.getLocalAvatarPath(ctx)?.let { java.io.File(it) }

        val input = EditText(ctx).apply {
            setText(currentName)
            setSelection(text.length)
            textSize = 17f
            setTextColor(pl.sinusoide.sinuschat.ui.ThemeUi.textPrimary(ctx))
            setHintTextColor(pl.sinusoide.sinuschat.ui.ThemeUi.textMuted(ctx))
            hint = getString(R.string.your_name_hint)
            isSingleLine = true
            filters = arrayOf(android.text.InputFilter.LengthFilter(maxLen))
            background = null
            setPadding(0, (4 * d).toInt(), 0, (4 * d).toInt())
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_WORDS
        }
        val counter = TextView(ctx).apply { textSize = 12f; setTextColor(secondary) }
        val hintLine = TextView(ctx).apply {
            textSize = 12f; setTextColor(red)
            setPadding((4 * d).toInt(), (6 * d).toInt(), 0, 0)
            visibility = View.INVISIBLE
        }
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((16 * d).toInt(), (10 * d).toInt(), (16 * d).toInt(), (10 * d).toInt())
        }
        fun cardBg(focused: Boolean, error: Boolean) = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = 16 * d
            setColor(pl.sinusoide.sinuschat.ui.ThemeUi.card(ctx))
            setStroke((1.5f * d).toInt(), when {
                error -> red
                focused -> accent
                else -> 0x00000000
            })
        }
        val labelRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        labelRow.addView(TextView(ctx).apply {
            text = getString(R.string.display_name_label); textSize = 12f; setTextColor(accent)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        labelRow.addView(counter)
        card.addView(labelRow)
        card.addView(input, LinearLayout.LayoutParams(-1, -2))
        root.addView(card, LinearLayout.LayoutParams(-1, -2))
        root.addView(hintLine, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = (18 * d).toInt() })

        val saveBtn = TextView(ctx).apply {
            text = getString(R.string.save)
            textSize = 16f; setTextColor(android.graphics.Color.WHITE)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = android.view.Gravity.CENTER
            background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = 26 * d; setColor(accent) }
            setPadding(0, (14 * d).toInt(), 0, (14 * d).toInt())
            isClickable = true
        }
        root.addView(saveBtn, LinearLayout.LayoutParams(-1, -2))
        root.addView(TextView(ctx).apply {
            text = getString(R.string.cancel)
            textSize = 15f; setTextColor(secondary); gravity = android.view.Gravity.CENTER
            setPadding(0, (14 * d).toInt(), 0, (4 * d).toInt())
            isClickable = true
            setOnClickListener { sheet.dismiss() }
        }, LinearLayout.LayoutParams(-1, -2))

        fun render() {
            val raw = input.text.toString()
            val trimmed = raw.trim()
            val letterBmp = AvatarHelper.createLetterBitmap(
                AvatarHelper.initialOf(trimmed.ifEmpty { currentName }), color, previewSize)
            if (localPhoto != null) {
                val ph = android.graphics.drawable.BitmapDrawable(resources, letterBmp)
                com.bumptech.glide.Glide.with(preview).load(localPhoto)
                    .signature(com.bumptech.glide.signature.ObjectKey(localPhoto.lastModified()))
                    .circleCrop().placeholder(ph).error(ph).into(preview)
            } else {
                preview.setImageBitmap(letterBmp)
            }
            counter.text = "${raw.length}/$maxLen"
            val tooShort = trimmed.isNotEmpty() && trimmed.length < minLen
            val empty = trimmed.isEmpty()
            hintLine.text = when {
                empty -> getString(R.string.name_required)
                tooShort -> getString(R.string.name_too_short, minLen)
                else -> ""
            }
            hintLine.visibility = if (empty || tooShort) View.VISIBLE else View.INVISIBLE
            card.background = cardBg(input.hasFocus(), empty || tooShort)
            val ok = trimmed.length >= minLen && trimmed != currentName
            saveBtn.isEnabled = ok
            saveBtn.alpha = if (ok) 1f else 0.45f
        }

        input.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) { render() }
        })
        input.setOnFocusChangeListener { _, _ -> render() }

        fun submit() {
            val newName = input.text.toString().trim()
            if (newName.length < minLen || newName == currentName) return
            updateName(newName)
            sheet.dismiss()
        }
        saveBtn.setOnClickListener { submit() }
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) { submit(); true } else false
        }

        render()
        sheet.setContentView(root)
        sheet.behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
        sheet.behavior.skipCollapsed = true
        sheet.setOnShowListener {
            input.requestFocus()
            val imm = ctx.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
            input.post { imm?.showSoftInput(input, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT) }
        }
        pl.sinusoide.sinuschat.ui.FlowGlass.show(sheet)
    }

    private fun updateName(newName: String) {
        val ctx = requireContext()
        AppExecutor.io {
            val ok = ApiClient.post("/api/user/update",
                org.json.JSONObject().put("displayName", pl.sinusoide.sinuschat.crypto.ProfileCrypto.sealName(ctx, newName)), ctx)
            AppExecutor.main {
                if (ok != null) {
                    SetupActivity.setUserDisplayName(ctx, newName)
                    pl.sinusoide.sinuschat.crypto.ProfileCrypto.announceProfileChange(ctx)
                    tvName.text = newName
                    
                    val color = AvatarHelper.getMyColor(ctx)
                    val dp = resources.displayMetrics.density
                    val size = (96 * dp).toInt()
                    val letter = pl.sinusoide.sinuschat.util.AvatarHelper.initialOf(newName)
                    avatarLetter.setImageBitmap(AvatarHelper.createLetterBitmap(letter, color, size))
                    
                    PopupHelper.showSuccess(requireActivity(), getString(R.string.name_changed))
                } else {
                    PopupHelper.showError(requireActivity(), getString(R.string.error_rename))
                }
            }
        }
    }

    private fun uploadNewAvatar(uri: Uri) {
        val ctx = requireContext().applicationContext
        PopupHelper.show(requireActivity(), getString(R.string.sending_photo))

        val localPath = AvatarHelper.saveAvatarLocally(requireContext(), uri)
        if (localPath != null) {
            SetupActivity.setUserAvatarUri(requireContext(), localPath)
            AvatarHelper.loadAvatarInto(requireContext(), avatarImage)
            avatarImage.visibility = View.VISIBLE
            avatarLetter.visibility = View.GONE
        }

        val myHash = SetupActivity.getUserPhoneHash(ctx)
        FileUploader.uploadAvatarAsync(requireContext(), uri, myHash) { remoteUrl ->
            if (remoteUrl == null) {
                if (isAdded) PopupHelper.show(requireActivity(), getString(R.string.photo_saved_local))
                return@uploadAvatarAsync
            }
            val versioned = AvatarHelper.versionedUrl(remoteUrl)
            AppExecutor.io {
                val ok = ApiClient.post("/api/user/update",
                    org.json.JSONObject()
                        .put("avatarUrl", versioned)
                        .put("avatarUpdatedAt", System.currentTimeMillis()), ctx)
                AppExecutor.main {
                    if (ok != null) {
                        pl.sinusoide.sinuschat.util.ContactCache.saveAvatarUrl(ctx, myHash, versioned)
                        pl.sinusoide.sinuschat.util.ConversationCache.clear(ctx)
                        pl.sinusoide.sinuschat.crypto.ProfileCrypto.announceProfileChange(ctx)
                    }
                    if (!isAdded) return@main
                    if (ok != null) {
                        PopupHelper.showSuccess(requireActivity(), getString(R.string.photo_changed))
                    } else {
                        PopupHelper.showError(requireActivity(), getString(R.string.photo_upload_server_error))
                    }
                }
            }
        }
    }
}
