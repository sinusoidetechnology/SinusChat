package pl.sinusoide.sinuschat.setup

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.addCallback
import androidx.fragment.app.Fragment
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.LabelFormatter
import com.google.android.material.slider.Slider
import pl.sinusoide.sinuschat.R
import pl.sinusoide.sinuschat.ui.GreetingView
import pl.sinusoide.sinuschat.ui.SetupIllustration
import pl.sinusoide.sinuschat.ui.ThemeUi
import pl.sinusoide.sinuschat.util.AutoDelete
import pl.sinusoide.sinuschat.util.BackupPrefs
import pl.sinusoide.sinuschat.util.PopupHelper
import pl.sinusoide.sinuschat.util.ExperimentalFeatures
import pl.sinusoide.sinuschat.util.LocaleManager
import pl.sinusoide.sinuschat.util.NetworkSettings
import pl.sinusoide.sinuschat.util.NotificationPrefs
import pl.sinusoide.sinuschat.util.SoundPrefs
import pl.sinusoide.sinuschat.util.ThemeManager
import pl.sinusoide.sinuschat.util.Translations

class SetupStepFragment : Fragment() {

    enum class Step {
        WELCOME, TERMS, START, LANGUAGE, CUSTOMIZE, THEME, ACCOUNT, LOCK, PRIVACY,
        NOTIFY, INTERNET, EXPERIMENT, SOUND, TRANSLATE, BACKUP, DONE, TRANSFER
    }

    companion object {
        private const val ARG_STEP = "step"
        var lastStep: Step? = null

        fun of(step: Step) = SetupStepFragment().apply {
            arguments = Bundle().apply { putString(ARG_STEP, step.name) }
        }

        private const val ARG_EMAIL = "email"

        fun transferFor(email: String) = of(Step.TRANSFER).apply { requireArguments().putString(ARG_EMAIL, email) }

        private val WIZARD = listOf(Step.NOTIFY, Step.CUSTOMIZE, Step.THEME, Step.ACCOUNT,
            Step.LOCK, Step.PRIVACY, Step.INTERNET, Step.EXPERIMENT, Step.SOUND, Step.TRANSLATE, Step.BACKUP, Step.DONE)
    }

    private val step: Step
        get() = runCatching { Step.valueOf(arguments?.getString(ARG_STEP) ?: "") }
            .getOrDefault(Step.START)

    private val dp get() = resources.displayMetrics.density
    private var art: SetupIllustration? = null

    private var lockHasPin = false

    private var lockHasDuress = false

    private var notifyBatteryFree = false

    private var doneLogo: View? = null

    private var privacyOptions: pl.sinusoide.sinuschat.ui.PrivacyOptionsView? = null

