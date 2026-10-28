package pl.sinusoide.sinuschat.call

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import pl.sinusoide.sinuschat.R
import pl.sinusoide.sinuschat.util.ContactCache
import java.util.concurrent.ConcurrentHashMap

object GroupCallRing {

    private const val TAG = "GroupCallRing"
    private const val NOTIF_ID = 7303
    const val ACTION_DECLINE = "pl.sinusoide.sinuschat.GROUP_CALL_DECLINE"

    private val ringing = ConcurrentHashMap<String, GroupCallHub.State>()
    private val handler = Handler(Looper.getMainLooper())

    fun isRinging(groupId: String) = ringing.containsKey(groupId)

    fun groupTitle(context: Context, groupId: String): String {
        ContactCache.getName(context, groupId)?.let { return it }
        val fallback = context.getString(R.string.group)
        val arr = pl.sinusoide.sinuschat.util.ConversationCache.load(context) ?: return fallback
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("id") != groupId) continue
            return pl.sinusoide.sinuschat.crypto.GroupMeta.open(context, groupId, o.optString("group_name", ""), fallback)
        }
        return fallback
    }

    fun groupAvatar(context: Context, groupId: String): Pair<String, String?> {
        var color = "#5288C1"
        var url = ContactCache.getAvatarUrl(context, groupId)?.takeIf { it.startsWith("http") || it.startsWith("/") }
        val arr = pl.sinusoide.sinuschat.util.ConversationCache.load(context) ?: return color to url
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("id") != groupId) continue
            o.optString("group_avatar_color").takeIf { it.startsWith("#") }?.let { color = it }
            if (url == null) url = o.optString("avatar_url").takeIf { it.startsWith("http") }
                ?.let { pl.sinusoide.sinuschat.crypto.ProfileCrypto.displayAvatar(context, groupId, it) }
            break
        }
        return color to url
    }

    fun start(context: Context, state: GroupCallHub.State, durationMs: Long) {
        if (CallActivity.activeConversationId != null) return
        val app = context.applicationContext
        CallNotifier.createChannel(app)
        ringing[state.groupId] = state
        val silent = pl.sinusoide.sinuschat.util.NotificationPrefs.silentCall(app)
        val title = groupTitle(app, state.groupId)
        val starter = ContactCache.getName(app, state.startedBy) ?: app.getString(R.string.caller_unknown)
        val text = app.getString(if (state.isVideo) R.string.gcall_incoming_video else R.string.gcall_incoming_audio, starter)

        val full = GroupCallActivity.intent(app, state.groupId, state.kind, GroupCallActivity.MODE_INCOMING)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NO_USER_ACTION)
        val fullPi = PendingIntent.getActivity(app, 21, full, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val joinPi = PendingIntent.getActivity(app, 22,
            GroupCallActivity.intent(app, state.groupId, state.kind, GroupCallActivity.MODE_JOIN)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val declinePi = PendingIntent.getBroadcast(app, 23,
            Intent(app, GroupCallRingReceiver::class.java).setAction(ACTION_DECLINE).putExtra("groupId", state.groupId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val window = durationMs.coerceIn(5_000L, GroupCallHub.RING_WINDOW_MS)
        val builder = NotificationCompat.Builder(app, CallNotifier.CHANNEL_ID)
            .setSmallIcon(if (state.isVideo) R.drawable.ic_call_video_on else R.drawable.ic_call_answer)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setTimeoutAfter(window)
            .setColor(0xFF5288C1.toInt())
            .setContentIntent(fullPi)
        if (silent) builder.setSilent(true) else builder.setFullScreenIntent(fullPi, true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setStyle(NotificationCompat.CallStyle.forIncomingCall(
                Person.Builder().setName(title).setImportant(true).build(), declinePi, joinPi))
        } else {
            builder.addAction(R.drawable.ic_call_end, app.getString(R.string.decline), declinePi)
            builder.addAction(R.drawable.ic_call_answer, app.getString(R.string.gcall_join), joinPi)
        }
        try { NotificationManagerCompat.from(app).notify(NOTIF_ID, builder.build()) }
        catch (e: SecurityException) { Log.w(TAG, "notify: ${e.message}") }
        CallRinger.start(app, state.groupId, silent)
        try {
            if (pl.sinusoide.sinuschat.SinusApp.isForeground && !silent) app.startActivity(full)
        } catch (_: Exception) {}
        handler.postDelayed({ stop(app, state.groupId) }, window)
    }

    fun stop(context: Context, groupId: String) {
        ringing.remove(groupId) ?: return
        CallRinger.stop(groupId)
        try { NotificationManagerCompat.from(context).cancel(NOTIF_ID) } catch (_: Exception) {}
        context.sendBroadcast(Intent(GroupCallActivity.ACTION_RING_STOPPED).setPackage(context.packageName)
            .putExtra("groupId", groupId))
    }
}

class GroupCallRingReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != GroupCallRing.ACTION_DECLINE) return
        val groupId = intent.getStringExtra("groupId") ?: return
        GroupCallRing.stop(context.applicationContext, groupId)
    }
}
