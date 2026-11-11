package pl.sinusoide.sinuschat.messenger

import pl.sinusoide.sinuschat.util.optStringOrNull

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import pl.sinusoide.sinuschat.network.ApiClient
import pl.sinusoide.sinuschat.util.AppExecutor
import pl.sinusoide.sinuschat.R
import pl.sinusoide.sinuschat.ScreenLockActivity
import pl.sinusoide.sinuschat.crypto.DbKeyManager
import pl.sinusoide.sinuschat.crypto.E2EManager
import pl.sinusoide.sinuschat.setup.SetupActivity

class NewChatActivity : AppCompatActivity() {
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(pl.sinusoide.sinuschat.util.LocaleManager.wrap(newBase))
    }


    private lateinit var recycler: RecyclerView
    private lateinit var progress: ProgressBar
    private lateinit var emptyText: TextView
    private lateinit var manualSection: View
    private lateinit var inputEmail: EditText
    private lateinit var btnSearch: Button
    private lateinit var resultText: TextView
    private lateinit var resultCard: View
    private lateinit var resultName: TextView
    private lateinit var resultAvatar: TextView
    private lateinit var resultStatus: TextView
    private lateinit var btnStartChat: Button

    private var myHash = ""
    private var myDisplayName = ""
    private var foundHash = ""
    private var foundName = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        if (DbKeyManager.needsPinUnlock(this)) {
            ScreenLockActivity.start(this, ScreenLockActivity.MODE_UNLOCK)
            super.onCreate(null)
            finish()
            return
        }

        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_new_chat)
        supportActionBar?.hide()

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.new_chat_status_spacer)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.layoutParams.height = systemBars.top
            v.requestLayout()
            insets
        }

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.new_chat_nav_spacer)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.layoutParams.height = systemBars.bottom
            v.requestLayout()
            insets
        }

        myHash = SetupActivity.getUserPhoneHash(this)
        myDisplayName = SetupActivity.getUserDisplayName(this)

        recycler = findViewById(R.id.contacts_recycler)
        progress = findViewById(R.id.progress)
        emptyText = findViewById(R.id.empty_contacts_text)
        manualSection = findViewById(R.id.manual_section)
        inputEmail = findViewById(R.id.new_chat_phone)
        inputEmail.hint = getString(R.string.enter_email_hint)
        inputEmail.inputType = android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS

        findViewById<View>(R.id.new_chat_prefix)?.visibility = View.GONE

        btnSearch = findViewById(R.id.btn_search_user)
        resultText = findViewById(R.id.result_text)
        resultCard = findViewById(R.id.result_card)
        resultName = findViewById(R.id.result_contact_name)
        resultAvatar = findViewById(R.id.result_contact_avatar)
        resultStatus = findViewById(R.id.result_contact_status)
        btnStartChat = findViewById(R.id.btn_start_chat)

        recycler.layoutManager = LinearLayoutManager(this)

        findViewById<ImageButton>(R.id.btn_new_chat_back).setOnClickListener { finish() }
        btnSearch.setOnClickListener { searchByEmail() }
        btnStartChat.setOnClickListener { createOrOpenConversation(foundHash, foundName) }

        manualSection.visibility = View.VISIBLE
        emptyText.text = getString(R.string.enter_email_hint)
        emptyText.visibility = View.VISIBLE
        setupFlowGlass()
    }

    private fun setupFlowGlass() {
        if (!pl.sinusoide.sinuschat.util.ThemeManager.isFlow(this)) return
        val glass = pl.sinusoide.sinuschat.ui.FlowGlass
        val dp = resources.displayMetrics.density
        val root = findViewById<LinearLayout>(R.id.new_chat_root)
        val toolbar = findViewById<View>(R.id.new_chat_toolbar)
        findViewById<View>(R.id.new_chat_status_spacer).background = null
        findViewById<View>(R.id.new_chat_nav_spacer).background = null
        root.getChildAt(root.indexOfChild(toolbar) + 1)?.takeIf { it !is ViewGroup }?.visibility = View.GONE
        glass.bar(toolbar, 28f)
        glass.glass(inputEmail, 24f, press = false)
        glass.button(btnSearch)
        glass.button(btnStartChat)
        pl.sinusoide.sinuschat.ui.FlowLens.underBars(root, findViewById(R.id.new_chat_scroll_content), listOf(
            pl.sinusoide.sinuschat.ui.FlowLens.Lens(toolbar, 28 * dp, magnify = 0.12f, blurDp = 0.8f)))
    }

    private fun searchByEmail() {
        val email = inputEmail.text.toString().trim().lowercase()
        if (!email.contains("@") || !email.contains(".")) {
            resultText.text = getString(R.string.enter_valid_email)
            resultText.visibility = View.VISIBLE
            return
        }

        val targetHash = E2EManager.sha256(email)
        if (targetHash == myHash) {
            resultText.text = getString(R.string.cant_message_self)
            resultText.visibility = View.VISIBLE
            return
        }

        progress.visibility = View.VISIBLE
        btnSearch.isEnabled = false
        resultText.visibility = View.GONE
        resultCard.visibility = View.GONE

        AppExecutor.io {
            val h = mapOf("x-search" to "true")
            val user = ApiClient.getObject("/api/user/$targetHash", this, h)
                ?: ApiClient.getArray("/api/users/search?q=$targetHash", this, h)
                    ?.let { if (it.length() > 0) it.getJSONObject(0) else null }

            runOnUiThread {
                progress.visibility = View.GONE
                btnSearch.isEnabled = true

                if (user != null && user.optStringOrNull("e2e_public_key") != null) {
                    val hash = user.optString("email_hash")
                    val name = pl.sinusoide.sinuschat.util.ContactCache.getNick(this@NewChatActivity, hash)
                        ?: pl.sinusoide.sinuschat.crypto.ProfileCrypto.openName(this@NewChatActivity, hash, user.optStringOrNull("display_name"))
                        ?: email.substringBefore('@')
                    val heartbeat = user.optLong("last_heartbeat_ms", 0)
                    val lastSeen = user.optLong("last_seen_ms", 0)
                    val bestTs = maxOf(heartbeat, lastSeen)
                    
                    val isOnline = user.optBoolean("is_online",
                        heartbeat > 0 && pl.sinusoide.sinuschat.util.ServerTime.ageMs(heartbeat) < 60_000)
                    foundHash = hash
                    foundName = name
                    updateStartChatButtonForBlock(hash)

                    resultCard.visibility = View.VISIBLE
                    resultName.text = name
                    resultAvatar.text = pl.sinusoide.sinuschat.util.AvatarHelper.initialOf(name)
                    if (!isOnline && bestTs > 0) pl.sinusoide.sinuschat.util.LastSeenText.show(resultStatus, bestTs)
                    else resultStatus.text = getString(if (isOnline) R.string.active_now else R.string.active_recently)
                    resultStatus.setTextColor(
                        if (isOnline) Color.parseColor("#2ECC71") else Color.parseColor("#9B9B9B"))

                    val avatarUrl = pl.sinusoide.sinuschat.crypto.ProfileCrypto.displayAvatar(this, user.optString("email_hash"), user.optStringOrNull("avatar_url"))
                    val avatarImg = findViewById<ImageView>(R.id.result_contact_avatar_image)
                    if (avatarImg != null && avatarUrl != null) {
                        avatarImg.clipToOutline = true
                        Glide.with(this)
                            .load(avatarUrl)
                            .circleCrop()
                            .into(object : com.bumptech.glide.request.target.CustomTarget<android.graphics.drawable.Drawable>() {
                                override fun onResourceReady(r: android.graphics.drawable.Drawable,
                                    t: com.bumptech.glide.request.transition.Transition<in android.graphics.drawable.Drawable>?) {
                                    avatarImg.setImageDrawable(r)
                                    avatarImg.visibility = View.VISIBLE
                                    resultAvatar.visibility = View.GONE
                                }
                                override fun onLoadCleared(p: android.graphics.drawable.Drawable?) {}
                                override fun onLoadFailed(e: android.graphics.drawable.Drawable?) {
                                }
                            })
                    }
                } else {
                        resultText.text = getString(R.string.user_not_found)
                    resultText.visibility = View.VISIBLE
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
    }

    override fun onPause() {
        super.onPause()
    }

    private fun updateStartChatButtonForBlock(otherHash: String) {
        setStartChatEnabled(true)
    }

    private fun setStartChatEnabled(enabled: Boolean) {
        btnStartChat.isEnabled = enabled
        btnStartChat.alpha = if (enabled) 1f else 0.4f
    }

    private fun createOrOpenConversation(otherHash: String, otherName: String) {
        if (otherHash.isEmpty()) return
        if (!btnStartChat.isEnabled) return
        val convId = if (myHash < otherHash) "${myHash}_${otherHash}" else "${otherHash}_${myHash}"
        startActivity(Intent(this, ChatActivity::class.java).apply {
            putExtra("conversationId", convId)
            putExtra("otherUserName", otherName)
            putExtra("otherUserHash", otherHash)
            putExtra("weStarted", true)
        })
        finish()
    }
}
