package pl.sinusoide.sinuschat.pclink

import android.content.Context
import android.util.Log
import androidx.room.InvalidationTracker
import org.json.JSONArray
import pl.sinusoide.sinuschat.crypto.DbKeyManager
import pl.sinusoide.sinuschat.crypto.E2EManager
import pl.sinusoide.sinuschat.data.MessageEntity
import pl.sinusoide.sinuschat.data.SinusChatDatabase
import pl.sinusoide.sinuschat.link.ChatMessage
import pl.sinusoide.sinuschat.link.ChatSummary
import pl.sinusoide.sinuschat.link.LinkConnection
import pl.sinusoide.sinuschat.link.LinkFrame
import pl.sinusoide.sinuschat.link.LinkProtocol
import pl.sinusoide.sinuschat.messenger.GroupRepository
import pl.sinusoide.sinuschat.messenger.MessageListenerService
import pl.sinusoide.sinuschat.messenger.MessageRepository
import pl.sinusoide.sinuschat.messenger.ReplyLabel
import pl.sinusoide.sinuschat.network.ApiClient
import pl.sinusoide.sinuschat.network.WebSocketManager
import pl.sinusoide.sinuschat.setup.SetupActivity
import pl.sinusoide.sinuschat.util.AvatarHelper
import pl.sinusoide.sinuschat.util.BlockList
import pl.sinusoide.sinuschat.util.ContactCache
import pl.sinusoide.sinuschat.util.ConversationCache
import pl.sinusoide.sinuschat.util.DisappearingMessages
import pl.sinusoide.sinuschat.util.LocalChats
import pl.sinusoide.sinuschat.util.MediaManager
import pl.sinusoide.sinuschat.util.MessageNotifier
import pl.sinusoide.sinuschat.util.MuteSettings
import pl.sinusoide.sinuschat.util.NotificationPrefs
import pl.sinusoide.sinuschat.util.PrivacySettings
import pl.sinusoide.sinuschat.util.UserStatusManager
import pl.sinusoide.sinuschat.util.optStringOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

object PcLinkHub {

    private const val TAG = "PcLinkHub"
    private const val LIST_DEBOUNCE_MS = 400L
    private const val CHAT_DEBOUNCE_MS = 150L
    private const val SERVER_REFRESH_MS = 60_000L
    private const val LOCK_POLL_MS = 3_000L
    private const val NOTIFY_LOOKBACK_MS = 5 * 60_000L
    private const val GROUP_INFO_MS = 5 * 60_000L
    private const val MAX_TEXT = 8_000
    private const val MAX_LIMIT = 200
    private const val MAX_PINNED = 100
    private const val PENDING_SEND_MS = 3 * 60_000L
    private const val TRANSLATE_WAIT_MS = 8_000L
    private const val MAX_MEDIA_LIST = 500
    private const val MAX_TTL_MS = 365L * 86_400_000L
    private const val MAX_EMOJI = 16
    private const val PRESENCE_MS = 5_000L
    private const val SENT_PUSH_MS = 350L
    private const val SEND_PROGRESS_MS = 400L
    private const val OPEN_PRESENCE_EVERY_ONLINE = 6
    private const val LIST_PRESENCE_EVERY_ONLINE = 12
    private const val LIST_PRESENCE_EVERY = 6
    private const val OPEN_PRESENCE_EVERY_ACTIVE = 2
    private const val LIST_PRESENCE_EVERY_ACTIVE = 4
    private const val SEEN_TOLERANCE_MS = 60_000L

    private class Session(val connection: LinkConnection) {
        @Volatile var openConv: String? = null
        @Volatile var openLimit: Int = 80
        @Volatile var activeUntil: Long = 0L
    }

    private val sessions = ConcurrentHashMap<LinkConnection, Session>()
    private val worker = Executors.newSingleThreadExecutor()
    private val sender = Executors.newSingleThreadExecutor()
    private val net = Executors.newSingleThreadExecutor()
    private val scheduler = Executors.newSingleThreadScheduledExecutor()

