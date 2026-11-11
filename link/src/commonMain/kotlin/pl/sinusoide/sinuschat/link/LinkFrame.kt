package pl.sinusoide.sinuschat.link

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class ChatSummary(
    val id: String,
    val title: String,
    val isGroup: Boolean = false,
    val peer: String = "",
    val color: String = "#5288C1",
    val lastText: String = "",
    val lastKind: String = ChatMessage.KIND_TEXT,
    val lastAt: Long = 0L,
    val lastFromMe: Boolean = false,
    val lastStatus: String = "",
    val unread: Int = 0,
    val muted: Boolean = false,
    val online: Boolean = false,
    val lastSeen: Long = 0L,
    val avatar: String = "",
    val pinnedAt: Long = 0L,
    val blocked: Boolean = false
)

@Serializable
data class TranslateLang(val code: String, val ready: Boolean, val mb: Int = 0)

@Serializable
data class TranslateChat(val conv: String, val name: String, val lang: String = "", val yes: Boolean = false)

@Serializable
data class IceServer(val urls: List<String>, val username: String = "", val credential: String = "")

@Serializable
data class ChatMessage(
    val id: String,
    val conv: String,
    val sender: String,
    val senderName: String = "",
    val mine: Boolean = false,
    val text: String = "",
    val kind: String = KIND_TEXT,
    val fileName: String? = null,
    val at: Long,
    val status: String = "",
    val replyTo: String? = null,
    val replyText: String? = null,
    val edited: Boolean = false,
    val reactions: Map<String, String> = emptyMap(),
    val viewOnce: Boolean = false,
    val expiresAt: Long? = null,
    val pinned: Boolean = false,
    val size: Long = 0L,
    val durationMs: Long = 0L,
    val albumId: String? = null,
    val hasThumb: Boolean = false,
    val sysType: String = "",
    val viewed: Boolean = false,
    val senderColor: String = "",
    val trShown: String? = null,
    val trOriginal: String? = null,
    val trFrom: String = "",
    val trTo: String = ""
) {
    companion object {
        const val KIND_TEXT = "text"
        const val KIND_SYSTEM = "system"
        const val KIND_PHOTO = "photo"
        const val KIND_VIDEO = "video"
        const val KIND_VOICE = "voice"
        const val KIND_FILE = "file"
        const val KIND_LOCATION = "location"

        const val STATUS_PENDING = "pending"
        const val STATUS_SENT = "sent"
        const val STATUS_DELIVERED = "delivered"
        const val STATUS_READ = "read"
        const val STATUS_FAILED = "failed"
        const val STATUS_UPLOADING = "uploading"
    }
}

@Serializable
sealed class LinkFrame {

    @Serializable
    @SerialName("hello")
    data class Hello(val name: String, val app: String, val protocol: Int = LinkProtocol.VERSION) : LinkFrame()

    @Serializable
    @SerialName("ping")
    data class Ping(val id: Long) : LinkFrame()

    @Serializable
    @SerialName("pong")
    data class Pong(val id: Long) : LinkFrame()

    @Serializable
    @SerialName("bye")
    data class Bye(val reason: String) : LinkFrame()

    @Serializable
    @SerialName("chats_get")
    data class ChatsRequest(val refresh: Boolean = false) : LinkFrame()

    @Serializable
    @SerialName("chat_open")
    data class OpenChat(val conv: String, val limit: Int = 80) : LinkFrame()

    @Serializable
    @SerialName("chat_close")
    data class CloseChat(val conv: String) : LinkFrame()

    @Serializable
    @SerialName("chat_older")
    data class OlderMessages(val conv: String, val before: Long, val limit: Int = 80) : LinkFrame()

    @Serializable
    @SerialName("send_text")
    data class SendText(val id: String, val conv: String, val text: String, val replyTo: String? = null) : LinkFrame()

    @Serializable
    @SerialName("mark_read")
    data class MarkRead(val conv: String) : LinkFrame()

    @Serializable
    @SerialName("typing_out")
    data class TypingOut(val conv: String, val typing: Boolean) : LinkFrame()

    @Serializable
    @SerialName("chats")
    data class Chats(val items: List<ChatSummary>, val hidePreviews: Boolean = false) : LinkFrame()

    @Serializable
    @SerialName("messages")
    data class Messages(
        val conv: String,
        val items: List<ChatMessage>,
        val mode: String = MODE_LATEST,
        val more: Boolean = false
    ) : LinkFrame()

    @Serializable
    @SerialName("send_result")
    data class SendResult(val id: String, val ok: Boolean, val error: String? = null) : LinkFrame()

    @Serializable
    @SerialName("phone_state")
    data class PhoneState(val locked: Boolean) : LinkFrame()

