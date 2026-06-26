package com.agent42.core

import ai.nexa.ml.LlmWrapper
import ai.nexa.ml.bean.LlmCreateInput
import ai.nexa.ml.bean.ModelConfig
import ai.nexa.core.NexaSdk
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

class ModelManager(
    private val context: Context,
    private val nexaToken: String
) {
    private var llmWrapper: LlmWrapper? = null

    companion object {
        private const val MODEL_DIR = "models/Qwen3-8B-NPU"
        private const val MODEL_FILE = "files-1-1.nexa"
    }

    suspend fun loadModel(
        onProgress: (Float) -> Unit,
        onComplete: () -> Unit,
        onError: (String) -> Unit
    ) = withContext(Dispatchers.IO) {
        try {
            onProgress(0.1f)
            val modelPath = File(context.filesDir, "$MODEL_DIR/$MODEL_FILE").absolutePath
            if (!File(modelPath).exists()) {
                onError("Model not found at $modelPath. Download Qwen3-8B-NPU from HuggingFace first.")
                return@withContext
            }
            onProgress(0.5f)
            NexaSdk.getInstance().init(context)
            onProgress(0.7f)
            LlmWrapper.builder()
                .llmCreateInput(LlmCreateInput(
                    model_name = "qwen3-8b",
                    model_path = modelPath,
                    config = ModelConfig(max_tokens = 4096, enable_thinking = true),
                    plugin_id = "npu"
                ))
                .build()
                .onSuccess {
                    llmWrapper = it
                    onProgress(1.0f)
                    onComplete()
                }
                .onFailure { error -> onError("Model load failed: ${error.message}") }
        } catch (e: Exception) {
            onError("Init error: ${e.message}")
        }
    }

    fun getModel(): LlmWrapper? = llmWrapper
    fun isModelLoaded(): Boolean = llmWrapper != null
    fun unloadModel() {
        llmWrapper?.let {
            runCatching { it.javaClass.getMethod("close").invoke(it) }
        }
        llmWrapper = null
    }

    fun getModelStorageInfo(): ModelStorageInfo {
        val dir = File(context.filesDir, MODEL_DIR)
        val sizeBytes = if (dir.exists()) dir.walkTopDown().map { it.length() }.sum() else 0L
        return ModelStorageInfo(dir.absolutePath, sizeBytes, sizeBytes / (1024f * 1024f * 1024f), llmWrapper != null)
    }

    fun deleteModel(): Boolean {
        unloadModel()
        return File(context.filesDir, MODEL_DIR).deleteRecursively()
    }
}

data class ModelStorageInfo(
    val path: String, val sizeBytes: Long, val sizeGB: Float, val isLoaded: Boolean
)
