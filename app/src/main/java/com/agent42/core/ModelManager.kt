package com.agent42.core

import com.nexa.sdk.LlmWrapper
import com.nexa.sdk.bean.LlmCreateInput
import com.nexa.sdk.bean.ModelConfig
import com.nexa.sdk.NexaSdk
import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * Loads any on-device model the owner chooses — no hardcoded filename.
 */
class ModelManager(
    private val context: Context,
    private val nexaToken: String
) {
    private var llmWrapper: LlmWrapper? = null

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val activeDir: File get() = File(context.filesDir, ACTIVE_DIR_NAME)

    suspend fun loadModel(
        onProgress: (Float) -> Unit,
        onComplete: () -> Unit,
        onError: (String) -> Unit
    ) = withContext(Dispatchers.IO) {
        try {
            onProgress(0.1f)

            val entry = findEntryFile(activeDir)
            if (!activeDir.exists() || activeDir.listFiles()?.isNotEmpty() != true || entry == null) {
                val debugMsg = "activeDir exists: ${activeDir.exists()}\nfiles: ${activeDir.listFiles()?.size ?: 0}\nentry: ${entry?.name ?: "NONE"}"
                onError("No valid model installed.\n\n$debugMsg")
                return@withContext
            }

            val isGguf = entry.name.endsWith(".gguf", ignoreCase = true)
            val modelName = "qwen3-8b"
            val pluginId = if (isGguf) "cpu_gpu" else "npu"

            onProgress(0.5f)
            runCatching { NexaSdk.getInstance().init(context) }
            onProgress(0.7f)

            LlmWrapper.builder()
                .llmCreateInput(LlmCreateInput(
                    model_name = modelName,
                    model_path = entry.absolutePath,
                    tokenizer_path = "",
                    config = ModelConfig(max_tokens = 2048, enable_thinking = false),
                    plugin_id = pluginId
                ))
                .build()
                .onSuccess {
                    llmWrapper = it
                    prefs.edit().putString(KEY_MODEL_NAME, modelName).apply()
                    onProgress(1.0f)
                    onComplete()
                }
                .onFailure { error ->
                    onError("Model load failed: ${error.message}\nPlugin used: $pluginId")
                }

        } catch (e: Exception) {
            Log.e(TAG, "loadModel failed", e)
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

    fun hasInstalledModel(): Boolean =
        activeDir.exists() && activeDir.listFiles()?.isNotEmpty() == true

    fun getModelStorageInfo(): ModelStorageInfo {
        val sizeBytes = if (activeDir.exists()) {
            activeDir.walkTopDown().filter { it.isFile }.map { it.length() }.sum()
        } else 0L
        val entry = if (activeDir.exists()) findEntryFile(activeDir) else null
        val manifestName = readManifest(activeDir)?.optString("ModelName") ?: ""
        return ModelStorageInfo(
            path = activeDir.absolutePath,
            sizeBytes = sizeBytes,
            sizeGB = sizeBytes / (1024f * 1024f * 1024f),
            isLoaded = llmWrapper != null,
            entryFile = entry?.name ?: "",
            modelName = if (manifestName.isNotBlank()) manifestName else "qwen3-8b",
            fileCount = activeDir.listFiles()?.count { it.isFile } ?: 0
        )
    }

    fun deleteModel(): Boolean {
        unloadModel()
        prefs.edit().remove(KEY_MODEL_NAME).remove(KEY_ACTIVE_PATH).apply()
        return activeDir.deleteRecursively()
    }

    suspend fun importModelFromTreeUri(
        treeUri: Uri,
        onProgress: (Float, String) -> Unit = { _, _ -> }
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    treeUri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            val root = DocumentFile.fromTreeUri(context, treeUri)
                ?: return@withContext Result.failure(IllegalStateException("Could not open the selected folder."))

            onProgress(0.0f, "Searching for model files…")
            val bundle = findModelBundle(root)
                ?: return@withContext Result.failure(IllegalArgumentException("No model found in the selected folder."))

            val files = bundle.listFiles().filter { it.isFile && it.canRead() }
            if (files.isEmpty()) return@withContext Result.failure(IllegalStateException("Found model folder but no readable files."))

            if (activeDir.exists()) activeDir.deleteRecursively()
            activeDir.mkdirs()

            val total = files.size
            files.forEachIndexed { index, doc ->
                val name = doc.name ?: "file-$index"
                onProgress(index.toFloat() / total, "Copying $name (${index + 1}/$total)…")
                val dest = File(activeDir, name)
                context.contentResolver.openInputStream(doc.uri).use { input ->
                    if (input == null) return@withContext Result.failure(IllegalStateException("Could not read $name."))
                    dest.outputStream().use { output -> input.copyTo(output) }
                }
            }
            onProgress(1.0f, "Import complete.")
            prefs.edit().putString(KEY_ACTIVE_PATH, activeDir.absolutePath).apply()
            Result.success(activeDir)
        } catch (e: Exception) {
            Log.e(TAG, "importModelFromTreeUri failed", e)
            Result.failure(e)
        }
    }

    private fun findModelBundle(root: DocumentFile): DocumentFile? {
        val queue = ArrayDeque<DocumentFile>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val dir = queue.removeFirst()
            val children = dir.listFiles()
            val isBundle = children.any { child ->
                child.isFile && (
                    child.name.equals("nexa.manifest", ignoreCase = true) ||
                    child.name?.matches(Regex("(?i)^files-1-.*\\.nexa$")) == true ||
                    child.name?.endsWith(".gguf", ignoreCase = true) == true
                )
            }
            if (isBundle) return dir
            children.filter { it.isDirectory }.forEach { queue.add(it) }
        }
        return null
    }

    private fun findEntryFile(dir: File): File? {
        if (!dir.exists()) return null
        val files = dir.listFiles()?.filter { it.isFile } ?: return null
        files.firstOrNull { it.name.matches(Regex("(?i)^files-1-.*\\.nexa$")) }?.let { return it }
        files.firstOrNull { it.name.endsWith(".gguf", ignoreCase = true) }?.let { return it }
        files.firstOrNull { it.name.equals("files-1-1.nexa", ignoreCase = true) }?.let { return it }
        return null
    }

    private fun readManifest(dir: File): JSONObject? {
        val manifest = File(dir, "nexa.manifest")
        if (!manifest.exists()) return null
        return runCatching { JSONObject(manifest.readText()) }.getOrNull()
    }

    companion object {
        private const val TAG = "ModelManager"
        private const val PREFS = "agent42_model"
        private const val KEY_ACTIVE_PATH = "active_model_dir"
        private const val KEY_MODEL_NAME = "active_model_name"
        private const val ACTIVE_DIR_NAME = "models/active"
    }
}

data class ModelStorageInfo(
    val path: String,
    val sizeBytes: Long,
    val sizeGB: Float,
    val isLoaded: Boolean,
    val entryFile: String = "",
    val modelName: String = "",
    val fileCount: Int = 0
)
