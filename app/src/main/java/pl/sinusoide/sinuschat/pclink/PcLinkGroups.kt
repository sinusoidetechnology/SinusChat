package pl.sinusoide.sinuschat.pclink

import android.content.Context
import android.net.Uri
import android.util.Log
import pl.sinusoide.sinuschat.R
import pl.sinusoide.sinuschat.link.GroupMemberInfo
import pl.sinusoide.sinuschat.link.GroupRights
import pl.sinusoide.sinuschat.link.LinkConnection
import pl.sinusoide.sinuschat.link.LinkFrame
import pl.sinusoide.sinuschat.messenger.GroupInfo
import pl.sinusoide.sinuschat.messenger.GroupPerms
import pl.sinusoide.sinuschat.messenger.GroupRepository
import pl.sinusoide.sinuschat.setup.SetupActivity
import pl.sinusoide.sinuschat.util.AvatarHelper
import pl.sinusoide.sinuschat.util.ContactCache
import pl.sinusoide.sinuschat.util.DisappearingMessages
import pl.sinusoide.sinuschat.util.LocaleManager
import pl.sinusoide.sinuschat.util.MuteSettings
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

object PcLinkGroups {

    private const val TAG = "PcLinkGroups"
    private const val WAIT_S = 30L
    private const val MAX_TTL_MS = 365L * 86_400_000L

    private val exec = Executors.newSingleThreadExecutor()
    private val latest = ConcurrentHashMap<String, GroupInfo>()

    fun cached(conv: String): GroupInfo? = latest[conv]

    fun canCall(conv: String): Boolean = latest[conv]?.let { it.amIAdmin || it.perms.callGroup } ?: true

    fun onFrame(ctx: Context, conn: LinkConnection, frame: LinkFrame) {
        val app = ctx.applicationContext
        exec.execute {
            try {
                when (frame) {
                    is LinkFrame.GroupRequest -> send(app, conn, frame.conv)
                    is LinkFrame.GroupUpdate -> update(app, conn, frame)
                    is LinkFrame.GroupMemberAction -> member(app, conn, frame)
                    is LinkFrame.GroupLeave -> leave(app, conn, frame)
                    else -> Unit
                }
            } catch (e: Exception) {
                Log.w(TAG, "${frame::class.simpleName}: ${e.message}")
            }
        }
    }

    private fun <T> await(timeoutS: Long = WAIT_S, start: ((T) -> Unit) -> Unit): T? {
        val out = AtomicReference<T?>(null)
        val latch = CountDownLatch(1)
        start { value ->
            out.set(value)
            latch.countDown()
        }
        latch.await(timeoutS, TimeUnit.SECONDS)
        return out.get()
    }

    private fun load(ctx: Context, conv: String): GroupInfo? {
        if (!GroupRepository.isGroup(conv)) return null
        val info = await<GroupInfo?> { done -> GroupRepository.getGroupInfo(ctx, conv) { done(it) } } ?: return null
        latest[conv] = info
        return info
    }

    fun frameOf(ctx: Context, conv: String, info: GroupInfo?): LinkFrame.GroupInfo {
        if (info == null) return LinkFrame.GroupInfo(conv, ok = false)
        val me = SetupActivity.getUserPhoneHash(ctx)
        return LinkFrame.GroupInfo(
            conv = conv,
            name = info.name,
            color = info.avatarColor,
            me = me,
            members = info.members.map { m ->
                GroupMemberInfo(
                    hash = m.userHash,
                    name = m.displayName,
                    color = if (m.userHash == me && AvatarHelper.hasMyColor(ctx)) AvatarHelper.getMyColor(ctx) else m.avatarColor,
                    admin = m.isAdmin,
                    avatar = if (m.userHash == me) LinkFrame.AVATAR_ME else PcLinkHub.avatarTokenOf(ctx, m.userHash)
                )
            },
            active = info.activeCount,
            admin = info.amIAdmin,
            createdBy = info.createdBy,
            rights = GroupRights(info.perms.editSettings, info.perms.sendMessages, info.perms.addMembers, info.perms.callGroup),
            ttlMs = info.disappearingTtlMs,
            trigger = info.disappearingTrigger,
            muteUntil = MuteSettings.getMutedUntil(ctx, conv)
        )
    }

    private fun send(ctx: Context, conn: LinkConnection?, conv: String) {
        val frame = frameOf(ctx, conv, load(ctx, conv))
        if (conn != null) conn.send(frame) else PcLinkHub.broadcastFrame(frame)
    }

    private fun result(conn: LinkConnection, conv: String, req: String, ok: Boolean, error: String = "") {
        conn.send(LinkFrame.GroupResult(conv, req, ok, error))
    }

    private fun refreshed(ctx: Context, conv: String) {
        PcLinkHub.refreshChats()
        send(ctx, null, conv)
    }

