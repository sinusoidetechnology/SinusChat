package pl.sinusoide.sinuschat.messenger

import pl.sinusoide.sinuschat.util.MessageNotifier

class BubbleChatActivity : ChatActivity() {

    private fun bubbleConversation(): String? = intent?.getStringExtra("conversationId")?.takeIf { it.isNotEmpty() }

    override fun onResume() {
        MessageNotifier.openBubble = bubbleConversation()
        super.onResume()
    }

    override fun onPause() {
        super.onPause()
        val conv = bubbleConversation()
        if (MessageNotifier.openBubble == conv) MessageNotifier.openBubble = null
        if (conv != null && !isChangingConfigurations) MessageNotifier.dismiss(this, conv)
    }
}
