package pl.sinusoide.sinuschat

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.viewpager2.widget.ViewPager2
import pl.sinusoide.sinuschat.crypto.DbKeyManager
import pl.sinusoide.sinuschat.ui.SlideNavBar
import pl.sinusoide.sinuschat.crypto.E2EManager
import pl.sinusoide.sinuschat.messenger.MessageListenerService
import pl.sinusoide.sinuschat.setup.SetupActivity
import pl.sinusoide.sinuschat.util.PopupHelper
import pl.sinusoide.sinuschat.util.PrivacySettings
import pl.sinusoide.sinuschat.util.ThemeManager
import pl.sinusoide.sinuschat.util.optStringOrNull

class MainActivity : AppCompatActivity() {
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(pl.sinusoide.sinuschat.util.LocaleManager.wrap(newBase))
    }


    init {
        try {
            System.loadLibrary("signal_jni")
        } catch (e: Throwable) {
            Log.w("MainActivity", "Native library signal_jni load failed (might already be loaded)", e)
        }
    }

    companion object {
        var isAppInForeground = false
    }

    private lateinit var viewPager: ViewPager2
    private lateinit var bottomNav: SlideNavBar

    fun setChatsBadge(count: Int) {
        if (::bottomNav.isInitialized) bottomNav.setBadge(0, count)
    }

    private val CALLS_TAB = 1
    private val PREFS_CALLS = "sinuschat_calls_badge"
    private val KEY_CALLS_SEEN = "missed_seen_ms"

    private var latestCalls: List<pl.sinusoide.sinuschat.data.CallEntity> = emptyList()

    private fun callsSeenAt(): Long {
        val prefs = getSharedPreferences(PREFS_CALLS, MODE_PRIVATE)
        if (!prefs.contains(KEY_CALLS_SEEN)) {
            prefs.edit().putLong(KEY_CALLS_SEEN, System.currentTimeMillis()).apply()
        }
        return prefs.getLong(KEY_CALLS_SEEN, 0L)
    }

    private fun markCallsSeen() {
        getSharedPreferences(PREFS_CALLS, MODE_PRIVATE).edit()
            .putLong(KEY_CALLS_SEEN, System.currentTimeMillis()).apply()
        if (::bottomNav.isInitialized) bottomNav.setBadge(CALLS_TAB, 0)
    }

    private fun refreshMissedCallsBadge() {
        if (!::bottomNav.isInitialized) return
        if (isAppInForeground && ::viewPager.isInitialized && viewPager.currentItem == CALLS_TAB) {
            markCallsSeen(); return
        }
        val seen = callsSeenAt()
        val missed = latestCalls.count {
            it.direction == pl.sinusoide.sinuschat.data.CallLogRepository.MISSED && it.startedAtMs > seen
        }
        bottomNav.setBadge(CALLS_TAB, missed)
    }

    private val requestNotifPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            MessageListenerService.start(this)
        } else {
            PopupHelper.show(this, getString(R.string.no_notif_permission_hint))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        try {
            if (DbKeyManager.needsPinUnlock(this)) {
                ScreenLockActivity.start(this, ScreenLockActivity.MODE_UNLOCK)
                super.onCreate(null)
                finish()
                return
            }

            ThemeManager.applyTheme(this)
            appliedThemeKey = ThemeManager.getCurrent(this)
            enableEdgeToEdge(navigationBarStyle = androidx.activity.SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT))
            super.onCreate(savedInstanceState)

            if (!SetupActivity.isSetupDone(this) || SetupActivity.wizardStep(this) != null) {
                startActivity(Intent(this, SetupActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                })
                finish()
                return
            }

            try {
                val phoneHash = SetupActivity.getUserPhoneHash(this)
                if (phoneHash.isNotEmpty()) {
                    pl.sinusoide.sinuschat.util.UserStatusManager.init(phoneHash, this)
                    pl.sinusoide.sinuschat.network.NtfyManager.setup(this, phoneHash)
                    scheduleMessageSync()
                    pl.sinusoide.sinuschat.call.CallNotifier.createChannel(this)
                    registerNtfyTopic(phoneHash)

                    if (E2EManager.keysMissing(this)) {
                        pl.sinusoide.sinuschat.util.LinkLog.add(this, "keys", "brak klucza, tworzę nowy")
                        E2EManager.generateAndUploadKeys(this, phoneHash) { success ->
                            Log.d("MainActivity", "E2E key generation: $success")
                        }
                    }

                    pl.sinusoide.sinuschat.network.ApiClient.autoRegisterIfNeeded(this) { success ->
                        if (success) {
                            Log.d("MainActivity", "OVH auth OK — starting service")
                            PrivacySettings.syncPrivacyWithServer(this)
                        } else {
                            Log.w("MainActivity", "OVH auth FAILED — service may not work")
                        }
                        MessageListenerService.start(this)
                    }
                }
            } catch (e: Throwable) {
                Log.e("MainActivity", "E2E init check failed", e)
                if (e.message?.contains("decryption", ignoreCase = true) == true) {
                    SetupActivity.resetSetup(this)
                    startActivity(Intent(this, SetupActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    })
                    finish()
                    return
                }
            }

            if (intent.getBooleanExtra("unlocked", false)) {
                LockManager.sessionUnlocked = true
                pl.sinusoide.sinuschat.messenger.MessageListenerService.pollNow(this)
            }
            
            if (LockManager.isLockEnabled(this) && !LockManager.sessionUnlocked) {
                ScreenLockActivity.cover(this)
            }
            
            PrivacySettings.syncPrivacyWithServer(this)

            init()
            pl.sinusoide.sinuschat.ui.CircleReveal.playOn(this)
            handleIntent(intent)
            ensureNotificationPermission()
            ensureFullScreenCallPermission()
            pl.sinusoide.sinuschat.push.PushManager.syncOnStart(this)
            val appCtx = applicationContext
            pl.sinusoide.sinuschat.util.AppExecutor.io {
                pl.sinusoide.sinuschat.crypto.SignalProtocol.ensureKeysUploaded(appCtx)
                pl.sinusoide.sinuschat.crypto.ProfileCrypto.ensureSynced(appCtx)
                pl.sinusoide.sinuschat.util.BlockList.syncServer(appCtx)
            }
        } catch (e: Throwable) {
            Log.e("MainActivity", "CRITICAL CRASH IN ONCREATE", e)
            try {
                setContentView(android.R.layout.simple_list_item_1)
                PopupHelper.showError(this, getString(R.string.error_start_format, e.message))
            } catch (_: Throwable) {}
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val url = intent?.getStringExtra("url")
        if (!url.isNullOrEmpty()) {
            Log.d("MainActivity", "Received URL: $url")
        }
    }

    private fun requestBatteryExemption() {
        if (MessageListenerService.onDemand(this)) return
        try {
            val pm = getSystemService(android.os.PowerManager::class.java)
            if (pm != null && !pm.isIgnoringBatteryOptimizations(packageName)) {
                @android.annotation.SuppressLint("BatteryLife")
                val intent = Intent(
                    android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    android.net.Uri.parse("package:$packageName")
                )
                startActivity(intent)
            }
        } catch (e: Exception) {
            android.util.Log.w("MainActivity", "battery exemption: ${e.message}")
        }
    }

    private fun ensureFullScreenCallPermission() {
        if (Build.VERSION.SDK_INT < 34) return
        val nm = getSystemService(android.app.NotificationManager::class.java) ?: return
        if (nm.canUseFullScreenIntent()) return
        val prefs = getSharedPreferences("sinuschat_call_perms", MODE_PRIVATE)
        val last = prefs.getLong("fsi_asked_ms", 0L)
        if (System.currentTimeMillis() - last < 3L * 24 * 3600_000) return
        prefs.edit().putLong("fsi_asked_ms", System.currentTimeMillis()).apply()
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this, R.style.Theme_SinusChat_Dialog)
            .setTitle(R.string.fsi_perm_title)
            .setMessage(R.string.fsi_perm_message)
            .setPositiveButton(R.string.fsi_perm_open) { _, _ ->
                try {
                    startActivity(Intent(
                        android.provider.Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
                        android.net.Uri.parse("package:$packageName")))
                } catch (_: Exception) {
                    try {
                        startActivity(Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, packageName))
                    } catch (_: Exception) {}
                }
            }
            .setNegativeButton(R.string.fsi_perm_later, null)
            .show()
    }

    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(this,
                Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            if (granted) {
                MessageListenerService.start(this)
            } else {
                requestNotifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        } else {
            MessageListenerService.start(this)
        }
    }

    private fun init() {
        setContentView(R.layout.activity_main)
        supportActionBar?.hide()

        requestBatteryExemption()

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.view_pager)) { _, insets ->
            insets
        }
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.bottom_nav)) { _, insets ->
            insets
        }

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main_nav_spacer)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.layoutParams.height = systemBars.bottom
            v.requestLayout()
            insets
        }

        createNotificationChannel()

        viewPager = findViewById(R.id.view_pager)
        bottomNav = findViewById(R.id.bottom_nav)
        if (ThemeManager.isFlow(this)) findViewById<android.view.View>(R.id.main_nav_spacer).background = null
        if (ThemeManager.isRetro(this)) {
            bottomNav.background = pl.sinusoide.sinuschat.ui.RetroUi.bottomBar(this)
            findViewById<android.view.View>(R.id.main_nav_spacer).setBackgroundColor(pl.sinusoide.sinuschat.ui.RetroUi.CHROME)
            pl.sinusoide.sinuschat.ui.RetroUi.lightStatusIcons(this)
        }
        viewPager.adapter = ViewPagerAdapter(this)
        viewPager.isUserInputEnabled = false

        val retroTabs = ThemeManager.isRetro(this)
        bottomNav.onTabSelected = { index ->
            viewPager.setCurrentItem(index, !retroTabs)
        }

        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                bottomNav.setSelected(position)
                if (position == CALLS_TAB) markCallsSeen()
            }
        })

        pl.sinusoide.sinuschat.data.SinusChatDatabase.getInstance(this).callDao().observeAll().observe(this) { calls ->
            latestCalls = calls
            refreshMissedCallsBadge()
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                isEnabled = false; onBackPressedDispatcher.onBackPressed()
            }
        })
    }

    private var appliedThemeKey: String? = null


    private fun maybeAskForBackup() {
        if (!pl.sinusoide.sinuschat.util.BackupRunner.shouldAskOnOpen(this)) return
        pl.sinusoide.sinuschat.util.BackupPrefs.markAsked(this)
        androidx.appcompat.app.AlertDialog.Builder(this, R.style.Theme_SinusChat_Dialog)
            .setTitle(getString(R.string.backup_ask_title))
            .setMessage(getString(R.string.backup_ask_text))
            .setPositiveButton(getString(R.string.backup_ask_go)) { _, _ ->
                startActivity(Intent(this, BackupActivity::class.java))
            }
            .setNegativeButton(getString(R.string.backup_ask_later), null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        pl.sinusoide.sinuschat.util.MessageNotifier.dismissSummary(this)
        val appliedTheme = appliedThemeKey
        if (appliedTheme != null && appliedTheme != ThemeManager.getCurrent(this)) {
            recreate()
            return
        }
        isAppInForeground = true
        if (pl.sinusoide.sinuschat.util.AutoDelete.isExpired(this))
            pl.sinusoide.sinuschat.util.AppExecutor.io { pl.sinusoide.sinuschat.util.AutoDelete.check(this) }
        else pl.sinusoide.sinuschat.util.AutoDelete.touch(this)
        maybeAskForBackup()
        window.decorView.postDelayed({
            if (!isFinishing && !isDestroyed) pl.sinusoide.sinuschat.util.SafeMode.showPending(this)
        }, 600L)
        clearNotifications()
        enableNfcReader()
        refreshMissedCallsBadge()
    }

    private fun clearNotifications() {
        pl.sinusoide.sinuschat.util.MessageNotifier.markAllCleared(this)
        val nm = getSystemService(android.app.NotificationManager::class.java)
        if (nm == null || !pl.sinusoide.sinuschat.util.ChatBubbles.isActive(this)) {
            NotificationManagerCompat.from(this).cancelAll()
            return
        }
        try {
            nm.activeNotifications.filter { it.notification.bubbleMetadata == null }.forEach { nm.cancel(it.tag, it.id) }
        } catch (_: Exception) {
            NotificationManagerCompat.from(this).cancelAll()
        }
    }
    override fun onPause() {
        super.onPause()
        isAppInForeground = false
        disableNfcReader()
    }

    private val nfcReaderCallback = android.nfc.NfcAdapter.ReaderCallback { tag ->
        android.util.Log.d("NfcReader", "Tag detected: ${tag.techList?.joinToString()}")
        try {
            val iso = android.nfc.tech.IsoDep.get(tag)
            if (iso == null) {
                android.util.Log.d("NfcReader", "IsoDep not supported")
                return@ReaderCallback
            }
            iso.timeout = 5000
            iso.connect()
            
            val aid = byteArrayOf(0xF0.toByte(), 0x53, 0x49, 0x4E, 0x55, 0x53, 0x43)
            val selectApdu = byteArrayOf(0x00, 0xA4.toByte(), 0x04, 0x00, aid.size.toByte()) + aid + byteArrayOf(0x00)
            
            android.util.Log.d("NfcReader", "Sending SELECT AID...")
            var resp = iso.transceive(selectApdu)
            
            if (resp == null || resp.size < 2) {
                android.util.Log.d("NfcReader", "Empty response, retrying...")
                Thread.sleep(100)
                resp = iso.transceive(selectApdu)
            }
            
            iso.close()
            
            if (resp == null || resp.size < 2) {
                android.util.Log.d("NfcReader", "No response from HCE service")
                return@ReaderCallback
            }
            
            val sw1 = resp[resp.size - 2].toInt() and 0xFF
            val sw2 = resp[resp.size - 1].toInt() and 0xFF
            if (sw1 == 0x90 && sw2 == 0x00) {
                val payload = String(resp.copyOfRange(0, resp.size - 2), Charsets.UTF_8)
                android.util.Log.d("NfcReader", "Odebrano kontakt przez NFC (${payload.length} B)")
                runOnUiThread {
                    pl.sinusoide.sinuschat.nfc.ContactLinkHandler.handle(this, payload)
                }
            } else {
                android.util.Log.d("NfcReader", "Status error: ${"%02X %02X".format(sw1, sw2)}")
            }
        } catch (e: Exception) {
            android.util.Log.e("NfcReader", "NFC read error", e)
        }
    }

    private fun enableNfcReader() {
        val adapter = android.nfc.NfcAdapter.getDefaultAdapter(this) ?: return
        if (!adapter.isEnabled) return
        
        val flags = android.nfc.NfcAdapter.FLAG_READER_NFC_A or
                android.nfc.NfcAdapter.FLAG_READER_NFC_B or
                android.nfc.NfcAdapter.FLAG_READER_NFC_F or
                android.nfc.NfcAdapter.FLAG_READER_NFC_V or
                android.nfc.NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK or
                android.nfc.NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS
                
        val extras = android.os.Bundle().apply {
            putInt(android.nfc.NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, 500)
        }
        
        try {
            adapter.enableReaderMode(this, nfcReaderCallback, flags, extras)
            android.util.Log.d("NfcReader", "Reader mode enabled (exclusive)")
        } catch (e: Exception) {
            android.util.Log.e("NfcReader", "Failed to enable reader mode", e)
        }
    }

    private fun disableNfcReader() {
        val adapter = android.nfc.NfcAdapter.getDefaultAdapter(this) ?: return
        try {
            adapter.disableReaderMode(this)
        } catch (_: Exception) {}
    }


    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel("sinuschat_id", getString(R.string.notif_channel_messages), NotificationManager.IMPORTANCE_HIGH).apply {
                description = getString(R.string.notif_channel_messages_desc); enableVibration(true)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun registerNtfyTopic(phoneHash: String) {
        val ctx = applicationContext
        pl.sinusoide.sinuschat.util.AppExecutor.io {
            val resp = pl.sinusoide.sinuschat.network.ApiClient.post(
                "/api/user/ntfy-topic", org.json.JSONObject(), ctx)
            val topic = resp?.optStringOrNull("ntfyTopic")
            if (topic != null) {
                pl.sinusoide.sinuschat.network.NtfyManager.saveTopic(ctx, topic)
            }
        }
    }

    private fun scheduleMessageSync() {
        val request = androidx.work.PeriodicWorkRequestBuilder<pl.sinusoide.sinuschat.messenger.MessageSyncWorker>(
            15, java.util.concurrent.TimeUnit.MINUTES
        )
            .setConstraints(
                androidx.work.Constraints.Builder()
                    .setRequiredNetworkType(androidx.work.NetworkType.CONNECTED)
                    .build()
            )
            .build()

        androidx.work.WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "sinuschat_sync",
            androidx.work.ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }
}
