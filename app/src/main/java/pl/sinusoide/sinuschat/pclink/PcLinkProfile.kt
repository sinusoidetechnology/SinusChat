package pl.sinusoide.sinuschat.pclink

import android.content.Context
import android.net.Uri
import android.util.Log
import org.json.JSONObject
import pl.sinusoide.sinuschat.crypto.ProfileCrypto
import pl.sinusoide.sinuschat.link.LinkFrame
import pl.sinusoide.sinuschat.network.ApiClient
import pl.sinusoide.sinuschat.setup.SetupActivity
import pl.sinusoide.sinuschat.util.AvatarHelper
import pl.sinusoide.sinuschat.util.ContactCache
import pl.sinusoide.sinuschat.util.ConversationCache
import pl.sinusoide.sinuschat.util.FileUploader
import pl.sinusoide.sinuschat.util.ThemeManager
import java.io.File

object PcLinkProfile {

    private const val TAG = "PcLinkProfile"

    fun frame(ctx: Context): LinkFrame.Profile = LinkFrame.Profile(
        name = SetupActivity.getUserDisplayName(ctx),
        avatar = avatarToken(ctx),
        color = AvatarHelper.getMyColor(ctx),
        theme = ThemeManager.getCurrent(ctx)
    )

    fun avatarFile(ctx: Context): File? = AvatarHelper.getLocalAvatarPath(ctx)?.let { File(it) }?.takeIf { it.isFile }

    private fun avatarToken(ctx: Context): String {
        val f = avatarFile(ctx) ?: return ""
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest("${f.length()}:${f.lastModified()}".toByteArray())
        return digest.take(6).joinToString("") { "%02x".format(it) }
    }

    fun cleanName(raw: String): String? {
        val clean = raw.filter { !it.isISOControl() }.replace(Regex("\\s+"), " ").trim()
        return clean.takeIf { it.length in LinkFrame.NAME_MIN..LinkFrame.NAME_MAX }
    }

    fun rename(ctx: Context, raw: String): Boolean {
        val name = cleanName(raw) ?: return false
        if (name == SetupActivity.getUserDisplayName(ctx)) return true
        ApiClient.post("/api/user/update", JSONObject().put("displayName", ProfileCrypto.sealName(ctx, name)), ctx)
            ?: return false
        SetupActivity.setUserDisplayName(ctx, name)
        ProfileCrypto.announceProfileChange(ctx)
        refreshPhoneUi(ctx)
        return true
    }

    fun setAvatar(ctx: Context, file: File): Boolean {
        try {
            if (AvatarHelper.saveAvatarLocally(ctx, Uri.fromFile(file)) == null) return false
            AvatarHelper.getLocalAvatarPath(ctx)?.let { SetupActivity.setUserAvatarUri(ctx, it) }
            PcLinkHub.onProfileChanged()
            refreshPhoneUi(ctx)
            val local = avatarFile(ctx) ?: return false
            val remote = FileUploader.uploadAvatarBlocking(ctx, local) ?: return false
            val versioned = AvatarHelper.versionedUrl(remote)
            ApiClient.post("/api/user/update", JSONObject()
                .put("avatarUrl", versioned)
                .put("avatarUpdatedAt", System.currentTimeMillis()), ctx) ?: return false
            ContactCache.saveAvatarUrl(ctx, SetupActivity.getUserPhoneHash(ctx), versioned)
            ConversationCache.clear(ctx)
            ProfileCrypto.announceProfileChange(ctx)
            return true
        } catch (e: Exception) {
            Log.w(TAG, "zdjęcie z komputera: ${e.message}")
            return false
        } finally {
            file.delete()
        }
    }

    fun setColor(ctx: Context, color: String): Boolean {
        val hex = AvatarHelper.PALETTE.firstOrNull { it.equals(color.trim(), ignoreCase = true) } ?: return false
        AvatarHelper.setMyColor(ctx, hex)
        refreshPhoneUi(ctx)
        return ApiClient.post("/api/user/update", JSONObject().put("avatarColor", hex), ctx) != null
    }

    fun removeAvatar(ctx: Context): Boolean {
        AvatarHelper.deleteLocalAvatar(ctx)
        PcLinkHub.onProfileChanged()
        refreshPhoneUi(ctx)
        val ok = ApiClient.post("/api/user/update", JSONObject().put("avatarUrl", ""), ctx)
        ContactCache.saveAvatarUrl(ctx, SetupActivity.getUserPhoneHash(ctx), null)
        ConversationCache.clear(ctx)
        if (ok != null) ProfileCrypto.announceProfileChange(ctx)
        return ok != null
    }

    private fun refreshPhoneUi(ctx: Context) {
        pl.sinusoide.sinuschat.util.AppExecutor.main {
            ctx.sendBroadcast(android.content.Intent(ACTION_PROFILE_CHANGED).setPackage(ctx.packageName))
        }
    }

    const val ACTION_PROFILE_CHANGED = "pl.sinusoide.sinuschat.PROFILE_CHANGED"
}
