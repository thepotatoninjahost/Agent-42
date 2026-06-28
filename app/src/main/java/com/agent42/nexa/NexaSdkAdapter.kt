package com.agent42.nexa

import com.nexa.sdk.LlmWrapper
import com.nexa.sdk.bean.GenerationConfig
import com.nexa.sdk.bean.LlmCreateInput
import com.nexa.sdk.bean.ModelConfig
import com.nexa.sdk.NexaSdk
import com.nexa.sdk.bean.LlmStreamResult
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File

class NexaSdkAdapter(private val context: Context) {
    private var llmWrapper: LlmWrapper? = null
    private var isInitialized = false

    suspend fun initialize(nexaToken: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            NexaSdk.getInstance().init(context)
            isInitialized = true
        }
    }

    suspend fun loadModel(
        modelPath: String, modelName: String = "qwen3-8b",
        pluginId: String = "npu", maxTokens: Int = 4096,
        enableThinking: Boolean = true
    ): Result<LlmWrapper> = withContext(Dispatchers.IO) {
        if (!isInitialized) return@withContext Result.failure(
            IllegalStateException("SDK not initialized")
        )
        if (!File(modelPath).exists()) return@withContext Result.failure(
            IllegalArgumentException("Model not found: $modelPath")
        )
        runCatching {
            val result = LlmWrapper.builder()
                .llmCreateInput(LlmCreateInput(
                    model_name = modelName, model_path = modelPath, tokenizer_path = "",
                    config = ModelConfig(max_tokens = maxTokens, enable_thinking = enableThinking),
                    plugin_id = pluginId
                ))
                .build()
            val wrapper = result.getOrThrow()
            llmWrapper = wrapper
            wrapper
        }.recoverCatching { e -> throw NexaSdkException("Load failed: ${e.message}", e) }
    }

    fun generate(
        prompt: String, maxTokens: Int = 4096,
        enableThinking: Boolean = true, temperature: Float = 0.7f
    ): Flow<String> {
        val wrapper = llmWrapper ?: throw NexaSdkException("Model not loaded")
        val config = GenerationConfig(maxTokens = maxTokens)
        return wrapper.generateStreamFlow(prompt, config)
            .map { result -> if (result is LlmStreamResult.Token) result.text else "" }
            .filter { it.isNotEmpty() }
            .catch { e -> throw NexaSdkException("Generation failed: ${e.message}", e) }
    }

    fun getModel(): LlmWrapper? = llmWrapper
    fun isModelLoaded(): Boolean = llmWrapper != null

    fun unloadModel() {
        llmWrapper?.let { runCatching { it.javaClass.getMethod("close").invoke(it) } }
        llmWrapper = null
    }

    fun getModelPath(modelName: String): String =
        File(context.filesDir, "models/$modelName/files-1-1.nexa").absolutePath
    fun isModelDownloaded(modelName: String): Boolean = File(getModelPath(modelName)).exists()
}

class NexaSdkException(message: String, cause: Throwable? = null) : Exception(message, cause)
