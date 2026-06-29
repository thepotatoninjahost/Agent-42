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
 *
 * How it works:
 *  1. The owner picks a folder (via the system folder picker / SAF
 *     `OpenDocumentTree`) that contains the model they downloaded — e.g. the
 *     Downloads folder, or a subfolder with the Qwen3-8B-NPU files, or a
 *     folder with a .gguf file. See [importModelFromTreeUri].
 *  2. [importModelFromTreeUri] searches that tree, locates the model bundle
 *     (a directory containing `nexa.manifest`, or a `files-1-*.nexa` entry
 *     file, or a `.gguf` file), copies every file in that bundle into the
 *     app's private `models/active/` directory, and persists that path.
 *  3. [loadModel] reads the persisted directory, [findEntryFile] auto-detects
 *     the entry file the Nexa SDK expects (the `files-1-*` part, or the
 *     `.gguf`), parses `nexa.manifest` for the real `model_name` / `plugin_id`
 *     if present, and builds the [LlmWrapper]. Default model remains
 *     Qwen3-8B-NPU, but any Nexa-format or GGUF model the owner supplies will
 *     load the same way.
 *
 * Nothing here assumes a specific model name or a specific file name. The
 * owner is never locked out: if no model is installed, [loadModel] reports a
 * clear, recoverable error and the UI stays fully navigable.
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

            // ==================== DEBUG INFO ====================
            val debugMsg = buildString {
                append("activeDir: ${activeDir.absolutePath}\n")
                append("exists: ${activeDir.exists()}\n")
                append("file count: ${activeDir.listFiles()?.size ?: 0}\n")
                activeDir.listFiles()?.forEach { f ->
                    append("   • ${f.name} (${f.length() / (1024*1024)} MB)\n")
                }
                append("Entry file: ${findEntryFile(activeDir)?.name ?: "NOT FOUND"}\n")
            }

            android.widget.Toast.makeText(context, debugMsg.take(300), android.widget.Toast.LENGTH_LONG).show()
            // ====================================================

            if (!activeDir.exists() || activeDir.listFiles()?.isNotEmpty() != true) {
                onError("No model installed yet.\n\nDebug:\n$debugMsg")
                return@withContext
            }

            val entry = findEntryFile(activeDir)
            if (entry == null) {
                onError("Could not find a model entry file.\n\nDebug:\n$debugMsg")
                return@withContext
            }

            val manifest = readManifest(activeDir)
            val modelName = manifest?.optString("ModelName")?.takeIf { it.isNotBlank() }
                ?: prefs.getString(KEY_MODEL_NAME, "qwen3-8b") ?: "qwen3-8b"
            val pluginId = manifest?.optString("PluginId")?.takeIf { it.isNotBlank() } ?: "npu"

            onProgress(0.5f)
            runCatching { NexaSdk.getInstance().init(context) }
            onProgress(0.7f)

            LlmWrapper.builder()
                .llmCreateInput(LlmCreateInput(
                    model_name = modelName,
                    model_path = entry.absolutePath,
                    tokenizer_path = "",
                    config = ModelConfig(max_tokens = 4096, enable_thinking = true),
                    plugin_id = pluginId
                ))
                .build()
                .onSuccess {
                    llmWrapper = it
                    prefs.edit().putString(KEY_MODEL_NAME, modelName).apply()
                    onProgress(1.0f)
                    onComplete()
                }
                .onFailure { error -> onError("Model load failed: ${error.message}") }

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

    /** True if a model bundle has been imported into app storage. */
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
            modelName = if (manifestName.isNotBlank()) manifestName
                else prefs.getString(KEY_MODEL_NAME, "qwen3-8b") ?: "qwen3-8b",
            fileCount = activeDir.listFiles()?.count { it.isFile } ?: 0
        )
    }

    fun deleteModel(): Boolean {
        unloadModel()
        prefs.edit().remove(KEY_MODEL_NAME).remove(KEY_ACTIVE_PATH).apply()
        return activeDir.deleteRecursively()
    }

    /**
     * The owner picks a folder via the system folder picker. We search that
     * tree for a model bundle, copy every file in the bundle directory into
     * `models/active/`, and persist the path so [loadModel] finds it next
     * launch. Works for Qwen3-8B-NPU (10-file bundle), OmniNeural, or any
     * `.gguf` model. Reports progress per file so the UI can show what's
     * happening during a multi-GB copy.
     *
     * @param treeUri the URI returned by `OpenDocumentTree`.
     * @param onProgress called with (fractionDone, message) during the copy.
     * @return the active directory on success, or an exception.
     */
    suspend fun importModelFromTreeUri(
        treeUri: Uri,
        onProgress: (Float, String) -> Unit = { _, _ -> }
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            // Take a persistable grant so we could re-scan later without
            // re-prompting (defensive — current flow copies once).
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    treeUri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            val root = DocumentFile.fromTreeUri(context, treeUri)
                ?: return@withContext Result.failure(
                    IllegalStateException("Could not open the selected folder.")
                )
            onProgress(0.0f, "Searching for model files…")
            val bundle = findModelBundle(root)
                ?: return@withContext Result.failure(
                    IllegalArgumentException(
                        "No model found in the selected folder. Look for a folder containing " +
                            "'nexa.manifest' + '.nexa' files, or a single '.gguf' file. " +
                            "If you downloaded Qwen3-8B-NPU, pick the folder that has all 10 files."
                    )
                )

            val files = bundle.listFiles().filter { it.isFile && it.canRead() }
            if (files.isEmpty()) return@withContext Result.failure(
                IllegalStateException("Found model folder but it had no readable files.")
            )

            // Reset the active directory so a re-import never mixes files
            // from two different models.
            if (activeDir.exists()) activeDir.deleteRecursively()
            activeDir.mkdirs()

            val total = files.size
            files.forEachIndexed { index, doc ->
                val name = doc.name ?: "file-$index"
                onProgress(
                    index.toFloat() / total,
                    "Copying $name (${index + 1}/$total)…"
                )
                val dest = File(activeDir, name)
                context.contentResolver.openInputStream(doc.uri).use { input ->
                    if (input == null) return@withContext Result.failure(
                        IllegalStateException("Could not read $name.")
                    )
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

    /**
     * Walk [root] looking for a directory that holds a model bundle. A bundle
     * directory is one that directly contains any of:
     *   - `nexa.manifest` (Nexa NPU models — Qwen3-8B-NPU, OmniNeural, …)
     *   - a file matching `files-1-*.nexa` (the SDK entry file)
     *   - a `*.gguf` file (any GGUF LLM/VLM)
     * Returns the first matching directory found (BFS), or null.
     */
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

    /**
     * Auto-detect the entry file the Nexa SDK wants as `model_path`.
     * Priority: `files-1-*.nexa` (Nexa NPU/GGUF entry) → any `.gguf` →
     * `files-1-1.nexa` (legacy). Returns null if nothing usable.
     */
    private fun findEntryFile(dir: File): File? {
        if (!dir.exists()) return null
        val files = dir.listFiles()?.filter { it.isFile } ?: return null
        files.firstOrNull { it.name.matches(Regex("(?i)^files-1-.*\\.nexa$")) }
            ?.let { return it }
        files.firstOrNull { it.name.endsWith(".gguf", ignoreCase = true) }
            ?.let { return it }
        files.firstOrNull { it.name.equals("files-1-1.nexa", ignoreCase = true) }
            ?.let { return it }
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
