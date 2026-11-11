package pl.sinusoide.sinuschat.call

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import androidx.core.app.Person
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import pl.sinusoide.sinuschat.R

object CallNotifier {

    private const val TAG = "CallNotifier"
    const val CHANNEL_ID = "sinuschat_calls_v2"
    const val NOTIF_ID = 7301
    private const val MISSED_NOTIF_ID = 4201

    const val ACTION_ANSWER = "pl.sinusoide.sinuschat.CALL_ANSWER"
    const val ACTION_DECLINE = "pl.sinusoide.sinuschat.CALL_DECLINE"

    private val cancelledCalls = java.util.Collections.synchronizedSet(HashSet<String>())

    private const val RING_TIMEOUT_MS = 35_000L

    private class Ring(val fromHash: String, val fromName: String, val isVideo: Boolean, val at: Long)
    private val ringing = java.util.concurrent.ConcurrentHashMap<String, Ring>()
    private val timeoutHandler = android.os.Handler(android.os.Looper.getMainLooper())

    fun timeout(ctx: Context, conversationId: String): Boolean {
        val ring = ringing[conversationId] ?: return false
        ringTimedOut(ctx.applicationContext, conversationId, ring)
        return true
    }

    private fun ringTimedOut(ctx: Context, conversationId: String, ring: Ring) {
        if (ringing[conversationId] !== ring) return
        pl.sinusoide.sinuschat.pclink.PcLinkCalls.onRingStopped(conversationId, pl.sinusoide.sinuschat.link.LinkFrame.CALL_NO_ANSWER)
        showMissedCall(ctx, conversationId, ring.fromHash, ring.fromName, ring.isVideo)
        markCancelled(conversationId)
        ctx.sendBroadcast(Intent(IncomingCallActivity.ACTION_CALL_CANCELLED).setPackage(ctx.packageName)
            .putExtra("conversationId", conversationId))
        pl.sinusoide.sinuschat.util.AppExecutor.io {
            try {
                pl.sinusoide.sinuschat.network.WebSocketManager.send(
                    org.json.JSONObject().put("type", "call_reject").put("conversationId", conversationId)
                        .put("reason", "timeout"))
            } catch (_: Exception) {}
        }
    }

    fun answered(conversationId: String) {
        CallRinger.stop(conversationId)
        ringing.remove(conversationId)
    }

    fun callerHungUp(ctx: Context, conversationId: String) {
        CallRinger.stop(conversationId)
        val ring = ringing[conversationId] ?: return
        showMissedCall(ctx, conversationId, ring.fromHash, ring.fromName, ring.isVideo)
    }

    fun markCancelled(conversationId: String) {
        if (conversationId.isNotEmpty()) cancelledCalls.add(conversationId)
    }
    fun isCancelled(conversationId: String): Boolean = cancelledCalls.contains(conversationId)
    fun clearCancelled(conversationId: String) { cancelledCalls.remove(conversationId) }

    fun createChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = ctx.getSystemService(NotificationManager::class.java) ?: return
        if (mgr.getNotificationChannel(CHANNEL_ID) != null) return

        val ch = NotificationChannel(
            CHANNEL_ID, ctx.getString(R.string.incoming_calls),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = ctx.getString(R.string.ringing_priority)
            setSound(null, null)
            enableVibration(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            setBypassDnd(true)
            setShowBadge(false)
        }
        mgr.createNotificationChannel(ch)
    }