    @Volatile private var app: Context? = null
    @Volatile private var observedDb: SinusChatDatabase? = null
    @Volatile private var lastServerRefresh = 0L
    @Volatile private var lastLocked: Boolean? = null
    @Volatile private var notifyFrom = 0L
    private val listScheduled = AtomicBoolean(false)
    private val notifyScheduled = AtomicBoolean(false)
    private val refreshing = AtomicBoolean(false)
    private val chatScheduled = ConcurrentHashMap.newKeySet<String>()
    private val notified = object : LinkedHashMap<String, Boolean>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?) = size > 500
    }
    private val groupNames = ConcurrentHashMap<String, Map<String, String>>()
    private val groupInfoAt = ConcurrentHashMap<String, Long>()
    private val groupReadOnly = ConcurrentHashMap<String, Boolean>()
    private val lockPoll = AtomicReference<ScheduledFuture<*>?>(null)
    private val progressPoll = AtomicReference<ScheduledFuture<*>?>(null)
    private val lastProgress = ConcurrentHashMap<String, Map<String, Float>>()

    private class Seen(val online: Boolean, val lastSeen: Long, val at: Long = System.currentTimeMillis())
    private val presence = ConcurrentHashMap<String, Seen>()
    private val presenceNet = Executors.newSingleThreadExecutor()
    private val presencePoll = AtomicReference<ScheduledFuture<*>?>(null)
    private val presenceFetching = ConcurrentHashMap.newKeySet<String>()
    private val listPresenceBusy = AtomicBoolean(false)
    @Volatile private var presenceTick = 0
    private val pcActive = AtomicBoolean(false)
    @Volatile private var awake: android.os.PowerManager.WakeLock? = null

    private val observer = object : InvalidationTracker.Observer("messages") {
        override fun onInvalidated(tables: Set<String>) = onDbChanged()
    }

    fun onConnected(ctx: Context, connection: LinkConnection) {
        app = ctx.applicationContext
        sessions[connection] = Session(connection)
        if (notifyFrom == 0L) notifyFrom = System.currentTimeMillis()
        startLockPoll()
        startPresencePoll()
        startProgressPoll()
        keepServerLink(ctx.applicationContext)
        worker.execute {
            val locked = isLocked()
            connection.send(LinkFrame.PhoneState(locked))
            if (!locked) {
                ensureObserver()
                connection.send(PcLinkProfile.frame(ctx.applicationContext))
            }
        }
    }

    fun onProfileChanged() {
        val ctx = app ?: return
        if (sessions.isEmpty()) return
        worker.execute {
            if (!isLocked()) broadcast(PcLinkProfile.frame(ctx))
        }
    }

    private fun profileFromPc(connection: LinkConnection, frame: LinkFrame) {
        val ctx = app ?: return
        val (field, ok) = when {
            isLocked() -> {
                if (frame is LinkFrame.SetProfileAvatar) PcLinkMedia.takeStaged(frame.req, Long.MAX_VALUE)?.delete()
                (if (frame is LinkFrame.SetProfileName) LinkFrame.PROFILE_NAME else LinkFrame.PROFILE_AVATAR) to false
            }
            frame is LinkFrame.SetProfileName -> LinkFrame.PROFILE_NAME to PcLinkProfile.rename(ctx, frame.name)
            frame is LinkFrame.SetProfileAvatar && frame.remove -> LinkFrame.PROFILE_AVATAR to PcLinkProfile.removeAvatar(ctx)
            frame is LinkFrame.SetProfileAvatar -> LinkFrame.PROFILE_AVATAR to
                (PcLinkMedia.takeStaged(frame.req, LinkFrame.AVATAR_MAX_BYTES)?.let { PcLinkProfile.setAvatar(ctx, it) } ?: false)
            else -> return
        }
        connection.send(LinkFrame.ProfileResult(field, ok))
        onProfileChanged()
    }

    fun onDisconnected(connection: LinkConnection) {
        sessions.remove(connection)
        updateActivity()
        PcLinkCalls.onPcGone(connection)
        if (sessions.isEmpty()) {
            PcLinkMedia.dropAll()
            notifyFrom = 0L
            lockPoll.getAndSet(null)?.cancel(false)
            presencePoll.getAndSet(null)?.cancel(false)
            progressPoll.getAndSet(null)?.cancel(false)
            lastProgress.clear()
        }
    }

    fun onFrame(connection: LinkConnection, frame: LinkFrame) {
        val session = sessions[connection] ?: return
        val ctx = app ?: return
        when (frame) {
            is LinkFrame.Activity -> {
                session.activeUntil = if (frame.active) android.os.SystemClock.elapsedRealtime() + LinkProtocol.ACTIVITY_TTL_MS else 0L
                updateActivity()
            }
            is LinkFrame.TypingOut -> worker.execute { sendTyping(frame.conv, frame.typing) }
            is LinkFrame.SendText -> sender.execute {
                val result = try {
                    if (isLocked()) LinkFrame.SendResult(frame.id, false, LinkFrame.ERROR_LOCKED)
                    else if (pendingSends.containsKey(frame.id)) return@execute
                    else {
                        ensureObserver()
                        val offer = translationOffer(ctx, frame)
                        if (offer != null) {
                            pendingSends[frame.id] = PendingSend(frame, offer, connection)
                            connection.send(LinkFrame.TranslateSendOffer(frame.id, frame.conv, offer.original, offer.translated, offer.from, offer.to))
                            scheduler.schedule({ resolvePending(frame.id, false) }, PENDING_SEND_MS, TimeUnit.MILLISECONDS)
                            return@execute
                        }
                        sendText(frame)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "wysyłanie: ${e.message}")
                    LinkFrame.SendResult(frame.id, false, LinkFrame.ERROR_FAILED)
                }
                connection.send(result)
                if (result.ok) {
                    scheduler.schedule({ worker.execute { pushLatest(frame.conv, null) } }, SENT_PUSH_MS, TimeUnit.MILLISECONDS)
                    scheduleList(refresh = false)
                }
            }
            is LinkFrame.DeleteMessages -> sender.execute {
                try {
                    if (!isLocked()) deleteFromPc(frame)
                } catch (e: Exception) {
                    Log.w(TAG, "usuwanie: ${e.message}")
                }
            }
            is LinkFrame.OpenOnPhone -> PcHandoff.request(ctx, frame.section)
            is LinkFrame.TranslateSendChoice -> if (frame.cancel) {
                pendingSends.remove(frame.id)?.conn?.send(LinkFrame.SendResult(frame.id, false, LinkFrame.ERROR_CANCELLED))
            } else resolvePending(frame.id, frame.translated)
            is LinkFrame.TranslateDecision -> worker.execute {
                PcLinkTranslate.decide(ctx, frame.conv, frame.lang, frame.yes)
                scheduleChat(frame.conv)
            }
            is LinkFrame.TranslateInfoRequest -> worker.execute { if (!isLocked()) connection.send(PcLinkTranslate.info(ctx)) }
            is LinkFrame.TranslateSet -> worker.execute {
                if (isLocked()) return@execute
                PcLinkTranslate.apply(ctx, frame)
                broadcast(PcLinkTranslate.info(ctx))
                sessions.values.mapNotNull { it.openConv }.toSet().forEach { scheduleChat(it) }
            }
            is LinkFrame.DesktopDiag -> onDesktopDiag(frame)
            is LinkFrame.ChatAction -> net.execute {
                try {
                    if (isLocked()) connection.send(LinkFrame.PhoneState(true)) else chatAction(ctx, frame)
                } catch (e: Exception) {
                    Log.w(TAG, "akcja czatu: ${e.message}")
                }
            }
            is LinkFrame.VoiceCommit -> net.execute {
                val result = try {
                    if (isLocked()) {
                        PcLinkMedia.takeStaged(frame.req, Long.MAX_VALUE)?.delete()
                        LinkFrame.UploadResult(frame.batch, false, LinkFrame.ERROR_LOCKED)
                    } else {
                        ensureObserver()
                        voiceFromPc(ctx, frame)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "głosówka: ${e.message}")
                    LinkFrame.UploadResult(frame.batch, false, LinkFrame.ERROR_FAILED)
                }
                connection.send(result)
            }
            is LinkFrame.ContactRequest, is LinkFrame.ContactUpdate, is LinkFrame.SafetyRequest, is LinkFrame.MediaListRequest -> net.execute {
                try {
                    if (isLocked()) connection.send(LinkFrame.PhoneState(true))
                    else {
                        ensureObserver()
                        contactFromPc(connection, frame)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "kontakt: ${e.message}")
                }
            }
            is LinkFrame.SetProfileColor -> net.execute {
                val ok = !isLocked() && runCatching { PcLinkProfile.setColor(ctx, frame.color) }.getOrDefault(false)
                connection.send(LinkFrame.ProfileResult(LinkFrame.PROFILE_COLOR, ok))
                onProfileChanged()
            }
            is LinkFrame.SetProfileName, is LinkFrame.SetProfileAvatar -> net.execute {
                try {
                    profileFromPc(connection, frame)
                } catch (e: Exception) {
                    Log.w(TAG, "profil: ${e.message}")
                    val field = if (frame is LinkFrame.SetProfileName) LinkFrame.PROFILE_NAME else LinkFrame.PROFILE_AVATAR
                    connection.send(LinkFrame.ProfileResult(field, false))
                }
            }
            is LinkFrame.PinMessage, is LinkFrame.ReactMessage, is LinkFrame.EditMessage -> sender.execute {
                try {
                    if (!isLocked()) {
                        ensureObserver()
                        changeFromPc(frame)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "zmiana wiadomości: ${e.message}")
                }
            }
            is LinkFrame.CallStart, is LinkFrame.CallAccept, is LinkFrame.CallSignal, is LinkFrame.CallEnd ->
                if (isLocked() && frame is LinkFrame.CallStart) connection.send(LinkFrame.CallEnd(frame.call, LinkFrame.CALL_FAILED))
                else PcLinkCalls.onFrame(ctx, connection, frame)
            is LinkFrame.UploadStart -> if (!isLocked()) PcLinkMedia.uploadStart(ctx, frame)
            is LinkFrame.UploadChunk -> PcLinkMedia.uploadChunk(frame)
            is LinkFrame.UploadEnd -> PcLinkMedia.uploadEnd(frame)
            is LinkFrame.UploadCancel -> PcLinkMedia.uploadCancel(frame.batch)
            is LinkFrame.MediaCancel -> PcLinkMedia.cancel(frame.req)
            is LinkFrame.MediaRequest ->
                if (isLocked()) connection.send(LinkFrame.MediaError(frame.req, LinkFrame.ERROR_LOCKED))
                else PcLinkMedia.request(ctx, connection, frame)
            is LinkFrame.UploadCommit -> net.execute {
                val result = try {
                    if (isLocked()) LinkFrame.UploadResult(frame.batch, false, LinkFrame.ERROR_LOCKED)
                    else PcLinkMedia.commit(ctx, frame, me(ctx)) { conv -> attachmentTarget(ctx, conv) }
                } catch (e: Exception) {
                    Log.w(TAG, "upload: ${e.message}")
                    PcLinkMedia.uploadCancel(frame.batch)
                    LinkFrame.UploadResult(frame.batch, false, LinkFrame.ERROR_FAILED)
                }
                connection.send(result)
            }
            else -> worker.execute {
                try {
                    handle(session, frame)
                } catch (e: Exception) {
                    Log.w(TAG, "obsługa ${frame::class.simpleName}: ${e.message}")
                }
            }
        }
    }

    fun onTyping(conversationId: String, senderHash: String, typing: Boolean) {
        val ctx = app ?: return
        if (sessions.isEmpty() || conversationId.isEmpty()) return
        val name = ContactCache.getName(ctx, senderHash) ?: ""
        broadcast(LinkFrame.TypingIn(conversationId, name, typing))
    }

    fun onPresence(peer: String, heartbeatMs: Long, lastSeenMs: Long) {
        if (peer.isEmpty()) return
        setPresence(peer, heartbeatMs > 0, maxOf(heartbeatMs, lastSeenMs))
    }

    fun onMessagesDeleted(conv: String, ids: List<String>) {
        if (sessions.isEmpty() || conv.isEmpty() || ids.isEmpty()) return
        worker.execute { broadcast(LinkFrame.MessagesDeleted(conv, ids)) }
    }

    private fun deleteFromPc(f: LinkFrame.DeleteMessages) {
        val ctx = ctx() ?: return
        val me = me(ctx)
        val isGroup = GroupRepository.isGroup(f.conv)
        val other = if (isGroup) "" else otherOf(f.conv, me) ?: return
        val dao = SinusChatDatabase.getInstance(ctx).messageDao()
        for (id in f.ids.distinct().take(500)) {
            val msg = try { dao.getById(id) } catch (_: Exception) { null } ?: continue
            if (msg.conversationId != f.conv) continue
            if (msg.senderHash == me && !msg.isSystemMessage) MessageRepository.deleteMessageEverywhere(ctx, f.conv, me, other, id)
            else MessageRepository.deleteWithMediaBlocking(ctx, id)
        }
        pl.sinusoide.sinuschat.util.AppExecutor.main {
            ctx.sendBroadcast(android.content.Intent("pl.sinusoide.sinuschat.REFRESH_CONVERSATIONS").setPackage(ctx.packageName))
        }
    }

    private fun changeFromPc(f: LinkFrame) {
        val ctx = ctx() ?: return
        val (conv, id) = when (f) {
            is LinkFrame.PinMessage -> f.conv to f.id
            is LinkFrame.ReactMessage -> f.conv to f.id
            is LinkFrame.EditMessage -> f.conv to f.id
            else -> return
        }
        val me = me(ctx)
        val isGroup = GroupRepository.isGroup(conv)
        val other = if (isGroup) "" else otherOf(conv, me) ?: return
        val dao = SinusChatDatabase.getInstance(ctx).messageDao()
        val msg = dao.getById(id)?.takeIf { it.conversationId == conv && !it.isSystemMessage } ?: return
        when (f) {
            is LinkFrame.PinMessage ->
                if (msg.pinned != f.pinned) MessageRepository.setPinned(ctx, conv, me, other, id, f.pinned)
            is LinkFrame.ReactMessage -> {
                if (isGroup || BlockList.isBlocked(ctx, other) || BlockList.isDeleted(ctx, other)) return
                val emoji = f.emoji.trim().takeIf { it.isNotEmpty() && it.length <= MAX_EMOJI && it.none { c -> c.isWhitespace() } }
                val current = msg.reactions[me]
                val next = if (emoji == null || emoji == current) null else emoji
                if (next == null && current == null) return
                val secret = secretFor(ctx, other) ?: return
                MessageRepository.setReaction(ctx, conv, me, other, secret, id, next)
            }
            is LinkFrame.EditMessage -> {
                if (isGroup || msg.senderHash != me || msg.type != MessageEntity.TYPE_TEXT) return
                if (BlockList.isBlocked(ctx, other) || BlockList.isDeleted(ctx, other)) return
                val text = f.text.trim().take(MAX_TEXT)
                if (text.isEmpty() || text == msg.text) return
                val secret = secretFor(ctx, other) ?: return
                MessageRepository.editMessageEverywhere(ctx, conv, me, other, secret, id, text)
            }
            else -> Unit
        }
    }

    @Volatile private var desktopDiag: LinkFrame.DesktopDiag? = null
    private val diagWaiters = ConcurrentHashMap<String, CountDownLatch>()

    private fun onDesktopDiag(f: LinkFrame.DesktopDiag) {
        if (f.text.length > 16_000) return
        desktopDiag = f
        diagWaiters.remove(f.req)?.countDown()
    }

    fun cachedDesktopDiag(): LinkFrame.DesktopDiag? = desktopDiag

    fun requestDesktopDiag(timeoutMs: Long): LinkFrame.DesktopDiag? {
        if (sessions.isEmpty()) return null
        val req = java.util.UUID.randomUUID().toString()
        val latch = CountDownLatch(1)
        diagWaiters[req] = latch
        broadcast(LinkFrame.DesktopDiagRequest(req))
        val got = latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        diagWaiters.remove(req)
        return if (got) desktopDiag?.takeIf { it.req == req } else null
    }

    private class PendingSend(val frame: LinkFrame.SendText, val offer: PcLinkTranslate.SendOffer, val conn: LinkConnection)

    private val pendingSends = ConcurrentHashMap<String, PendingSend>()

    private fun translationOffer(ctx: Context, f: LinkFrame.SendText): PcLinkTranslate.SendOffer? {
        if (GroupRepository.isGroup(f.conv)) return null
        val text = f.text.trim().take(MAX_TEXT)
        if (text.isEmpty()) return null
        val result = AtomicReference<PcLinkTranslate.SendOffer?>(null)
        val latch = CountDownLatch(1)
        PcLinkTranslate.prepareSend(ctx, f.conv, text) { offer ->
            result.set(offer)
            latch.countDown()
        }
        latch.await(TRANSLATE_WAIT_MS, TimeUnit.MILLISECONDS)
        return result.get()
    }

    private fun resolvePending(id: String, translated: Boolean) {
        sender.execute {
            val p = pendingSends.remove(id) ?: return@execute
            val result = try {
                if (isLocked()) LinkFrame.SendResult(id, false, LinkFrame.ERROR_LOCKED)
                else sendText(p.frame, if (translated) p.offer else null)
            } catch (e: Exception) {
                Log.w(TAG, "wysyłanie z tłumaczeniem: ${e.message}")
                LinkFrame.SendResult(id, false, LinkFrame.ERROR_FAILED)
            }
            p.conn.send(result)
            if (result.ok) {
                scheduler.schedule({ worker.execute { pushLatest(p.frame.conv, null) } }, SENT_PUSH_MS, TimeUnit.MILLISECONDS)
                scheduleList(refresh = false)
            }
        }
    }

    fun refreshChats() {
        if (sessions.isEmpty()) return
        scheduleList(refresh = false)
    }

    private fun chatAction(ctx: Context, f: LinkFrame.ChatAction) {
        val conv = f.conv
        if (conv.isEmpty()) return
        val me = me(ctx)
        val isGroup = GroupRepository.isGroup(conv)
        val other = if (isGroup) null else otherOf(conv, me)
        when (f.action) {
            LinkFrame.CHAT_PIN -> pl.sinusoide.sinuschat.util.ChatPins.setPinned(ctx, conv, true)
            LinkFrame.CHAT_UNPIN -> pl.sinusoide.sinuschat.util.ChatPins.setPinned(ctx, conv, false)
            LinkFrame.CHAT_MUTE -> {
                val until = f.until
                MuteSettings.setMutedUntil(ctx, conv, if (until == LinkFrame.MUTE_FOREVER || until > System.currentTimeMillis()) until else 0L)
            }
            LinkFrame.CHAT_BLOCK, LinkFrame.CHAT_BLOCK_DELETE -> {
                val peer = other ?: return
                BlockList.block(ctx, peer)
                if (f.action == LinkFrame.CHAT_BLOCK_DELETE) deleteChat(ctx, conv, me)
            }
            LinkFrame.CHAT_UNBLOCK -> BlockList.unblock(ctx, other ?: return)
            LinkFrame.CHAT_DELETE -> deleteChat(ctx, conv, me)
            else -> return
        }
        pl.sinusoide.sinuschat.util.AppExecutor.main {
            ctx.sendBroadcast(android.content.Intent("pl.sinusoide.sinuschat.REFRESH_CONVERSATIONS").setPackage(ctx.packageName))
        }
        scheduleList(refresh = false)
    }

    private fun deleteChat(ctx: Context, conv: String, me: String) {
        pl.sinusoide.sinuschat.util.ChatPins.setPinned(ctx, conv, false)
        val done = CountDownLatch(1)
        MessageRepository.deleteConversation(ctx, conv, me) { done.countDown() }
        done.await(30, TimeUnit.SECONDS)
    }

    private fun voiceFromPc(ctx: Context, f: LinkFrame.VoiceCommit): LinkFrame.UploadResult {
        fun fail(error: String) = LinkFrame.UploadResult(f.batch, false, error)
        val wav = PcLinkMedia.takeStaged(f.req, LinkFrame.MAX_UPLOAD_BYTES) ?: return fail(LinkFrame.ERROR_FAILED)
        val ogg = java.io.File(ctx.cacheDir, "voice_pc_${java.util.UUID.randomUUID()}.ogg")
        try {
            if (GroupRepository.isGroup(f.conv)) return fail(LinkFrame.ERROR_GROUP_VOICE)
            val me = me(ctx)
            val other = otherOf(f.conv, me) ?: return fail(LinkFrame.ERROR_FAILED)
            if (BlockList.isBlocked(ctx, other)) return fail(LinkFrame.ERROR_BLOCKED)
            if (BlockList.isDeleted(ctx, other)) return fail(LinkFrame.ERROR_DELETED)
            val pcm = PcLinkVoice.readWav(wav) ?: return fail(LinkFrame.ERROR_FAILED)
            val ms = PcLinkVoice.durationMs(pcm)
            if (ms < 1_000L) return fail(LinkFrame.ERROR_TOO_SHORT)
            if (ms > LinkFrame.VOICE_MAX_MS + 5_000L) return fail(LinkFrame.ERROR_TOO_BIG)
            if (!PcLinkVoice.encodeOgg(pcm, ogg)) return fail(LinkFrame.ERROR_FAILED)
            val secret = secretFor(ctx, other) ?: return fail(LinkFrame.ERROR_NO_KEY)
            val up = pl.sinusoide.sinuschat.util.FileUploader.uploadVoiceBlocking(ctx, ogg) ?: return fail(LinkFrame.ERROR_FAILED)
            MessageRepository.sendVoice(ctx, f.conv, me, other, secret, up.first, up.second, (ms / 1000L).toInt().coerceAtLeast(1), ogg)
            scheduleList(refresh = false)
            return LinkFrame.UploadResult(f.batch, true)
        } finally {
            wav.delete()
            ogg.delete()
        }
    }

    private val linkPattern = Regex("""(?:https?://|www\.|[a-zA-Z0-9-]+\.)[a-zA-Z0-9-]+\.[a-zA-Z]{2,}(?:/\S*)?""", RegexOption.IGNORE_CASE)

    private fun contactFromPc(conn: LinkConnection, f: LinkFrame) {
        val ctx = ctx() ?: return
        val conv = when (f) {
            is LinkFrame.ContactRequest -> f.conv
            is LinkFrame.ContactUpdate -> f.conv
            is LinkFrame.SafetyRequest -> f.conv
            is LinkFrame.MediaListRequest -> f.conv
            else -> return
        }
        if (GroupRepository.isGroup(conv)) return
        val me = me(ctx)
        val other = otherOf(conv, me) ?: return
        when (f) {
            is LinkFrame.ContactRequest -> conn.send(contactInfo(ctx, conv, other))
            is LinkFrame.ContactUpdate -> {
                applyContact(ctx, f, other)
                broadcast(contactInfo(ctx, conv, other))
                scheduleList(refresh = false)
            }
            is LinkFrame.SafetyRequest -> conn.send(safetyNumber(ctx, conv, me, other))
            is LinkFrame.MediaListRequest -> {
                val limit = f.limit.coerceIn(1, MAX_MEDIA_LIST)
                val rows = SinusChatDatabase.getInstance(ctx).messageDao().getAllForConversationSync(conv)
                val picked = alive(rows).asSequence().filter { m ->
                    when {
                        m.isSystemMessage || m.viewOnce -> false
                        m.type == MessageEntity.TYPE_ATTACHMENT -> m.fileId != null && !(m.attachmentName ?: "").startsWith("location_")
                        m.type == MessageEntity.TYPE_TEXT -> linkPattern.containsMatchIn(m.text)
                        else -> false
                    }
                }.take(limit).toList()
                conn.send(LinkFrame.MediaList(conv, picked.map { toMessage(ctx, it, me, null) }))
            }
            else -> Unit
        }
    }

    private fun contactInfo(ctx: Context, conv: String, other: String): LinkFrame.ContactInfo {
        val awaiting = DisappearingMessages.getAwaitingConsent(ctx, conv)
        val real = ContactCache.realName(ctx, other).orEmpty()
        return LinkFrame.ContactInfo(
            conv = conv,
            peer = other,
            name = ContactCache.bestName(ctx, other, real.ifEmpty { null }),
            realName = real,
            nick = ContactCache.getNick(ctx, other).orEmpty(),
            muteUntil = MuteSettings.getMutedUntil(ctx, conv),
            ttlMs = DisappearingMessages.getTtl(ctx, conv),
            trigger = DisappearingMessages.getTrigger(ctx, conv),
            awaitingTtl = awaiting?.first ?: -1L,
            blocked = BlockList.isBlocked(ctx, other),
            translate = pl.sinusoide.sinuschat.util.Translations.supported(),
            translateBlocked = pl.sinusoide.sinuschat.util.Translations.isBlocked(ctx, conv)
        )
    }

    private fun applyContact(ctx: Context, f: LinkFrame.ContactUpdate, other: String) {
        val conv = f.conv
        f.nick?.let { ContactCache.setNick(ctx, other, it.filter { c -> !c.isISOControl() }.trim().ifEmpty { null }) }
        f.muteUntil?.let { until ->
            val clean = if (until == LinkFrame.MUTE_FOREVER || until > System.currentTimeMillis()) until else 0L
            MuteSettings.setMutedUntil(ctx, conv, clean)
        }
        f.ttlMs?.let { ttl ->
            if (ttl in 0L..MAX_TTL_MS) {
                pl.sinusoide.sinuschat.util.DisappearingCoordinator.propose(
                    ctx, conv, other, ttl, f.trigger?.takeIf { it.isNotBlank() } ?: DisappearingMessages.TRIGGER_READ)
            }
        }
        f.translateBlocked?.let { on ->
            if (pl.sinusoide.sinuschat.util.Translations.supported()) {
                pl.sinusoide.sinuschat.util.Translations.setBlocked(ctx, conv, on, ContactCache.getName(ctx, other).orEmpty(), other, false)
            }
        }
        f.verified?.let { on ->
            if (!on) pl.sinusoide.sinuschat.util.SecurityUtils.storeContactKey(ctx, other, "")
            else peerKey(ctx, other)?.let { pl.sinusoide.sinuschat.util.SecurityUtils.storeContactKey(ctx, other, it) }
        }
        f.blocked?.let { on -> if (on) BlockList.block(ctx, other) else BlockList.unblock(ctx, other) }
        if (f.clearChat) MessageRepository.clearChatLocal(ctx, conv)
        pl.sinusoide.sinuschat.util.AppExecutor.main {
            ctx.sendBroadcast(android.content.Intent("pl.sinusoide.sinuschat.REFRESH_CONVERSATIONS").setPackage(ctx.packageName))
        }
    }

    private fun peerKey(ctx: Context, hash: String): String? =
        ApiClient.getObject("/api/user/$hash", ctx)?.optStringOrNull("e2e_public_key")

    private fun safetyNumber(ctx: Context, conv: String, me: String, other: String): LinkFrame.SafetyNumber {
        val mine = peerKey(ctx, me) ?: return LinkFrame.SafetyNumber(conv, ok = false)
        val theirs = peerKey(ctx, other) ?: return LinkFrame.SafetyNumber(conv, ok = false)
        val rows = pl.sinusoide.sinuschat.util.SecurityUtils.generateSafetyNumber(mine, theirs)
        val verified = pl.sinusoide.sinuschat.util.SecurityUtils.getStoredPublicKey(ctx, other) == theirs
        return LinkFrame.SafetyNumber(conv, rows, verified)
    }

    private fun pushPinned(conv: String, conn: LinkConnection) {
        val ctx = ctx() ?: return
        val me = me(ctx)
        val rows = SinusChatDatabase.getInstance(ctx).messageDao().getPinned(conv, MAX_PINNED)
        conn.send(LinkFrame.PinnedList(conv, alive(rows).map { toMessage(ctx, it, me, groupNames[conv]) }))
    }

    fun onAvatarChanged(key: String) {
        val ctx = app ?: return
        if (sessions.isEmpty() || key.isEmpty()) return
        worker.execute { broadcast(LinkFrame.AvatarChanged(key, avatarToken(ctx, key))) }
    }

    private fun startPresencePoll() {
        if (presencePoll.get() != null) return
        val task = scheduler.scheduleWithFixedDelay({ pollPresence() }, PRESENCE_MS, PRESENCE_MS, TimeUnit.MILLISECONDS)
        if (!presencePoll.compareAndSet(null, task)) task.cancel(false)
    }

    private fun pollPresence() {
        updateActivity()
        if (sessions.isEmpty() || isLocked()) return
        app?.let { keepServerLink(it) }
        val tick = presenceTick++
        val live = WebSocketManager.isConnected()
        val active = pcActive.get()
        val openEvery = when {
            !live -> 1
            active -> OPEN_PRESENCE_EVERY_ACTIVE
            else -> OPEN_PRESENCE_EVERY_ONLINE
        }
        val listEvery = when {
            active -> LIST_PRESENCE_EVERY_ACTIVE
            live -> LIST_PRESENCE_EVERY_ONLINE
            else -> LIST_PRESENCE_EVERY
        }
        if (tick % openEvery == 0) sessions.values.mapNotNull { it.openConv }.toSet().forEach { refreshPresence(it) }
        if (tick % listEvery == 0) refreshListPresence()
    }

    private fun updateActivity() {
        val ctx = app ?: return
        val active = synchronized(pcActive) {
            val now = android.os.SystemClock.elapsedRealtime()
            val on = sessions.values.any { it.activeUntil > now }
            if (on) holdAwake(ctx) else releaseAwake()
            if (pcActive.getAndSet(on) == on) return
            UserStatusManager.setRemoteActive(ctx, on)
            on
        }
        if (!active || isLocked()) return
        presenceTick = 1
        keepServerLink(ctx)
        sessions.values.mapNotNull { it.openConv }.toSet().forEach { refreshPresence(it) }
        refreshListPresence()
    }

    private fun holdAwake(ctx: Context) {
        try {
            val wl = awake ?: ctx.getSystemService(android.os.PowerManager::class.java)
                .newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "sinuschat:pc")
                .apply { setReferenceCounted(false) }
                .also { awake = it }
            wl.acquire(LinkProtocol.ACTIVITY_TTL_MS)
        } catch (e: Exception) {
            Log.w(TAG, "wakelock: ${e.message}")
        }
    }

    private fun releaseAwake() {
        try {
            awake?.takeIf { it.isHeld }?.release()
        } catch (_: Exception) {
        }
    }

    private fun keepServerLink(ctx: Context) {
        if (sessions.isEmpty()) return
        if (!SetupActivity.isSetupDone(ctx) || me(ctx).isEmpty()) return
        if (!MessageListenerService.isRunning) {
            if (PcLinkService.running || pl.sinusoide.sinuschat.SinusApp.isForeground) MessageListenerService.start(ctx)
        } else if (!WebSocketManager.isConnected()) WebSocketManager.connect(ctx)
    }

    private fun refreshListPresence() {
        val ctx = ctx() ?: return
        if (!listPresenceBusy.compareAndSet(false, true)) return
        presenceNet.execute {
            try {
                val arr = ConversationCache.load(ctx) ?: return@execute
                val peers = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
                    .filter { !it.optBoolean("is_group", false) && !GroupRepository.isGroup(it.optString("id")) }
                    .map { it.optString("other_hash") }.filter { it.isNotEmpty() }.distinct().take(200)
                if (peers.isEmpty()) return@execute
                val resp = ApiClient.post("/api/presence", ApiClient.presenceBody(ctx, peers), ctx) ?: return@execute
                for (peer in peers) {
                    val p = resp.optJSONObject(peer) ?: continue
                    setPresence(peer, p.optBoolean("online", false), p.optLong("seen", 0L))
                }
            } catch (e: Exception) {
                Log.w(TAG, "obecność listy: ${e.message}")
            } finally {
                listPresenceBusy.set(false)
            }
        }
    }

    private fun setPresence(peer: String, online: Boolean, serverMs: Long) {
        val seen = Seen(online, localTime(serverMs))
        val prev = presence.put(peer, seen)
        val ctx = app ?: return
        if (sessions.isEmpty()) return
        if (prev != null && prev.online == seen.online && kotlin.math.abs(prev.lastSeen - seen.lastSeen) < SEEN_TOLERANCE_MS) return
        worker.execute {
            if (BlockList.isCutOff(ctx, peer)) broadcast(LinkFrame.Presence(peer, false, 0L))
            else broadcast(LinkFrame.Presence(peer, seen.online, seen.lastSeen))
        }
    }

    private fun localTime(serverMs: Long): Long =
        if (serverMs <= 0L) 0L
        else System.currentTimeMillis() - pl.sinusoide.sinuschat.util.ServerTime.ageMs(serverMs).coerceAtLeast(0L)

    private fun avatarToken(ctx: Context, key: String): String {
        val raw = ContactCache.storedAvatarUrl(ctx, key)?.takeIf { it.isNotBlank() && it != "null" } ?: return ""
        val ready = pl.sinusoide.sinuschat.crypto.ProfileCrypto.avatarReady(ctx, key, raw)
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(raw.toByteArray())
        val hex = digest.take(6).joinToString("") { "%02x".format(it) }
        return if (ready) hex else "$hex-"
    }

    private fun refreshPresence(conv: String) {
        val ctx = ctx() ?: return
        if (GroupRepository.isGroup(conv)) return
        val peer = otherOf(conv, me(ctx)) ?: return
        if (!presenceFetching.add(peer)) return
        presenceNet.execute {
            try {
                val user = ApiClient.getObject("/api/user/$peer", ctx) ?: return@execute
                setPresence(peer, user.optBoolean("is_online", false),
                    maxOf(user.optLong("last_heartbeat_ms", 0L), user.optLong("last_seen_ms", 0L)))
            } catch (e: Exception) {
                Log.w(TAG, "obecność: ${e.message}")
            } finally {
                presenceFetching.remove(peer)
            }
        }
    }

    private fun ctx(): Context? = app

    private fun isLocked(): Boolean {
        val ctx = ctx() ?: return true
        return DbKeyManager.isEnabled(ctx) && DbKeyManager.cachedKey() == null
    }

    private fun me(ctx: Context) = SetupActivity.getUserPhoneHash(ctx)

    private fun broadcast(frame: LinkFrame) {
        for (s in sessions.values) s.connection.send(frame)
    }

    fun broadcastFrame(frame: LinkFrame, except: LinkConnection? = null) {
        for (s in sessions.values) if (s.connection !== except) s.connection.send(frame)
    }

    fun peerOf(ctx: Context, conv: String): String? = otherOf(conv, me(ctx))

    fun phoneLocked(): Boolean = isLocked()

    private fun startProgressPoll() {
        if (progressPoll.get() != null) return
        val task = scheduler.scheduleWithFixedDelay({ pushSendProgress() }, SEND_PROGRESS_MS, SEND_PROGRESS_MS, TimeUnit.MILLISECONDS)
        if (!progressPoll.compareAndSet(null, task)) task.cancel(false)
    }

    private fun pushSendProgress() {
        try {
            if (sessions.isEmpty() || isLocked()) return
            val convs = sessions.values.mapNotNull { it.openConv }.toSet()
            lastProgress.keys.retainAll(convs)
            for (conv in convs) {
                val now = pl.sinusoide.sinuschat.messenger.AttachmentSender.activeIn(conv)
                if (now.isEmpty()) {
                    if (lastProgress.remove(conv) == null) continue
                } else lastProgress[conv] = now
                val frame = LinkFrame.SendProgress(conv, now)
                sessions.values.filter { it.openConv == conv }.forEach { it.connection.send(frame) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "postęp: ${e.message}")
        }
    }

    private fun startLockPoll() {
        if (lockPoll.get() != null) return
        val task = scheduler.scheduleWithFixedDelay({
            worker.execute { checkLock() }
        }, LOCK_POLL_MS, LOCK_POLL_MS, TimeUnit.MILLISECONDS)
        if (!lockPoll.compareAndSet(null, task)) task.cancel(false)
    }

    private fun checkLock() {
        if (sessions.isEmpty()) return
        val locked = isLocked()
        if (lastLocked == locked) {
            if (!locked) ensureObserver()
            return
        }
        lastLocked = locked
        broadcast(LinkFrame.PhoneState(locked))
        if (!locked) {
            ensureObserver()
            ctx()?.let { broadcast(PcLinkProfile.frame(it)) }
            scheduleList(refresh = true)
            sessions.values.mapNotNull { it.openConv }.toSet().forEach { scheduleChat(it) }
        }
    }

    private fun ensureObserver() {
        val ctx = ctx() ?: return
        if (isLocked()) return
        val db = try { SinusChatDatabase.getInstance(ctx) } catch (_: Exception) { return }
        if (db === observedDb) return
        try {
            db.invalidationTracker.addObserver(observer)
            observedDb = db
        } catch (e: Exception) {
            Log.w(TAG, "observer: ${e.message}")
        }
    }

    private fun onDbChanged() {
        if (sessions.isEmpty()) return
        scheduleList(refresh = false)
        sessions.values.mapNotNull { it.openConv }.toSet().forEach { scheduleChat(it) }
        scheduleNotify()
    }

    private fun scheduleList(refresh: Boolean) {
        if (refresh) {
            refreshServer()
            return
        }
        if (!listScheduled.compareAndSet(false, true)) return
        scheduler.schedule({
            listScheduled.set(false)
            worker.execute { pushChats() }
        }, LIST_DEBOUNCE_MS, TimeUnit.MILLISECONDS)
    }

    private fun scheduleChat(conv: String) {
        if (!chatScheduled.add(conv)) return
        scheduler.schedule({
            chatScheduled.remove(conv)
            worker.execute { pushLatest(conv, null) }
        }, CHAT_DEBOUNCE_MS, TimeUnit.MILLISECONDS)
    }

    private fun scheduleNotify() {
        if (!notifyScheduled.compareAndSet(false, true)) return
        scheduler.schedule({
            notifyScheduled.set(false)
            worker.execute { pushIncoming() }
        }, CHAT_DEBOUNCE_MS, TimeUnit.MILLISECONDS)
    }

    private fun handle(session: Session, frame: LinkFrame) {
        val conn = session.connection
        if (isLocked()) {
            conn.send(LinkFrame.PhoneState(true))
            if (frame is LinkFrame.SendText) conn.send(LinkFrame.SendResult(frame.id, false, LinkFrame.ERROR_LOCKED))
            return
        }
        ensureObserver()
        when (frame) {
            is LinkFrame.ChatsRequest -> {
                pushChats(conn)
                if (frame.refresh || System.currentTimeMillis() - lastServerRefresh > SERVER_REFRESH_MS) refreshServer()
            }
            is LinkFrame.OpenChat -> {
                session.openConv = frame.conv
                session.openLimit = frame.limit.coerceIn(1, MAX_LIMIT)
                pushLatest(frame.conv, conn)
                if (GroupRepository.isGroup(frame.conv)) loadGroupInfo(frame.conv)
                else refreshPresence(frame.conv)
            }
            is LinkFrame.CloseChat -> if (session.openConv == frame.conv) session.openConv = null
            is LinkFrame.OlderMessages -> pushOlder(frame, conn)
            is LinkFrame.SendText -> conn.send(sendText(frame))
            is LinkFrame.MarkRead -> markRead(frame.conv)
            is LinkFrame.DeleteMessages -> deleteFromPc(frame)
            is LinkFrame.PinnedRequest -> pushPinned(frame.conv, conn)
            else -> Unit
        }
    }

    private fun refreshServer() {
        if (!refreshing.compareAndSet(false, true)) return
        net.execute {
            try {
                val ctx = ctx() ?: return@execute
                if (isLocked()) return@execute
                val me = me(ctx)
                val server: JSONArray = ApiClient.getArray("/api/conversations", ctx) ?: return@execute
                val arr = LocalChats.merge(ctx, me, server)
                pl.sinusoide.sinuschat.crypto.GroupMeta.openConversations(ctx, arr)
                ConversationCache.save(ctx, arr)
                MessageListenerService.syncPeerKeys(ctx, arr)
                val repair = MessageRepository.needsAckRepair(ctx)
                for (i in 0 until arr.length()) {
                    val j = arr.optJSONObject(i) ?: continue
                    val id = j.optString("id")
                    if (id.isEmpty() || GroupRepository.isGroup(id) || j.optBoolean("is_group", false)) continue
                    if (BlockList.isBlockedByThem(ctx, j.optString("other_hash"))) continue
                    MessageRepository.applyServerAcks(ctx, id, me,
                        j.optLong("other_delivered_up_to", 0), j.optLong("other_read_up_to", 0), repair)
                }
                MessageRepository.markAckRepairDone(ctx)
                lastServerRefresh = System.currentTimeMillis()
            } catch (e: Exception) {
                Log.w(TAG, "odświeżanie listy: ${e.message}")
            } finally {
                refreshing.set(false)
                worker.execute { pushChats() }
            }
        }
    }

    private fun kindOf(e: MessageEntity): String {
        val name = e.attachmentName.orEmpty()
        return when {
            e.isSystemMessage || e.type == MessageEntity.TYPE_EXPIRED_NOTICE -> ChatMessage.KIND_SYSTEM
            e.type == MessageEntity.TYPE_VOICE -> ChatMessage.KIND_VOICE
            e.text.startsWith("geo:") || name.startsWith("location_") -> ChatMessage.KIND_LOCATION
            e.type == MessageEntity.TYPE_ATTACHMENT -> when {
                name.isNotEmpty() && MediaManager.isImage(name) -> ChatMessage.KIND_PHOTO
                name.isNotEmpty() && MediaManager.isVideo(name) -> ChatMessage.KIND_VIDEO
                else -> ChatMessage.KIND_FILE
            }
            else -> ChatMessage.KIND_TEXT
        }
    }

    private fun visibleText(e: MessageEntity, kind: String): String = when (kind) {
        ChatMessage.KIND_LOCATION, ChatMessage.KIND_VOICE -> ""
        else -> e.text
    }

    private fun buildChats(ctx: Context): List<ChatSummary> {
        val arr = ConversationCache.load(ctx) ?: JSONArray()
        val dao = SinusChatDatabase.getInstance(ctx).messageDao()
        val me = me(ctx)
        val pins = pl.sinusoide.sinuschat.util.ChatPins.all(ctx)
        val out = ArrayList<ChatSummary>(arr.length())
        for (i in 0 until arr.length()) {
            val j = arr.optJSONObject(i) ?: continue
            val id = j.optString("id")
            if (id.isEmpty()) continue
            val isGroup = j.optBoolean("is_group", false) || GroupRepository.isGroup(id)
            val peer = if (isGroup) "" else j.optString("other_hash")
            val title = if (isGroup) j.optStringOrNull("group_name") ?: ContactCache.getName(ctx, id) ?: ""
            else ContactCache.bestName(ctx, peer, j.optStringOrNull("other_name"))
            val color = if (isGroup) j.optStringOrNull("group_avatar_color") ?: "#5288C1"
            else AvatarHelper.getContactColor(ctx, peer)
            val last = try { dao.getLastMessage(id) } catch (_: Exception) { null }
            val kind = last?.let { kindOf(it) } ?: ChatMessage.KIND_TEXT
            val unread = try { dao.countIncomingSince(id, me, LocalChats.readMark(ctx, id)) } catch (_: Exception) { 0 }
            val cut = !isGroup && BlockList.isCutOff(ctx, peer)
            val live = if (isGroup) null else presence[peer]?.takeIf { it.at >= lastServerRefresh }
            val serverSeen = maxOf(j.optLong("last_heartbeat_ms", 0L), j.optLong("last_seen_ms", 0L))
            out += ChatSummary(
                id = id,
                title = title,
                isGroup = isGroup,
                peer = peer,
                color = color,
                lastText = last?.let { visibleText(it, kind) }.orEmpty().take(200),
                lastKind = kind,
                lastAt = maxOf(j.optLong("last_message_at", 0L), last?.timestampMs ?: 0L),
                lastFromMe = last?.senderHash == me,
                lastStatus = if (last?.senderHash == me) last.status else "",
                unread = unread,
                muted = MuteSettings.isMuted(ctx, id),
                online = !cut && (live?.online ?: j.optBoolean("is_online", false)),
                lastSeen = if (cut) 0L else live?.lastSeen ?: localTime(serverSeen),
                avatar = avatarToken(ctx, if (isGroup) id else peer),
                pinnedAt = pins[id] ?: 0L,
                blocked = !isGroup && BlockList.isBlocked(ctx, peer)
            )
        }
        val (top, rest) = out.sortedByDescending { it.lastAt }.partition { it.pinnedAt > 0L }
        return top.sortedByDescending { it.pinnedAt } + rest
    }

    private fun pushChats(target: LinkConnection? = null) {
        val ctx = ctx() ?: return
        if (sessions.isEmpty() || isLocked()) return
        val frame = LinkFrame.Chats(buildChats(ctx), PrivacySettings.isHideLastMessageEnabled(ctx))
        if (target != null) target.send(frame) else broadcast(frame)
    }

    private fun toMessage(ctx: Context, e: MessageEntity, me: String, names: Map<String, String>?): ChatMessage {
        val kind = kindOf(e)
        val mine = e.senderHash == me
        val senderName = if (mine || kind == ChatMessage.KIND_SYSTEM) ""
        else names?.get(e.senderHash) ?: ContactCache.getName(ctx, e.senderHash) ?: ""
        val shown = if (e.type == MessageEntity.TYPE_TEXT) pl.sinusoide.sinuschat.util.Translations.view(e) else null
        return ChatMessage(
            sysType = if (kind == ChatMessage.KIND_SYSTEM) (e.fileId ?: e.type).orEmpty() else "",
            id = e.id,
            conv = e.conversationId,
            sender = e.senderHash,
            senderName = senderName,
            mine = mine,
            text = visibleText(e, kind),
            kind = kind,
            fileName = e.attachmentName,
            at = e.timestampMs,
            status = if (mine) e.status else "",
            replyTo = e.replyToId,
            replyText = e.replyToText,
            edited = e.edited,
            reactions = e.reactions,
            viewOnce = e.viewOnce,
            viewed = e.viewed,
            expiresAt = e.expiresAtMs,
            pinned = e.pinned,
            size = PcLinkMedia.sizeOf(ctx, e),
            durationMs = PcLinkMedia.durationOf(ctx, e),
            albumId = e.albumId,
            hasThumb = !e.viewOnce && PcLinkMedia.hasThumb(ctx, e, kind),
            senderColor = if (mine || kind == ChatMessage.KIND_SYSTEM || e.senderHash.isEmpty()) ""
                else AvatarHelper.getContactColor(ctx, e.senderHash),
            trShown = shown?.translated,
            trOriginal = shown?.original,
            trFrom = shown?.from.orEmpty(),
            trTo = shown?.to.orEmpty()
        )
    }

    private fun attachmentTarget(ctx: Context, conv: String): Pair<pl.sinusoide.sinuschat.messenger.AttachmentSender.Target?, String?> {
        val me = me(ctx)
        if (GroupRepository.isGroup(conv)) {
            if (groupReadOnly[conv] == true) return null to LinkFrame.ERROR_READ_ONLY
            val recipients = (groupNames[conv]?.size?.minus(1) ?: 50).coerceIn(1, 500)
            return pl.sinusoide.sinuschat.messenger.AttachmentSender.Target(conv, me, "", null, true, recipients) to null
        }
        val other = otherOf(conv, me) ?: return null to LinkFrame.ERROR_FAILED
        if (BlockList.isBlocked(ctx, other)) return null to LinkFrame.ERROR_BLOCKED
        if (BlockList.isDeleted(ctx, other)) return null to LinkFrame.ERROR_DELETED
        val secret = secretFor(ctx, other) ?: return null to LinkFrame.ERROR_NO_KEY
        return pl.sinusoide.sinuschat.messenger.AttachmentSender.Target(conv, me, other, secret, false, 1) to null
    }

    private fun alive(list: List<MessageEntity>): List<MessageEntity> {
        val now = System.currentTimeMillis()
        return list.filter { it.expiresAtMs == null || it.expiresAtMs > now }
    }

    private fun pushLatest(conv: String, target: LinkConnection?) {
        val ctx = ctx() ?: return
        if (isLocked()) return
        val receivers = if (target != null) listOfNotNull(sessions[target])
        else sessions.values.filter { it.openConv == conv }
        if (receivers.isEmpty()) return
        val dao = SinusChatDatabase.getInstance(ctx).messageDao()
        val me = me(ctx)
        val limit = receivers.maxOf { it.openLimit }
        val rows = dao.getLatest(conv, limit + 1)
        val more = rows.size > limit
        val names = groupNames[conv]
        val items = alive(rows.take(limit)).asReversed().map { toMessage(ctx, it, me, names) }
        val frame = LinkFrame.Messages(conv, items, LinkFrame.MODE_LATEST, more)
        for (s in receivers) s.connection.send(frame)
        PcLinkTranslate.scan(ctx, conv, me, rows) { c, lang ->
            val to = pl.sinusoide.sinuschat.util.Translations.appLang(ctx)
            for (s in sessions.values.filter { it.openConv == c }) s.connection.send(LinkFrame.TranslateOffer(c, lang, to))
        }
    }

    private fun pushOlder(f: LinkFrame.OlderMessages, conn: LinkConnection) {
        val ctx = ctx() ?: return
        val dao = SinusChatDatabase.getInstance(ctx).messageDao()
        val me = me(ctx)
        val limit = f.limit.coerceIn(1, MAX_LIMIT)
        val rows = dao.getBefore(f.conv, f.before, limit + 1)
        val items = alive(rows.take(limit)).asReversed().map { toMessage(ctx, it, me, groupNames[f.conv]) }
        conn.send(LinkFrame.Messages(f.conv, items, LinkFrame.MODE_OLDER, rows.size > limit))
    }

    private fun pushIncoming() {
        val ctx = ctx() ?: return
        if (sessions.isEmpty() || isLocked() || notifyFrom == 0L) return
        val me = me(ctx)
        val since = maxOf(notifyFrom, System.currentTimeMillis() - NOTIFY_LOOKBACK_MS)
        val rows = try { SinusChatDatabase.getInstance(ctx).messageDao().getIncomingSince(me, since) } catch (_: Exception) { return }
        val preview = when (NotificationPrefs.preview(ctx)) {
            NotificationPrefs.Preview.FULL -> LinkFrame.PREVIEW_FULL
            NotificationPrefs.Preview.NAME -> LinkFrame.PREVIEW_NAME
            NotificationPrefs.Preview.HIDDEN -> LinkFrame.PREVIEW_HIDDEN
        }
        for (e in rows) {
            synchronized(notified) {
                if (notified.containsKey(e.id)) return@synchronized null
                notified[e.id] = true
                e
            } ?: continue
            val conv = e.conversationId
            val isGroup = GroupRepository.isGroup(conv)
            if (MuteSettings.isMuted(ctx, conv)) continue
            if (isGroup && !NotificationPrefs.groupsEnabled(ctx)) continue
            if (!isGroup && !NotificationPrefs.messagesEnabled(ctx)) continue
            if (BlockList.isBlocked(ctx, e.senderHash)) continue
            val kind = kindOf(e)
            val sender = groupNames[conv]?.get(e.senderHash) ?: ContactCache.getName(ctx, e.senderHash) ?: ""
            val title = if (isGroup) ContactCache.getName(ctx, conv) ?: groupTitle(ctx, conv) else sender
            broadcast(LinkFrame.Incoming(
                conv = conv,
                id = e.id,
                title = title,
                sender = sender,
                text = if (preview == LinkFrame.PREVIEW_FULL) visibleText(e, kind).take(300) else "",
                kind = kind,
                at = e.timestampMs,
                preview = preview
            ))
        }
    }

    private fun groupTitle(ctx: Context, conv: String): String {
        val arr = ConversationCache.load(ctx) ?: return ""
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("id") == conv) return o.optStringOrNull("group_name").orEmpty()
        }
        return ""
    }

    private fun loadGroupInfo(conv: String) {
        val ctx = ctx() ?: return
        val now = System.currentTimeMillis()
        if (now - (groupInfoAt[conv] ?: 0L) < GROUP_INFO_MS) return
        groupInfoAt[conv] = now
        GroupRepository.getGroupInfo(ctx, conv) { info ->
            if (info == null) {
                groupInfoAt.remove(conv)
                return@getGroupInfo
            }
            val me = me(ctx)
            groupNames[conv] = info.members.associate { it.userHash to it.displayName }
            groupReadOnly[conv] = !(info.amIAdmin || info.perms.sendMessages)
            ContactCache.saveName(ctx, conv, info.name)
            net.execute {
                try {
                    pl.sinusoide.sinuschat.crypto.GroupKeyManager.distributeMyKey(
                        ctx, conv, me, info.members.map { it.userHash })
                } catch (e: Exception) {
                    Log.w(TAG, "klucz grupy: ${e.message}")
                }
            }
            scheduleChat(conv)
        }
    }

    private fun otherOf(conv: String, me: String): String? =
        conv.split('_').takeIf { it.size == 2 && me in it }?.firstOrNull { it != me }?.takeIf { it.isNotEmpty() }

    private fun secretFor(ctx: Context, other: String): ByteArray? {
        E2EManager.getSharedSecretCached(ctx, other)?.let { return it }
        val result = AtomicReference<ByteArray?>(null)
        val latch = CountDownLatch(1)
        E2EManager.getSharedSecretForUser(ctx, other) { secret ->
            result.set(secret)
            latch.countDown()
        }
        latch.await(20, TimeUnit.SECONDS)
        return result.get()
    }

    private val uuidPattern = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
    private val recentSends = object : LinkedHashMap<String, Boolean>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?) = size > 300
    }

    private fun sendText(f: LinkFrame.SendText, tr: PcLinkTranslate.SendOffer? = null): LinkFrame.SendResult {
        val ctx = ctx() ?: return LinkFrame.SendResult(f.id, false, LinkFrame.ERROR_FAILED)
        val text = f.text.trim().take(MAX_TEXT)
        if (text.isEmpty() || f.conv.isEmpty()) return LinkFrame.SendResult(f.id, false, LinkFrame.ERROR_FAILED)
        val me = me(ctx)
        val dao = SinusChatDatabase.getInstance(ctx).messageDao()
        val localId = f.id.lowercase().takeIf { uuidPattern.matches(it) }
        if (localId != null) {
            if (synchronized(recentSends) { recentSends.containsKey(localId) } || dao.getById(localId) != null)
                return LinkFrame.SendResult(f.id, true)
            synchronized(recentSends) { recentSends[localId] = true }
        }
        val reply = f.replyTo?.let { dao.getById(it) }
        val replyText = reply?.let { ReplyLabel.of(ctx, it) }

        if (GroupRepository.isGroup(f.conv)) {
            if (groupReadOnly[f.conv] == true) return LinkFrame.SendResult(f.id, false, LinkFrame.ERROR_READ_ONLY)
            val replyId = reply?.id?.removeSuffix("_" + me.take(16))
            if (!GroupRepository.sendTextBlocking(ctx, f.conv, me, text, replyId, replyText, localId))
                return LinkFrame.SendResult(f.id, false, LinkFrame.ERROR_FAILED)
            MessageRepository.publishRead(ctx, f.conv, me, System.currentTimeMillis())
            return LinkFrame.SendResult(f.id, true)
        }

        val other = otherOf(f.conv, me) ?: return LinkFrame.SendResult(f.id, false, LinkFrame.ERROR_FAILED)
        if (BlockList.isBlocked(ctx, other)) return LinkFrame.SendResult(f.id, false, LinkFrame.ERROR_BLOCKED)
        if (BlockList.isDeleted(ctx, other)) return LinkFrame.SendResult(f.id, false, LinkFrame.ERROR_DELETED)
        val secret = secretFor(ctx, other) ?: return LinkFrame.SendResult(f.id, false, LinkFrame.ERROR_NO_KEY)
        val wire = tr?.takeIf { it.original == text }
        MessageRepository.sendText(ctx, f.conv, me, other, secret, wire?.translated ?: text, reply?.id, replyText,
            translation = wire?.let { pl.sinusoide.sinuschat.util.Translations.Wire(it.original, it.from, it.to) }, id = localId)
        return LinkFrame.SendResult(f.id, true)
    }

    private fun markRead(conv: String) {
        val ctx = ctx() ?: return
        val me = me(ctx)
        val dao = SinusChatDatabase.getInstance(ctx).messageDao()
        MessageNotifier.dismiss(ctx, conv)
        val last = dao.getLastIncoming(conv, me, 1).firstOrNull()?.timestampMs ?: return
        if (last > LocalChats.readMark(ctx, conv)) {
            MessageRepository.publishRead(ctx, conv, me, last)
            MessageRepository.publishDelivered(ctx, conv, me, last)
        }
        DisappearingMessages.applyOnRead(ctx, conv, me)
        scheduleList(refresh = false)
    }

    private fun sendTyping(conv: String, typing: Boolean) {
        val ctx = ctx() ?: return
        if (PrivacySettings.isTypingStatusDisabled(ctx) || conv.isEmpty()) return
        val me = me(ctx)
        val isGroup = GroupRepository.isGroup(conv)
        val other = if (isGroup) null else otherOf(conv, me) ?: return
        try {
            if (other != null && pl.sinusoide.sinuschat.crypto.SealedSender.sendTyping(ctx, conv, other, typing)) return
            val msg = org.json.JSONObject()
                .put("type", if (typing) "typing" else "typing_stop")
                .put("conversationId", conv)
            if (other != null) msg.put("recipientHash", other)
            WebSocketManager.send(msg)
        } catch (_: Exception) {
        }
    }
}