    @Serializable
    @SerialName("incoming")
    data class Incoming(
        val conv: String,
        val id: String,
        val title: String,
        val sender: String,
        val text: String,
        val kind: String = ChatMessage.KIND_TEXT,
        val at: Long,
        val preview: String = PREVIEW_FULL
    ) : LinkFrame()

    @Serializable
    @SerialName("typing_in")
    data class TypingIn(val conv: String, val name: String, val typing: Boolean) : LinkFrame()

    @Serializable
    @SerialName("media_get")
    data class MediaRequest(val req: String, val conv: String, val msg: String, val kind: String) : LinkFrame()

    @Serializable
    @SerialName("media_cancel")
    data class MediaCancel(val req: String) : LinkFrame()

    @Serializable
    @SerialName("media_start")
    data class MediaStart(val req: String, val name: String, val mime: String, val size: Long) : LinkFrame()

    @Serializable
    @SerialName("media_chunk")
    data class MediaChunk(val req: String, val seq: Int, val data: String) : LinkFrame()

    @Serializable
    @SerialName("media_end")
    data class MediaEnd(val req: String) : LinkFrame()

    @Serializable
    @SerialName("media_error")
    data class MediaError(val req: String, val error: String) : LinkFrame()

    @Serializable
    @SerialName("upload_start")
    data class UploadStart(
        val req: String,
        val batch: String,
        val name: String,
        val mime: String,
        val size: Long,
        val viewOnce: Boolean = false,
        val original: Boolean = false
    ) : LinkFrame()

    @Serializable
    @SerialName("upload_chunk")
    data class UploadChunk(val req: String, val seq: Int, val data: String) : LinkFrame()

    @Serializable
    @SerialName("upload_end")
    data class UploadEnd(val req: String) : LinkFrame()

    @Serializable
    @SerialName("upload_commit")
    data class UploadCommit(
        val batch: String,
        val conv: String,
        val reqs: List<String>,
        val caption: String = "",
        val replyTo: String? = null
    ) : LinkFrame()

    @Serializable
    @SerialName("upload_cancel")
    data class UploadCancel(val batch: String) : LinkFrame()

    @Serializable
    @SerialName("upload_result")
    data class UploadResult(
        val batch: String,
        val ok: Boolean,
        val error: String? = null,
        val ids: List<String> = emptyList()
    ) : LinkFrame()

    @Serializable
    @SerialName("send_progress")
    data class SendProgress(val conv: String, val items: Map<String, Float> = emptyMap()) : LinkFrame()

    @Serializable
    @SerialName("presence")
    data class Presence(val peer: String, val online: Boolean, val lastSeen: Long = 0L) : LinkFrame()

    @Serializable
    @SerialName("activity")
    data class Activity(val active: Boolean) : LinkFrame()

    @Serializable
    @SerialName("delete_messages")
    data class DeleteMessages(val conv: String, val ids: List<String>) : LinkFrame()

    @Serializable
    @SerialName("messages_deleted")
    data class MessagesDeleted(val conv: String, val ids: List<String>) : LinkFrame()

    @Serializable
    @SerialName("pin_message")
    data class PinMessage(val conv: String, val id: String, val pinned: Boolean) : LinkFrame()

    @Serializable
    @SerialName("react")
    data class ReactMessage(val conv: String, val id: String, val emoji: String = "") : LinkFrame()

    @Serializable
    @SerialName("edit_message")
    data class EditMessage(val conv: String, val id: String, val text: String) : LinkFrame()

    @Serializable
    @SerialName("pinned_get")
    data class PinnedRequest(val conv: String) : LinkFrame()

    @Serializable
    @SerialName("pinned")
    data class PinnedList(val conv: String, val items: List<ChatMessage>) : LinkFrame()

    @Serializable
    @SerialName("profile")
    data class Profile(
        val name: String,
        val avatar: String = "",
        val color: String = "",
        val theme: String = ""
    ) : LinkFrame()

    @Serializable
    @SerialName("profile_name")
    data class SetProfileName(val name: String) : LinkFrame()

    @Serializable
    @SerialName("profile_avatar")
    data class SetProfileAvatar(val req: String = "", val remove: Boolean = false) : LinkFrame()

    @Serializable
    @SerialName("profile_color")
    data class SetProfileColor(val color: String) : LinkFrame()

    @Serializable
    @SerialName("profile_result")
    data class ProfileResult(val field: String, val ok: Boolean) : LinkFrame()

    @Serializable
    @SerialName("tr_offer")
    data class TranslateOffer(val conv: String, val lang: String, val to: String) : LinkFrame()

    @Serializable
    @SerialName("tr_decision")
    data class TranslateDecision(val conv: String, val lang: String, val yes: Boolean) : LinkFrame()

