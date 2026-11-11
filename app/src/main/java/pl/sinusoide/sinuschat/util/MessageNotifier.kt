package pl.sinusoide.sinuschat.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import pl.sinusoide.sinuschat.MainActivity
import pl.sinusoide.sinuschat.R
import pl.sinusoide.sinuschat.messenger.ChatActivity

object MessageNotifier {

    private const val TAG = "MessageNotifier"

    private const val SUMMARY_NOTIF_ID = 9100
    private const val BG_DATA_NOTIF_ID = 9101
    private const val KEY_BG_DATA_HINT = "bg_data_hint_at"
    private const val BG_DATA_HINT_EVERY_MS = 12 * 3_600_000L

    fun createChannel(context: Context) {
        channelId(context)
    }

    fun channelId(context: Context): String = SoundPrefs.messageChannel(context)

    private const val HISTORY = 7
    private const val MARKS = "sinuschat_notif_marks"
    private const val RESTORE_WINDOW_MS = 7 * 24 * 3_600_000L
    private const val RESTORE_GAP_MS = 15_000L
    const val ACTION_DISMISSED = "pl.sinusoide.sinuschat.NOTIF_DISMISSED"
    private val worker = java.util.concurrent.Executors.newSingleThreadExecutor()
    @Volatile private var lastRestoreMs = 0L

    private fun marks(ctx: Context) = ctx.applicationContext.getSharedPreferences(MARKS, Context.MODE_PRIVATE)

    fun markDismissed(context: Context, convId: String) {
        if (convId.isNotEmpty()) marks(context).edit().putLong("d_$convId", System.currentTimeMillis()).apply()
    }

    fun markAllCleared(context: Context) {
        marks(context).edit().putLong("all", System.currentTimeMillis()).apply()
    }

