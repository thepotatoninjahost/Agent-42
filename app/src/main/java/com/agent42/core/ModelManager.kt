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