    @Serializable
    @SerialName("tr_send_offer")
    data class TranslateSendOffer(
        val id: String,
        val conv: String,
        val original: String,
        val translated: String,
        val from: String,
        val to: String
    ) : LinkFrame()

    @Serializable
    @SerialName("tr_send_choice")
    data class TranslateSendChoice(val id: String, val translated: Boolean, val cancel: Boolean = false) : LinkFrame()

    @Serializable
    @SerialName("tr_info_get")
    data class TranslateInfoRequest(val refresh: Boolean = true) : LinkFrame()

    @Serializable
    @SerialName("tr_info")
    data class TranslateInfo(
        val supported: Boolean,
        val enabled: Boolean,
        val askSend: Boolean,
        val appLang: String,
        val langs: List<TranslateLang> = emptyList(),
        val blocked: List<TranslateChat> = emptyList(),
        val decisions: List<TranslateChat> = emptyList()
    ) : LinkFrame()

    @Serializable
    @SerialName("tr_set")
    data class TranslateSet(
        val enabled: Boolean? = null,
        val askSend: Boolean? = null,
        val unblock: String? = null,
        val conv: String? = null,
        val lang: String? = null,
        val yes: Boolean? = null
    ) : LinkFrame()

    @Serializable
    @SerialName("desktop_diag_get")
    data class DesktopDiagRequest(val req: String) : LinkFrame()

    @Serializable
    @SerialName("desktop_diag")
    data class DesktopDiag(
        val req: String,
        val text: String,
        val appVersion: String = "",
        val device: String = "",
        val os: String = ""
    ) : LinkFrame()

    @Serializable
    @SerialName("open_on_phone")
    data class OpenOnPhone(val section: String) : LinkFrame()

    @Serializable
    @SerialName("chat_action")
    data class ChatAction(val conv: String, val action: String, val until: Long = 0L) : LinkFrame()

    @Serializable
    @SerialName("voice_commit")
    data class VoiceCommit(val batch: String, val conv: String, val req: String) : LinkFrame()

    @Serializable
    @SerialName("contact_get")
    data class ContactRequest(val conv: String) : LinkFrame()

    @Serializable
    @SerialName("contact")
    data class ContactInfo(
        val conv: String,
        val peer: String,
        val name: String,
        val realName: String = "",
        val nick: String = "",
        val muteUntil: Long = 0L,
        val ttlMs: Long = 0L,
        val trigger: String = "",
        val awaitingTtl: Long = -1L,
        val blocked: Boolean = false,
        val translate: Boolean = false,
        val translateBlocked: Boolean = false
    ) : LinkFrame()

    @Serializable
    @SerialName("contact_set")
    data class ContactUpdate(
        val conv: String,
        val nick: String? = null,
        val muteUntil: Long? = null,
        val ttlMs: Long? = null,
        val trigger: String? = null,
        val translateBlocked: Boolean? = null,
        val blocked: Boolean? = null,
        val verified: Boolean? = null,
        val clearChat: Boolean = false
    ) : LinkFrame()

    @Serializable
    @SerialName("safety_get")
    data class SafetyRequest(val conv: String) : LinkFrame()

    @Serializable
    @SerialName("safety")
    data class SafetyNumber(val conv: String, val rows: List<String> = emptyList(), val verified: Boolean = false, val ok: Boolean = true) : LinkFrame()

    @Serializable
    @SerialName("media_list_get")
    data class MediaListRequest(val conv: String, val limit: Int = 300) : LinkFrame()

    @Serializable
    @SerialName("media_list")
    data class MediaList(val conv: String, val items: List<ChatMessage>) : LinkFrame()

    @Serializable
    @SerialName("avatar_changed")
    data class AvatarChanged(val key: String, val token: String) : LinkFrame()

    @Serializable
    @SerialName("call_start")
    data class CallStart(val call: String, val conv: String, val video: Boolean) : LinkFrame()

    @Serializable
    @SerialName("call_ring")
    data class CallRing(
        val call: String,
        val conv: String,
        val name: String,
        val video: Boolean,
        val ring: String = "",
        val volume: Int = -1
    ) : LinkFrame()

    @Serializable
    @SerialName("call_accept")
    data class CallAccept(val call: String) : LinkFrame()

    @Serializable
    @SerialName("call_setup")
    data class CallSetup(
        val call: String,
        val conv: String,
        val name: String,
        val video: Boolean,
        val caller: Boolean,
        val ice: List<IceServer>,
        val relayOnly: Boolean = false,
        val offer: String? = null,
        val ringback: Boolean = true
    ) : LinkFrame()

    @Serializable
    @SerialName("call_signal")
    data class CallSignal(val call: String, val type: String, val body: String) : LinkFrame()

    @Serializable
    @SerialName("proximity")
    data class Proximity(val on: Boolean) : LinkFrame()

