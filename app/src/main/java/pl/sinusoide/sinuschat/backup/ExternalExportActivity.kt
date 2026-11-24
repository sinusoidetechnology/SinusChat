package pl.sinusoide.sinuschat.backup

import android.app.KeyguardManager
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.graphics.ColorUtils
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.progressindicator.LinearProgressIndicator
import pl.sinusoide.sinuschat.LockManager
import pl.sinusoide.sinuschat.R
import pl.sinusoide.sinuschat.RecoveryKeyActivity
import pl.sinusoide.sinuschat.ScreenLockActivity
import pl.sinusoide.sinuschat.crypto.DbKeyManager
import pl.sinusoide.sinuschat.setup.SetupActivity
import pl.sinusoide.sinuschat.ui.ConfirmSheet
import pl.sinusoide.sinuschat.ui.FlowGlass
import pl.sinusoide.sinuschat.ui.ThemeUi
import pl.sinusoide.sinuschat.util.AppExecutor
import pl.sinusoide.sinuschat.util.BackupManager
import pl.sinusoide.sinuschat.util.BackupPrefs
import pl.sinusoide.sinuschat.util.LocaleManager
import pl.sinusoide.sinuschat.util.RecoveryKey
import pl.sinusoide.sinuschat.util.ThemeManager
import java.io.File

class ExternalExportActivity : AppCompatActivity() {

    private var caller: String = ""
    private var callerName: String = ""
    private var withMedia = false
    private var authStarted = false
    private var done = false

    private val dp by lazy { resources.displayMetrics.density }

