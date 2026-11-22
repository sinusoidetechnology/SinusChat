package pl.sinusoide.sinuschat.util

import android.content.Context
import pl.sinusoide.sinuschat.R
import pl.sinusoide.sinuschat.data.MessageEntity
import pl.sinusoide.sinuschat.data.SinusChatDatabase

object GroupSystemMessages {

    fun insert(
        context: Context,
        groupId: String,
        text: String,
        idSuffix: String = "",
        type: String = "sys_group_change"
    ) {
        if (groupId.isEmpty() || text.isEmpty()) return
        val app = context.applicationContext
        val entity = MessageEntity(
            id = "sys_group_${idSuffix.ifEmpty { System.currentTimeMillis().toString() }}",
            conversationId = groupId,
            senderHash = "",
            text = text,
            timestampMs = System.currentTimeMillis(),
            type = type,
            isSystemMessage = true,
            status = MessageEntity.STATUS_READ
        )
        AppExecutor.io {
            try { SinusChatDatabase.getInstance(app).messageDao().insert(entity) } catch (_: Exception) {}
        }
    }

    fun nameOf(context: Context, hash: String): String {
        if (hash.isNotEmpty() && hash == pl.sinusoide.sinuschat.setup.SetupActivity.getUserPhoneHash(context)) {
            pl.sinusoide.sinuschat.setup.SetupActivity.getUserDisplayName(context).takeIf { it.isNotBlank() }?.let { return it }
        }
        return ContactCache.getName(context, hash)?.takeIf { it.isNotBlank() }
            ?: ContactCache.realName(context, hash)?.takeIf { it.isNotBlank() }
            ?: context.getString(R.string.group_participant)
    }

    fun memberLeft(context: Context, groupId: String, hash: String) {
        if (!GroupNotices.allows(context, groupId, GroupNotices.LEAVE)) return
        insert(context, groupId, context.getString(R.string.group_member_left_sys, nameOf(context, hash)))
    }

    fun memberJoined(context: Context, groupId: String, hash: String) {
        if (!GroupNotices.allows(context, groupId, GroupNotices.JOIN)) return
        insert(context, groupId, context.getString(R.string.group_member_joined_sys, nameOf(context, hash)))
    }

    fun emailChanged(context: Context, groupId: String, hash: String) {
        if (!GroupNotices.allows(context, groupId, GroupNotices.EMAIL)) return
        insert(context, groupId, context.getString(R.string.group_member_email_changed_sys, nameOf(context, hash)),
            idSuffix = "moved_${groupId.takeLast(8)}_${hash.take(16)}")
    }

    fun keyChanged(context: Context, groupId: String, hash: String, keyTag: Int) {
        if (!GroupNotices.allows(context, groupId, GroupNotices.KEY)) return
        insert(context, groupId, context.getString(R.string.group_member_key_changed_sys, nameOf(context, hash)),
            idSuffix = "key_${hash.take(16)}_$keyTag")
    }

    fun memberRemoved(context: Context, groupId: String, hash: String) {
        if (!GroupNotices.allows(context, groupId, GroupNotices.LEAVE)) return
        insert(context, groupId, context.getString(R.string.group_member_removed_sys, nameOf(context, hash)))
    }

    fun adminChanged(context: Context, groupId: String, hash: String, isAdmin: Boolean) =
        insert(context, groupId, context.getString(
            if (isAdmin) R.string.group_admin_granted_sys else R.string.group_admin_revoked_sys,
            nameOf(context, hash)))

    fun settingsChanged(context: Context, groupId: String) =
        insert(context, groupId, context.getString(R.string.group_settings_changed_sys))

    fun disappearingChanged(context: Context, groupId: String, ttlMs: Long) =
        insert(context, groupId, context.getString(R.string.disappearing_update_sys_msg,
            DisappearingMessages.label(context, ttlMs)), type = "sys_disappearing_change")
}