    fun showIncomingCall(
        ctx: Context,
        conversationId: String,
        fromHash: String,
        fromName: String,
        isVideo: Boolean,
        offerSDP: String
    ) {
        createChannel(ctx)
        val ring = Ring(fromHash, fromName, isVideo, System.currentTimeMillis())
        ringing[conversationId] = ring
        val app = ctx.applicationContext
        timeoutHandler.postDelayed({ ringTimedOut(app, conversationId, ring) }, RING_TIMEOUT_MS)
        CallPrep.prefetch(app, fromHash)
        val silent = pl.sinusoide.sinuschat.util.NotificationPrefs.silentCall(ctx)

        val fullScreen = Intent(ctx, IncomingCallActivity::class.java).apply {
            putExtra("conversationId", conversationId)
            putExtra("fromHash", fromHash)
            putExtra("fromName", fromName)
            putExtra("isVideo", isVideo)
            putExtra("offerSDP", offerSDP)
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_NO_USER_ACTION
            )
        }
        val fullScreenPi = PendingIntent.getActivity(
            ctx, 1, fullScreen,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val answerPi = PendingIntent.getActivity(
            ctx, 2,
            Intent(fullScreen).apply { action = ACTION_ANSWER; putExtra("autoAnswer", true) },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val declinePi = PendingIntent.getBroadcast(
            ctx, 3,
            Intent(ctx, CallActionReceiver::class.java).apply {
                action = ACTION_DECLINE
                putExtra("conversationId", conversationId)
                putExtra("fromHash", fromHash)
                putExtra("fromName", fromName)
                putExtra("isVideo", isVideo)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_call_answer)
            .setContentTitle(fromName)
            .setContentText(if (isVideo) ctx.getString(R.string.incoming_video_call) else ctx.getString(R.string.incoming_voice_call))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setTimeoutAfter(35_000)
            .setColor(0xFF5288C1.toInt())
            .setContentIntent(fullScreenPi)
        if (silent) builder.setSilent(true)
        else builder.setFullScreenIntent(fullScreenPi, true)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val caller = Person.Builder().setName(fromName).setImportant(true).build()
            builder.setStyle(
                NotificationCompat.CallStyle.forIncomingCall(caller, declinePi, answerPi)
            )
        } else {
            builder.addAction(R.drawable.ic_call_end, ctx.getString(R.string.decline), declinePi)
            builder.addAction(R.drawable.ic_call_answer, ctx.getString(R.string.answer), answerPi)
        }

        try {
            NotificationManagerCompat.from(ctx).notify(NOTIF_ID, builder.build())
        } catch (e: SecurityException) {
            Log.w(TAG, "Brak POST_NOTIFICATIONS — nie mogę pokazać dzwonka", e)
        }

        CallRinger.start(ctx, conversationId, silent)

        try {
            if (pl.sinusoide.sinuschat.SinusApp.isForeground && !silent) {
                ctx.startActivity(fullScreen)
            }
        } catch (_: Exception) {}
    }

    fun showMissedCall(
        ctx: Context,
        conversationId: String,
        fromHash: String,
        fromName: String,
        isVideo: Boolean,
        declined: Boolean = false
    ) {
        createChannel(ctx)
        cancel(ctx)
        val startedAt = ringing.remove(conversationId)?.at ?: System.currentTimeMillis()
        val title = ctx.getString(if (declined) R.string.call_declined else R.string.call_missed)
        val label = title + (if (isVideo) " " + ctx.getString(R.string.video).lowercase() else "")
        pl.sinusoide.sinuschat.data.CallLogRepository.log(ctx, fromHash, fromName, conversationId, isVideo,
            if (declined) pl.sinusoide.sinuschat.data.CallLogRepository.DECLINED
            else pl.sinusoide.sinuschat.data.CallLogRepository.MISSED, 0, startedAt)
        pl.sinusoide.sinuschat.util.AppExecutor.io {
            pl.sinusoide.sinuschat.messenger.MessageRepository.insertSystemMessage(
                ctx, conversationId, label, if (declined) "call_declined" else "call_missed")
        }
        if (!pl.sinusoide.sinuschat.util.NotificationPrefs.missedCallsEnabled(ctx)) return

        val callBack = Intent(ctx, CallActivity::class.java).apply {
            putExtra("conversationId", conversationId)
            putExtra("otherUserHash", fromHash)
            putExtra("otherUserName", fromName)
            putExtra("isVideo", isVideo)
            putExtra("isCaller", true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val callBackPi = PendingIntent.getActivity(
            ctx, 7, callBack,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(if (declined) R.drawable.ic_call_end else R.drawable.ic_call_missed)
            .setContentTitle(label)
            .setContentText(fromName)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setAutoCancel(true)
            .setColor(0xFF5288C1.toInt())
            .addAction(R.drawable.ic_call_answer, ctx.getString(R.string.call_back), callBackPi)
            .setContentIntent(callBackPi)

        try {
            NotificationManagerCompat.from(ctx).notify(MISSED_NOTIF_ID, builder.build())
        } catch (e: SecurityException) {
            Log.w(TAG, "Brak POST_NOTIFICATIONS — nieodebrane", e)
        }
    }

    fun cancel(ctx: Context, stopRinging: Boolean = true) {
        if (stopRinging) CallRinger.stop()
        try { NotificationManagerCompat.from(ctx).cancel(NOTIF_ID) } catch (_: Exception) {}
    }
}

class CallActionReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != CallNotifier.ACTION_DECLINE) return
        val convId = intent.getStringExtra("conversationId") ?: return
        val fromHash = intent.getStringExtra("fromHash") ?: ""
        val fromName = intent.getStringExtra("fromName") ?: "Nieznany"
        val isVideo  = intent.getBooleanExtra("isVideo", false)
        CallNotifier.cancel(ctx)
        pl.sinusoide.sinuschat.pclink.PcLinkCalls.onRingStopped(convId, pl.sinusoide.sinuschat.link.LinkFrame.CALL_DECLINED)

        if (fromHash.isNotEmpty()) {
            CallNotifier.showMissedCall(ctx, convId, fromHash, fromName, isVideo, declined = true)
        }

        pl.sinusoide.sinuschat.util.AppExecutor.io {
            try {
                pl.sinusoide.sinuschat.network.WebSocketManager.send(
                    org.json.JSONObject()
                        .put("type", "call_reject")
                        .put("reason", "declined")
                        .put("conversationId", convId)
                )
                pl.sinusoide.sinuschat.network.ApiClient.post(
                    "/api/call/reject",
                    org.json.JSONObject().put("conversationId", convId),
                    ctx
                )
            } catch (_: Exception) {}
        }
    }
}
