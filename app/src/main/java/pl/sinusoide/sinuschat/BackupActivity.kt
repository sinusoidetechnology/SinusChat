package pl.sinusoide.sinuschat

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.materialswitch.MaterialSwitch
import pl.sinusoide.sinuschat.ui.FlowGlass
import pl.sinusoide.sinuschat.ui.ThemeUi
import pl.sinusoide.sinuschat.util.AppExecutor
import pl.sinusoide.sinuschat.util.BackupCrypto
import pl.sinusoide.sinuschat.util.BackupManager
import pl.sinusoide.sinuschat.util.BackupPrefs
import pl.sinusoide.sinuschat.util.BackupRunner
import pl.sinusoide.sinuschat.util.LocaleManager
import pl.sinusoide.sinuschat.util.PopupHelper
import pl.sinusoide.sinuschat.util.RecoveryKey
import pl.sinusoide.sinuschat.util.ThemeManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.activity.enableEdgeToEdge

class BackupActivity : AppCompatActivity() {

    private val dp get() = resources.displayMetrics.density

    private var statusText: TextView? = null
    private var nextText: TextView? = null
    private var mediaSub: TextView? = null
    private var intervalSub: TextView? = null
    private var folderSub: TextView? = null
    private var remindSub: TextView? = null
    private var keySub: TextView? = null

    private var verifyUri: Uri? = null

    private var backupAfterPick = false

    private val pickFolder = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        val thenBackup = backupAfterPick
        backupAfterPick = false
        if (uri == null) return@registerForActivityResult
        try {
            contentResolver.takePersistableUriPermission(uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        } catch (_: Exception) {}
        val name = androidx.documentfile.provider.DocumentFile.fromTreeUri(this, uri)?.name.orEmpty()
        BackupPrefs.setFolder(this, uri.toString(), name)
        refresh()
        if (thenBackup) runBackup()
    }

