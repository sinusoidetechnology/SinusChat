package pl.sinusoide.sinuschat.backup

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.IntentCompat
import com.google.android.material.bottomsheet.BottomSheetDialog
import pl.sinusoide.sinuschat.BackupActivity
import pl.sinusoide.sinuschat.R
import pl.sinusoide.sinuschat.setup.SetupActivity
import pl.sinusoide.sinuschat.ui.ConfirmSheet
import pl.sinusoide.sinuschat.ui.FlowGlass
import pl.sinusoide.sinuschat.ui.ThemeUi
import pl.sinusoide.sinuschat.util.AppExecutor
import pl.sinusoide.sinuschat.util.BackupCrypto
import pl.sinusoide.sinuschat.util.LocaleManager
import pl.sinusoide.sinuschat.util.ThemeManager

class ExternalImportActivity : AppCompatActivity() {

    private val dp by lazy { resources.displayMetrics.density }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleManager.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyTheme(this)
        theme.applyStyle(R.style.ThemeOverlay_SinusChat_Translucent, true)
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        if (savedInstanceState != null) { finish(); return }

        val stream = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
        val source = ExternalBackup.importSource(intent?.action, intent?.dataString, stream?.toString())
        if (source == null) { finish(); return }
        val uri = Uri.parse(source)
        val from = ExternalBackup.callerFromReferrer(referrer?.toString())?.let { labelOf(it) }
        stage(uri, from)
    }

    private fun labelOf(pkg: String): String = try {
        packageManager.getApplicationInfo(pkg, 0).loadLabel(packageManager).toString()
    } catch (_: Exception) { pkg }

    private fun stage(uri: Uri, from: String?) {
        val sheet = BottomSheetDialog(this, R.style.Theme_SinusChat_BottomSheet)
        sheet.setCancelable(false)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding((24 * dp).toInt(), (26 * dp).toInt(), (24 * dp).toInt(), (30 * dp).toInt())
        }
        root.addView(ProgressBar(this), LinearLayout.LayoutParams((44 * dp).toInt(), (44 * dp).toInt()).apply {
            bottomMargin = (14 * dp).toInt()
        })
        root.addView(TextView(this).apply {
            text = getString(R.string.ext_import_reading)
            textSize = 18f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
            setTextColor(ThemeUi.textPrimary(this@ExternalImportActivity))
        })
        sheet.setContentView(root)
        FlowGlass.show(sheet)

        val app = applicationContext
        val setupDone = SetupActivity.isSetupDone(app)
        AppExecutor.io {
            val error = try {
                ExternalBackup.stage(app, uri, forRestore = !setupDone, source = from)
                null
            } catch (e: Throwable) {
                Log.w(TAG, "plik: ${e.javaClass.simpleName}")
                e
            }
            AppExecutor.main {
                if (isFinishing || isDestroyed) return@main
                sheet.dismiss()
                when {
                    error != null -> failed(error)
                    setupDone -> openInspect()
                    else -> readyForSetup()
                }
            }
        }
    }

    private fun failed(e: Throwable) {
        val msg = when (e) {
            is BackupCrypto.LegacyFormatException -> getString(R.string.backup_verify_legacy)
            is ExternalBackup.NoSpaceException -> getString(R.string.backup_drive_no_space)
            is ExternalBackup.NotBackupException -> getString(R.string.backup_verify_not_backup)
            else -> getString(R.string.ext_import_failed)
        }
        info(R.drawable.ic_warning, getString(R.string.ext_import_failed_title), msg) { finish() }
    }

    private fun openInspect() {
        startActivity(Intent(this, BackupActivity::class.java)
            .putExtra(BackupActivity.EXTRA_INSPECT_EXTERNAL, true))
        finish()
    }

    private fun readyForSetup() {
        ConfirmSheet.show(this,
            icon = R.drawable.ic_backup,
            color = ThemeUi.accent(this),
            title = getString(R.string.ext_import_ready_title),
            message = getString(R.string.ext_import_ready_msg),
            confirm = getString(R.string.ext_import_ready_confirm),
            onConfirm = {
                packageManager.getLaunchIntentForPackage(packageName)?.let {
                    startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
                finish()
            },
            onCancel = { finish() },
            dismiss = getString(R.string.action_close))
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

    companion object {
        private const val TAG = "ExternalImport"
    }
}
