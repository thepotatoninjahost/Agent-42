package com.agent42.core

import com.nexa.sdk.LlmWrapper
import com.nexa.sdk.bean.LlmCreateInput
import com.nexa.sdk.bean.ModelConfig
import com.nexa.sdk.NexaSdk
import android.content.Context
import android.os.Environment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

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
            onProgress(0.05f)
            val modelPath = File(context.filesDir, "$MODEL_DIR/$MODEL_FILE").absolutePath
            val localFile = File(modelPath)

            if (!localFile.exists()) {
                onProgress(0.1f)
                val sourceFile = scanForModelFile()
                if (sourceFile != null) {
                    onProgress(0.15f)
                    val installSuccess = installModel(sourceFile, onProgress)
                    if (!installSuccess) {
                        onError("Found model at ${sourceFile.absolutePath}, but installation to local system failed.")
                        return@withContext
                    }
                } else {
                    onError("Model file not found. Please place 'files-1-1.nexa' in your Downloads, Documents, or App storage folder.")
                    return@withContext
                }
            }

            onProgress(0.6f)
            NexaSdk.getInstance().init(context)
            onProgress(0.7f)
            LlmWrapper.builder()
                .llmCreateInput(LlmCreateInput(
                    model_name = "qwen3-8b",
                    model_path = modelPath,
                    tokenizer_path = "",
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
            // Gracefully catch potential Security/Permission Exceptions
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
                    if (name == MODEL_FILE || (name.endsWith(".nexa") && file.length() >= 500 * 1024 * 1024L)) {
                        return file
                    }
                }
            }
        } catch (e: Exception) {
            // Squelch permission issues
        }
        return null
    }

    private suspend fun installModel(sourceFile: File, onProgress: (Float) -> Unit): Boolean = withContext(Dispatchers.IO) {
        val destDir = File(context.filesDir, MODEL_DIR)
        if (!destDir.exists() && !destDir.mkdirs()) {
            return@withContext false
        }
        val destFile = File(destDir, MODEL_FILE)

        if (destFile.exists() && destFile.length() == sourceFile.length()) {
            return@withContext true
        }

        try {
            val totalBytes = sourceFile.length()
            var bytesCopied = 0L
            val buffer = ByteArray(64 * 1024)

            sourceFile.inputStream().use { input ->
                destFile.outputStream().use { output ->
                    var bytes = input.read(buffer)
                    while (bytes >= 0) {
                        output.write(buffer, 0, bytes)
                        bytesCopied += bytes
                        if (totalBytes > 0) {
                            val progress = 0.15f + (bytesCopied.toFloat() / totalBytes) * 0.40f
                            onProgress(progress)
                        }
                        bytes = input.read(buffer)
                    }
                }
            }
            return@withContext true
        } catch (e: Exception) {
            destFile.delete()
            return@withContext false
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
