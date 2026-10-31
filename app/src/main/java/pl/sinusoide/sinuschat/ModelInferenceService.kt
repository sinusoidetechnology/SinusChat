package pl.sinusoide.sinuschat

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Messenger
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.SamplerConfig
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors

typealias AndroidMessage = android.os.Message

class ModelInferenceService : Service() {

    companion object {
        const val TAG = "ModelInference"
        const val MSG_LOAD_MODEL   = 1
        const val MSG_SEND_MESSAGE = 2
        const val MSG_CLOSE        = 3
        const val MSG_STOP         = 4
        const val MSG_RESET_CHAT   = 5
        const val MSG_LOAD_OK      = 10
        const val MSG_LOAD_ERROR   = 11
        const val MSG_PARTIAL      = 12
        const val MSG_DONE         = 13
        const val MSG_SEND_ERROR   = 14
        const val MSG_RESET_OK     = 15
        const val KEY_MODEL_PATH    = "model_path"
        const val KEY_SYSTEM_PROMPT = "system_prompt"
        const val KEY_TEXT         = "text"
        const val KEY_IMAGE_PATH   = "image_path"
        const val KEY_ERROR        = "error"
        const val KEY_BACKEND      = "backend"
        const val KEY_SUPPORT_IMAGE = "support_image"
        const val KEY_TEMPERATURE   = "temperature"
        const val KEY_TOP_K         = "top_k"
        const val KEY_TOP_P         = "top_p"
        const val KEY_MAX_TOKENS    = "max_tokens"
        const val KEY_HISTORY_ROLES = "history_roles"
        const val KEY_HISTORY_TEXTS = "history_texts"
        const val KEY_THINKING      = "thinking"
        const val KEY_THOUGHT       = "thought"
        private const val THOUGHT_CHANNEL = "thought"
    }

    private var engine       : Engine?       = null
    private var conversation : Conversation? = null
    private val executor     = Executors.newSingleThreadExecutor()

    private var samplerConfig  : SamplerConfig? = null
    private var systemContents : Contents?      = null