    private val pickBackupFile = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? -> if (uri != null) verifyBackup(uri) }

    private val keyKit = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        refresh()
        if (result.resultCode == RESULT_OK)
            PopupHelper.showSuccess(this, getString(R.string.backup_key_saved))
    }

    private val enterKey = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uri = verifyUri ?: return@registerForActivityResult
        val key = RecoveryKeyActivity.keyFrom(result.data)
        if (result.resultCode == RESULT_OK && key != null) openBackup(uri, key)
    }

    private val googleConsent = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        val token = pl.sinusoide.sinuschat.util.DriveBackup.tokenFromResult(this, result.data)
        if (token.isNullOrEmpty()) {
            PopupHelper.showError(this, getString(R.string.backup_drive_denied))
        } else {
            BackupPrefs.setTarget(this, BackupPrefs.TARGET_DRIVE)
            BackupPrefs.setDriveAccount(this, accountFrom(result.data))
            refresh()
            PopupHelper.showSuccess(this, getString(R.string.backup_drive_connected))
        }
    }

    private fun accountFrom(data: android.content.Intent?): String = try {
        com.google.android.gms.auth.api.identity.Identity.getAuthorizationClient(this)
            .getAuthorizationResultFromIntent(data).toGoogleSignInAccount()?.email.orEmpty()
    } catch (_: Throwable) { "" }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleManager.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyTheme(this)
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val flow = ThemeManager.isFlow(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(if (flow) Color.TRANSPARENT else ThemeUi.backgroundSolid(this@BackupActivity))
        }
        root.addView(topBar())

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((14 * dp).toInt(), (2 * dp).toInt(), (14 * dp).toInt(), (28 * dp).toInt())
        }
        content.addView(statusCard())
        content.addView(sectionTitle(getString(R.string.backup_section_settings)))
        content.addView(settingsCard())
        content.addView(sectionTitle(getString(R.string.backup_section_security)))
        content.addView(securityCard())
        content.addView(sectionTitle(getString(R.string.backup_section_transfer)))
        content.addView(transferCard())
        content.addView(TextView(this).apply {
            text = getString(R.string.backup_note)
            textSize = 12.5f
            setTextColor(if (flow) 0xAAFFFFFF.toInt() else ThemeUi.textSecondary(context))
            gravity = Gravity.CENTER
            setLineSpacing(0f, 1.2f)
            setPadding((10 * dp).toInt(), (18 * dp).toInt(), (10 * dp).toInt(), 0)
        })

        root.addView(ScrollView(this).apply { addView(content) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }
        refresh()
        if (intent.getBooleanExtra(EXTRA_INSPECT_EXTERNAL, false)) {
            pl.sinusoide.sinuschat.backup.ExternalBackup.inspectFileIfReady(this)?.let { file ->
                PopupHelper.show(this, getString(R.string.ext_import_inspect_only))
                verifyBackup(Uri.fromFile(file))
            }
        }
    }

    override fun onDestroy() {
        if (intent.getBooleanExtra(EXTRA_INSPECT_EXTERNAL, false) && isFinishing) {
            pl.sinusoide.sinuschat.backup.ExternalBackup.clearInspect(this)
        }
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun statusCard(): View {
        val card = cardContainer()
        card.setPadding((16 * dp).toInt(), (16 * dp).toInt(), (16 * dp).toInt(), (16 * dp).toInt())
        card.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_backup)
            setColorFilter(onCard())
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor((ThemeUi.accent(context) and 0x00FFFFFF) or 0x33000000)
            }
            val p = (13 * dp).toInt(); setPadding(p, p, p, p)
            layoutParams = LinearLayout.LayoutParams((54 * dp).toInt(), (54 * dp).toInt())
                .apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = (10 * dp).toInt() }
        })
        statusText = TextView(this).apply {
            textSize = 15.5f
            setTextColor(onCard())
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        card.addView(statusText)
        nextText = TextView(this).apply {
            textSize = 12.5f
            setTextColor(subColor())
            gravity = Gravity.CENTER
            setPadding(0, (3 * dp).toInt(), 0, (14 * dp).toInt())
        }
        card.addView(nextText)
        card.addView(bigButton(getString(R.string.backup_now)) { backupNow() })
        return card
    }

    private fun settingsCard(): View {
        val card = cardContainer()
        card.addView(switchRow(R.drawable.ic_download, getString(R.string.backup_media_title),
            BackupPrefs.withMedia(this)) { on ->
            BackupPrefs.setWithMedia(this, on); refresh()
        }.also { mediaSub = it.findViewWithTag<TextView>("sub") })
        card.addView(divider())
        card.addView(row(R.drawable.ic_timer, getString(R.string.backup_interval_title)) { chooseInterval() }
            .also { intervalSub = it.findViewWithTag<TextView>("sub") })
        card.addView(divider())
        card.addView(row(R.drawable.ic_where_save, getString(R.string.backup_folder_title)) { chooseTarget() }
            .also { folderSub = it.findViewWithTag<TextView>("sub") })
        card.addView(divider())
        card.addView(row(R.drawable.ic_notifications, getString(R.string.backup_remind_row)) { chooseRemind() }
            .also { remindSub = it.findViewWithTag<TextView>("sub") })
        return card
    }

    private fun securityCard(): View {
        val card = cardContainer()
        card.addView(row(R.drawable.ic_key, getString(R.string.backup_key_title)) {
            keyKit.launch(RecoveryKeyActivity.kitIntent(this))
        }.also { keySub = it.findViewWithTag<TextView>("sub") })
        card.addView(divider())
        card.addView(row(R.drawable.ic_document, getString(R.string.backup_verify_title),
            getString(R.string.backup_verify_sub)) {
            pickBackupFile.launch(arrayOf("*/*"))
        })
        return card
    }

    private fun transferCard(): View {
        val card = cardContainer()
        card.addView(row(R.drawable.ic_move_data, getString(R.string.backup_transfer_title),
            getString(R.string.backup_transfer_sub)) {
            startActivity(Intent(this, TransferSendActivity::class.java))
        })
        return card
    }

    private fun refresh() {
        val last = BackupPrefs.lastBackupMs(this)
        statusText?.text =
            if (last == 0L) getString(R.string.backup_never)
            else getString(R.string.backup_last, dateTime(last))
        val next = BackupPrefs.nextDueMs(this)
        nextText?.text = when {
            next == 0L -> getString(R.string.backup_interval_off)
            System.currentTimeMillis() >= next -> getString(R.string.backup_due_now)
            else -> getString(R.string.backup_next, dateTime(next))
        }
        mediaSub?.text =
            if (BackupPrefs.withMedia(this)) getString(R.string.backup_media_on, sizeLabel(BackupManager.mediaBytes(this)))
            else getString(R.string.backup_media_off)
        intervalSub?.text = intervalLabel(BackupPrefs.intervalSec(this))
        folderSub?.text = when {
            BackupPrefs.target(this) == BackupPrefs.TARGET_DRIVE -> {
                val where = getString(
                    if (BackupPrefs.driveHidden(this)) R.string.backup_drive_hidden_short
                    else R.string.backup_drive_folder_short)
                val acc = BackupPrefs.driveAccount(this)
                if (acc.isEmpty()) getString(R.string.backup_drive_row_ok, where)
                else getString(R.string.backup_drive_row_acc, acc, where)
            }
            BackupPrefs.target(this) == BackupPrefs.TARGET_LOCAL -> when {
                BackupPrefs.folderUri(this) == null -> getString(R.string.backup_local_none)
                BackupRunner.folderReady(this) -> getString(R.string.backup_local_ready, BackupPrefs.folderName(this))
                else -> getString(R.string.backup_local_missing, BackupPrefs.folderName(this))
            }
            BackupPrefs.folderUri(this) == null -> getString(R.string.backup_folder_none)
            BackupRunner.folderReady(this) -> getString(R.string.backup_folder_ready, BackupPrefs.folderName(this))
            else -> getString(R.string.backup_folder_missing, BackupPrefs.folderName(this))
        }
        remindSub?.text = remindLabel(BackupPrefs.remindMode(this))
        keySub?.text =
            if (BackupPrefs.hasRecoveryKey(this)) getString(R.string.backup_key_set,
                date(BackupPrefs.recoveryKeyCreatedMs(this)), BackupPrefs.recoveryKeyId(this))
            else getString(R.string.backup_key_none)
    }

    private fun intervalLabel(sec: Long): String = when (sec) {
        0L -> getString(R.string.backup_interval_off)
        BackupPrefs.DAY -> getString(R.string.backup_daily)
        BackupPrefs.WEEK -> getString(R.string.backup_weekly)
        BackupPrefs.MONTH -> getString(R.string.backup_monthly)
        BackupPrefs.QUARTER -> getString(R.string.backup_quarterly)
        BackupPrefs.YEAR -> getString(R.string.backup_yearly)
        else -> getString(R.string.backup_every, pl.sinusoide.sinuschat.util.AutoDelete.label(this, sec))
    }

    private fun remindLabel(mode: Int): String = when (mode) {
        BackupPrefs.REMIND_NOTIFICATION -> getString(R.string.backup_remind_notif)
        BackupPrefs.REMIND_ON_OPEN -> getString(R.string.backup_remind_open)
        else -> getString(R.string.backup_remind_both)
    }

    private fun backupNow() {
        if (!BackupPrefs.hasRecoveryKey(this)) {
            PopupHelper.showError(this, getString(R.string.backup_no_key))
            keyKit.launch(RecoveryKeyActivity.kitIntent(this))
            return
        }
        if (BackupPrefs.target(this) == BackupPrefs.TARGET_USB) {
            askUsbThenBackup()
            return
        }
        if (BackupPrefs.target(this) == BackupPrefs.TARGET_LOCAL && !BackupRunner.folderReady(this)) {
            backupAfterPick = true
            pickFolder.launch(pl.sinusoide.sinuschat.ui.UsbPlugSheet.phoneDocumentsUri())
            return
        }
        runBackup()
    }

    private fun askUsbThenBackup() {
        var attachedSince = 0L
        pl.sinusoide.sinuschat.ui.UsbPlugSheet.show(this, getString(R.string.backup_usb_desc),
            isReady = {
                val hasFolder = BackupPrefs.folderUri(this) != null
                when {
                    hasFolder && BackupRunner.folderReady(this) -> true
                    pl.sinusoide.sinuschat.ui.UsbPlugSheet.usbAttached(this) -> {
                        val now = android.os.SystemClock.elapsedRealtime()
                        if (attachedSince == 0L) attachedSince = now
                        !hasFolder || now - attachedSince >= OTHER_DRIVE_MS
                    }
                    else -> { attachedSince = 0L; false }
                }
            },
            onReady = {
                if (BackupRunner.folderReady(this)) runBackup()
                else {
                    backupAfterPick = true
                    pickFolder.launch(pl.sinusoide.sinuschat.ui.UsbPlugSheet.usbRootUri(this))
                }
            })
    }

    private fun runBackup() {
        val sheet = BottomSheetDialog(this, R.style.Theme_SinusChat_BottomSheet)
        sheet.setCancelable(false)
        val (root, desc) = sheetRoot(R.drawable.ic_backup, getString(R.string.backup_running), "")
        sheet.setContentView(root)
        FlowGlass.show(sheet)
        sheet.show()

        AppExecutor.io {
            val result = BackupRunner.runNow(this) { done, total ->
                AppExecutor.main { desc.text = getString(R.string.backup_progress, done, total) }
            }
            AppExecutor.main {
                sheet.dismiss()
                refresh()
                when (result) {
                    is BackupRunner.Result.Ok -> PopupHelper.showSuccess(this,
                        getString(R.string.backup_done, result.name))
                    is BackupRunner.Result.NoFolder -> PopupHelper.showError(this,
                        getString(R.string.backup_no_media_device))
                    is BackupRunner.Result.NeedsGoogle -> googleConsent.launch(
                        androidx.activity.result.IntentSenderRequest.Builder(result.intent).build())
                    is BackupRunner.Result.GoogleNotConfigured -> PopupHelper.showError(this,
                        getString(R.string.backup_drive_not_configured))
                    is BackupRunner.Result.NoKey -> {
                        PopupHelper.showError(this, getString(R.string.backup_no_key))
                        keyKit.launch(RecoveryKeyActivity.kitIntent(this))
                    }
                    is BackupRunner.Result.Failed -> PopupHelper.showError(this,
                        getString(R.string.backup_failed, result.message))
                }
            }
        }
    }

    private fun chooseTarget() {
        val sheet = BottomSheetDialog(this, R.style.Theme_SinusChat_BottomSheet)
        val (root, _) = sheetRoot(R.drawable.ic_where_save, getString(R.string.backup_folder_title),
            getString(R.string.backup_target_desc))
        val current = BackupPrefs.target(this)
        val list = listContainer()
        list.addView(option(getString(R.string.backup_target_usb), current == BackupPrefs.TARGET_USB) {
            BackupPrefs.setTarget(this, BackupPrefs.TARGET_USB)
            sheet.dismiss()
            pl.sinusoide.sinuschat.ui.UsbPlugSheet.show(this, getString(R.string.setup_usb_desc),
                isReady = { pl.sinusoide.sinuschat.ui.UsbPlugSheet.usbAttached(this) },
                onReady = { pickFolder.launch(pl.sinusoide.sinuschat.ui.UsbPlugSheet.usbRootUri(this)) })
        })
        list.addView(divider())
        list.addView(option(getString(R.string.backup_target_local), current == BackupPrefs.TARGET_LOCAL) {
            sheet.dismiss()
            pl.sinusoide.sinuschat.ui.ConfirmSheet.show(this,
                icon = R.drawable.ic_storage,
                color = 0xFFE67E22.toInt(),
                title = getString(R.string.backup_local_title),
                message = getString(R.string.backup_local_desc),
                confirm = getString(R.string.backup_local_pick),
                onConfirm = {
                    BackupPrefs.setTarget(this, BackupPrefs.TARGET_LOCAL)
                    BackupPrefs.setFolder(this, null, "")
                    refresh()
                    pickFolder.launch(pl.sinusoide.sinuschat.ui.UsbPlugSheet.phoneDocumentsUri())
                })
        })
        list.addView(divider())
        val drive = current == BackupPrefs.TARGET_DRIVE
        list.addView(option(getString(R.string.backup_target_drive_hidden),
            drive && BackupPrefs.driveHidden(this)) {
            sheet.dismiss()
            connectDrive(hidden = true)
        })
        list.addView(divider())
        list.addView(option(getString(R.string.backup_target_drive_folder),
            drive && !BackupPrefs.driveHidden(this)) {
            sheet.dismiss()
            connectDrive(hidden = false)
        })
        root.addView(list, LinearLayout.LayoutParams(-1, -2))
        root.addView(TextView(this).apply {
            text = getString(R.string.backup_target_warning)
            textSize = 12.5f
            setTextColor(ThemeUi.textSecondary(context))
            gravity = Gravity.CENTER
            setLineSpacing(0f, 1.2f)
            setPadding((6 * dp).toInt(), (14 * dp).toInt(), (6 * dp).toInt(), 0)
        }, LinearLayout.LayoutParams(-1, -2))
        sheet.setContentView(root)
        FlowGlass.show(sheet)
        sheet.show()
    }

    private fun connectDrive(hidden: Boolean) {
        pl.sinusoide.sinuschat.util.SafeEngine.guard(this, pl.sinusoide.sinuschat.util.SafeEngine.Risk.CLOUD_BACKUP) { connectDriveConfirmed(hidden) }
    }

    private fun connectDriveConfirmed(hidden: Boolean) {
        BackupPrefs.setDriveHidden(this, hidden)
        AppExecutor.io {
            val auth = pl.sinusoide.sinuschat.util.DriveBackup.authorize(this, hidden)
            AppExecutor.main {
                when (auth) {
                    is pl.sinusoide.sinuschat.util.DriveBackup.Auth.Token -> {
                        BackupPrefs.setTarget(this, BackupPrefs.TARGET_DRIVE)
                        refresh()
                        PopupHelper.showSuccess(this, getString(R.string.backup_drive_connected))
                    }
                    is pl.sinusoide.sinuschat.util.DriveBackup.Auth.NeedsUser ->
                        googleConsent.launch(
                            androidx.activity.result.IntentSenderRequest.Builder(auth.intent).build())
                    is pl.sinusoide.sinuschat.util.DriveBackup.Auth.Error ->
                        PopupHelper.showError(this,
                            if (auth.message == "setup") getString(R.string.backup_drive_not_configured)
                            else getString(R.string.backup_failed, auth.message))
                }
            }
        }
    }

    private fun chooseInterval() {
        val labels = listOf(
            0L to getString(R.string.backup_interval_off),
            BackupPrefs.DAY to getString(R.string.backup_daily),
            BackupPrefs.WEEK to getString(R.string.backup_weekly),
            BackupPrefs.MONTH to getString(R.string.backup_monthly),
            BackupPrefs.QUARTER to getString(R.string.backup_quarterly),
            BackupPrefs.YEAR to getString(R.string.backup_yearly)
        )
        val current = BackupPrefs.intervalSec(this)
        val sheet = BottomSheetDialog(this, R.style.Theme_SinusChat_BottomSheet)
        val (root, _) = sheetRoot(R.drawable.ic_timer, getString(R.string.backup_interval_title),
            getString(R.string.backup_interval_desc))
        val list = listContainer()
        for ((sec, label) in labels) {
            if (list.childCount > 0) list.addView(divider())
            list.addView(option(label, current == sec) {
                BackupPrefs.setIntervalSec(this, sec); sheet.dismiss(); refresh()
            })
        }
        list.addView(divider())
        val customOn = current > 0L && BackupPrefs.PRESETS.none { it == current }
        list.addView(option(
            if (customOn) getString(R.string.backup_custom_value, pl.sinusoide.sinuschat.util.AutoDelete.label(this, current))
            else getString(R.string.backup_custom), customOn) {
            sheet.dismiss(); chooseCustomInterval()
        })
        root.addView(list, LinearLayout.LayoutParams(-1, -2))
        sheet.setContentView(root)
        FlowGlass.show(sheet)
        sheet.show()
    }

    private fun chooseCustomInterval() {
        val sheet = BottomSheetDialog(this, R.style.Theme_SinusChat_BottomSheet)
        val (root, _) = sheetRoot(R.drawable.ic_timer, getString(R.string.backup_custom),
            getString(R.string.auto_delete_custom_desc))
        val input = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            textSize = 17f; gravity = Gravity.CENTER
            setTextColor(ThemeUi.textPrimary(context))
            background = ThemeUi.rounded(context, ThemeUi.inset(context), 16f)
            setPadding((16 * dp).toInt(), (14 * dp).toInt(), (16 * dp).toInt(), (14 * dp).toInt())
            hint = "14"
            setHintTextColor(ThemeUi.textMuted(context))
        }
        root.addView(input, LinearLayout.LayoutParams(-1, -2))

        val units = listOf(
            pl.sinusoide.sinuschat.util.AutoDelete.HOUR to R.string.unit_hour,
            pl.sinusoide.sinuschat.util.AutoDelete.DAY to R.string.unit_day,
            pl.sinusoide.sinuschat.util.AutoDelete.MONTH to R.string.unit_month,
            pl.sinusoide.sinuschat.util.AutoDelete.YEAR to R.string.unit_year
        )
        var unit = pl.sinusoide.sinuschat.util.AutoDelete.DAY
        val chips = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, (12 * dp).toInt(), 0, (4 * dp).toInt())
        }
        val chipViews = ArrayList<TextView>()
        fun paint() {
            for ((i, v) in chipViews.withIndex()) {
                val on = units[i].first == unit
                v.background = ThemeUi.rounded(this, if (on) ThemeUi.accent(this) else ThemeUi.inset(this), 14f)
                v.setTextColor(if (on) Color.WHITE else ThemeUi.textPrimary(this))
            }
        }
        for ((value, nameRes) in units) {
            val chip = TextView(this).apply {
                text = getString(nameRes); textSize = 13f; gravity = Gravity.CENTER; maxLines = 1
                setPadding((4 * dp).toInt(), (9 * dp).toInt(), (4 * dp).toInt(), (9 * dp).toInt())
                isClickable = true
                setOnClickListener { unit = value; paint() }
            }
            chipViews.add(chip)
            chips.addView(chip, LinearLayout.LayoutParams(0, -2, 1f).apply {
                marginStart = (3 * dp).toInt(); marginEnd = (3 * dp).toInt()
            })
        }
        paint()
        root.addView(chips, LinearLayout.LayoutParams(-1, -2))
        root.addView(bigButton(getString(R.string.auto_delete_apply)) {
            val n = input.text.toString().trim().toLongOrNull() ?: 0L
            if (n <= 0L) { PopupHelper.showError(this, getString(R.string.auto_delete_bad_number)); return@bigButton }
            BackupPrefs.setIntervalSec(this, n * unit)
            sheet.dismiss(); refresh()
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = (16 * dp).toInt() })
        sheet.setContentView(root)
        FlowGlass.show(sheet)
        sheet.show()
    }

    private fun chooseRemind() {
        val sheet = BottomSheetDialog(this, R.style.Theme_SinusChat_BottomSheet)
        val (root, _) = sheetRoot(R.drawable.ic_notifications, getString(R.string.backup_remind_row),
            getString(R.string.backup_remind_desc))
        val current = BackupPrefs.remindMode(this)
        val list = listContainer()
        for (mode in listOf(BackupPrefs.REMIND_NOTIFICATION, BackupPrefs.REMIND_ON_OPEN, BackupPrefs.REMIND_BOTH)) {
            if (list.childCount > 0) list.addView(divider())
            list.addView(option(remindLabel(mode), current == mode) {
                BackupPrefs.setRemindMode(this, mode); sheet.dismiss(); refresh()
            })
        }
        root.addView(list, LinearLayout.LayoutParams(-1, -2))
        sheet.setContentView(root)
        FlowGlass.show(sheet)
        sheet.show()
    }

    private fun verifyBackup(uri: Uri) {
        AppExecutor.io {
            val header = try {
                contentResolver.openInputStream(uri)!!.use { BackupManager.readHeader(it) }
            } catch (e: BackupCrypto.LegacyFormatException) {
                AppExecutor.main { PopupHelper.showError(this, getString(R.string.backup_verify_legacy)) }
                return@io
            } catch (e: Throwable) {
                AppExecutor.main { PopupHelper.showError(this, getString(R.string.backup_verify_not_backup)) }
                return@io
            }
            val mine = BackupPrefs.recoveryKey(this)
            if (mine != null && mine.matches(header.keyId)) openBackup(uri, mine)
            else AppExecutor.main {
                verifyUri = uri
                enterKey.launch(RecoveryKeyActivity.enterIntent(this, header.keyId))
            }
        }
    }

    private fun openBackup(uri: Uri, key: RecoveryKey) {
        AppExecutor.io {
            val manifest = try {
                contentResolver.openInputStream(uri)!!.use { BackupManager.readManifest(it, key) }
            } catch (e: Throwable) { null }
            AppExecutor.main {
                if (manifest == null) {
                    PopupHelper.showError(this, getString(R.string.backup_verify_failed))
                    return@main
                }
                val mine = BackupManager.belongsToMe(this, manifest)
                val sheet = BottomSheetDialog(this, R.style.Theme_SinusChat_BottomSheet)
                val (root, _) = sheetRoot(
                    if (mine) R.drawable.ic_shield else R.drawable.ic_warning,
                    getString(R.string.backup_verify_ok),
                    getString(R.string.backup_verify_desc, dateTime(manifest.createdAt),
                        manifest.conversations, manifest.messages, manifest.files))
                root.addView(TextView(this).apply {
                    text = if (mine) getString(R.string.backup_verify_mine)
                    else getString(R.string.backup_verify_foreign, manifest.displayName.ifEmpty { "—" })
                    textSize = 13.5f
                    gravity = Gravity.CENTER
                    setLineSpacing(0f, 1.2f)
                    setTextColor(if (mine) ContextCompat.getColor(this@BackupActivity, R.color.sinuschat_success)
                    else ContextCompat.getColor(this@BackupActivity, R.color.sinuschat_error))
                    setPadding((8 * dp).toInt(), 0, (8 * dp).toInt(), (10 * dp).toInt())
                }, LinearLayout.LayoutParams(-1, -2))
                sheet.setContentView(root)
                FlowGlass.show(sheet)
                sheet.show()
            }
        }
    }

    private fun onCard(): Int =
        if (ThemeManager.isFlow(this)) Color.WHITE else ThemeUi.textPrimary(this)

    private fun subColor(): Int =
        if (ThemeManager.isFlow(this)) 0xB3FFFFFF.toInt() else ThemeUi.textSecondary(this)

    private fun cardContainer(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        clipToOutline = true
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = (10 * dp).toInt() }
        background = if (ThemeManager.isFlow(this@BackupActivity))
            pl.sinusoide.sinuschat.ui.GlassDrawable(dp, null, false, 20 * dp, null)
        else GradientDrawable().apply {
            cornerRadius = 20 * dp
            setColor(ThemeUi.card(this@BackupActivity))
            setStroke(dp.toInt().coerceAtLeast(1), ThemeUi.border(this@BackupActivity))
        }
    }

    private fun sectionTitle(text: String) = TextView(this).apply {
        this.text = text.uppercase()
        textSize = 12f
        setTextColor(if (ThemeManager.isFlow(this@BackupActivity)) 0xCCFFFFFF.toInt() else ThemeUi.accent(context))
        typeface = Typeface.DEFAULT_BOLD
        letterSpacing = 0.06f
        setPadding((6 * dp).toInt(), (20 * dp).toInt(), 0, (2 * dp).toInt())
    }

    private fun divider() = View(this).apply {
        setBackgroundColor(ThemeUi.divider(this@BackupActivity))
        layoutParams = LinearLayout.LayoutParams(-1, 1)
    }

    private fun listContainer() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        clipToOutline = true
        background = ThemeUi.rounded(this@BackupActivity, ThemeUi.card(this@BackupActivity), 18f)
    }

    private fun option(text: String, selected: Boolean, onClick: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = ContextCompat.getDrawable(this@BackupActivity, R.drawable.bg_menu_ripple)
        setPadding((16 * dp).toInt(), (14 * dp).toInt(), (16 * dp).toInt(), (14 * dp).toInt())
        isClickable = true
        setOnClickListener { onClick() }
        addView(TextView(this@BackupActivity).apply {
            this.text = text
            textSize = 15.5f
            setTextColor(if (selected) ThemeUi.accent(context) else ThemeUi.textPrimary(context))
            if (selected) typeface = Typeface.DEFAULT_BOLD
        }, LinearLayout.LayoutParams(0, -2, 1f))
        if (selected) addView(ImageView(this@BackupActivity).apply {
            setImageResource(R.drawable.ic_check)
            setColorFilter(ThemeUi.accent(context))
        }, LinearLayout.LayoutParams((20 * dp).toInt(), (20 * dp).toInt()))
    }

    private fun iconTile(icon: Int) = ImageView(this).apply {
        setImageResource(icon)
        setColorFilter(onCard())
        background = GradientDrawable().apply {
            cornerRadius = 12 * dp
            setColor((ThemeUi.accent(context) and 0x00FFFFFF) or 0x24000000)
        }
        val p = (9 * dp).toInt(); setPadding(p, p, p, p)
        layoutParams = LinearLayout.LayoutParams((40 * dp).toInt(), (40 * dp).toInt())
            .apply { marginEnd = (14 * dp).toInt() }
    }

    private fun texts(title: String, sub: String?) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(TextView(this@BackupActivity).apply {
            text = title; textSize = 15.5f; setTextColor(onCard())
        })
        addView(TextView(this@BackupActivity).apply {
            tag = "sub"
            text = sub.orEmpty(); textSize = 12.5f; setTextColor(subColor())
            setPadding(0, (2 * dp).toInt(), 0, 0)
        })
    }

    private fun row(icon: Int, title: String, sub: String? = null, enabled: Boolean = true,
                    onClick: () -> Unit): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding((14 * dp).toInt(), (13 * dp).toInt(), (12 * dp).toInt(), (13 * dp).toInt())
        alpha = if (enabled) 1f else 0.5f
        if (enabled) {
            background = ContextCompat.getDrawable(this@BackupActivity, R.drawable.bg_menu_ripple)
            isClickable = true
            setOnClickListener { onClick() }
        }
        addView(iconTile(icon))
        addView(texts(title, sub), LinearLayout.LayoutParams(0, -2, 1f)
            .apply { marginEnd = (8 * dp).toInt() })
        if (enabled) addView(ImageView(this@BackupActivity).apply {
            setImageResource(R.drawable.ic_chevron_right)
            setColorFilter(subColor())
        }, LinearLayout.LayoutParams((20 * dp).toInt(), (20 * dp).toInt()))
    }

    private fun switchRow(icon: Int, title: String, checked: Boolean,
                          onChange: (Boolean) -> Unit): LinearLayout {
        val sw = MaterialSwitch(this).apply {
            isChecked = checked
            thumbTintList = android.content.res.ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(Color.WHITE, 0xFF8AA0B8.toInt()))
            trackTintList = android.content.res.ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(ThemeUi.accent(context), 0xFF2A3A4A.toInt()))
            setOnCheckedChangeListener { _, on -> onChange(on) }
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((14 * dp).toInt(), (11 * dp).toInt(), (10 * dp).toInt(), (11 * dp).toInt())
            background = ContextCompat.getDrawable(this@BackupActivity, R.drawable.bg_menu_ripple)
            isClickable = true
            setOnClickListener { sw.isChecked = !sw.isChecked }
            addView(iconTile(icon))
            addView(texts(title, ""), LinearLayout.LayoutParams(0, -2, 1f)
                .apply { marginEnd = (8 * dp).toInt() })
            addView(sw)
        }
    }

    private fun bigButton(text: String, onClick: () -> Unit) = TextView(this).apply {
        this.text = text
        textSize = 16f
        setTextColor(Color.WHITE)
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        background = ThemeUi.rounded(context, ThemeUi.accent(context), 26f)
        setPadding(0, (14 * dp).toInt(), 0, (14 * dp).toInt())
        isClickable = true
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(-1, -2)
    }

    private fun sheetRoot(icon: Int, title: String, desc: String): Pair<LinearLayout, TextView> {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding((24 * dp).toInt(), (12 * dp).toInt(), (24 * dp).toInt(), (20 * dp).toInt())
        }
        root.addView(View(this).apply {
            background = GradientDrawable().apply {
                cornerRadius = 3 * dp; setColor(ThemeUi.handle(context))
            }
        }, LinearLayout.LayoutParams((36 * dp).toInt(), (4 * dp).toInt())
            .apply { bottomMargin = (20 * dp).toInt() })
        root.addView(ImageView(this).apply {
            setImageResource(icon)
            setColorFilter(ThemeUi.accent(context))
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor((ThemeUi.accent(context) and 0x00FFFFFF) or 0x26000000)
            }
            val p = (16 * dp).toInt(); setPadding(p, p, p, p)
        }, LinearLayout.LayoutParams((64 * dp).toInt(), (64 * dp).toInt())
            .apply { bottomMargin = (16 * dp).toInt() })
        root.addView(TextView(this).apply {
            this.text = title; textSize = 20f
            setTextColor(ThemeUi.textPrimary(context))
            typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
        })
        val descView = TextView(this).apply {
            this.text = desc; textSize = 14f
            setTextColor(ThemeUi.textSecondary(context))
            gravity = Gravity.CENTER
            setLineSpacing(0f, 1.2f)
            setPadding(0, (8 * dp).toInt(), 0, (16 * dp).toInt())
        }
        root.addView(descView)
        return root to descView
    }

    private fun topBar(): View {
        val flow = ThemeManager.isFlow(this)
        val onBar = if (flow) Color.WHITE else ThemeUi.textPrimary(this)
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((6 * dp).toInt(), (6 * dp).toInt(), (6 * dp).toInt(), (6 * dp).toInt())
            layoutParams = LinearLayout.LayoutParams(-1, -2)
        }
        bar.addView(android.widget.ImageButton(this).apply {
            setImageResource(R.drawable.ic_back)
            background = null
            imageTintList = android.content.res.ColorStateList.valueOf(onBar)
            setPadding((9 * dp).toInt(), (9 * dp).toInt(), (9 * dp).toInt(), (9 * dp).toInt())
            contentDescription = getString(R.string.action_back)
            layoutParams = LinearLayout.LayoutParams((42 * dp).toInt(), (42 * dp).toInt())
            setOnClickListener { finish() }
        })
        bar.addView(TextView(this).apply {
            text = getString(R.string.backup_title)
            setTextColor(onBar); textSize = 17f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                .apply { marginStart = (6 * dp).toInt() }
        })
        FlowGlass.bar(bar, 22f)
        return bar
    }

    companion object {
        const val EXTRA_INSPECT_EXTERNAL = "inspect_external"
        private const val OTHER_DRIVE_MS = 4000L
    }

    private fun date(ms: Long): String =
        SimpleDateFormat("d MMMM yyyy", Locale.getDefault()).format(Date(ms))

    private fun dateTime(ms: Long): String =
        SimpleDateFormat("d MMMM yyyy, HH:mm", Locale.getDefault()).format(Date(ms))

    private fun sizeLabel(bytes: Long): String = when {
        bytes >= 1_073_741_824 -> String.format(Locale.getDefault(), "%.1f GB", bytes / 1_073_741_824.0)
        bytes >= 1_048_576 -> String.format(Locale.getDefault(), "%.0f MB", bytes / 1_048_576.0)
        bytes >= 1024 -> String.format(Locale.getDefault(), "%.0f KB", bytes / 1024.0)
        else -> "$bytes B"
    }
}