    private fun dismissIntent(context: Context, convId: String): PendingIntent =
        PendingIntent.getBroadcast(context, ("dismiss$convId").hashCode(),
            Intent(context, NotificationDismissReceiver::class.java).apply {
                action = ACTION_DISMISSED
                setPackage(context.packageName)
                putExtra("conversationId", convId)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    fun restoreUnread(context: Context, delayMs: Long = 0L) {
        val app = context.applicationContext
        AppExecutor.io {
            if (delayMs > 0L) try { Thread.sleep(delayMs) } catch (_: InterruptedException) {}
            val now = System.currentTimeMillis()
            if (now - lastRestoreMs < RESTORE_GAP_MS) return@io
            lastRestoreMs = now
            try { restoreNow(app) } catch (e: Exception) { Log.w(TAG, "Przywracanie: ${e.javaClass.simpleName}") }
        }
    }

    private fun restoreNow(app: Context) {
        if (pl.sinusoide.sinuschat.SinusApp.isForeground) return
        if (pl.sinusoide.sinuschat.crypto.DbKeyManager.needsPinUnlock(app)) return
        if (!NotificationManagerCompat.from(app).areNotificationsEnabled()) return
        val me = pl.sinusoide.sinuschat.setup.SetupActivity.getUserPhoneHash(app)
        if (me.isEmpty()) return
        val active = try {
            app.getSystemService(NotificationManager::class.java)?.activeNotifications?.map { it.id }?.toHashSet()
        } catch (_: Exception) { null } ?: hashSetOf()
        val dao = pl.sinusoide.sinuschat.data.SinusChatDatabase.getInstance(app).messageDao()
        val m = marks(app)
        val floor = maxOf(System.currentTimeMillis() - RESTORE_WINDOW_MS, m.getLong("all", 0L))
        val accepted = app.getSharedPreferences("sinuschat_accepted_convs", Context.MODE_PRIVATE)
        for (convId in dao.getConversationIds()) {
            if (convId.isEmpty() || convId.hashCode() in active) continue
            if (MuteSettings.isMuted(app, convId) || !NotificationPrefs.allowMessage(app, convId)) continue
            if (!pl.sinusoide.sinuschat.messenger.GroupRepository.isGroup(convId) && NotificationPrefs.ignoreRequests(app) &&
                !accepted.getBoolean(convId, false) && dao.countFromSender(convId, me) == 0) continue
            val mark = maxOf(floor, LocalChats.readMark(app, convId), m.getLong("d_$convId", 0L))
            val unread = dao.getLastIncoming(convId, me, HISTORY)
                .filter { it.timestampMs > mark && !it.viewOnce && hasContent(it) }
                .sortedBy { it.timestampMs }
            for (msg in unread) show(app, convId, msg.senderHash, previewOf(app, msg), silent = true, messageId = msg.id)
        }
    }

    private fun hasContent(m: pl.sinusoide.sinuschat.data.MessageEntity) =
        m.type == "attachment" || m.type == pl.sinusoide.sinuschat.data.MessageEntity.TYPE_VOICE || m.text.isNotBlank()

    private fun previewOf(context: Context, m: pl.sinusoide.sinuschat.data.MessageEntity) = when (m.type) {
        "attachment" -> context.getString(R.string.attachment_prefix)
        pl.sinusoide.sinuschat.data.MessageEntity.TYPE_VOICE -> context.getString(R.string.voice_message)
        else -> m.text
    }

    const val KEY_REPLY_TEXT = "sinuschat_reply_text"

    private fun chatIntent(context: Context, convId: String, otherHash: String, groupTitle: String?): PendingIntent =
        PendingIntent.getActivity(
            context, convId.hashCode(),
            Intent(context, ChatActivity::class.java).apply {
                putExtra("conversationId", convId)
                putExtra("otherUserHash", otherHash)
                putExtra("otherUserName", groupTitle ?: ContactCache.getName(context, otherHash) ?: "Kontakt")
                if (groupTitle != null) putExtra("isGroup", true)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun replyAction(context: Context, convId: String, otherHash: String, messageId: String?): NotificationCompat.Action? {
        if (messageId.isNullOrEmpty() || !PrivacySettings.isNotificationReplyEnabled(context)) return null
        if (pl.sinusoide.sinuschat.crypto.DbKeyManager.isEnabled(context) &&
            pl.sinusoide.sinuschat.crypto.DbKeyManager.cachedKey() == null) return null
        val input = androidx.core.app.RemoteInput.Builder(KEY_REPLY_TEXT)
            .setLabel(context.getString(R.string.notif_reply_hint))
            .build()
        val intent = Intent(context, NotificationReplyReceiver::class.java).apply {
            action = NotificationReplyReceiver.ACTION_REPLY
            setPackage(context.packageName)
            putExtra("conversationId", convId)
            putExtra("otherHash", otherHash)
            putExtra("messageId", messageId)
        }
        val pi = PendingIntent.getBroadcast(context, convId.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
        return NotificationCompat.Action.Builder(R.drawable.ic_reply, context.getString(R.string.notif_reply_action), pi)
            .addRemoteInput(input)
            .setAllowGeneratedReplies(false)
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
            .setShowsUserInterface(false)
            .setAuthenticationRequired(true)
            .build()
    }

    fun afterReply(context: Context, convId: String) {
        val id = convId.hashCode()
        forget(id)
        val active = try {
            context.getSystemService(NotificationManager::class.java)?.activeNotifications?.firstOrNull { it.id == id }
        } catch (_: Exception) { null } ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                active.notification.flags and android.app.Notification.FLAG_BUBBLE != 0) {
                val rebuilt = android.app.Notification.Builder.recoverBuilder(context, active.notification)
                    .setOnlyAlertOnce(true)
                    .build()
                NotificationManagerCompat.from(context).notify(id, rebuilt)
            } else NotificationManagerCompat.from(context).cancel(id)
        } catch (_: Exception) {}
    }

    fun showReplyFailed(context: Context, convId: String, otherHash: String) {
        createChannel(context)
        val isGroup = pl.sinusoide.sinuschat.messenger.GroupRepository.isGroup(convId)
        val groupTitle = if (isGroup) groupTitle(context, convId) else null
        forget(convId.hashCode())
        showPlain(context, convId, groupTitle ?: ContactCache.getName(context, otherHash) ?: context.getString(R.string.app_name),
            context.getString(R.string.notif_reply_failed), chatIntent(context, convId, otherHash, groupTitle), silent = true)
    }

    fun show(context: Context, convId: String, otherHash: String, preview: String, silent: Boolean = false,
             messageId: String? = null) {
        createChannel(context)
        val isGroup = pl.sinusoide.sinuschat.messenger.GroupRepository.isGroup(convId)
        val groupTitle = if (isGroup) groupTitle(context, convId) else null
        val pi = chatIntent(context, convId, otherHash, groupTitle)

        val mode = NotificationPrefs.preview(context)
        val sender = ContactCache.getName(context, otherHash) ?: context.getString(R.string.new_message)
        val content = if (mode == NotificationPrefs.Preview.FULL) preview else context.getString(R.string.new_message)

        if (mode == NotificationPrefs.Preview.HIDDEN || otherHash.isEmpty()) {
            val title = if (mode == NotificationPrefs.Preview.HIDDEN) context.getString(R.string.app_name) else groupTitle ?: sender
            showPlain(context, convId, title, content, pi, silent)
            return
        }
        val app = context.applicationContext
        val reply = replyAction(app, convId, otherHash, messageId)
        worker.execute {
            if (!isGroup && otherHash.length == 64 && ChatBubbles.isActive(app) &&
                showBubble(app, convId, otherHash, sender, content, pi, silent, reply)) return@execute
            if (!showConversation(app, convId, otherHash, sender, content, pi, silent, groupTitle, reply))
                showPlain(app, convId, groupTitle ?: sender, content, pi, silent)
        }
    }

    private fun groupTitle(context: Context, groupId: String): String {
        ContactCache.getName(context, groupId)?.let { return it }
        val fallback = context.getString(R.string.group)
        val arr = ConversationCache.load(context) ?: return fallback
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("id") != groupId) continue
            return pl.sinusoide.sinuschat.crypto.GroupMeta.open(context, groupId, o.optString("group_name", ""), fallback)
        }
        return fallback
    }

    private fun previousStyle(context: Context, id: Int): NotificationCompat.MessagingStyle? = try {
        context.getSystemService(NotificationManager::class.java)?.activeNotifications
            ?.firstOrNull { it.id == id }?.notification
            ?.let { NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(it) }
    } catch (_: Exception) { null }

    private val histories = HashMap<Int, ArrayList<NotificationCompat.MessagingStyle.Message>>()
    private val postedAt = HashMap<Int, Long>()

    private fun forget(id: Int) = synchronized(histories) { histories.remove(id); postedAt.remove(id) }

    private fun conversationStyle(context: Context, id: Int, text: String, person: androidx.core.app.Person,
                                  groupTitle: String?): NotificationCompat.MessagingStyle {
        val style = NotificationCompat.MessagingStyle(
            androidx.core.app.Person.Builder().setName(context.getString(R.string.you)).build())
        val now = System.currentTimeMillis()
        val messages = synchronized(histories) {
            val shown = previousStyle(context, id)
            if (shown == null && now - (postedAt[id] ?: 0L) > 3000L) histories.remove(id)
            val h = histories.getOrPut(id) { ArrayList(shown?.messages.orEmpty()) }
            h.add(NotificationCompat.MessagingStyle.Message(text, now, person))
            while (h.size > HISTORY) h.removeAt(0)
            postedAt[id] = now
            ArrayList(h)
        }
        messages.forEach { style.addMessage(it) }
        if (groupTitle != null) {
            style.conversationTitle = groupTitle
            style.isGroupConversation = true
        }
        return style
    }

    private fun showConversation(
        context: Context, convId: String, otherHash: String, sender: String, text: String, pi: PendingIntent,
        silent: Boolean, groupTitle: String?, reply: NotificationCompat.Action? = null
    ): Boolean = try {
        val id = convId.hashCode()
        val person = try { ChatBubbles.person(context, otherHash, sender) }
            catch (_: Exception) { androidx.core.app.Person.Builder().setName(sender).setKey(otherHash).build() }
        val shortcut = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && try {
            if (groupTitle != null) ChatBubbles.publishGroupShortcut(context, convId, groupTitle,
                ChatBubbles.groupIcon(context, convId, groupTitle))
            else ChatBubbles.publishShortcut(context, convId, otherHash, sender, person)
            ChatBubbles.hideFromLauncher(context, convId)
            true
        } catch (_: Exception) { false }
        val large = if (shortcut) null else try {
            if (groupTitle != null) ChatBubbles.groupLarge(context, convId, groupTitle)
            else ChatBubbles.contactLarge(context, otherHash, sender)
        } catch (_: Exception) { null }
        val notif = NotificationCompat.Builder(context, channelId(context))
            .setSmallIcon(R.drawable.ic_stat_name)
            .setContentTitle(groupTitle ?: sender)
            .setContentText(text)
            .setStyle(conversationStyle(context, id, text, person, groupTitle))
            .apply {
                if (shortcut) {
                    setShortcutId(convId)
                    setLocusId(androidx.core.content.LocusIdCompat(convId))
                }
                if (groupTitle == null) addPerson(person)
                large?.let { setLargeIcon(it) }
            }
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .setDeleteIntent(dismissIntent(context, convId))
            .setColor(0xFF5288C1.toInt())
            .setSilent(silent || NotificationPrefs.silentMessage(context))
            .apply { reply?.let { addAction(it) } }
            .build()
        NotificationManagerCompat.from(context).notify(id, notif)
        NotificationSound.alert(context, silent)
        true
    } catch (e: SecurityException) {
        Log.w(TAG, "Notif denied", e)
        true
    } catch (e: Exception) {
        Log.w(TAG, "Powiadomienie rozmowy: ${e.javaClass.simpleName}")
        false
    }

    private fun showPlain(context: Context, convId: String, title: String, content: String, pi: PendingIntent, silent: Boolean = false) {
        val notif = NotificationCompat.Builder(context, channelId(context))
            .setSmallIcon(R.drawable.ic_stat_name)
            .setContentTitle(title)
            .setContentText(content)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .setDeleteIntent(dismissIntent(context, convId))
            .setColor(0xFF5288C1.toInt())
            .setSilent(silent || NotificationPrefs.silentMessage(context))
            .build()
        try {
            NotificationManagerCompat.from(context).notify(convId.hashCode(), notif)
            NotificationSound.alert(context, silent)
        } catch (e: SecurityException) { Log.w(TAG, "Notif denied", e) }
    }

    private fun showBubble(
        context: Context, convId: String, otherHash: String, sender: String, text: String, pi: PendingIntent,
        silent: Boolean = false, reply: NotificationCompat.Action? = null
    ): Boolean = try {
        val person = ChatBubbles.person(context, otherHash, sender)
        ChatBubbles.publishShortcut(context, convId, otherHash, sender, person)
        val id = convId.hashCode()
        val style = conversationStyle(context, id, text, person, null)
        val bubbleIntent = PendingIntent.getActivity(
            context, id,
            Intent(context, pl.sinusoide.sinuschat.messenger.BubbleChatActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                data = android.net.Uri.parse("https://sinusoide.pl/bubble/" + android.net.Uri.encode(convId))
                putExtra("conversationId", convId)
                putExtra("otherUserHash", otherHash)
                putExtra("otherUserName", sender)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
        val icon = person.icon ?: androidx.core.graphics.drawable.IconCompat.createWithResource(context, R.drawable.ic_chat)
        val notif = NotificationCompat.Builder(context, channelId(context))
            .setSmallIcon(R.drawable.ic_stat_name)
            .setContentTitle(sender)
            .setContentText(text)
            .setStyle(style)
            .setShortcutId(convId)
            .setLocusId(androidx.core.content.LocusIdCompat(convId))
            .addPerson(person)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setBubbleMetadata(NotificationCompat.BubbleMetadata.Builder(bubbleIntent, icon)
                .setDesiredHeight(640)
                .setSuppressNotification(true)
                .build())
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .setDeleteIntent(dismissIntent(context, convId))
            .setColor(0xFF5288C1.toInt())
            .setSilent(silent || NotificationPrefs.silentMessage(context))
            .apply { reply?.let { addAction(it) } }
            .build()
        NotificationManagerCompat.from(context).notify(id, notif)
        NotificationSound.alert(context, silent)
        ChatBubbles.hideFromLauncher(context, convId)
        true
    } catch (e: Exception) {
        Log.w(TAG, "Dymek: ${e.javaClass.simpleName}")
        false
    }

    fun dismiss(context: Context, convId: String) {
        if (convId.isEmpty()) return
        val id = convId.hashCode()
        forget(id)
        val active = try {
            context.getSystemService(NotificationManager::class.java)?.activeNotifications?.firstOrNull { it.id == id }
        } catch (_: Exception) { null } ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            active.notification.flags and android.app.Notification.FLAG_BUBBLE != 0) return
        try { NotificationManagerCompat.from(context).cancel(id) } catch (_: Exception) {}
    }

    fun onMessageDeleted(context: Context, deleted: pl.sinusoide.sinuschat.data.MessageEntity) {
        val convId = deleted.conversationId
        val id = convId.hashCode()
        val active = try {
            context.getSystemService(NotificationManager::class.java)?.activeNotifications?.firstOrNull { it.id == id }
        } catch (_: Exception) { null } ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            active.notification.flags and android.app.Notification.FLAG_BUBBLE != 0) return
        val me = pl.sinusoide.sinuschat.setup.SetupActivity.getUserPhoneHash(context)
        val readMark = LocalChats.readMark(context, convId)
        val unread = try {
            pl.sinusoide.sinuschat.data.SinusChatDatabase.getInstance(context).messageDao()
                .getLastIncoming(convId, me, HISTORY)
                .filter { it.id != deleted.id && it.timestampMs > readMark }
                .sortedBy { it.timestampMs }
        } catch (_: Exception) { emptyList() }
        forget(id)
        try { NotificationManagerCompat.from(context).cancel(id) } catch (_: Exception) {}
        for (m in unread) {
            val preview = when (m.type) {
                "attachment" -> context.getString(R.string.attachment_prefix)
                pl.sinusoide.sinuschat.data.MessageEntity.TYPE_VOICE -> context.getString(R.string.voice_message)
                else -> m.text
            }
            show(context, convId, m.senderHash, preview, silent = true, messageId = m.id)
        }
    }

    fun dismissSummary(context: Context) {
        try { NotificationManagerCompat.from(context).cancel(SUMMARY_NOTIF_ID) } catch (_: Exception) {}
    }

    fun showBackgroundDataOff(context: Context) {
        val marks = marks(context)
        val now = System.currentTimeMillis()
        if (now - marks.getLong(KEY_BG_DATA_HINT, 0L) < BG_DATA_HINT_EVERY_MS) return
        if (!NotificationPrefs.messagesEnabled(context) && !NotificationPrefs.groupsEnabled(context)) return
        marks.edit().putLong(KEY_BG_DATA_HINT, now).apply()
        createChannel(context)
        val settings = Intent(android.provider.Settings.ACTION_IGNORE_BACKGROUND_DATA_RESTRICTIONS_SETTINGS,
            android.net.Uri.parse("package:" + context.packageName))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val target = if (settings.resolveActivity(context.packageManager) != null) settings
            else Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pi = PendingIntent.getActivity(context, BG_DATA_NOTIF_ID, target,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val text = context.getString(R.string.bg_data_off_text)
        val notif = NotificationCompat.Builder(context, channelId(context))
            .setSmallIcon(R.drawable.ic_stat_name)
            .setContentTitle(context.getString(R.string.bg_data_off_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .setColor(0xFF5288C1.toInt())
            .setSilent(NotificationPrefs.silentMessage(context))
            .build()
        try {
            NotificationManagerCompat.from(context).notify(BG_DATA_NOTIF_ID, notif)
        } catch (e: SecurityException) { Log.w(TAG, "Notif denied", e) }
    }

    fun showPendingSummary(context: Context, count: Int) {
        createChannel(context)
        val pi = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        if (!NotificationPrefs.messagesEnabled(context) && !NotificationPrefs.groupsEnabled(context)) return
        val text = context.resources.getQuantityString(R.plurals.pending_messages_waiting, count, count)
        val notif = NotificationCompat.Builder(context, channelId(context))
            .setSmallIcon(R.drawable.ic_stat_name)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .setColor(0xFF5288C1.toInt())
            .setSilent(NotificationPrefs.silentMessage(context))
            .build()
        try {
            NotificationManagerCompat.from(context).notify(SUMMARY_NOTIF_ID, notif)
            NotificationSound.alert(context, false)
        } catch (e: SecurityException) { Log.w(TAG, "Notif denied", e) }
    }
}