    private val authResult = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == RESULT_OK) afterAuth() else cancel(ExternalBackup.ERROR_CANCELLED)
    }

    private val keyKit = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (BackupPrefs.hasRecoveryKey(this)) afterAuth() else cancel(ExternalBackup.ERROR_NO_KEY)
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleManager.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyTheme(this)
        theme.applyStyle(R.style.ThemeOverlay_SinusChat_Translucent, true)
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        if (savedInstanceState != null) { cancel(ExternalBackup.ERROR_CANCELLED); return }
        if (!ExternalBackup.isExportRequest(intent?.action)) { cancel(ExternalBackup.ERROR_CANCELLED); return }
        caller = callingPackage ?: run { cancel(ExternalBackup.ERROR_NO_CALLER); return }
        if (caller == packageName) { cancel(ExternalBackup.ERROR_NO_CALLER); return }
        callerName = labelOf(caller)
        if (!SetupActivity.isSetupDone(this)) {
            info(R.drawable.ic_warning, getString(R.string.ext_export_not_ready_title), getString(R.string.ext_export_not_ready_msg)) {
                cancel(ExternalBackup.ERROR_NOT_READY)
            }
            return
        }
        withMedia = intent.getBooleanExtra(ExternalBackup.EXTRA_WITH_MEDIA, BackupPrefs.withMedia(this))
        showConsent()
    }

    private fun labelOf(pkg: String): String = try {
        packageManager.getApplicationInfo(pkg, 0).loadLabel(packageManager).toString()
    } catch (_: Exception) { pkg }

    private fun showConsent() {
        val mediaBytes = BackupManager.mediaBytes(this)
        val box = CheckBox(this).apply {
            text = getString(R.string.ext_export_with_media, sizeLabel(mediaBytes))
            textSize = 14f
            setTextColor(ThemeUi.textPrimary(this@ExternalExportActivity))
            isChecked = withMedia && mediaBytes > 0
            isEnabled = mediaBytes > 0
            setOnCheckedChangeListener { _, v -> withMedia = v }
        }
        val who = if (callerName == caller) caller else "$callerName ($caller)"
        ConfirmSheet.show(this,
            icon = R.drawable.ic_backup,
            color = ThemeUi.accent(this),
            title = getString(R.string.ext_export_title),
            message = getString(R.string.ext_export_msg, who),
            confirm = getString(R.string.ext_export_confirm),
            dismiss = getString(R.string.ext_export_deny),
            extra = box,
            onConfirm = {
                withMedia = box.isChecked
                authStarted = true
                authenticate()
            },
            onCancel = { if (!authStarted) cancel(ExternalBackup.ERROR_CANCELLED) })
    }

    private fun authenticate() {
        if (LockManager.hasPin(this)) {
            if (DbKeyManager.needsPinUnlock(this) || !LockManager.sessionUnlocked) {
                authResult.launch(Intent(this, ScreenLockActivity::class.java)
                    .putExtra(ScreenLockActivity.EXTRA_MODE, ScreenLockActivity.MODE_UNLOCK)
                    .putExtra(ScreenLockActivity.EXTRA_RETURN_RESULT, true))
                return
            }
            val askPin = {
                authResult.launch(Intent(this, ScreenLockActivity::class.java)
                    .putExtra(ScreenLockActivity.EXTRA_MODE, ScreenLockActivity.MODE_CONFIRM)
                    .putExtra(ScreenLockActivity.EXTRA_TITLE, getString(R.string.ext_export_auth_title)))
            }
            if (LockManager.isBiometricEnabled(this) && LockManager.isBiometricAvailable(this)) {
                LockManager.showBiometricPrompt(this, onSuccess = { afterAuth() }, onFailed = { askPin() })
            } else askPin()
            return
        }
        val km = getSystemService(KeyguardManager::class.java)
        @Suppress("DEPRECATION")
        val confirm = if (km != null && km.isDeviceSecure)
            km.createConfirmDeviceCredentialIntent(getString(R.string.ext_export_auth_title),
                getString(R.string.ext_export_auth_desc, callerName))
        else null
        if (confirm != null) authResult.launch(confirm) else afterAuth()
    }

    private fun afterAuth() {
        val key = BackupPrefs.recoveryKey(this)
        if (key != null) { export(key); return }
        if (!BackupPrefs.hasRecoveryKey(this)) {
            ConfirmSheet.show(this,
                icon = R.drawable.ic_key,
                color = ThemeUi.accent(this),
                title = getString(R.string.ext_export_no_key_title),
                message = getString(R.string.ext_export_no_key_msg),
                confirm = getString(R.string.ext_export_no_key_confirm),
                onConfirm = { keyKit.launch(RecoveryKeyActivity.kitIntent(this)) },
                onCancel = { cancel(ExternalBackup.ERROR_NO_KEY) })
            return
        }
        cancel(ExternalBackup.ERROR_NO_KEY)
    }

    private fun export(key: RecoveryKey) {
        val sheet = BottomSheetDialog(this, R.style.Theme_SinusChat_BottomSheet)
        sheet.setCancelable(false)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding((24 * dp).toInt(), (26 * dp).toInt(), (24 * dp).toInt(), (30 * dp).toInt())
        }
        val accent = ThemeUi.accent(this)
        val fallback = ContextCompat.getDrawable(this, R.drawable.ic_backup)!!
        val ownIcon = try { packageManager.getApplicationIcon(packageName) } catch (_: Exception) { fallback }
        val callerIcon = try { packageManager.getApplicationIcon(caller) } catch (_: Exception) { fallback }
        val handoff = HandoffView(this, ownIcon, callerIcon, accent,
            ThemeUi.textSecondary(this))
        root.addView(handoff, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = (18 * dp).toInt() })
        val title = TextView(this).apply {
            text = getString(R.string.ext_export_working_for, callerName)
            textSize = 18f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
            setTextColor(ThemeUi.textPrimary(this@ExternalExportActivity))
        }
        root.addView(title)
        val desc = TextView(this).apply {
            text = getString(R.string.ext_export_working_desc, callerName)
            textSize = 13.5f; gravity = Gravity.CENTER
            setLineSpacing(0f, 1.15f)
            setTextColor(ThemeUi.textSecondary(this@ExternalExportActivity))
            setPadding(0, (6 * dp).toInt(), 0, (16 * dp).toInt())
        }
        root.addView(desc)
        val bar = LinearProgressIndicator(this).apply {
            isIndeterminate = true
            trackCornerRadius = (3 * dp).toInt()
            trackThickness = (6 * dp).toInt()
            setIndicatorColor(accent)
            trackColor = ColorUtils.setAlphaComponent(accent, 0x30)
        }
        root.addView(bar, LinearLayout.LayoutParams(-1, -2))
        val progress = TextView(this).apply {
            textSize = 13f; gravity = Gravity.CENTER
            setTextColor(ThemeUi.textSecondary(this@ExternalExportActivity))
            setPadding(0, (8 * dp).toInt(), 0, 0)
        }
        root.addView(progress)
        sheet.setContentView(root)
        FlowGlass.show(sheet)

        val app = applicationContext
        val media = withMedia
        AppExecutor.io {
            val file = try {
                ExternalBackup.clearExports(app)
                val dir = ExternalBackup.exportDir(app).apply { mkdirs() }
                if (media && app.cacheDir.usableSpace < BackupManager.mediaBytes(app) + 64L * 1024 * 1024)
                    throw java.io.IOException(getString(R.string.backup_drive_no_space))
                val f = File(dir, BackupManager.fileName(System.currentTimeMillis()))
                f.outputStream().buffered(256 * 1024).use { out ->
                    BackupManager.create(app, out, key, media) { d, t ->
                        AppExecutor.main {
                            progress.text = getString(R.string.backup_progress, d, t)
                            if (t > 0) {
                                if (bar.isIndeterminate) { bar.isIndeterminate = false; bar.max = t }
                                bar.setProgressCompat(d, true)
                            }
                        }
                    }
                }
                ExternalBackup.scheduleCleanup(app)
                f
            } catch (e: Throwable) {
                Log.e(TAG, "eksport nieudany: ${e.javaClass.simpleName}")
                ExternalBackup.clearExports(app)
                AppExecutor.main {
                    if (isFinishing || isDestroyed) return@main
                    sheet.dismiss()
                    info(R.drawable.ic_warning, getString(R.string.ext_export_failed_title),
                        getString(R.string.backup_failed, e.message ?: e.javaClass.simpleName)) {
                        cancel(ExternalBackup.ERROR_FAILED)
                    }
                }
                return@io
            }
            AppExecutor.main {
                if (isFinishing || isDestroyed) return@main
                bar.isIndeterminate = false
                bar.max = 100
                bar.setProgressCompat(100, true)
                bar.setIndicatorColor(SENT_GREEN)
                title.text = getString(R.string.ext_export_sent, callerName)
                desc.text = getString(R.string.ext_export_sent_desc)
                progress.text = ""
                handoff.complete {
                    if (isFinishing || isDestroyed) return@complete
                    sheet.dismiss()
                    deliver(file, key)
                }
            }
        }
    }

    private fun deliver(file: File, key: RecoveryKey) {
        val uri = try {
            FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        } catch (e: Exception) {
            cancel(ExternalBackup.ERROR_FAILED)
            return
        }
        grantUriPermission(caller, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val data = Intent()
            .setDataAndType(uri, ExternalBackup.MIME)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .putExtra(ExternalBackup.EXTRA_KEY_ID, ExternalBackup.hex(key.id))
            .putExtra(Intent.EXTRA_TITLE, file.name)
            .putExtra(ExternalBackup.EXTRA_WITH_MEDIA, withMedia)
        data.clipData = ClipData(file.name, arrayOf(ExternalBackup.MIME), ClipData.Item(uri))
        done = true
        setResult(RESULT_OK, data)
        finish()
    }

    private fun info(icon: Int, title: String, message: String, after: () -> Unit) {
        var handled = false
        val once = { if (!handled) { handled = true; after() } }
        ConfirmSheet.show(this,
            icon = icon,
            color = ThemeUi.accent(this),
            title = title,
            message = message,
            confirm = getString(R.string.understand),
            onConfirm = once,
            onCancel = once,
            dismiss = getString(R.string.action_close))
    }

    private fun cancel(code: String) {
        if (done) return
        done = true
        setResult(RESULT_CANCELED, Intent().putExtra(ExternalBackup.EXTRA_ERROR, code))
        finish()
    }

    private fun sizeLabel(bytes: Long): String = when {
        bytes >= 1L shl 30 -> String.format(java.util.Locale.US, "%.1f GB", bytes / (1L shl 30).toDouble())
        bytes >= 1L shl 20 -> String.format(java.util.Locale.US, "%.0f MB", bytes / (1L shl 20).toDouble())
        bytes > 0 -> String.format(java.util.Locale.US, "%.0f KB", bytes / 1024.0)
        else -> "0 MB"
    }

    companion object {
        private const val TAG = "ExternalExport"
        private const val SENT_GREEN = 0xFF2ECC71.toInt()
    }
}
