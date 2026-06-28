package com.agent42.nexa

import com.nexa.sdk.LlmWrapper
import com.nexa.sdk.bean.GenerationConfig
import com.nexa.sdk.bean.LlmCreateInput
import com.nexa.sdk.bean.ModelConfig
import com.nexa.sdk.NexaSdk
import com.nexa.sdk.bean.LlmStreamResult
import android.content.Context
import android.os.Environment
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

        var targetPath = modelPath
        var targetFile = File(targetPath)

        if (!targetFile.exists()) {
            val sourceFile = scanForModelFile()
            if (sourceFile != null) {
                val installedFile = installModel(sourceFile, modelName)
                if (installedFile != null && installedFile.exists()) {
                    targetPath = installedFile.absolutePath
                    targetFile = installedFile
                }
            }
        }

        if (!targetFile.exists()) return@withContext Result.failure(
            IllegalArgumentException("Model not found at: $modelPath and local discovery failed.")
        )

        runCatching {
            val result = LlmWrapper.builder()
                .llmCreateInput(LlmCreateInput(
                    model_name = modelName, model_path = targetPath, tokenizer_path = "",
                    config = ModelConfig(max_tokens = maxTokens, enable_thinking = enableThinking),
                    plugin_id = pluginId
                ))
                .build()
            val wrapper = result.getOrThrow()
            llmWrapper = wrapper
            wrapper
        }.recoverCatching { e -> throw NexaSdkException("Load failed: ${e.message}", e) }
    }

    private fun scanForModelFile(): File? {
        val searchDirs = mutableListOf<File>()

        context.getExternalFilesDirs(null)?.filterNotNull()?.forEach { searchDirs.add(it) }
        context.externalCacheDir?.let { searchDirs.add(it) }

        try {
            searchDirs.add(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS))
            searchDirs.add(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS))
            searchDirs.add(File("/storage/emulated/0/Download"))
            searchDirs.add(File("/storage/emulated/0/Documents"))
            searchDirs.add(Environment.getExternalStorageDirectory())
        } catch (e: Exception) {
            // ignore
        }

        for (dir in searchDirs.distinct()) {
            if (dir.exists() && dir.isDirectory) {
                val found = searchDirRecursively(dir)
                if (found != null) return found
            }
        }
        return null
    }

    private fun searchDirRecursively(dir: File): File? {
        try {
            val walk = dir.walkTopDown()
                .maxDepth(4)
                .onFail { _, _ -> }

            for (file in walk) {
                if (file.isFile) {
                    val name = file.name
                    if (name == "files-1-1.nexa" || (name.endsWith(".nexa") && file.length() >= 500 * 1024 * 1024L)) {
                        return file
                    }
                }
            }
        } catch (e: Exception) {
            // ignore
        }
        return null
    }

    private suspend fun installModel(sourceFile: File, modelName: String): File? = withContext(Dispatchers.IO) {
        val destDir = File(context.filesDir, "models/$modelName")
        if (!destDir.exists() && !destDir.mkdirs()) {
            return@withContext null
        }
        val destFile = File(destDir, "files-1-1.nexa")

        if (destFile.exists() && destFile.length() == sourceFile.length()) {
            return@withContext destFile
        }

        try {
            val buffer = ByteArray(64 * 1024)
            sourceFile.inputStream().use { input ->
                destFile.outputStream().use { output ->
                    var bytes = input.read(buffer)
                    while (bytes >= 0) {
                        output.write(buffer, 0, bytes)
                        bytes = input.read(buffer)
                    }
                }
            }
            return@withContext destFile
        } catch (e: Exception) {
            destFile.delete()
            return@withContext null
        }
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

    fun isModelDownloaded(modelName: String): Boolean {
        val path = getModelPath(modelName)
        if (File(path).exists()) return true
        return scanForModelFile() != null
    }
}

class NexaSdkException(message: String, cause: Throwable? = null) : Exception(message, cause)