    @Serializable
    @SerialName("proximity_state")
    data class ProximityState(val state: String) : LinkFrame()

    @Serializable
    @SerialName("proximity_setup")
    data class ProximitySetup(val step: String, val rssi: Int = 0, val decimeters: Int = 0) : LinkFrame()

    @Serializable
    @SerialName("call_end")
    data class CallEnd(val call: String, val reason: String = "", val seconds: Int = 0, val connected: Boolean = false) : LinkFrame()

    companion object {
        const val BYE_UNPAIRED = "unpaired"
        const val BYE_CLOSING = "closing"
        const val BYE_PAUSED = "paused"

        const val MODE_LATEST = "latest"
        const val MODE_OLDER = "older"

        const val PREVIEW_FULL = "full"
        const val PREVIEW_NAME = "name"
        const val PREVIEW_HIDDEN = "hidden"

        const val ERROR_BLOCKED = "blocked"
        const val ERROR_DELETED = "deleted"
        const val ERROR_NO_KEY = "no_key"
        const val ERROR_READ_ONLY = "read_only"
        const val ERROR_LOCKED = "locked"
        const val ERROR_FAILED = "failed"
        const val ERROR_GONE = "gone"
        const val ERROR_TOO_BIG = "too_big"

        const val MEDIA_THUMB = "thumb"
        const val MEDIA_FILE = "file"
        const val MEDIA_VOICE = "voice"
        const val MEDIA_AVATAR = "avatar"
        const val MEDIA_VIEW_ONCE = "view_once"
        const val SEND_PREPARING = -1f
        const val ERROR_OPENED = "opened"

        const val AVATAR_ME = "me"
        const val PROFILE_NAME = "name"
        const val PROFILE_AVATAR = "avatar"
        const val PROFILE_COLOR = "color"
        const val NAME_MIN = 2
        const val NAME_MAX = 32
        const val AVATAR_MAX_BYTES = 15L * 1024 * 1024
        const val MUTE_FOREVER = Long.MAX_VALUE
        const val SECTION_ACCOUNT = "account"
        const val SECTION_PRIVACY = "privacy"
        const val SECTION_INTERNET = "internet"
        const val SECTION_BACKUP = "backup"
        const val SECTION_REPORT_DESKTOP = "report_desktop"
        const val CHAT_PIN = "pin"
        const val CHAT_UNPIN = "unpin"
        const val CHAT_MUTE = "mute"
        const val CHAT_BLOCK = "block"
        const val CHAT_BLOCK_DELETE = "block_delete"
        const val CHAT_UNBLOCK = "unblock"
        const val CHAT_DELETE = "delete"
        const val VOICE_RATE = 16_000
        const val VOICE_MAX_MS = 20 * 60_000L
        const val ERROR_TOO_SHORT = "too_short"
        const val ERROR_CANCELLED = "cancelled"
        const val ERROR_GROUP_VOICE = "group_voice"
        const val TRIGGER_READ = "read"

        const val CALL_HANGUP = "hangup"
        const val CALL_DECLINED = "declined"
        const val CALL_PEER_HUNG_UP = "peer_hung_up"
        const val CALL_PEER_REJECTED = "peer_rejected"
        const val CALL_NO_ANSWER = "no_answer"
        const val CALL_BUSY = "busy"
        const val CALL_FAILED = "failed"
        const val CALL_ON_PHONE = "on_phone"
        const val CALL_GROUP = "group"

        const val PROXIMITY_ON = "on"
        const val PROXIMITY_OFF = "off"
        const val PROXIMITY_PERMISSION = "permission"
        const val PROXIMITY_BLUETOOTH_OFF = "bluetooth_off"
        const val PROXIMITY_UNSUPPORTED = "unsupported"
        const val SETUP_START = "start"
        const val SETUP_BLUETOOTH = "bluetooth"
        const val SETUP_NEARBY = "nearby"
        const val SETUP_READY = "ready"
        const val SETUP_CONNECTED = "connected"
        const val SETUP_TEST = "test"
        const val SETUP_SKIP = "skip"
        const val SETUP_SIGNAL = "signal"
        const val SETUP_LOCKED = "locked"
        const val SETUP_DISTANCE = "distance"
        const val SETUP_DONE = "done"
        const val SETUP_CANCEL = "cancel"

        const val CHUNK_BYTES = 192 * 1024
        const val MAX_UPLOAD_BYTES = 150L * 1024 * 1024

        val json = Json {
            classDiscriminator = "t"
            ignoreUnknownKeys = true
            encodeDefaults = false
        }

        fun encode(frame: LinkFrame): String = json.encodeToString(serializer(), frame)

        fun decode(text: String): LinkFrame? = try {
            json.decodeFromString(serializer(), text)
        } catch (_: Exception) {
            null
        }
    }
}
