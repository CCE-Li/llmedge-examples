package com.example.llmedgeexample

import android.app.ActivityManager
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import io.aatricks.llmedge.LLMEdgeManager
import io.aatricks.llmedge.SmolLM
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * Activity demonstrating loading and running a model from local assets.
 * 
 * Features:
 * - Blocking and streaming generation modes
 * - Memory metrics display
 * - Timeout handling for generation
 */
class LocalAssetDemoActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "LocalAssetDemoActivity"
        private const val BYTES_IN_MB = 1024L * 1024L
        private const val GENERATION_TIMEOUT_MS = 120_000L // 120 seconds
        private const val MODEL_FILE_NAME = "YourModel.gguf"
    }

    private lateinit var statusText: TextView
    private lateinit var metricsText: TextView
    private lateinit var chatScroll: ScrollView
    private lateinit var chatContainer: LinearLayout
    private lateinit var inputText: EditText
    private lateinit var sendButton: Button
    private var smol: SmolLM? = null
    private var isGenerating = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_local_asset_demo)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        statusText = findViewById(R.id.statusText)
        metricsText = findViewById(R.id.metricsText)
        chatScroll = findViewById(R.id.chatScroll)
        chatContainer = findViewById(R.id.chatContainer)
        inputText = findViewById(R.id.inputText)
        sendButton = findViewById(R.id.btnSend)

        sendButton.isEnabled = false
        inputText.isEnabled = false

        sendButton.setOnClickListener {
            val prompt = inputText.text.toString().trim()
            if (prompt.isEmpty()) {
                return@setOnClickListener
            }
            if (isGenerating) {
                return@setOnClickListener
            }
            val model = smol ?: return@setOnClickListener
            addUserBubble(prompt)
            inputText.setText("")
            val assistantBubble = addAssistantBubble()
            isGenerating = true
            sendButton.isEnabled = false
            statusText.text = "正在生成..."

            lifecycleScope.launch {
                try {
                    val buffer = StringBuilder()
                    model.getResponseAsFlow(prompt).collect { piece ->
                        buffer.append(piece)
                        val parsed = parseThink(buffer.toString())
                        withContext(Dispatchers.Main) {
                            updateAssistantBubble(assistantBubble, parsed)
                            scrollToBottom()
                        }
                    }
                    withContext(Dispatchers.Main) {
                        statusText.text = "完成"
                        metricsText.text = formatMetrics(model)
                    }
                } catch (t: Throwable) {
                    withContext(Dispatchers.Main) {
                        statusText.text = "生成失败: ${t.message}"
                    }
                } finally {
                    withContext(Dispatchers.Main) {
                        isGenerating = false
                        sendButton.isEnabled = true
                    }
                }
            }
        }

        lifecycleScope.launch {
            try {
                LLMEdgeManager.preferPerformanceMode = false
                statusText.text = "正在准备模型..."

                val modelPath = copyAssetIfNeeded(MODEL_FILE_NAME)
                val modelFile = File(modelPath)
                if (!modelFile.exists() || modelFile.length() == 0L) {
                    statusText.text = "模型文件不存在，请将 $MODEL_FILE_NAME 放入 assets"
                    return@launch
                }

                val availMemMB = getAvailableMemoryMB()
                if (availMemMB < 1500) {
                    statusText.text = "内存不足（${availMemMB}MB），可能加载失败"
                } else {
                    statusText.text = "加载模型中..."
                }

                val loaded = withContext(Dispatchers.IO) {
                    val instance = LLMEdgeManager.getSmolLMInstance(this@LocalAssetDemoActivity)
                    instance.load(
                        modelPath,
                        SmolLM.InferenceParams(
                            thinkingMode = SmolLM.ThinkingMode.DISABLED
                        )
                    )
                    instance
                }

                smol = loaded
                statusText.text = "模型已就绪"
                sendButton.isEnabled = true
                inputText.isEnabled = true
            } catch (t: Throwable) {
                statusText.text = "模型加载失败: ${t.message}"
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun copyAssetIfNeeded(assetName: String): String {
        val outFile = File(filesDir, assetName)
        if (!outFile.exists()) {
            try {
                assets.open(assetName).use { input ->
                    outFile.outputStream().use { output -> input.copyTo(output) }
                }
            } catch (e: Exception) {
                android.util.Log.w(TAG, "Could not copy asset $assetName: ${e.message}")
            }
        }
        return outFile.absolutePath
    }

    private fun getAvailableMemoryMB(): Long {
        val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)
        return memInfo.availMem / BYTES_IN_MB
    }

    private fun addUserBubble(text: String): TextView {
        val row = LinearLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dpToPx(8)
            }
            gravity = Gravity.END
        }

        val bubble = TextView(this).apply {
            this.text = text
            textSize = 15f
            typeface = Typeface.DEFAULT
            setTextColor(0xFF0B0B0B.toInt())
            setPadding(dpToPx(12), dpToPx(8), dpToPx(12), dpToPx(8))
            maxWidth = (resources.displayMetrics.widthPixels * 0.78f).toInt()
            background = GradientDrawable().apply {
                cornerRadius = dpToPx(14).toFloat()
                setColor(0xFFDCF8C6.toInt())
            }
        }

        row.addView(bubble)
        chatContainer.addView(row)
        scrollToBottom()
        return bubble
    }

    private data class AssistantBubble(
        val container: LinearLayout,
        val thinkHeader: TextView,
        val thinkBody: TextView,
        val answer: TextView
    )

    private data class ThinkParseResult(
        val think: String?,
        val answer: String
    )

    private fun addAssistantBubble(): AssistantBubble {
        val row = LinearLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dpToPx(8)
            }
            gravity = Gravity.START
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpToPx(12), dpToPx(10), dpToPx(12), dpToPx(10))
            background = GradientDrawable().apply {
                cornerRadius = dpToPx(14).toFloat()
                setColor(0xFF1E1E1E.toInt())
            }
        }

        val thinkHeader = TextView(this).apply {
            textSize = 12f
            setTextColor(0xFF9AA4B2.toInt())
            text = "思考过程 ▸"
            visibility = TextView.GONE
        }

        val thinkBody = TextView(this).apply {
            textSize = 13f
            setTextColor(0xFFB9C1CC.toInt())
            setPadding(0, dpToPx(6), 0, dpToPx(6))
            visibility = TextView.GONE
            maxWidth = (resources.displayMetrics.widthPixels * 0.78f).toInt()
        }

        val answer = TextView(this).apply {
            textSize = 15f
            typeface = Typeface.DEFAULT
            setTextColor(0xFFE6E6E6.toInt())
            maxWidth = (resources.displayMetrics.widthPixels * 0.78f).toInt()
        }

        thinkHeader.setOnClickListener {
            val nextVisible = thinkBody.visibility != TextView.VISIBLE
            thinkBody.visibility = if (nextVisible) TextView.VISIBLE else TextView.GONE
            thinkHeader.text = if (nextVisible) "思考过程 ▾" else "思考过程 ▸"
        }

        container.addView(thinkHeader)
        container.addView(thinkBody)
        container.addView(answer)

        row.addView(container)
        chatContainer.addView(row)
        scrollToBottom()
        return AssistantBubble(container, thinkHeader, thinkBody, answer)
    }

    private fun updateAssistantBubble(bubble: AssistantBubble, parsed: ThinkParseResult) {
        bubble.answer.text = parsed.answer
        val thinkText = parsed.think?.trim()
        if (!thinkText.isNullOrEmpty()) {
            bubble.thinkHeader.visibility = TextView.VISIBLE
            bubble.thinkBody.text = thinkText
        } else {
            bubble.thinkHeader.visibility = TextView.GONE
            bubble.thinkBody.visibility = TextView.GONE
        }
    }

    private fun parseThink(raw: String): ThinkParseResult {
        val analysisStart = "<|channel|>analysis<|message|>"
        val analysisEnd = "<|end|>"
        val thinkStart = "<think>"
        val thinkEnd = "</think>"

        val analysisIndex = raw.indexOf(analysisStart)
        if (analysisIndex >= 0) {
            val endIndex = raw.indexOf(analysisEnd, analysisIndex + analysisStart.length)
            return if (endIndex >= 0) {
                val think = raw.substring(analysisIndex + analysisStart.length, endIndex)
                val answer =
                    (raw.substring(0, analysisIndex) + raw.substring(endIndex + analysisEnd.length)).trim()
                ThinkParseResult(think, answer)
            } else {
                val think = raw.substring(analysisIndex + analysisStart.length)
                val answer = raw.substring(0, analysisIndex).trim()
                ThinkParseResult(think, answer)
            }
        }

        val thinkIndex = raw.indexOf(thinkStart)
        if (thinkIndex >= 0) {
            val endIndex = raw.indexOf(thinkEnd, thinkIndex + thinkStart.length)
            return if (endIndex >= 0) {
                val think = raw.substring(thinkIndex + thinkStart.length, endIndex)
                val answer =
                    (raw.substring(0, thinkIndex) + raw.substring(endIndex + thinkEnd.length)).trim()
                ThinkParseResult(think, answer)
            } else {
                val think = raw.substring(thinkIndex + thinkStart.length)
                val answer = raw.substring(0, thinkIndex).trim()
                ThinkParseResult(think, answer)
            }
        }

        return ThinkParseResult(null, raw)
    }

    private fun formatMetrics(model: SmolLM): String {
        val metrics = model.getLastGenerationMetrics()
        val tokensPerSecond = String.format(Locale.US, "%.2f", metrics.tokensPerSecond)
        val duration = String.format(Locale.US, "%.2f", metrics.elapsedSeconds)
        val ctxUsed = model.getContextLengthUsed()
        return "tokens/s $tokensPerSecond | tokens ${metrics.tokenCount} | ${duration}s | ctx $ctxUsed"
    }

    private fun scrollToBottom() {
        chatScroll.post {
            chatScroll.fullScroll(ScrollView.FOCUS_DOWN)
        }
    }

    private fun dpToPx(dp: Int): Int {
        val density = resources.displayMetrics.density
        return (dp * density).toInt()
    }
}