    private val incomingHandler = object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: AndroidMessage) {
            when (msg.what) {
                MSG_LOAD_MODEL   -> handleLoadModel(msg)
                MSG_SEND_MESSAGE -> handleSendMessage(msg)
                MSG_STOP         -> stopGeneration()
                MSG_RESET_CHAT   -> handleResetChat(msg)
                MSG_CLOSE        -> { stopGeneration(); runOnWorker { closeModel() }; stopSelf() }
            }
        }
    }
    private val messenger = Messenger(incomingHandler)

    private fun runOnWorker(block: () -> Unit) {
        if (executor.isShutdown) return
        try { executor.execute(block) } catch (_: java.util.concurrent.RejectedExecutionException) {}
    }

    override fun onBind(intent: Intent): IBinder = messenger.binder

    override fun onDestroy() {
        super.onDestroy()
        stopGeneration()
        runOnWorker { closeModel() }
        executor.shutdown()
    }

    private fun handleLoadModel(msg: AndroidMessage) {
        val replyTo   = msg.replyTo ?: return
        val modelPath    = msg.data?.getString(KEY_MODEL_PATH) ?: return
        val systemPrompt = msg.data?.getString(KEY_SYSTEM_PROMPT).orEmpty()
        val useGpu    = (msg.data?.getString(KEY_BACKEND) ?: "gpu") == "gpu"
        val supportImage = msg.data?.getBoolean(KEY_SUPPORT_IMAGE, false) ?: false
        val temperature = msg.data?.getFloat(KEY_TEMPERATURE, 1.0f)?.toDouble() ?: 1.0
        val topK        = msg.data?.getInt(KEY_TOP_K, 64) ?: 64
        val topP        = msg.data?.getFloat(KEY_TOP_P, 0.95f)?.toDouble() ?: 0.95
        val maxTokens   = msg.data?.getInt(KEY_MAX_TOKENS, 4000) ?: 4000

        runOnWorker {
            try {
                closeModel()
                val backend: Backend = if (useGpu) Backend.GPU() else Backend.CPU()
                val engineConfig = EngineConfig(
                    modelPath = modelPath,
                    backend = backend,
                    visionBackend = if (supportImage) Backend.GPU() else null,
                    maxNumTokens = maxTokens
                )
                val eng = Engine(engineConfig)
                eng.initialize()

                systemContents = Contents.of(listOf(Content.Text(systemPrompt)))
                samplerConfig  = SamplerConfig(topK = topK, topP = topP, temperature = temperature)
                val conv = eng.createConversation(ConversationConfig(
                    samplerConfig     = samplerConfig,
                    systemInstruction = systemContents
                ))
                engine = eng; conversation = conv

                val reply = AndroidMessage.obtain(null, MSG_LOAD_OK)
                reply.data = Bundle().apply { putString(KEY_BACKEND, if (useGpu) "gpu" else "cpu") }
                replyTo.send(reply)
                Log.d(TAG, "Model loaded OK on ${if (useGpu) "GPU" else "CPU"} (temp=$temperature topK=$topK topP=$topP max=$maxTokens vision=$supportImage)")

            } catch (t: Throwable) {
                Log.e(TAG, "Load failed", t)
                val reply = AndroidMessage.obtain(null, MSG_LOAD_ERROR)
                reply.data = Bundle().apply { putString(KEY_ERROR, t.message ?: t.javaClass.simpleName) }
                try { replyTo.send(reply) } catch (_: Exception) {}
            }
        }
    }

    private fun handleResetChat(msg: AndroidMessage) {
        val replyTo = msg.replyTo ?: return
        val roles = msg.data?.getStringArray(KEY_HISTORY_ROLES)
        val texts = msg.data?.getStringArray(KEY_HISTORY_TEXTS)
        stopGeneration()
        runOnWorker {
            val eng = engine
            if (eng == null) { sendError(replyTo, "MODEL_NOT_READY"); return@runOnWorker }
            try {
                try { conversation?.close() } catch (_: Throwable) {}
                conversation = null

                val initial = mutableListOf<com.google.ai.edge.litertlm.Message>()
                if (roles != null && texts != null && roles.size == texts.size) {
                    for (i in roles.indices) {
                        val text = texts[i]
                        if (text.isBlank()) continue
                        initial.add(
                            if (roles[i] == "user") com.google.ai.edge.litertlm.Message.user(text)
                            else com.google.ai.edge.litertlm.Message.model(Contents.of(listOf(Content.Text(text))))
                        )
                    }
                }

                conversation = eng.createConversation(ConversationConfig(
                    samplerConfig     = samplerConfig,
                    systemInstruction = systemContents,
                    initialMessages   = initial
                ))
                Log.d(TAG, "Conversation reset (history: ${initial.size})")
                try { replyTo.send(AndroidMessage.obtain(null, MSG_RESET_OK)) } catch (_: Exception) {}
            } catch (t: Throwable) {
                Log.e(TAG, "Reset failed", t)
                sendError(replyTo, t.message ?: getString(R.string.error))
            }
        }
    }

    private fun handleSendMessage(msg: AndroidMessage) {
        val replyTo = msg.replyTo ?: return
        val conv    = conversation ?: run { sendError(replyTo, "MODEL_NOT_READY"); return }
        val text     = msg.data?.getString(KEY_TEXT) ?: ""
        val imgPath  = msg.data?.getString(KEY_IMAGE_PATH)
        val thinking = msg.data?.getBoolean(KEY_THINKING, false) ?: false

        runOnWorker {
            try {
                val contentsList = mutableListOf<Content>()

                if (imgPath != null) {
                    Log.d(TAG, "Adding image from file: " + imgPath)
                    contentsList.add(Content.ImageFile(imgPath))
                }

                val promptText = text.ifEmpty { getString(R.string.sinia_describe_photo) }
                contentsList.add(Content.Text(promptText))

                val contents = Contents.of(contentsList)

                val extra = mapOf<String, Any>("enable_thinking" to thinking)

                conv.sendMessageAsync(contents, object : MessageCallback {
                    override fun onMessage(message: com.google.ai.edge.litertlm.Message) {
                        val reply = AndroidMessage.obtain(null, MSG_PARTIAL)
                        reply.data = Bundle().apply {
                            putString(KEY_TEXT, message.toString())
                            message.channels[THOUGHT_CHANNEL]?.let { putString(KEY_THOUGHT, it) }
                        }
                        try { replyTo.send(reply) } catch (_: Exception) {}
                    }
                    override fun onDone() {
                        dropImage(imgPath)
                        try { replyTo.send(AndroidMessage.obtain(null, MSG_DONE)) } catch (_: Exception) {}
                    }
                    override fun onError(throwable: Throwable) {
                        dropImage(imgPath)
                        if (throwable is CancellationException) {
                            Log.i(TAG, "Inference cancelled")
                            try { replyTo.send(AndroidMessage.obtain(null, MSG_DONE)) } catch (_: Exception) {}
                            return
                        }
                        Log.e(TAG, "Inference error", throwable)
                        sendError(replyTo, throwable.message ?: getString(R.string.error))
                    }
                }, extra)
            } catch (t: Throwable) {
                Log.e(TAG, "Message handling crash", t)
                dropImage(imgPath)
                sendError(replyTo, t.message ?: getString(R.string.error))
            }
        }
    }

    private fun dropImage(path: String?) {
        if (path == null) return
        try { java.io.File(path).delete() } catch (_: Throwable) {}
    }

    private fun stopGeneration() {
        val conv = conversation ?: return
        try {
            if (conv.isAlive) conv.cancelProcess()
        } catch (t: Throwable) {
            Log.w(TAG, "cancelProcess failed: ${t.message}")
        }
    }

    private fun sendError(replyTo: Messenger, msg: String) {
        val reply = AndroidMessage.obtain(null, MSG_SEND_ERROR)
        reply.data = Bundle().apply { putString(KEY_ERROR, msg) }
        try { replyTo.send(reply) } catch (_: Exception) {}
    }

    private fun closeModel() {
        try { conversation?.close() } catch (_: Throwable) {}
        try { engine?.close() }       catch (_: Throwable) {}
        conversation = null; engine = null
        samplerConfig = null; systemContents = null
    }
}