    private fun update(ctx: Context, conn: LinkConnection, f: LinkFrame.GroupUpdate) {
        val conv = f.conv
        if (!GroupRepository.isGroup(conv)) return result(conn, conv, f.req, false)
        var ok = true
        f.muteUntil?.let { until ->
            MuteSettings.setMutedUntil(ctx, conv, if (until == LinkFrame.MUTE_FOREVER || until > System.currentTimeMillis()) until else 0L)
        }
        if (f.clearChat) {
            pl.sinusoide.sinuschat.data.SinusChatDatabase.getInstance(ctx).messageDao().clearConversation(conv)
            runCatching { pl.sinusoide.sinuschat.util.MediaManager.clearConversationMedia(ctx, conv) }
        }
        val info = latest[conv] ?: load(ctx, conv)
        val name = f.name?.trim()?.take(LinkFrame.GROUP_NAME_MAX)?.takeIf { it.isNotEmpty() && it != info?.name }
        val color = f.color?.let { c -> AvatarHelper.PALETTE.firstOrNull { it.equals(c.trim(), ignoreCase = true) } }
            ?.takeIf { !it.equals(info?.avatarColor, ignoreCase = true) }
        var avatarUrl: String? = null
        val staged = f.avatarReq?.let { PcLinkMedia.takeStaged(it, LinkFrame.AVATAR_MAX_BYTES) }
        if (f.avatarReq != null) {
            if (staged == null) ok = false
            else try {
                avatarUrl = pl.sinusoide.sinuschat.util.FileUploader.uploadGroupAvatarBlocking(
                    ctx, Uri.fromFile(staged), pl.sinusoide.sinuschat.crypto.GroupMeta.profileKey(ctx, conv)
                )
                if (avatarUrl.isNullOrEmpty()) ok = false
            } finally {
                staged.delete()
            }
        } else if (f.removeAvatar) {
            avatarUrl = ""
        }
        if (ok && (name != null || color != null || avatarUrl != null)) {
            val done = await<Boolean> { cb ->
                GroupRepository.updateGroup(ctx, conv, name = name, avatarUrl = avatarUrl, avatarColor = color) { cb(it) }
            } == true
            if (done) {
                name?.let { ContactCache.saveName(ctx, conv, it) }
                avatarUrl?.let { ContactCache.saveAvatarUrl(ctx, conv, it.ifEmpty { null }) }
                color?.let { AvatarHelper.setContactColor(ctx, conv, it) }
                pl.sinusoide.sinuschat.util.GroupSystemMessages.insert(ctx, conv,
                    LocaleManager.wrap(ctx).getString(R.string.group_settings_changed_by_me_sys))
            } else ok = false
        }
        f.rights?.let { r ->
            val next = (info?.perms ?: GroupPerms()).copy(editSettings = r.editSettings, sendMessages = r.sendMessages,
                addMembers = r.addMembers, callGroup = r.callGroup)
            if (next != info?.perms) {
                if (await<Boolean> { cb -> GroupRepository.setPermissions(ctx, conv, next) { cb(it) } } != true) ok = false
            }
        }
        f.ttlMs?.let { ttl ->
            val safe = ttl.coerceIn(0L, MAX_TTL_MS)
            val trigger = f.trigger?.takeIf { it.isNotBlank() } ?: DisappearingMessages.getTrigger(ctx, conv)
            if (await<Boolean> { cb -> GroupRepository.setDisappearing(ctx, conv, safe, trigger) { cb(it) } } != true) ok = false
        }
        result(conn, conv, f.req, ok)
        refreshed(ctx, conv)
    }

    private fun member(ctx: Context, conn: LinkConnection, f: LinkFrame.GroupMemberAction) {
        val conv = f.conv
        val target = f.member.trim().lowercase()
        val me = SetupActivity.getUserPhoneHash(ctx)
        if (!GroupRepository.isGroup(conv) || !Regex("^[a-f0-9]{64}$").matches(target) || target == me) {
            return result(conn, conv, f.req, false)
        }
        val ok = await<Boolean> { cb ->
            when (f.action) {
                LinkFrame.GROUP_ADD -> GroupRepository.addMember(ctx, conv, target) { cb(it) }
                LinkFrame.GROUP_KICK -> GroupRepository.kickMember(ctx, conv, target) { cb(it) }
                LinkFrame.GROUP_ADMIN -> GroupRepository.setAdmin(ctx, conv, target, true) { cb(it) }
                LinkFrame.GROUP_UNADMIN -> GroupRepository.setAdmin(ctx, conv, target, false) { cb(it) }
                else -> cb(false)
            }
        } == true
        result(conn, conv, f.req, ok)
        if (ok) refreshed(ctx, conv)
    }

    private fun leave(ctx: Context, conn: LinkConnection, f: LinkFrame.GroupLeave) {
        val conv = f.conv
        if (!GroupRepository.isGroup(conv)) return result(conn, conv, f.req, false)
        if (PcLinkGroupCalls.active(conv)) PcLinkGroupCalls.endFor(conv, LinkFrame.CALL_HANGUP)
        val ok = await<Boolean> { cb -> GroupRepository.leaveGroup(ctx, conv, f.silent) { cb(it) } } == true
        if (ok) {
            latest.remove(conv)
            pl.sinusoide.sinuschat.util.ChatPins.setPinned(ctx, conv, false)
        }
        result(conn, conv, f.req, ok)
        if (ok) {
            pl.sinusoide.sinuschat.util.AppExecutor.main {
                ctx.sendBroadcast(android.content.Intent("pl.sinusoide.sinuschat.REFRESH_CONVERSATIONS").setPackage(ctx.packageName))
            }
            PcLinkHub.refreshChats()
        }
    }
}
