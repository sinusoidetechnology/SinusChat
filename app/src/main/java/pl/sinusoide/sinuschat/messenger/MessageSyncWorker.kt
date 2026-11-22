package pl.sinusoide.sinuschat.messenger

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters
import pl.sinusoide.sinuschat.network.ApiClient
import pl.sinusoide.sinuschat.setup.SetupActivity
import pl.sinusoide.sinuschat.util.LinkLog
import pl.sinusoide.sinuschat.util.MessageNotifier

class MessageSyncWorker(
    context: Context,
    params: WorkerParameters
) : Worker(context, params) {

    override fun doWork(): Result {
        val ctx = applicationContext
        if (!SetupActivity.isSetupDone(ctx)) return Result.success()
        if (SetupActivity.getUserPhoneHash(ctx).isEmpty()) return Result.success()
        if (!ApiClient.isAuthenticated(ctx)) return Result.success()

        if (MessageListenerService.isRunning) return Result.success()

        val onDemand = MessageListenerService.onDemand(ctx)
        if (onDemand) MessageListenerService.housekeeping(ctx)
        else if (MessageListenerService.startChecked(ctx)) return Result.success()

        val unread = ApiClient.getObject("/api/messages/waiting", ctx)?.optInt("count", 0)
            ?: return if (onDemand) Result.success() else Result.retry()

        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val lastUnread = prefs.getInt(KEY_LAST_UNREAD, 0)
        prefs.edit().putInt(KEY_LAST_UNREAD, unread).apply()
        if (unread <= lastUnread) return Result.success()

        if (onDemand) {
            LinkLog.add(ctx, "fcm", "wiadomości czekały bez pusha: $unread")
            if (MessageListenerService.startChecked(ctx)) return Result.success()
        }
        MessageNotifier.showPendingSummary(ctx, unread - lastUnread)
        return Result.success()
    }

    private companion object {
        const val PREFS = "sinuschat_sync_worker"
        const val KEY_LAST_UNREAD = "last_unread_total"
    }
}