    private val lockNext = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK && isAdded) go(Step.PRIVACY)
    }

    private var notifyPermissionAsked = false
    private val notifyPermission = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { if (isAdded) askNotificationConsents() }

    private val pickBackupFolder = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri == null || !isAdded) return@registerForActivityResult
        val ctx = requireContext()
        try {
            ctx.contentResolver.takePersistableUriPermission(uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        } catch (_: Exception) {}
        val name = androidx.documentfile.provider.DocumentFile.fromTreeUri(ctx, uri)?.name.orEmpty()
        BackupPrefs.setFolder(ctx, uri.toString(), name)
        refresh()
        val app = ctx.applicationContext
        pl.sinusoide.sinuschat.util.AppExecutor.io {
            androidx.documentfile.provider.DocumentFile.fromTreeUri(app, uri)?.let {
                pl.sinusoide.sinuschat.util.BackupRunner.backupDir(it)
            }
        }
    }

    private val recoveryKit = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (!isAdded) return@registerForActivityResult
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            PopupHelper.showSuccess(requireActivity(), getString(R.string.backup_key_saved))
            go(Step.DONE)
        } else PopupHelper.showError(requireActivity(), getString(R.string.setup_backup_key_needed))
    }

    private var restoreUri: android.net.Uri? = null
    private var restoreFile: java.io.File? = null
    private var restoreDriveHidden: Boolean? = null
    private var working = false
    private enum class TransferState { CHOICE, WORKING, DONE, ERROR }
    private var transferState = TransferState.CHOICE
    private var moveIcon: pl.sinusoide.sinuschat.ui.MoveDataIconView? = null
    private var retry: (() -> Unit)? = null
    private var workTitle: TextView? = null
    private var workBar: android.widget.ProgressBar? = null
    private var workDetail: TextView? = null
    private val blockBack = object : androidx.activity.OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            if (transferState == TransferState.ERROR) backToChoice()
        }
    }

    private val scanMoveCode = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (!isAdded) return@registerForActivityResult
        val text = result.data?.getStringExtra("qr_result") ?: return@registerForActivityResult
        val code = pl.sinusoide.sinuschat.util.DeviceTransfer.parse(text)
        if (code == null) showTransferError(getString(R.string.setup_transfer_bad_qr))
        else receiveFromPhone(code)
    }

    private val pickRestoreFile = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null && isAdded) onRestoreFilePicked(uri) }

    private val pickUsbFile = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uri = result.data?.data
        if (result.resultCode == android.app.Activity.RESULT_OK && uri != null && isAdded) onRestoreFilePicked(uri)
    }

    private fun onRestoreFilePicked(uri: android.net.Uri) {
        restoreUri = uri
        restoreFile = null
        restoreDriveHidden = null
        val resolver = requireContext().contentResolver
        askKeyFor { resolver.openInputStream(uri) ?: throw java.io.IOException("Nie da się otworzyć pliku") }
    }

    private val anonKey = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (!isAdded) return@registerForActivityResult
        val rka = pl.sinusoide.sinuschat.RecoveryKeyActivity
        val key = rka.keyFrom(result.data)
        if (result.resultCode == android.app.Activity.RESULT_OK && key != null) {
            anonRetryWords = if (rka.enteredByWords(result.data)) key.words(requireContext()) else null
            loginAnon(key)
            return@registerForActivityResult
        }
        when (rka.failureFrom(result.data)) {
            rka.FAIL_BAD_QR -> anonFailed(getString(R.string.anon_fail_bad_qr), null)
            rka.FAIL_BAD_WORDS -> anonFailed(getString(R.string.anon_fail_bad_words), rka.wordsFrom(result.data))
            else -> Unit
        }
    }

    private var anonRetryWords: List<String>? = null

    private fun openAnonEntry() {
        val words = anonRetryWords
        anonRetryWords = null
        anonKey.launch(pl.sinusoide.sinuschat.RecoveryKeyActivity.anonLoginIntent(requireContext(), words))
    }

    private fun anonFailed(message: String, words: List<String>?) {
        anonRetryWords = words
        retry = { openAnonEntry() }
        showTransferError(message, getString(R.string.anon_fail_title))
    }

    private fun loginAnon(key: pl.sinusoide.sinuschat.util.RecoveryKey) {
        val app = requireContext().applicationContext
        showWorking(getString(R.string.anon_login_working))
        pl.sinusoide.sinuschat.util.AppExecutor.io {
            val r = pl.sinusoide.sinuschat.setup.AnonAccount.login(app, key)
            if (r == pl.sinusoide.sinuschat.setup.AnonAccount.Result.Ok) pl.sinusoide.sinuschat.crypto.ProfileCrypto.restoreForAnon(app, key)
            pl.sinusoide.sinuschat.util.AppExecutor.main {
                if (!isAdded) return@main
                when (r) {
                    pl.sinusoide.sinuschat.setup.AnonAccount.Result.Ok -> {
                        pl.sinusoide.sinuschat.util.BackupPrefs.setRecoveryKey(app, key)
                        SetupActivity.setRestoreWanted(app, false)
                        (activity as? SetupActivity)?.navigateTo(
                            transferFor(pl.sinusoide.sinuschat.setup.AnonAccount.idFor(key)))
                    }
                    pl.sinusoide.sinuschat.setup.AnonAccount.Result.NoAccount ->
                        anonFailed(getString(R.string.anon_fail_no_account), anonRetryWords)
                    pl.sinusoide.sinuschat.setup.AnonAccount.Result.Busy -> {
                        retry = { loginAnon(key) }
                        showTransferError(getString(R.string.anon_fail_busy), getString(R.string.anon_fail_title))
                    }
                    else -> {
                        retry = { loginAnon(key) }
                        showTransferError(getString(R.string.anon_fail_offline), getString(R.string.anon_fail_title))
                    }
                }
            }
        }
    }

    private val restoreKey = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (!isAdded) return@registerForActivityResult
        val key = pl.sinusoide.sinuschat.RecoveryKeyActivity.keyFrom(result.data)
        if (result.resultCode == android.app.Activity.RESULT_OK && key != null) restoreFromBackup(key)
        else if (transferState != TransferState.CHOICE) backToChoice()
    }

    private val driveRestoreConsent = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (!isAdded) return@registerForActivityResult
        val token = pl.sinusoide.sinuschat.util.DriveBackup.tokenFromResult(requireContext(), result.data)
        if (token.isNullOrEmpty()) {
            showTransferError(getString(R.string.backup_drive_denied))
            return@registerForActivityResult
        }
        showWorking(getString(R.string.setup_transfer_drive_search))
        val app = requireContext().applicationContext
        pl.sinusoide.sinuschat.util.AppExecutor.io { searchDrive(app, token) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        savedInstanceState?.let { st ->
            restoreUri = androidx.core.os.BundleCompat.getParcelable(st, "restore_uri", android.net.Uri::class.java)
            restoreFile = st.getString("restore_file")?.let { java.io.File(it) }
            if (st.containsKey("restore_drive_hidden")) restoreDriveHidden = st.getBoolean("restore_drive_hidden")
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        restoreUri?.let { outState.putParcelable("restore_uri", it) }
        restoreFile?.let { outState.putString("restore_file", it.path) }
        restoreDriveHidden?.let { outState.putBoolean("restore_drive_hidden", it) }
    }

    private val driveConsent = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (!isAdded) return@registerForActivityResult
        val ctx = requireContext()
        val token = pl.sinusoide.sinuschat.util.DriveBackup.tokenFromResult(ctx, result.data)
        if (token.isNullOrEmpty()) {
            PopupHelper.showError(requireActivity(), getString(R.string.backup_drive_denied))
        } else {
            BackupPrefs.setTarget(ctx, BackupPrefs.TARGET_DRIVE)
            BackupPrefs.setDriveAccount(ctx, driveAccount(result.data))
            refresh()
            PopupHelper.showSuccess(requireActivity(), getString(R.string.backup_drive_connected))
        }
    }

    private fun driveAccount(data: Intent?): String = try {
        com.google.android.gms.auth.api.identity.Identity.getAuthorizationClient(requireContext())
            .getAuthorizationResultFromIntent(data).toGoogleSignInAccount()?.email.orEmpty()
    } catch (_: Throwable) { "" }

    private lateinit var content: LinearLayout
    private var scrollHeader: View? = null

    private lateinit var column: LinearLayout
    private lateinit var scroll: ScrollView
    private var pinnedHead: View? = null
    private var onCard = Color.WHITE
    private var onCardDim = 0xB3FFFFFF.toInt()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?,
                              savedInstanceState: Bundle?): View {
        val ctx = requireContext()
        lastStep = step
        if (step in WIZARD) SetupActivity.setWizardStep(ctx, step.name)
        onCard = if (ThemeManager.isFlow(ctx)) Color.WHITE else ThemeUi.textPrimary(ctx)
        onCardDim = if (ThemeManager.isFlow(ctx)) 0xB3FFFFFF.toInt() else ThemeUi.textSecondary(ctx)

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((22 * dp).toInt(), (10 * dp).toInt(), (22 * dp).toInt(), (24 * dp).toInt())
        }
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            v.setPadding(v.paddingLeft, bars.top + (10 * dp).toInt(),
                v.paddingRight, bars.bottom + (18 * dp).toInt())
            insets
        }

        if (step == Step.WELCOME) return welcomeScreen(ctx, root)

        val icon = when (step) {
            Step.START -> pl.sinusoide.sinuschat.ui.IconDrawView.chatSetup(ctx)
            Step.LANGUAGE -> pl.sinusoide.sinuschat.ui.IconDrawView.language(ctx)
            Step.THEME -> pl.sinusoide.sinuschat.ui.IconDrawView.palette(ctx)
            Step.INTERNET -> pl.sinusoide.sinuschat.ui.IconDrawView.wifi(ctx)
            Step.SOUND -> pl.sinusoide.sinuschat.ui.IconDrawView.speaker(ctx)
            else -> null
        }
        if (step == Step.CUSTOMIZE) {
            root.addView(pl.sinusoide.sinuschat.ui.JuggleIconView(ctx).apply {
                setColor(pl.sinusoide.sinuschat.ui.IconDrawView.ink(ctx))
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                layoutParams = LinearLayout.LayoutParams(-1, (196 * dp).toInt())
            })
        } else if (step == Step.LOCK) {
            Unit
        } else if (step == Step.TRANSFER) {
            moveIcon = pl.sinusoide.sinuschat.ui.MoveDataIconView(ctx).apply {
                setColor(pl.sinusoide.sinuschat.ui.IconDrawView.ink(ctx))
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                layoutParams = LinearLayout.LayoutParams(-1, (196 * dp).toInt())
            }
            root.addView(moveIcon)
        } else if (step == Step.TERMS) {
            root.addView(pl.sinusoide.sinuschat.ui.DocumentIconView(ctx).apply {
                setColor(pl.sinusoide.sinuschat.ui.IconDrawView.ink(ctx))
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                layoutParams = LinearLayout.LayoutParams(-1, (196 * dp).toInt())
            })
        } else if (step == Step.DONE) {
            doneLogo = pl.sinusoide.sinuschat.ui.LogoDrawView(ctx).apply {
                contentDescription = getString(R.string.app_name)
                layoutParams = LinearLayout.LayoutParams((176 * dp).toInt(), (176 * dp).toInt()).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                    topMargin = (10 * dp).toInt()
                    bottomMargin = (10 * dp).toInt()
                }
            }
            root.addView(doneLogo)
        } else if (step == Step.TRANSLATE) {
            val white = pl.sinusoide.sinuschat.ui.IconDrawView.ink(ctx)
            root.addView(pl.sinusoide.sinuschat.ui.IconDrawView(ctx).apply {
                setColors(white, white)
                setDrawing(pl.sinusoide.sinuschat.ui.IconDrawView.translate(ctx))
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                layoutParams = LinearLayout.LayoutParams(-1, (196 * dp).toInt())
            })
        } else if (step == Step.BACKUP) {
            root.addView(pl.sinusoide.sinuschat.ui.CloudIconView(ctx).apply {
                setColor(pl.sinusoide.sinuschat.ui.IconDrawView.ink(ctx))
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                layoutParams = LinearLayout.LayoutParams(-1, (196 * dp).toInt())
            })
        } else if (step == Step.EXPERIMENT) {
            root.addView(pl.sinusoide.sinuschat.ui.FlaskIconView(ctx).apply {
                setColor(pl.sinusoide.sinuschat.ui.IconDrawView.ink(ctx))
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                layoutParams = LinearLayout.LayoutParams(-1, (196 * dp).toInt())
            })
        } else if (step == Step.NOTIFY) {
            root.addView(pl.sinusoide.sinuschat.ui.BellIconView(ctx).apply {
                setColor(pl.sinusoide.sinuschat.ui.IconDrawView.ink(ctx))
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                layoutParams = LinearLayout.LayoutParams(-1, (196 * dp).toInt())
            })
        } else if (step == Step.PRIVACY) {
            root.addView(pl.sinusoide.sinuschat.ui.ShieldIconView(ctx).apply {
                setColor(pl.sinusoide.sinuschat.ui.IconDrawView.ink(ctx))
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                layoutParams = LinearLayout.LayoutParams(-1, (196 * dp).toInt())
            })
        } else if (step == Step.ACCOUNT) {
            root.addView(pl.sinusoide.sinuschat.ui.ClockIconView(ctx).apply {
                setColor(pl.sinusoide.sinuschat.ui.IconDrawView.ink(ctx))
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                layoutParams = LinearLayout.LayoutParams(-1, (196 * dp).toInt())
            })
        } else if (icon != null) {
            val white = pl.sinusoide.sinuschat.ui.IconDrawView.ink(ctx)
            root.addView(pl.sinusoide.sinuschat.ui.IconDrawView(ctx).apply {
                setColors(white, white)
                setDrawing(icon)
                layoutParams = LinearLayout.LayoutParams(-1, (196 * dp).toInt())
            })
        } else {
            art = SetupIllustration(ctx).apply {
                setColors(onCard, ThemeUi.accent(ctx))
                setScene(sceneFor(step))
                layoutParams = LinearLayout.LayoutParams(-1, (196 * dp).toInt())
            }
            root.addView(art)
        }

        val side = (22 * dp).toInt()
        content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(side, 0, side, 0)
        }
        column = root
        val header = scrollHeader
        scroll = ScrollView(ctx).apply {
            isFillViewport = true
            scrollBarStyle = View.SCROLLBARS_OUTSIDE_OVERLAY
            if (header == null) addView(content)
            else addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                addView(header, LinearLayout.LayoutParams(-1, (196 * dp).toInt()).apply {
                    marginStart = side
                    marginEnd = side
                })
                addView(content, LinearLayout.LayoutParams(-1, -2))
            })
        }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f).apply {
            marginStart = -side
            marginEnd = -side
        })

        build()
        fadeIn(root)
        return root
    }

    private fun welcomeScreen(ctx: Context, root: LinearLayout): View {
        root.setPadding((26 * dp).toInt(), (24 * dp).toInt(), (26 * dp).toInt(), (28 * dp).toInt())
        root.gravity = Gravity.CENTER_HORIZONTAL
        val logoMs = pl.sinusoide.sinuschat.ui.LogoDrawView.DRAW_MS

        root.addView(View(ctx), LinearLayout.LayoutParams(-1, 0, 0.8f))

        root.addView(pl.sinusoide.sinuschat.ui.LogoDrawView(ctx).apply {
            contentDescription = getString(R.string.app_name)
            layoutParams = LinearLayout.LayoutParams((176 * dp).toInt(), (176 * dp).toInt())
        })

        val name = TextView(ctx).apply {
            text = getString(R.string.app_name)
            textSize = 26f
            setTextColor(onCard)
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.01f
            gravity = Gravity.CENTER
            alpha = 0f
            translationY = 8 * dp
            layoutParams = LinearLayout.LayoutParams(-1, -2)
                .apply { topMargin = (8 * dp).toInt() }
        }
        root.addView(name)
        name.animate().alpha(1f).translationY(0f).setStartDelay(logoMs - 350).setDuration(500)
            .setInterpolator(android.view.animation.PathInterpolator(0.2f, 0.8f, 0.2f, 1f)).start()

        root.addView(GreetingView(ctx).apply {
            setTextColor(onCard)
            startAfter(logoMs + 250)
            layoutParams = LinearLayout.LayoutParams(-1, (128 * dp).toInt())
                .apply { topMargin = (22 * dp).toInt() }
        })

        root.addView(View(ctx), LinearLayout.LayoutParams(-1, 0, 1f))

        content = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        root.addView(content, LinearLayout.LayoutParams(-1, -2))
        val go = TextView(ctx).apply {
            text = getString(R.string.setup_welcome_go)
            textSize = 16f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                cornerRadius = 26 * dp
                setColor(ThemeUi.accent(ctx))
            }
            setPadding(0, (15 * dp).toInt(), 0, (15 * dp).toInt())
            isClickable = true
            alpha = 0f
            setOnClickListener { go(Step.LANGUAGE) }
            layoutParams = LinearLayout.LayoutParams(-1, -2)
        }
        content.addView(go)
        go.animate().alpha(1f).setStartDelay(logoMs + 500).setDuration(450).start()

        fadeIn(root)
        return root
    }

    private fun build() {
        when (step) {
            Step.WELCOME -> Unit
            Step.TERMS -> terms()
            Step.START -> start()
            Step.LANGUAGE -> language()
            Step.CUSTOMIZE -> customize()
            Step.THEME -> theme()
            Step.ACCOUNT -> account()
            Step.LOCK -> lock()
            Step.PRIVACY -> privacy()
            Step.NOTIFY -> notifications()
            Step.INTERNET -> internet()
            Step.EXPERIMENT -> experiment()
            Step.SOUND -> sound()
            Step.TRANSLATE -> translate()
            Step.BACKUP -> backup()
            Step.DONE -> done()
            Step.TRANSFER -> transfer()
        }
    }

    private fun terms() {
        val ctx = requireContext()
        head(getString(R.string.setup_terms_title), getString(R.string.setup_terms_sub))
        var agreed = SetupActivity.termsAccepted(ctx)

        val box = com.google.android.material.checkbox.MaterialCheckBox(ctx).apply {
            isChecked = agreed
            buttonTintList = android.content.res.ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(ThemeUi.accent(ctx), onCardDim))
            isClickable = false
            isFocusable = false
        }
        val agreeText = TextView(ctx).apply {
            text = getString(R.string.setup_terms_agree)
            textSize = 15.5f
            setTextColor(onCard)
            typeface = Typeface.DEFAULT_BOLD
            setLineSpacing(0f, 1.15f)
        }
        val link = getString(R.string.setup_terms_docs_link)
        val full = getString(R.string.setup_terms_docs, link)
        val docsText = TextView(ctx).apply {
            val span = android.text.SpannableString(full)
            val at = full.indexOf(link)
            if (at >= 0) {
                span.setSpan(object : android.text.style.ClickableSpan() {
                    override fun onClick(widget: View) {
                        val lang = if (pl.sinusoide.sinuschat.util.LocaleManager.getEffective(ctx) == "pl") "pl" else "en"
                        pl.sinusoide.sinuschat.util.SafeEngine.openLink(requireActivity(),
                            "${pl.sinusoide.sinuschat.AppConfig.DOCS_URL}?lang=$lang")
                    }
                    override fun updateDrawState(ds: android.text.TextPaint) {
                        ds.color = ThemeUi.accent(ctx)
                        ds.isUnderlineText = true
                        ds.isFakeBoldText = true
                    }
                }, at, at + link.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            text = span
            textSize = 13.5f
            setTextColor(onCardDim)
            setLineSpacing(0f, 1.2f)
            movementMethod = android.text.method.LinkMovementMethod.getInstance()
            highlightColor = Color.TRANSPARENT
            setPadding(0, (6 * dp).toInt(), 0, 0)
        }

        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
            setPadding((12 * dp).toInt(), (14 * dp).toInt(), (16 * dp).toInt(), (16 * dp).toInt())
            background = card().apply { if (agreed) setStroke((2 * dp).toInt(), ThemeUi.accent(ctx)) }
            isClickable = true
            addView(box, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = (6 * dp).toInt() })
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, (8 * dp).toInt(), 0, 0)
                addView(agreeText)
                addView(docsText)
            }, LinearLayout.LayoutParams(0, -2, 1f))
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = (10 * dp).toInt() }
        }
        content.addView(row)

        primary(getString(R.string.next)) {
            if (!agreed) return@primary
            SetupActivity.acceptTerms(ctx)
            go(Step.START)
        }
        val primaryButton = content.getChildAt(content.childCount - 1)
        primaryButton.isEnabled = agreed
        primaryButton.alpha = if (agreed) 1f else 0.45f
        row.setOnClickListener {
            agreed = !agreed
            box.isChecked = agreed
            (row.background as? GradientDrawable)?.setStroke(
                (if (agreed) 2 * dp else 1 * dp).toInt(),
                if (agreed) ThemeUi.accent(ctx)
                else if (ThemeManager.isFlow(ctx)) 0x2EFFFFFF else ThemeUi.border(ctx))
            primaryButton.isEnabled = agreed
            primaryButton.animate().alpha(if (agreed) 1f else 0.45f).setDuration(180).start()
            if (!agreed) SetupActivity.revokeTerms(ctx)
        }
    }

    private fun start() {
        head(getString(R.string.setup_start_title), getString(R.string.setup_start_sub))
        val ctx = requireContext()
        option(getString(R.string.setup_start_new), getString(R.string.setup_start_new_sub)) {
            SetupActivity.setRestoreWanted(ctx, false)
            go(null, EmailInputFragment())
        }
        option(getString(R.string.setup_start_transfer), getString(R.string.setup_start_transfer_sub)) {
            SetupActivity.setRestoreWanted(ctx, false)
            go(Step.TRANSFER)
        }
    }

    private fun language() {
        head(getString(R.string.app_language), getString(R.string.setup_lang_sub))
        val current = LocaleManager.getEffective(requireContext())
        for (code in LocaleManager.BUILT_IN) {
            option(LocaleManager.displayName(code), null, current == code) {
                if (current != code) {
                    LocaleManager.setCurrent(requireContext(), code)
                    requireActivity().recreate()
                } else go(Step.TERMS)
            }
        }
        primary(getString(R.string.next)) { go(Step.TERMS) }
    }

    private fun customize() {
        head(getString(R.string.setup_customize_title), getString(R.string.setup_customize_sub))
        primary(getString(R.string.setup_customize_yes)) { go(Step.THEME) }
        secondary(getString(R.string.setup_customize_no)) { go(Step.DONE) }
    }

    private fun theme() {
        head(getString(R.string.setup_theme_title), getString(R.string.setup_theme_sub))
        val ctx = requireContext()
        content.addView(pl.sinusoide.sinuschat.ui.ThemePickerView(ctx) { key ->
            ThemeManager.setCurrent(ctx, key)
            requireActivity().recreate()
        }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = (8 * dp).toInt() })
        primary(getString(R.string.next)) { go(Step.ACCOUNT) }
    }

    private fun account() {
        head(getString(R.string.setup_account_title), getString(R.string.setup_account_sub))
        val ctx = requireContext()
        val now = AutoDelete.seconds(ctx)
        val choices = listOf(0L, 3 * AutoDelete.MONTH, 9 * AutoDelete.MONTH, AutoDelete.YEAR, AutoDelete.YEAR_AND_HALF, AutoDelete.FIVE_YEARS)
        for (sec in choices) {
            val label = if (sec == 0L) getString(R.string.setup_account_never)
            else AutoDelete.label(ctx, sec)
            option(label, null, now == sec) { AutoDelete.setSeconds(ctx, sec); refresh() }
        }
        primary(getString(R.string.next)) { go(Step.LOCK) }
    }

    private fun lock() {
        val ctx = requireContext()
        content.gravity = Gravity.CENTER_VERTICAL
        lockHasPin = pl.sinusoide.sinuschat.LockManager.hasPin(ctx)
        lockHasDuress = pl.sinusoide.sinuschat.LockManager.hasDuressPin(ctx)

        if (!lockHasPin) {
            content.addView(pl.sinusoide.sinuschat.ui.LockIconView(ctx).apply {
                setColor(pl.sinusoide.sinuschat.ui.IconDrawView.ink(ctx))
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                layoutParams = LinearLayout.LayoutParams(-1, (150 * dp).toInt())
            })
            head(getString(R.string.setup_lock_title), getString(R.string.setup_lock_sub))
            primary(getString(R.string.setup_lock_go)) {
                pl.sinusoide.sinuschat.ScreenLockActivity.start(requireActivity(),
                    pl.sinusoide.sinuschat.ScreenLockActivity.MODE_SET)
            }
            secondary(getString(R.string.setup_skip)) { go(Step.PRIVACY) }
            return
        }

        content.addView(pl.sinusoide.sinuschat.ui.FingerprintIconView(ctx).apply {
            setColor(pl.sinusoide.sinuschat.ui.IconDrawView.ink(ctx))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            layoutParams = LinearLayout.LayoutParams(-1, (150 * dp).toInt())
        })
        head(getString(R.string.setup_lock2_title), getString(R.string.setup_lock2_sub))
        primary(getString(R.string.next)) {
            lockNext.launch(Intent(ctx, pl.sinusoide.sinuschat.ScreenLockSettingsActivity::class.java)
                .putExtra(pl.sinusoide.sinuschat.ScreenLockSettingsActivity.EXTRA_FROM_SETUP, true))
        }
        secondary(getString(R.string.setup_skip)) { go(Step.PRIVACY) }
    }

    private fun privacy() {
        head(getString(R.string.setup_privacy_title), getString(R.string.setup_privacy_sub), pinned = true)
        val ctx = requireContext()
        privacyOptions = pl.sinusoide.sinuschat.ui.PrivacyOptionsView(ctx).apply {
            setFooterVisible(false)
        }
        content.addView(privacyOptions, LinearLayout.LayoutParams(-1, -2))
        primary(getString(R.string.next)) { go(Step.INTERNET) }
    }

    private fun notifications() {
        head(getString(R.string.setup_notify_title), getString(R.string.setup_notify_sub))
        val ctx = requireContext()
        val mode = NotificationPrefs.pushMode(ctx)
        option(getString(R.string.setup_notify_local), getString(R.string.setup_notify_local_sub),
            mode == NotificationPrefs.PushMode.NO_GOOGLE) {
            NotificationPrefs.setPushMode(ctx, NotificationPrefs.PushMode.NO_GOOGLE)
            refresh()
        }
        option(getString(R.string.setup_notify_google), getString(R.string.setup_notify_google_sub),
            mode == NotificationPrefs.PushMode.FCM) {
            NotificationPrefs.setPushMode(ctx, NotificationPrefs.PushMode.FCM)
            refresh()
        }
        notifyBatteryFree = batteryFree(ctx)
        if (mode == NotificationPrefs.PushMode.NO_GOOGLE && !notifyBatteryFree) {
            smallNote(getString(R.string.setup_notify_battery_note))
            secondary(getString(R.string.setup_notify_battery_btn)) { askBattery() }
        }
        primary(getString(R.string.next)) { askNotificationConsents() }
    }

    private fun internet() {
        head(getString(R.string.setup_net_title), getString(R.string.setup_net_sub))
        val ctx = requireContext()
        val wifiOnly = NetworkSettings.isWifiOnly(ctx)
        option(getString(R.string.setup_net_wifi), getString(R.string.setup_net_wifi_sub), wifiOnly) {
            NetworkSettings.setWifiOnly(ctx, true); refresh()
        }
        option(getString(R.string.setup_net_data), getString(R.string.setup_net_data_sub), !wifiOnly) {
            NetworkSettings.setWifiOnly(ctx, false); refresh()
        }
        primary(getString(R.string.next)) { go(if (hasExtraFeatures(ctx)) Step.EXPERIMENT else Step.SOUND) }
    }

    private fun hasExtraFeatures(ctx: Context): Boolean =
        pl.sinusoide.sinuschat.util.ChatBubbles.isSupported() ||
            ExperimentalFeatures.isPipSupported(ctx) || ramGb(ctx) >= 6

    private fun experiment() {
        val ctx = requireContext()
        if (!hasExtraFeatures(ctx)) { go(Step.SOUND); return }
        head(getString(R.string.setup_exp_title), getString(R.string.setup_exp_sub))
        val bubbles = pl.sinusoide.sinuschat.util.ChatBubbles
        if (bubbles.isSupported()) {
            label(getString(R.string.exp_section_messages))
            toggle(getString(R.string.exp_bubbles_title), getString(R.string.exp_bubbles_desc),
                ExperimentalFeatures.isBubblesEnabled(ctx)) { on ->
                ExperimentalFeatures.setBubblesEnabled(ctx, on)
                if (!on) bubbles.clear(ctx)
                else if (!bubbles.systemAllows(ctx)) {
                    PopupHelper.show(requireActivity(), getString(R.string.exp_bubbles_allow))
                    bubbles.openSystemSettings(requireActivity())
                }
            }
        }
        if (ExperimentalFeatures.isPipSupported(ctx)) {
            label(getString(R.string.exp_section_calls))
            toggle(getString(R.string.exp_pip_title), getString(R.string.exp_pip_desc),
                ExperimentalFeatures.isPipEnabled(ctx)) { ExperimentalFeatures.setPipEnabled(ctx, it) }
        }
        if (ramGb(ctx) >= 6) {
            label(getString(R.string.exp_section_ai))
            toggle(getString(R.string.setup_exp_ai), getString(R.string.setup_exp_ai_sub),
                ExperimentalFeatures.isAiEnabled(ctx)) { ExperimentalFeatures.setAiEnabled(ctx, it) }
        }
        smallNote(getString(R.string.exp_footer))
        primary(getString(R.string.next)) { go(Step.SOUND) }
    }

    private var soundPlayer: android.media.MediaPlayer? = null
    private val soundHandler = android.os.Handler(android.os.Looper.getMainLooper())

    private fun sound() {
        val ctx = requireContext()
        head(getString(R.string.sound_title), getString(R.string.setup_sound_sub))
        val def = getString(R.string.sound_default)

        label(getString(R.string.sound_section_ring))
        val ring = SoundPrefs.ring(ctx)
        for (r in SoundPrefs.Ring.values()) {
            option(getString(r.label), if (r == SoundPrefs.Ring.BIT) def else null, r == ring) {
                SoundPrefs.setRing(ctx, r)
                refresh()
                playPreview(r.raw, android.media.RingtoneManager.TYPE_RINGTONE, ringtone = true)
            }
        }
        volumeRow(getString(R.string.sound_volume_ring), R.drawable.ic_call_speaker_on, R.drawable.ic_call_speaker_off,
            ringtone = true)

        label(getString(R.string.sound_section_notif))
        val notif = SoundPrefs.notif(ctx)
        for (n in SoundPrefs.Notif.values()) {
            option(getString(n.label), if (n == SoundPrefs.Notif.WAVE) def else null, n == notif) {
                SoundPrefs.setNotif(ctx, n)
                refresh()
                playPreview(n.raw, android.media.RingtoneManager.TYPE_NOTIFICATION, ringtone = false)
            }
        }
        volumeRow(getString(R.string.sound_volume_notif), R.drawable.ic_notifications, R.drawable.ic_notifications_off,
            ringtone = false)
        smallNote(getString(R.string.sound_volume_note))

        label(getString(R.string.sound_section_outgoing))
        val out = SoundPrefs.outgoing(ctx)
        for (o in SoundPrefs.Outgoing.values()) {
            option(getString(o.label), getString(o.desc), o == out) {
                SoundPrefs.setOutgoing(ctx, o)
                refresh()
                if (o.raw != 0) playPreview(o.raw, android.media.RingtoneManager.TYPE_NOTIFICATION, ringtone = false)
                else stopPreview()
            }
        }

        smallNote(getString(R.string.sound_ring_note))
        primary(getString(R.string.next)) { stopPreview(); go(Step.TRANSLATE) }
    }

    private fun playPreview(raw: Int, systemType: Int, ringtone: Boolean) {
        stopPreview()
        val player = pl.sinusoide.sinuschat.util.SoundPrefs.preview(requireContext(), raw, systemType, ringtone) ?: return
        soundPlayer = player
        soundPreviewRing = ringtone
        player.setOnCompletionListener { if (soundPlayer === it) stopPreview() }
        soundHandler.postDelayed({ stopPreview() }, if (ringtone) 7000L else 4000L)
    }

    private fun stopPreview() {
        soundHandler.removeCallbacksAndMessages(null)
        soundPlayer?.let {
            try { it.stop() } catch (_: Exception) {}
            try { it.release() } catch (_: Exception) {}
        }
        soundPlayer = null
        soundPreviewRing = null
    }

    private var soundPreviewRing: Boolean? = null

    private fun previewSelected(ringtone: Boolean) {
        val ctx = context ?: return
        if (ringtone) playPreview(SoundPrefs.ring(ctx).raw, android.media.RingtoneManager.TYPE_RINGTONE, ringtone = true)
        else playPreview(SoundPrefs.notif(ctx).raw, android.media.RingtoneManager.TYPE_NOTIFICATION, ringtone = false)
    }

    private fun setVolume(ringtone: Boolean, percent: Int) {
        val ctx = context ?: return
        if (ringtone) SoundPrefs.setRingVolume(ctx, percent) else SoundPrefs.setNotifVolume(ctx, percent)
        if (soundPreviewRing == ringtone) {
            val g = SoundPrefs.gain(percent)
            try { soundPlayer?.setVolume(g, g) } catch (_: Exception) {}
        }
    }

    private fun volumeRow(title: String, iconOn: Int, iconOff: Int, ringtone: Boolean) {
        val ctx = requireContext()
        val accent = ThemeUi.accent(ctx)
        val initial = if (ringtone) SoundPrefs.ringVolume(ctx) else SoundPrefs.notifVolume(ctx)
        val valueText = TextView(ctx).apply {
            text = getString(R.string.sound_volume_value, initial)
            textSize = 14f; setTextColor(accent); typeface = Typeface.DEFAULT_BOLD
        }
        val icon = ImageView(ctx).apply {
            setImageResource(if (initial > 0) iconOn else iconOff); setColorFilter(accent)
            contentDescription = getString(if (initial > 0) R.string.sound_mute else R.string.sound_unmute)
            val p = (6 * dp).toInt(); setPadding(p, p, p, p)
        }
        val faint = (accent and 0x00FFFFFF) or 0x40000000
        val slider = Slider(ctx).apply {
            valueFrom = 0f
            valueTo = 100f
            value = initial.toFloat()
            labelBehavior = LabelFormatter.LABEL_GONE
            contentDescription = title
            haloRadius = 0
            trackStopIndicatorSize = 0
            trackActiveTintList = ColorStateList.valueOf(accent)
            trackInactiveTintList = ColorStateList.valueOf(faint)
            thumbTintList = ColorStateList.valueOf(accent)
            addOnChangeListener { _, v, fromUser ->
                val percent = Math.round(v)
                valueText.text = getString(R.string.sound_volume_value, percent)
                icon.setImageResource(if (percent > 0) iconOn else iconOff)
                icon.contentDescription = getString(if (percent > 0) R.string.sound_mute else R.string.sound_unmute)
                if (fromUser) setVolume(ringtone, percent)
            }
            addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
                override fun onStartTrackingTouch(slider: Slider) {
                    SoundPrefs.setLastVolume(ctx, ringtone, Math.round(slider.value))
                    if (soundPreviewRing != ringtone) previewSelected(ringtone)
                }

                override fun onStopTrackingTouch(slider: Slider) {
                    SoundPrefs.setLastVolume(ctx, ringtone, Math.round(slider.value))
                    if (soundPreviewRing != ringtone && slider.value > 0f) previewSelected(ringtone)
                }
            })
        }
        icon.setOnClickListener {
            val current = Math.round(slider.value)
            if (current > 0) {
                SoundPrefs.setLastVolume(ctx, ringtone, current)
                slider.value = 0f
                setVolume(ringtone, 0)
                if (soundPreviewRing == ringtone) stopPreview()
            } else {
                val back = SoundPrefs.lastVolume(ctx, ringtone)
                slider.value = back.toFloat()
                setVolume(ringtone, back)
                previewSelected(ringtone)
            }
        }
        content.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((12 * dp).toInt(), (10 * dp).toInt(), (10 * dp).toInt(), (4 * dp).toInt())
            background = card()
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = (10 * dp).toInt() }
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(icon, LinearLayout.LayoutParams((36 * dp).toInt(), (36 * dp).toInt()).apply {
                    marginEnd = (8 * dp).toInt()
                })
                addView(TextView(ctx).apply {
                    text = title; textSize = 16f; setTextColor(onCard); typeface = Typeface.DEFAULT_BOLD
                }, LinearLayout.LayoutParams(0, -2, 1f))
                addView(valueText, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = (6 * dp).toInt() })
            })
            addView(slider, LinearLayout.LayoutParams(-1, -2))
        })
    }

    override fun onStop() {
        super.onStop()
        stopPreview()
        Translations.removeListener(translateChanged)
    }

    private var translateList: pl.sinusoide.sinuschat.ui.TranslationLangList? = null
    private val translateChanged: () -> Unit = { if (isAdded) translateList?.refresh() }

    private fun translate() {
        val ctx = requireContext()
        head(getString(R.string.setup_translate_title), getString(R.string.setup_translate_sub))
        if (!Translations.supported()) {
            smallNote(getString(R.string.translate_unsupported))
            primary(getString(R.string.next)) { go(Step.BACKUP) }
            return
        }
        Translations.refresh(ctx)
        toggle(getString(R.string.translate_enabled), getString(R.string.translate_enabled_desc),
            Translations.enabledSetting(ctx)) { Translations.setEnabled(ctx, it) }
        toggle(getString(R.string.translate_ask_send), getString(R.string.translate_ask_send_desc),
            Translations.askSend(ctx)) { Translations.setAskSend(ctx, it) }
        label(getString(R.string.translate_section_langs))
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = card()
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = (10 * dp).toInt() }
        }
        content.addView(box)
        translateList = pl.sinusoide.sinuschat.ui.TranslationLangList(requireActivity(), box, onCard, onCardDim,
            ThemeUi.accent(ctx),
            androidx.core.content.ContextCompat.getColor(ctx, R.color.sinuschat_success),
            androidx.core.content.ContextCompat.getColor(ctx, R.color.sinuschat_error),
            0xFFE67E22.toInt()).also { it.refresh() }
        Translations.removeListener(translateChanged)
        Translations.addListener(translateChanged)
        smallNote(getString(R.string.translate_langs_hint, Translations.name(ctx, Translations.appLang(ctx))))
        smallNote(getString(R.string.translate_footer))
        primary(getString(R.string.next)) { go(Step.BACKUP) }
    }

    private fun label(text: String) {
        content.addView(TextView(requireContext()).apply {
            this.text = text
            textSize = 13f
            setTextColor(onCardDim)
            typeface = Typeface.DEFAULT_BOLD
            setPadding((6 * dp).toInt(), (8 * dp).toInt(), (6 * dp).toInt(), (8 * dp).toInt())
            layoutParams = LinearLayout.LayoutParams(-1, -2)
        })
    }

    private fun backup() {
        head(getString(R.string.setup_backup_title), getString(R.string.setup_backup_sub))
        val ctx = requireContext()
        val drive = BackupPrefs.target(ctx) == BackupPrefs.TARGET_DRIVE
        val folderSet = BackupPrefs.folderUri(ctx) != null
        val usb = !drive && folderSet
        val none = !drive && !folderSet

        option(getString(R.string.backup_target_usb),
            if (usb) getString(R.string.backup_folder_ready, BackupPrefs.folderName(ctx))
            else getString(R.string.setup_backup_usb_sub), usb) {
            BackupPrefs.setTarget(ctx, BackupPrefs.TARGET_USB)
            askUsbSheet()
        }
        option(getString(R.string.backup_target_drive_hidden), getString(R.string.setup_backup_drive_sub),
            drive && BackupPrefs.driveHidden(ctx)) { connectDrive(hidden = true) }
        option(getString(R.string.backup_target_drive_folder), getString(R.string.setup_backup_drive_folder_sub),
            drive && !BackupPrefs.driveHidden(ctx)) { connectDrive(hidden = false) }
        option(getString(R.string.setup_backup_none), getString(R.string.setup_backup_none_sub), none) {
            BackupPrefs.setTarget(ctx, BackupPrefs.TARGET_USB)
            BackupPrefs.setFolder(ctx, null, "")
            BackupPrefs.setDriveAccount(ctx, "")
            BackupPrefs.setIntervalSec(ctx, 0L)
            refresh()
        }

        if (!none) {
            option(getString(R.string.backup_interval_title), intervalLabel(ctx), false) { chooseBackupInterval() }
            toggle(getString(R.string.backup_media_title),
                getString(if (BackupPrefs.withMedia(ctx)) R.string.setup_backup_media_on
                else R.string.backup_media_off),
                BackupPrefs.withMedia(ctx)) { BackupPrefs.setWithMedia(ctx, it); refresh() }
            secondary(getString(R.string.setup_backup_more)) {
                startActivity(Intent(ctx, pl.sinusoide.sinuschat.BackupActivity::class.java))
            }
            if (!BackupPrefs.hasRecoveryKey(ctx)) smallNote(getString(R.string.setup_backup_note))
        }
        primary(getString(R.string.next)) {
            if (!none && !BackupPrefs.hasRecoveryKey(ctx))
                recoveryKit.launch(pl.sinusoide.sinuschat.RecoveryKeyActivity.kitIntent(ctx))
            else go(Step.DONE)
        }
    }

    private fun intervalLabel(ctx: Context): String = when (val sec = BackupPrefs.intervalSec(ctx)) {
        0L -> getString(R.string.backup_interval_off)
        BackupPrefs.DAY -> getString(R.string.backup_daily)
        BackupPrefs.WEEK -> getString(R.string.backup_weekly)
        BackupPrefs.MONTH -> getString(R.string.backup_monthly)
        BackupPrefs.QUARTER -> getString(R.string.backup_quarterly)
        BackupPrefs.YEAR -> getString(R.string.backup_yearly)
        else -> getString(R.string.backup_every, pl.sinusoide.sinuschat.util.AutoDelete.label(ctx, sec))
    }

    private fun chooseBackupInterval() {
        val ctx = requireContext()
        val items = listOf(
            0L to getString(R.string.backup_interval_off),
            BackupPrefs.DAY to getString(R.string.backup_daily),
            BackupPrefs.WEEK to getString(R.string.backup_weekly),
            BackupPrefs.MONTH to getString(R.string.backup_monthly),
            BackupPrefs.QUARTER to getString(R.string.backup_quarterly),
            BackupPrefs.YEAR to getString(R.string.backup_yearly)
        )
        val current = BackupPrefs.intervalSec(ctx)
        pickSheet(R.drawable.ic_timer, getString(R.string.backup_interval_title),
            getString(R.string.backup_interval_desc),
            items.map { it.second to (it.first == current) }) { i ->
            BackupPrefs.setIntervalSec(ctx, items[i].first)
            refresh()
        }
    }

    private fun connectDrive(hidden: Boolean) {
        val act = activity ?: return
        pl.sinusoide.sinuschat.util.SafeEngine.guard(act, pl.sinusoide.sinuschat.util.SafeEngine.Risk.CLOUD_BACKUP) { if (isAdded) connectDriveConfirmed(hidden) }
    }

    private fun connectDriveConfirmed(hidden: Boolean) {
        val ctx = requireContext()
        BackupPrefs.setDriveHidden(ctx, hidden)
        pl.sinusoide.sinuschat.util.AppExecutor.io {
            val auth = pl.sinusoide.sinuschat.util.DriveBackup.authorize(ctx, hidden)
            pl.sinusoide.sinuschat.util.AppExecutor.main {
                if (!isAdded) return@main
                when (auth) {
                    is pl.sinusoide.sinuschat.util.DriveBackup.Auth.Token -> {
                        BackupPrefs.setTarget(ctx, BackupPrefs.TARGET_DRIVE)
                        refresh()
                        PopupHelper.showSuccess(requireActivity(), getString(R.string.backup_drive_connected))
                    }
                    is pl.sinusoide.sinuschat.util.DriveBackup.Auth.NeedsUser ->
                        driveConsent.launch(
                            androidx.activity.result.IntentSenderRequest.Builder(auth.intent).build())
                    is pl.sinusoide.sinuschat.util.DriveBackup.Auth.Error ->
                        PopupHelper.showError(requireActivity(),
                            if (auth.message == "setup") getString(R.string.backup_drive_not_configured)
                            else getString(R.string.backup_failed, auth.message))
                }
            }
        }
    }

    private fun done() {
        val ctx = requireContext()
        val pending = SetupActivity.pendingRegistration(ctx)
        head(getString(R.string.setup_done_title),
            getString(if (pending != null) R.string.setup_done_sub_transfer else R.string.setup_done_sub))
        var busy = false
        lateinit var go: TextView
        go = primary(getString(R.string.setup_done_go)) {
            if (busy) return@primary
            lastStep = null
            if (pending == null) {
                (activity as? SetupActivity)?.finishSetup(doneLogo)
                return@primary
            }
            busy = true
            go.alpha = 0.5f
            val app = ctx.applicationContext
            pl.sinusoide.sinuschat.util.AppExecutor.io {
                val r = AccountRegistration.register(app, pending.email, pending.displayName)
                pl.sinusoide.sinuschat.util.AppExecutor.main {
                    busy = false
                    go.alpha = 1f
                    if (!isAdded) return@main
                    when (r) {
                        is AccountRegistration.Result.Ok -> {
                            SetupActivity.clearPendingRegistration(app)
                            pl.sinusoide.sinuschat.util.ProfileSync.pullMissing(app)
                            (activity as? SetupActivity)?.finishSetup(doneLogo)
                        }
                        is AccountRegistration.Result.NeedsCode -> {
                            PopupHelper.show(requireActivity(), getString(R.string.setup_transfer_confirm_email))
                            (activity as? SetupActivity)?.navigateTo(EmailInputFragment().apply {
                                arguments = Bundle().apply { putString("email", pending.email) }
                            })
                        }
                        is AccountRegistration.Result.Failed ->
                            PopupHelper.showError(requireActivity(), getString(R.string.error_server_connect))
                    }
                }
            }
        }
    }

    private fun transfer() {
        head(getString(R.string.setup_transfer_title), getString(R.string.setup_transfer_sub))
        if (!loggedIn()) {
            option(getString(R.string.setup_transfer_phone), getString(R.string.setup_transfer_phone_account_sub)) {
                scanFromPhone()
            }
            option(getString(R.string.setup_transfer_backup), getString(R.string.setup_transfer_backup_sub)) {
                SetupActivity.setRestoreWanted(requireContext(), true)
                go(null, EmailInputFragment())
            }
            option(getString(R.string.anon_login_option), getString(R.string.anon_login_option_sub)) {
                anonRetryWords = null
                openAnonEntry()
            }
            return
        }
        pl.sinusoide.sinuschat.backup.ExternalBackup.pendingRestore(requireContext())?.let { file ->
            val from = pl.sinusoide.sinuschat.backup.ExternalBackup.sourceLabel(requireContext())
                ?: getString(R.string.ext_restore_other_app)
            option(getString(R.string.ext_restore_option), getString(R.string.ext_restore_option_sub, from), selected = true) {
                restoreExternal(file)
            }
            if (!externalOffered) {
                externalOffered = true
                content.post {
                    if (!isAdded) return@post
                    pl.sinusoide.sinuschat.ui.ConfirmSheet.show(requireActivity(),
                        icon = R.drawable.ic_backup,
                        color = pl.sinusoide.sinuschat.ui.ThemeUi.accent(requireContext()),
                        title = getString(R.string.ext_restore_offer_title),
                        message = getString(R.string.ext_restore_offer_msg, from),
                        confirm = getString(R.string.ext_restore_offer_confirm),
                        onConfirm = { if (isAdded) restoreExternal(file) })
                }
            }
        }
        option(getString(R.string.setup_transfer_usb), getString(R.string.setup_transfer_usb_sub)) {
            pickFromUsb()
        }
        option(getString(R.string.setup_transfer_file), getString(R.string.setup_transfer_file_sub)) {
            pickFile()
        }
        option(getString(R.string.setup_transfer_drive), getString(R.string.setup_transfer_drive_sub)) {
            retry = { restoreFromDrive() }
            restoreFromDrive()
        }
        secondary(getString(R.string.setup_transfer_no_backup)) {
            SetupActivity.setRestoreWanted(requireContext(), false)
            (activity as? SetupActivity)?.navigateTo(ProfileSetupFragment().apply {
                arguments = Bundle().apply { putString("email", email()) }
            })
        }
    }

    private fun scanFromPhone() {
        retry = { scanFromPhone() }
        scanMoveCode.launch(Intent(requireContext(), pl.sinusoide.sinuschat.messenger.QrScanActivity::class.java))
    }

    private var externalOffered = false

    private fun restoreExternal(file: java.io.File) {
        retry = { restoreExternal(file) }
        restoreUri = null
        restoreFile = file
        restoreDriveHidden = null
        askKeyFor { file.inputStream() }
    }

    private fun pickFile() {
        retry = { pickFile() }
        pickRestoreFile.launch(arrayOf("*/*"))
    }

    private fun pickFromUsb() {
        retry = { pickFromUsb() }
        val ctx = requireContext()
        pl.sinusoide.sinuschat.ui.UsbPlugSheet.show(requireActivity(), getString(R.string.setup_usb_restore_desc),
            isReady = { pl.sinusoide.sinuschat.ui.UsbPlugSheet.usbAttached(ctx) },
            onReady = {
                if (isAdded) pickUsbFile.launch(Intent(Intent.ACTION_OPEN_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("*/*")
                    .apply {
                        pl.sinusoide.sinuschat.ui.UsbPlugSheet.backupFolderUri(ctx)?.let {
                            putExtra(android.provider.DocumentsContract.EXTRA_INITIAL_URI, it)
                        }
                    })
            })
    }

    private fun email(): String = arguments?.getString(ARG_EMAIL).orEmpty()

    private fun loggedIn(): Boolean = email().isNotEmpty()

    private fun accountHash(): String =
        if (loggedIn()) SetupActivity.accountHashOf(email()) else ""

    private fun receiveFromPhone(code: pl.sinusoide.sinuschat.util.DeviceTransfer.Code) {
        val app = requireContext().applicationContext
        val hash = accountHash()
        val look = lookNow(app)
        showWorking(getString(R.string.setup_transfer_connecting))
        pl.sinusoide.sinuschat.util.AppExecutor.io {
            val result = try {
                pl.sinusoide.sinuschat.util.DeviceTransfer.receive(app, code, hash,
                    onConnected = { pl.sinusoide.sinuschat.util.AppExecutor.main {
                        workTitle?.text = getString(R.string.setup_transfer_working) } },
                    onProgress = { d, t -> pl.sinusoide.sinuschat.util.AppExecutor.main { setWorkProgress(d, t) } })
            } catch (e: Throwable) {
                android.util.Log.e("SetupTransfer", "telefon", e)
                pl.sinusoide.sinuschat.util.AppExecutor.main { restoreFailed(e) }
                return@io
            }
            pl.sinusoide.sinuschat.util.AppExecutor.main { restored(result.restore, null, look, result.account) }
        }
    }

    private fun askKeyFor(open: () -> java.io.InputStream) {
        val ctx = requireContext()
        pl.sinusoide.sinuschat.util.AppExecutor.io {
            val header = try {
                open().use { pl.sinusoide.sinuschat.util.BackupManager.readHeader(it) }
            } catch (e: Throwable) {
                pl.sinusoide.sinuschat.util.AppExecutor.main { restoreFailed(e) }
                return@io
            }
            val stored = BackupPrefs.recoveryKey(ctx)?.takeIf { it.id.contentEquals(header.keyId) }
            pl.sinusoide.sinuschat.util.AppExecutor.main {
                if (!isAdded) return@main
                if (stored != null) restoreFromBackup(stored)
                else restoreKey.launch(pl.sinusoide.sinuschat.RecoveryKeyActivity.enterIntent(ctx, header.keyId))
            }
        }
    }

    private fun restoreFromBackup(key: pl.sinusoide.sinuschat.util.RecoveryKey) {
        val app = requireContext().applicationContext
        val uri = restoreUri
        val file = restoreFile
        val hash = accountHash()
        val look = lookNow(app)
        showWorking(getString(R.string.setup_transfer_working))
        pl.sinusoide.sinuschat.util.AppExecutor.io {
            val result = try {
                val inp = when {
                    file != null -> file.inputStream()
                    uri != null -> app.contentResolver.openInputStream(uri) ?: throw java.io.IOException("Brak pliku")
                    else -> throw java.io.IOException("Brak pliku")
                }
                inp.buffered(64 * 1024).use { stream ->
                    pl.sinusoide.sinuschat.util.BackupManager.open(stream, key).use { archive ->
                        pl.sinusoide.sinuschat.util.BackupRestore.restore(app, archive, hash) { d, t ->
                            pl.sinusoide.sinuschat.util.AppExecutor.main { setWorkProgress(d, t) }
                        }
                    }
                }
            } catch (e: Throwable) {
                android.util.Log.e("SetupTransfer", "kopia", e)
                pl.sinusoide.sinuschat.util.AppExecutor.main { restoreFailed(e) }
                return@io
            }
            pl.sinusoide.sinuschat.util.AppExecutor.main { restored(result, key, look) }
        }
    }

    private fun restoreFromDrive() {
        val app = requireContext().applicationContext
        showWorking(getString(R.string.setup_transfer_drive_search))
        pl.sinusoide.sinuschat.util.AppExecutor.io {
            when (val auth = pl.sinusoide.sinuschat.util.DriveBackup.authorizeRestore(app)) {
                is pl.sinusoide.sinuschat.util.DriveBackup.Auth.Token -> searchDrive(app, auth.value)
                is pl.sinusoide.sinuschat.util.DriveBackup.Auth.NeedsUser -> pl.sinusoide.sinuschat.util.AppExecutor.main {
                    if (isAdded) driveRestoreConsent.launch(
                        androidx.activity.result.IntentSenderRequest.Builder(auth.intent).build())
                }
                is pl.sinusoide.sinuschat.util.DriveBackup.Auth.Error -> pl.sinusoide.sinuschat.util.AppExecutor.main {
                    showTransferError(if (auth.message == "setup") getString(R.string.backup_drive_not_configured)
                        else getString(R.string.backup_failed, auth.message))
                }
            }
        }
    }

    private fun searchDrive(app: Context, token: String) {
        try {
            val remote = pl.sinusoide.sinuschat.util.DriveBackup.findLatest(token)
            if (remote == null) {
                pl.sinusoide.sinuschat.util.AppExecutor.main {
                    showTransferError(getString(R.string.setup_transfer_no_drive_backup))
                }
                return
            }
            val dest = java.io.File(app.cacheDir, "restore." + pl.sinusoide.sinuschat.util.BackupManager.EXTENSION)
            if (app.cacheDir.usableSpace < remote.size + 64L * 1024 * 1024) {
                pl.sinusoide.sinuschat.util.AppExecutor.main {
                    showTransferError(getString(R.string.setup_transfer_no_space))
                }
                return
            }
            var shown = -1
            pl.sinusoide.sinuschat.util.DriveBackup.download(token, remote.id, dest) { got ->
                val pct = if (remote.size > 0) (got * 100 / remote.size).toInt().coerceIn(0, 100) else 0
                if (pct != shown) {
                    shown = pct
                    pl.sinusoide.sinuschat.util.AppExecutor.main {
                        workTitle?.text = getString(R.string.setup_transfer_drive_download, pct)
                        setWorkProgress(pct, 100)
                    }
                }
            }
            pl.sinusoide.sinuschat.util.AppExecutor.main {
                if (!isAdded) return@main
                restoreUri = null
                restoreFile = dest
                restoreDriveHidden = remote.hidden
                askKeyFor { dest.inputStream() }
            }
        } catch (e: Throwable) {
            android.util.Log.e("SetupTransfer", "Dysk", e)
            pl.sinusoide.sinuschat.util.AppExecutor.main { restoreFailed(e) }
        }
    }

    private fun lookNow(ctx: Context): Pair<String, String> =
        ThemeManager.getCurrent(ctx) to LocaleManager.getEffective(ctx)

    private fun restored(result: pl.sinusoide.sinuschat.util.BackupRestore.Result,
                         key: pl.sinusoide.sinuschat.util.RecoveryKey?, lookBefore: Pair<String, String>,
                         account: pl.sinusoide.sinuschat.util.DeviceTransfer.Account? = null) {
        val app = (activity ?: return).applicationContext
        if (key != null && !BackupPrefs.hasRecoveryKey(app)) BackupPrefs.setRecoveryKey(app, key)
        restoreDriveHidden?.let {
            BackupPrefs.setTarget(app, BackupPrefs.TARGET_DRIVE)
            BackupPrefs.setDriveHidden(app, it)
        }
        restoreFile?.delete()
        restoreFile = null
        pl.sinusoide.sinuschat.backup.ExternalBackup.clearPending(app)
        val accountEmail = when {
            loggedIn() -> email()
            account != null && account.email.isNotEmpty() -> account.email
            else -> result.accountEmail
        }
        val name = account?.displayName?.takeIf { it.isNotBlank() } ?: result.displayName
        SetupActivity.setPendingRegistration(app, accountEmail, name)
        if (pl.sinusoide.sinuschat.setup.AnonAccount.isAnonId(accountEmail)) {
            (key ?: BackupPrefs.recoveryKey(app))?.let { rk ->
                pl.sinusoide.sinuschat.util.AppExecutor.io { pl.sinusoide.sinuschat.crypto.ProfileCrypto.restoreForAnon(app, rk) }
            }
        }
        SetupActivity.setRestoreWanted(app, false)
        if (!isAdded) return

        endWorking()
        transferState = TransferState.DONE
        blockBack.isEnabled = true
        moveIcon?.setMode(pl.sinusoide.sinuschat.ui.MoveDataIconView.Mode.DONE)
        clearContent()
        head(getString(R.string.setup_transfer_done_title),
            getString(R.string.setup_transfer_done, result.conversations, result.messages, result.files))
        if (account != null) smallNote(getString(R.string.setup_transfer_done_old_phone))
        primary(getString(R.string.next)) {
            val act = requireActivity()
            SetupActivity.setWizardStep(app, Step.NOTIFY.name)
            (act as? SetupActivity)?.startWizard(Step.NOTIFY)
            if (lookNow(app) != lookBefore) {
                ThemeManager.applyNightMode(app)
                act.window.decorView.post { if (!act.isFinishing) act.recreate() }
            }
        }
    }

    private fun restoreFailed(e: Throwable) {
        if (!isAdded) return
        val msg = when (e) {
            is pl.sinusoide.sinuschat.util.DeviceTransfer.NoConnectionException -> getString(R.string.setup_transfer_no_connection)
            is pl.sinusoide.sinuschat.util.DeviceTransfer.OtherAccountException -> getString(R.string.setup_transfer_other_account)
            is pl.sinusoide.sinuschat.util.BackupRestore.ForeignAccountException ->
                getString(R.string.setup_transfer_foreign, e.email.ifEmpty { "—" })
            is pl.sinusoide.sinuschat.util.BackupCrypto.LegacyFormatException -> getString(R.string.backup_verify_legacy)
            is pl.sinusoide.sinuschat.util.BackupCrypto.WrongKeyException,
            is javax.crypto.AEADBadTagException -> getString(R.string.backup_verify_failed)
            else -> getString(R.string.setup_transfer_failed, e.message ?: e.javaClass.simpleName)
        }
        showTransferError(msg)
    }

    private fun showTransferError(message: String, title: String = getString(R.string.setup_transfer_error_title)) {
        if (!isAdded) return
        endWorking()
        transferState = TransferState.ERROR
        blockBack.isEnabled = true
        moveIcon?.setMode(pl.sinusoide.sinuschat.ui.MoveDataIconView.Mode.ERROR)
        clearContent()
        head(title, message)
        primary(getString(R.string.transfer_send_retry)) {
            val again = retry
            if (again != null) again() else backToChoice()
        }
        secondary(getString(R.string.cancel)) { backToChoice() }
    }

    private fun backToChoice() {
        endWorking()
        transferState = TransferState.CHOICE
        blockBack.isEnabled = false
        moveIcon?.setMode(pl.sinusoide.sinuschat.ui.MoveDataIconView.Mode.INTRO)
        if (isAdded && step == Step.TRANSFER) refresh()
    }

    private fun clearContent() {
        pinnedHead?.let { column.removeView(it) }
        pinnedHead = null
        content.removeAllViews()
    }

    private fun showWorking(title: String) {
        val ctx = requireContext()
        working = true
        transferState = TransferState.WORKING
        blockBack.isEnabled = true
        requireActivity().window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        moveIcon?.setMode(pl.sinusoide.sinuschat.ui.MoveDataIconView.Mode.MOVING)
        clearContent()
        workTitle = TextView(ctx).apply {
            text = title
            textSize = 22f
            setTextColor(onCard)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(0, (6 * dp).toInt(), 0, 0)
        }
        content.addView(workTitle)
        content.addView(TextView(ctx).apply {
            text = getString(R.string.setup_transfer_keep_open)
            textSize = 14f
            setTextColor(onCardDim)
            gravity = Gravity.CENTER
            setLineSpacing(0f, 1.2f)
            setPadding((6 * dp).toInt(), (8 * dp).toInt(), (6 * dp).toInt(), (22 * dp).toInt())
        })
        workBar = android.widget.ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            progressTintList = android.content.res.ColorStateList.valueOf(ThemeUi.accent(ctx))
            indeterminateTintList = progressTintList
        }
        content.addView(workBar, LinearLayout.LayoutParams(-1, -2))
        workDetail = TextView(ctx).apply {
            textSize = 13f
            setTextColor(onCardDim)
            gravity = Gravity.CENTER
            setPadding(0, (10 * dp).toInt(), 0, 0)
        }
        content.addView(workDetail)
    }

    private fun setWorkProgress(done: Int, total: Int) {
        val bar = workBar ?: return
        bar.isIndeterminate = false
        bar.max = total.coerceAtLeast(1)
        bar.progress = done.coerceAtMost(bar.max)
        workDetail?.text = getString(R.string.backup_progress, done.coerceAtMost(total), total)
    }

    private fun endWorking() {
        working = false
        activity?.window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        workTitle = null
        workBar = null
        workDetail = null
    }

    private fun head(title: String, sub: String?, pinned: Boolean = false) {
        val box = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL }
        box.addView(TextView(requireContext()).apply {
            text = title
            textSize = 25f
            setTextColor(onCard)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(0, (6 * dp).toInt(), 0, 0)
        })
        if (!sub.isNullOrEmpty()) box.addView(TextView(requireContext()).apply {
            text = sub
            textSize = 14.5f
            setTextColor(onCardDim)
            gravity = Gravity.CENTER
            setLineSpacing(0f, 1.25f)
            setPadding((6 * dp).toInt(), (8 * dp).toInt(), (6 * dp).toInt(), (16 * dp).toInt())
        })
        if (pinned) {
            pinnedHead = box
            column.addView(box, column.indexOfChild(scroll))
        } else {
            content.addView(box)
        }
    }

    private fun card(): GradientDrawable = GradientDrawable().apply {
        cornerRadius = 20 * dp
        setColor(if (ThemeManager.isFlow(requireContext())) 0x1FFFFFFF else ThemeUi.card(requireContext()))
        setStroke((1 * dp).toInt(), if (ThemeManager.isFlow(requireContext())) 0x2EFFFFFF
        else ThemeUi.border(requireContext()))
    }

    private fun option(title: String, sub: String?, selected: Boolean = false, onClick: () -> Unit) {
        val ctx = requireContext()
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((16 * dp).toInt(), (15 * dp).toInt(), (14 * dp).toInt(), (15 * dp).toInt())
            background = card().apply {
                if (selected) setStroke((2 * dp).toInt(), ThemeUi.accent(ctx))
            }
            isClickable = true
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(-1, -2)
                .apply { bottomMargin = (10 * dp).toInt() }
        }
        row.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(ctx).apply {
                text = title
                textSize = 16f
                setTextColor(if (selected) ThemeUi.accent(ctx) else onCard)
                typeface = Typeface.DEFAULT_BOLD
            })
            if (!sub.isNullOrEmpty()) addView(TextView(ctx).apply {
                text = sub
                textSize = 13f
                setTextColor(onCardDim)
                setLineSpacing(0f, 1.15f)
                setPadding(0, (3 * dp).toInt(), 0, 0)
            })
        }, LinearLayout.LayoutParams(0, -2, 1f))
        if (selected) row.addView(ImageView(ctx).apply {
            setImageResource(R.drawable.ic_check)
            setColorFilter(ThemeUi.accent(ctx))
        }, LinearLayout.LayoutParams((22 * dp).toInt(), (22 * dp).toInt()))
        content.addView(row)
    }

    private fun toggle(title: String, sub: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
        val ctx = requireContext()
        val sw = MaterialSwitch(ctx).apply {
            isChecked = checked
            thumbTintList = android.content.res.ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(Color.WHITE, 0xFF8AA0B8.toInt()))
            trackTintList = android.content.res.ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(ThemeUi.accent(ctx), 0xFF2A3A4A.toInt()))
            setOnCheckedChangeListener { _, v -> onChange(v) }
        }
        content.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((16 * dp).toInt(), (12 * dp).toInt(), (12 * dp).toInt(), (12 * dp).toInt())
            background = card()
            isClickable = true
            setOnClickListener { sw.isChecked = !sw.isChecked }
            layoutParams = LinearLayout.LayoutParams(-1, -2)
                .apply { bottomMargin = (10 * dp).toInt() }
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(ctx).apply {
                    text = title; textSize = 15.5f; setTextColor(onCard)
                })
                if (!sub.isNullOrEmpty()) addView(TextView(ctx).apply {
                    text = sub; textSize = 12.5f; setTextColor(onCardDim)
                    setPadding(0, (2 * dp).toInt(), 0, 0)
                })
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = (10 * dp).toInt() })
            addView(sw)
        })
    }

    private fun primary(text: String, onClick: () -> Unit): TextView =
        TextView(requireContext()).apply {
            this.text = text
            textSize = 16f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                cornerRadius = 26 * dp
                setColor(ThemeUi.accent(context))
            }
            setPadding(0, (15 * dp).toInt(), 0, (15 * dp).toInt())
            isClickable = true
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(-1, -2)
                .apply { topMargin = (14 * dp).toInt() }
        }.also { content.addView(it) }

    private fun secondary(text: String, onClick: () -> Unit) {
        content.addView(TextView(requireContext()).apply {
            this.text = text
            textSize = 15f
            setTextColor(onCardDim)
            gravity = Gravity.CENTER
            setPadding(0, (14 * dp).toInt(), 0, (6 * dp).toInt())
            isClickable = true
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(-1, -2)
        })
    }

    private fun smallNote(text: String) {
        content.addView(TextView(requireContext()).apply {
            this.text = text
            textSize = 12.5f
            setTextColor(onCardDim)
            gravity = Gravity.CENTER
            setLineSpacing(0f, 1.2f)
            setPadding((6 * dp).toInt(), (10 * dp).toInt(), (6 * dp).toInt(), 0)
            layoutParams = LinearLayout.LayoutParams(-1, -2)
        })
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (step in WIZARD) handleWizardBack()
        if (step == Step.TRANSFER)
            requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, blockBack)
    }

    private fun handleWizardBack() {
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner) {
            val fm = parentFragmentManager
            when {
                step == WIZARD.first() -> go(Step.DONE)
                fm.backStackEntryCount > 0 -> fm.popBackStack()
                else -> (activity as? SetupActivity)?.navigateTo(of(previousStep()), addToBackStack = false)
            }
        }
    }

    private fun previousStep(): Step {
        val i = WIZARD.indexOf(step)
        if (i <= 0) return Step.CUSTOMIZE
        val prev = WIZARD[i - 1]
        return if (prev == Step.EXPERIMENT && !hasExtraFeatures(requireContext())) WIZARD[i - 2] else prev
    }

    private fun go(next: Step?, fragment: Fragment? = null) {
        val target = fragment ?: of(next ?: Step.DONE)
        (activity as? SetupActivity)?.navigateTo(target)
    }

    override fun onStart() {
        super.onStart()
        privacyOptions?.refresh()
        if (step == Step.TRANSLATE && translateList != null) {
            Translations.removeListener(translateChanged)
            Translations.addListener(translateChanged)
            translateList?.refresh()
        }
        if (step == Step.NOTIFY && isAdded && notifyBatteryFree != batteryFree(requireContext())) refresh()
        if (step != Step.LOCK || !isAdded) return
        val ctx = requireContext()
        if (lockHasPin != pl.sinusoide.sinuschat.LockManager.hasPin(ctx) ||
            lockHasDuress != pl.sinusoide.sinuschat.LockManager.hasDuressPin(ctx)) {
            refresh()
        }
    }

    private fun refresh() {
        pinnedHead?.let { column.removeView(it) }
        pinnedHead = null
        content.removeAllViews()
        build()
    }

    private fun fadeIn(root: View) {
        root.alpha = 0f
        root.translationY = 24 * dp
        root.animate().alpha(1f).translationY(0f).setDuration(320).start()
    }

    private fun askNotificationConsents() {
        val ctx = requireContext()
        val needsPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            androidx.core.content.ContextCompat.checkSelfPermission(ctx,
                android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        if (needsPermission && !notifyPermissionAsked) {
            notifyPermissionAsked = true
            notifyPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        val noGoogle = NotificationPrefs.pushMode(ctx) == NotificationPrefs.PushMode.NO_GOOGLE
        if (noGoogle && !batteryFree(ctx)) {
            askBatterySheet { go(afterNotify(ctx)) }
            return
        }
        go(afterNotify(ctx))
    }

    private fun afterNotify(ctx: Context): Step =
        if (SetupActivity.pendingRegistration(ctx) != null) Step.DONE else Step.CUSTOMIZE

    private fun batteryFree(ctx: Context): Boolean = try {
        (ctx.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager)
            .isIgnoringBatteryOptimizations(ctx.packageName)
    } catch (_: Exception) {
        false
    }

    private fun askUsbSheet() {
        val ctx = requireContext()
        pl.sinusoide.sinuschat.ui.UsbPlugSheet.show(requireActivity(), getString(R.string.setup_usb_desc),
            isReady = { pl.sinusoide.sinuschat.ui.UsbPlugSheet.usbAttached(ctx) },
            onReady = { if (isAdded) pickBackupFolder.launch(pl.sinusoide.sinuschat.ui.UsbPlugSheet.usbRootUri(ctx)) })
    }

    private fun pickSheet(icon: Int, title: String, desc: String,
                          items: List<Pair<String, Boolean>>, onPick: (Int) -> Unit) {
        val ctx = requireContext()
        val accent = ThemeUi.accent(ctx)
        val sheet = com.google.android.material.bottomsheet.BottomSheetDialog(ctx, R.style.Theme_SinusChat_BottomSheet)
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding((20 * dp).toInt(), (12 * dp).toInt(), (20 * dp).toInt(), (20 * dp).toInt())
        }
        root.addView(View(ctx).apply {
            background = GradientDrawable().apply { cornerRadius = 3 * dp; setColor(ThemeUi.handle(ctx)) }
        }, LinearLayout.LayoutParams((36 * dp).toInt(), (4 * dp).toInt()).apply { bottomMargin = (18 * dp).toInt() })
        root.addView(ImageView(ctx).apply {
            setImageResource(icon)
            setColorFilter(accent)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL; setColor((accent and 0x00FFFFFF) or 0x26000000)
            }
            val p = (14 * dp).toInt(); setPadding(p, p, p, p)
        }, LinearLayout.LayoutParams((56 * dp).toInt(), (56 * dp).toInt()).apply { bottomMargin = (12 * dp).toInt() })
        root.addView(TextView(ctx).apply {
            text = title
            textSize = 19f; setTextColor(ThemeUi.textPrimary(ctx)); typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        })
        root.addView(TextView(ctx).apply {
            text = desc
            textSize = 13.5f; setTextColor(ThemeUi.textSecondary(ctx)); gravity = Gravity.CENTER
            setLineSpacing(0f, 1.2f)
            setPadding(0, (6 * dp).toInt(), 0, (16 * dp).toInt())
        })
        for ((i, item) in items.withIndex()) {
            root.addView(TextView(ctx).apply {
                text = item.first
                textSize = 16f
                setTextColor(if (item.second) accent else ThemeUi.textPrimary(ctx))
                typeface = if (item.second) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                gravity = Gravity.CENTER_VERTICAL
                background = card().apply { if (item.second) setStroke((2 * dp).toInt(), accent) }
                setPadding((16 * dp).toInt(), (14 * dp).toInt(), (16 * dp).toInt(), (14 * dp).toInt())
                isClickable = true
                setOnClickListener { sheet.dismiss(); onPick(i) }
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = (8 * dp).toInt() }
            })
        }
        sheet.setContentView(root)
        pl.sinusoide.sinuschat.ui.FlowGlass.show(sheet)
    }

    private fun askBatterySheet(onClose: () -> Unit = {}) {
        val ctx = requireContext()
        val accent = ThemeUi.accent(ctx)
        val sheet = com.google.android.material.bottomsheet.BottomSheetDialog(ctx, R.style.Theme_SinusChat_BottomSheet)
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding((24 * dp).toInt(), (12 * dp).toInt(), (24 * dp).toInt(), (20 * dp).toInt())
        }
        root.addView(View(ctx).apply {
            background = GradientDrawable().apply { cornerRadius = 3 * dp; setColor(ThemeUi.handle(ctx)) }
        }, LinearLayout.LayoutParams((36 * dp).toInt(), (4 * dp).toInt()).apply { bottomMargin = (20 * dp).toInt() })
        root.addView(android.widget.ImageView(ctx).apply {
            setImageResource(R.drawable.ic_notifications)
            setColorFilter(accent)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL; setColor((accent and 0x00FFFFFF) or 0x26000000)
            }
            val p = (16 * dp).toInt(); setPadding(p, p, p, p)
        }, LinearLayout.LayoutParams((64 * dp).toInt(), (64 * dp).toInt()).apply { bottomMargin = (16 * dp).toInt() })
        root.addView(TextView(ctx).apply {
            text = getString(R.string.setup_notify_battery_title)
            textSize = 20f; setTextColor(ThemeUi.textPrimary(ctx)); typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        })
        root.addView(TextView(ctx).apply {
            text = getString(R.string.setup_notify_battery_ask)
            textSize = 14f; setTextColor(ThemeUi.textSecondary(ctx)); gravity = Gravity.CENTER
            setLineSpacing(0f, 1.2f)
            setPadding(0, (8 * dp).toInt(), 0, (22 * dp).toInt())
        })
        root.addView(TextView(ctx).apply {
            text = getString(R.string.setup_notify_battery_go)
            textSize = 16f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = GradientDrawable().apply { cornerRadius = 26 * dp; setColor(accent) }
            setPadding(0, (14 * dp).toInt(), 0, (14 * dp).toInt())
            isClickable = true
            setOnClickListener { sheet.dismiss(); askBattery() }
        }, LinearLayout.LayoutParams(-1, -2))
        root.addView(TextView(ctx).apply {
            text = getString(R.string.cancel)
            textSize = 15f; setTextColor(ThemeUi.textSecondary(ctx)); gravity = Gravity.CENTER
            setPadding(0, (14 * dp).toInt(), 0, (4 * dp).toInt())
            isClickable = true
            setOnClickListener { sheet.dismiss() }
        }, LinearLayout.LayoutParams(-1, -2))
        sheet.setOnDismissListener { onClose() }
        sheet.setContentView(root)
        pl.sinusoide.sinuschat.ui.FlowGlass.show(sheet)
    }

    private fun askBattery() {
        val ctx = requireContext()
        try {
            startActivity(Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                android.net.Uri.parse("package:" + ctx.packageName)))
        } catch (_: Exception) {
            try {
                startActivity(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (_: Exception) {}
        }
    }

    private fun ramGb(ctx: Context): Int = try {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        val gib = 1024.0 * 1024.0 * 1024.0
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && mi.advertisedMem > 0)
            Math.round(mi.advertisedMem / gib).toInt()
        else intArrayOf(2, 3, 4, 6, 8, 12, 16, 24, 32).firstOrNull { it >= mi.totalMem / gib }
            ?: Math.ceil(mi.totalMem / gib).toInt()
    } catch (_: Throwable) { 8 }

    private fun sceneFor(s: Step): SetupIllustration.Scene = when (s) {
        Step.WELCOME, Step.TERMS, Step.START -> SetupIllustration.Scene.MARK
        Step.LANGUAGE -> SetupIllustration.Scene.LANGUAGE
        Step.CUSTOMIZE -> SetupIllustration.Scene.PROFILE
        Step.THEME -> SetupIllustration.Scene.THEME
        Step.ACCOUNT -> SetupIllustration.Scene.ACCOUNT
        Step.LOCK -> SetupIllustration.Scene.LOCK
        Step.PRIVACY -> SetupIllustration.Scene.PRIVACY
        Step.NOTIFY -> SetupIllustration.Scene.NOTIFY
        Step.INTERNET -> SetupIllustration.Scene.INTERNET
        Step.EXPERIMENT -> SetupIllustration.Scene.EXPERIMENT
        Step.SOUND -> SetupIllustration.Scene.NOTIFY
        Step.TRANSLATE -> SetupIllustration.Scene.LANGUAGE
        Step.BACKUP -> SetupIllustration.Scene.BACKUP
        Step.TRANSFER -> SetupIllustration.Scene.TRANSFER
        Step.DONE -> SetupIllustration.Scene.DONE
    }
}
