package com.agent42.memory

import com.nexa.sdk.LlmWrapper
import com.nexa.sdk.bean.GenerationConfig
import com.nexa.sdk.bean.LlmStreamResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

class MemorySystem(
    private val db: AgentDatabase,
    embeddingModel: Any? = null
) {
    private val strategyMutex = Mutex()
    @Volatile
    private var embeddingModel: Any? = embeddingModel

    fun setEmbeddingModel(model: Any?) {
        embeddingModel = model
    }

    // ═══ RECALL ════════════════════════════════════════════

    suspend fun recallRelevant(query: String, limit: Int = 5): List<String> {
        val queryEmbedding = embed(query) ?: return fallbackKeywordRecall(query, limit)
        val allMemories = db.memoryDao().getTopMemories(minImportance = 0.2f, limit = 100)
        val scored = allMemories
            .filter { it.embedding != null }
            .map { memory ->
                val memoryEmbedding = byteArrayToFloatArray(memory.embedding!!)
                val similarity = cosineSimilarity(queryEmbedding, memoryEmbedding)
                memory to (similarity * memory.importance)
            }
            .sortedByDescending { it.second }
            .take(limit)
        scored.forEach { (memory, _) -> db.memoryDao().touch(memory.id, System.currentTimeMillis()) }
        return scored.map { it.first.content }
    }

    private suspend fun fallbackKeywordRecall(query: String, limit: Int): List<String> {
        val keywords = query.lowercase().split(" ").filter { it.length > 3 }
        if (keywords.isEmpty()) return db.memoryDao().getTopMemories(0.4f, limit).map { it.content }
        val results = mutableListOf<MemoryEntity>()
        for (keyword in keywords.take(5)) {
            results.addAll(db.memoryDao().searchByKeyword(keyword, limit * 2))
        }
        return results.distinctBy { it.id }
            .map { mem -> mem to keywords.count { kw -> mem.content.lowercase().contains(kw) } }
            .filter { it.second > 0 }
            .sortedByDescending { it.second * it.first.importance }
            .take(limit)
            .map { it.first.content }
    }

    // ═══ STORE ═════════════════════════════════════════════

    suspend fun extractAndStore(
        query: String, response: String, interactionId: Long, llm: LlmWrapper,
        sessionId: String? = null
    ) = withContext(Dispatchers.IO) {
        val extractionPrompt = """
            Analyze this exchange and extract memorable facts.
            User said: "$query"
            Assistant said: "$response"
            For each memorable item, output one line:
            CATEGORY|TYPE|content|importance(0.0-1.0)
            Categories: FACTUAL, PREFERENCE, PATTERN, EPISODIC, SKILL, RELATIONSHIP
            Types: SEMANTIC (timeless facts), EPISODIC (specific to this conversation), PROCEDURAL (how-to)
            Only extract genuinely useful, reusable information.
            Never extract passwords, credentials, or sensitive data.
            If nothing notable, output: NONE
        """.trimIndent()
        val result = StringBuilder()
        llm.generateStreamFlow(extractionPrompt, GenerationConfig(maxTokens = 512))
            .collect { chunk -> if (chunk is LlmStreamResult.Token) result.append(chunk.text) }
        val text = result.toString().trim()
        if (text.uppercase() == "NONE" || text.isBlank()) return@withContext

        val newMemoryIds = mutableListOf<Long>()
        text.lines().forEach { line ->
            val parts = line.split("|", limit = 4)
            if (parts.size >= 3) {
                val category = try { MemoryCategory.valueOf(parts[0].trim().uppercase()) }
                    catch (e: IllegalArgumentException) { return@forEach }
                val memType = try { MemoryType.valueOf(parts[1].trim().uppercase()) }
                    catch (e: IllegalArgumentException) { MemoryType.SEMANTIC }
                val content = parts[2].trim()
                val importance = if (parts.size == 4) parts[3].trim().toFloatOrNull()?.coerceIn(0f, 1f) ?: 0.5f else 0.5f
                if (content.isNotBlank() && content.length <= 2000) {
                    val embedding = embed(content)
                    val id = db.memoryDao().insert(MemoryEntity(
                        category = category, content = content,
                        sourceInteractionId = interactionId,
                        importance = importance,
                        lastAccessed = System.currentTimeMillis(),
                        accessCount = 0,
                        embedding = embedding?.let { floatArrayToByteArray(it) },
                        createdAt = System.currentTimeMillis(),
                        memoryType = memType,
                        sessionId = sessionId
                    ))
                    newMemoryIds.add(id)
                }
            }
        }

        // UPGRADE 5: Auto-link new memories to related existing ones
        if (newMemoryIds.isNotEmpty()) {
            autoLinkMemories(newMemoryIds)
        }
    }

    // ═══ UPGRADE 5: Associative Memory Links ═══

    private suspend fun autoLinkMemories(newMemoryIds: List<Long>) {
        for (newId in newMemoryIds) {
            val newMemory = db.memoryDao().getTopMemories(0f, 1000)
                .find { it.id == newId } ?: continue
            // Find related memories by keyword overlap
            val keywords = newMemory.content.split(" ")
                .filter { it.length > 4 }.take(5)
            val candidates = mutableListOf<MemoryEntity>()
            for (kw in keywords) {
                candidates.addAll(db.memoryDao().searchByKeyword(kw, 10))
            }
            candidates.distinctBy { it.id }
                .filter { it.id != newId }
                .take(5)
                .forEach { candidate ->
                    val similarity = contentSimilarity(newMemory.content, candidate.content)
                    if (similarity > 0.2f) {
                        db.memoryLinkDao().insert(MemoryLink(
                            sourceMemoryId = newId,
                            targetMemoryId = candidate.id,
                            linkType = LinkType.RELATED,
                            strength = similarity
                        ))
                    }
                }
        }
    }

    private fun contentSimilarity(a: String, b: String): Float {
        val wordsA = a.lowercase().split(Regex("\\W+")).toSet()
        val wordsB = b.lowercase().split(Regex("\\W+")).toSet()
        if (wordsA.isEmpty() || wordsB.isEmpty()) return 0f
        val intersection = wordsA.intersect(wordsB).size
        val union = wordsA.union(wordsB).size
        return intersection.toFloat() / union.toFloat()
    }

    /**
     * Recall memories, following associative links to find related context.
     */
    suspend fun recallWithLinks(query: String, limit: Int = 5): List<String> = withContext(Dispatchers.IO) {
        val primary = recallRelevant(query, limit)
        if (primary.isEmpty()) return@withContext emptyList()

        // Follow links from recalled memories to find related context
        val linked = mutableSetOf<String>()
        val topMemories = db.memoryDao().searchByKeyword(
            query.split(" ").filter { it.length > 3 }.firstOrNull() ?: query, limit * 2
        )
        for (mem in topMemories.take(3)) {
            val links = db.memoryLinkDao().getOutgoingLinks(mem.id)
            links.filter { it.strength > 0.3f }.take(2).forEach { link ->
                val linkedMem = db.memoryDao().getTopMemories(0f, 1000).find { it.id == link.targetMemoryId }
                if (linkedMem != null) {
                    linked.add(linkedMem.content.take(200))
                    db.memoryDao().touch(linkedMem.id, System.currentTimeMillis())
                }
            }
        }
        (primary + linked.toList()).distinct().take(limit * 2)
    }

    // ═══ UPGRADE 4: Memory Consolidation ═══

    suspend fun consolidateMemories(llm: LlmWrapper) = withContext(Dispatchers.IO) {
        // UPGRADE 8 (Continual Learning): Protect important memories before decay
        protectImportantMemories()

        // 1. Decay old, unused memories (but protected ones are boosted, not decayed)
        val threeDaysAgo = System.currentTimeMillis() - (3 * 24 * 60 * 60 * 1000L)
        db.memoryDao().decayImportance(0.9f, threeDaysAgo)

        // 2. Purge weak memories that were never accessed
        val decayed = db.memoryDao().getDecayedUnused(0.1f, threeDaysAgo)
        decayed.forEach { _ -> db.memoryDao().purgeWeakMemories(0.1f) }

        // 3. Merge similar memories
        val allMemories = db.memoryDao().getTopMemories(0f, 500)
        val processed = mutableSetOf<Long>()
        for (mem in allMemories) {
            if (mem.id in processed) continue
            val similar = db.memoryDao().getSameCategoryAndType(mem.category, mem.memoryType, mem.id)
                .filter { it.id !in processed }
                .filter { contentSimilarity(mem.content, it.content) > 0.5f }

            if (similar.isNotEmpty()) {
                // Merge into one consolidated memory
                val mergePrompt = """
                    Merge these ${similar.size + 1} related memories into one concise, complete memory.
                    Keep all unique information, remove duplicates.
                    ${(listOf(mem) + similar).mapIndexed { i, m -> "${i+1}. ${m.content.take(300)}" }.joinToString("\n")}
                    Output ONLY the merged memory content, nothing else.
                """.trimIndent()
                val result = StringBuilder()
                llm.generateStreamFlow(mergePrompt, GenerationConfig(maxTokens = 512))
                    .collect { chunk -> if (chunk is LlmStreamResult.Token) result.append(chunk.text) }
                val mergedContent = result.toString().trim()
                if (mergedContent.isNotBlank()) {
                    val mergedImportance = (listOf(mem) + similar).maxOf { it.importance }
                    val mergedId = db.memoryDao().insert(MemoryEntity(
                        category = mem.category,
                        content = mergedContent,
                        importance = mergedImportance,
                        lastAccessed = System.currentTimeMillis(),
                        accessCount = (listOf(mem) + similar).sumOf { it.accessCount },
                        embedding = embed(mergedContent)?.let { floatArrayToByteArray(it) },
                        createdAt = System.currentTimeMillis(),
                        memoryType = mem.memoryType,
                        mergedFrom = (listOf(mem) + similar).map { it.id }
                    ))
                    // Transfer links to merged memory
                    for (old in listOf(mem) + similar) {
                        db.memoryLinkDao().getLinks(old.id).forEach { link ->
                            val newSource = if (link.sourceMemoryId == old.id) mergedId else link.sourceMemoryId
                            val newTarget = if (link.targetMemoryId == old.id) mergedId else link.targetMemoryId
                            if (newSource != newTarget) {
                                db.memoryLinkDao().insert(link.copy(sourceMemoryId = newSource, targetMemoryId = newTarget))
                            }
                        }
                        db.memoryDao().purgeWeakMemories(-1f) // Delete originals by setting threshold below 0
                        processed.add(old.id)
                    }
                }
            }
            processed.add(mem.id)
        }

        // 4. Clean up weak links
        db.memoryLinkDao().purgeWeakLinks(0.1f)
    }

    // ═══ UPGRADE 6: Episodic vs Semantic Recall ═══

    suspend fun recallEpisodic(limit: Int = 5): List<String> = withContext(Dispatchers.IO) {
        db.memoryDao().getRecentEpisodic(limit).map { it.content }
    }

    suspend fun recallSemantic(query: String, limit: Int = 5): List<String> = withContext(Dispatchers.IO) {
        val semantic = db.memoryDao().getTopSemantic(0.3f, limit * 2)
        // Score by embedding similarity if available, otherwise by keyword
        val queryEmbedding = embed(query)
        if (queryEmbedding != null) {
            semantic.filter { it.embedding != null }
                .map { it to cosineSimilarity(queryEmbedding, byteArrayToFloatArray(it.embedding!!)) }
                .sortedByDescending { it.second }
                .take(limit)
                .map { it.first.content }
        } else {
            val keywords = query.split(Regex("\\W+")).filter { it.length > 3 }
            semantic.map { mem ->
                val score = keywords.count { kw -> mem.content.contains(kw, ignoreCase = true) }.toFloat()
                mem to score
            }.sortedByDescending { it.second }.take(limit).map { it.first.content }
        }
    }

    // ═══ UPGRADE 8: Cross-Session Memory ═══

    suspend fun saveActiveTopic(topic: String, sessionId: String, unresolvedQuestion: String? = null) = withContext(Dispatchers.IO) {
        db.activeTopicDao().insert(ActiveTopic(
            topic = topic,
            sessionId = sessionId,
            createdAt = System.currentTimeMillis(),
            lastActiveAt = System.currentTimeMillis(),
            unresolvedQuestion = unresolvedQuestion
        ))
    }

    suspend fun getUnresolvedTopics(): List<ActiveTopic> = withContext(Dispatchers.IO) {
        db.activeTopicDao().getUnresolved(10)
    }

    suspend fun touchTopic(topicId: Long) = withContext(Dispatchers.IO) {
        db.activeTopicDao().touch(topicId, System.currentTimeMillis())
    }

    suspend fun resolveTopic(topicId: Long) = withContext(Dispatchers.IO) {
        db.activeTopicDao().markResolved(topicId)
    }

    // ═══ LEARN ═════════════════════════════════════════════

    suspend fun recordFeedback(
        interactionId: Long, signalType: FeedbackType, signalValue: Float,
        userNotes: String? = null
    ) = withContext(Dispatchers.IO) {
            db.feedbackDao().insert(FeedbackEntity(
                interactionId = interactionId, signalType = signalType,
                signalValue = signalValue, timestamp = System.currentTimeMillis(),
                notes = userNotes
            ))
        }

    suspend fun updateStrategyWeights(
        strategyName: String, queryPattern: String, feedback: FeedbackEntity
    ) = strategyMutex.withLock {
        val existing = db.strategyDao().getStrategiesForPattern(queryPattern)
            .find { it.strategyName == strategyName }
        if (existing == null) {
            db.strategyDao().insert(StrategyWeightEntity(
                strategyName = strategyName, queryTypePattern = queryPattern,
                weight = (0.5f + (feedback.signalValue * 0.1f)).coerceIn(0f, 1f),
                usesCount = 1,
                positiveFeedback = if (feedback.signalValue > 0) 1 else 0,
                negativeFeedback = if (feedback.signalValue < 0) 1 else 0,
                lastUsed = System.currentTimeMillis()
            ))
        } else {
            val learningRate = 0.15f
            val targetWeight = when {
                feedback.signalValue > 0.3f -> 1.0f
                feedback.signalValue < -0.3f -> 0.0f
                else -> 0.5f
            }
            val newWeight = existing.weight + learningRate * (targetWeight - existing.weight)
            db.strategyDao().update(existing.copy(
                weight = newWeight.coerceIn(0f, 1f),
                usesCount = existing.usesCount + 1,
                positiveFeedback = existing.positiveFeedback +
                    if (feedback.signalValue > 0) 1 else 0,
                negativeFeedback = existing.negativeFeedback +
                    if (feedback.signalValue < 0) 1 else 0,
                lastUsed = System.currentTimeMillis()
            ))
        }
    }

    // ═══ REFLECT ═══════════════════════════════════════════

    suspend fun runReflection(llm: LlmWrapper) = withContext(Dispatchers.IO) {
        val since = System.currentTimeMillis() - (7 * 24 * 60 * 60 * 1000L)
        val effectiveness = db.feedbackDao().getStrategyEffectiveness(since)
        db.memoryDao().purgeWeakMemories(0.1f)
        db.memoryDao().decayImportance(0.95f, since)
        val recentMemories = db.memoryDao().getTopMemories(0.4f, 30)
        val reflectionPrompt = """
            You are reflecting on your recent interactions to improve.
            Strategy effectiveness this week:
            ${effectiveness.joinToString("\n") { "- ${it.strategyName}: ${it.avgSignal} avg (n=${it.count})" }}
            Recent memories:
            ${recentMemories.joinToString("\n") { "- [${it.category}] ${it.content.take(200)}" }}
            Identify: 1. Which strategies work well 2. Which need adjustment
            3. Patterns about the user you've missed 4. New skills to develop
        """.trimIndent()
        val result = StringBuilder()
        llm.generateStreamFlow(reflectionPrompt, GenerationConfig(maxTokens = 2048))
            .collect { chunk -> if (chunk is LlmStreamResult.Token) result.append(chunk.text) }
        val reflectionText = result.toString().trim()
        if (reflectionText.isNotBlank()) {
            val embedding = embed(reflectionText)
            db.memoryDao().insert(MemoryEntity(
                category = MemoryCategory.SKILL,
                content = "Reflection: $reflectionText",
                importance = 0.8f,
                lastAccessed = System.currentTimeMillis(),
                accessCount = 0,
                embedding = embedding?.let { floatArrayToByteArray(it) },
                createdAt = System.currentTimeMillis(),
                tags = listOf("reflection", "self-improvement")
            ))
        }
    }

    // ═══ QUERY METHODS ═════════════════════════════════════

    suspend fun getAllMemories(): List<MemoryEntity> = withContext(Dispatchers.IO) {
        db.memoryDao().getRecentMemories(100)
    }
    suspend fun getMemoryCount(): Int = withContext(Dispatchers.IO) { db.memoryDao().getCount() }
    suspend fun getInteractionCount(): Int = withContext(Dispatchers.IO) {
        db.conversationDao().getInteractionCount()
    }
    suspend fun getAllStrategyWeights(): List<StrategyWeightEntity> = withContext(Dispatchers.IO) {
        db.strategyDao().getAll()
    }
    suspend fun getReflections(limit: Int = 5): List<MemoryEntity> = withContext(Dispatchers.IO) {
        db.memoryDao().getReflections(limit)
    }

    // ═══ UPGRADE 8: CONTINUAL LEARNING — IMPORTANCE PROTECTOR ═══
    // Prevents catastrophic forgetting of core skills and highly-connected memories.
    // Before consolidation decays memories, this function identifies memories that
    // are "core" and boosts their importance instead of letting them decay.

    private suspend fun protectImportantMemories() {
        val allMemories = db.memoryDao().getTopMemories(0f, 500)
        for (mem in allMemories) {
            val linkCount = db.memoryLinkDao().getLinks(mem.id).size
            val isCoreSkill = mem.category == MemoryCategory.SKILL && mem.accessCount >= 3
            val isHighlyLinked = linkCount >= 3
            val isFrequentlyAccessed = mem.accessCount >= 5
            val isProcedural = mem.memoryType == MemoryType.PROCEDURAL && mem.accessCount >= 2

            if (isCoreSkill || isHighlyLinked || isFrequentlyAccessed || isProcedural) {
                // Boost importance instead of letting it decay
                val boost = when {
                    isFrequentlyAccessed -> 1.15f  // 15% boost for frequently used
                    isCoreSkill -> 1.10f           // 10% boost for core skills
                    isHighlyLinked -> 1.08f        // 8% boost for well-connected
                    else -> 1.05f                  // 5% boost for procedural
                }
                val newImportance = (mem.importance * boost).coerceAtMost(1.0f)
                if (newImportance > mem.importance) {
                    db.memoryDao().update(mem.copy(importance = newImportance))
                }
            }
        }
    }

    // ═══ EMBEDDING UTILITIES ═══════════════════════════════

    private suspend fun embed(text: String): FloatArray? {
        val model = embeddingModel ?: return null
        return try {
            when (model) {
                is com.nexa.sdk.LlmWrapper -> {
                    // Try generateEmbedding if available, otherwise fall back to keyword-based recall
                    val method = model.javaClass.methods.firstOrNull {
                        it.name == "generateEmbedding" || it.name == "embed"
                    }
                    val result = method?.invoke(model, text)
                    when (result) {
                        is FloatArray -> result
                        is List<*> -> result.filterIsInstance<Float>().toFloatArray()
                        else -> null
                    }
                }
                is FloatArray -> model
                else -> null
            }
        } catch (e: Exception) { null }
    }

    internal fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size || a.isEmpty()) return 0f
        var dotProduct = 0.0
        var normA = 0.0
        var normB = 0.0
        for (i in a.indices) {
            dotProduct += a[i] * b[i]
            normA += a[i] * a[i].toDouble()
            normB += b[i] * b[i].toDouble()
        }
        val denominator = sqrt(normA) * sqrt(normB)
        return if (denominator == 0.0) 0f else (dotProduct / denominator).toFloat()
    }

    private fun floatArrayToByteArray(arr: FloatArray): ByteArray {
        val buffer = ByteBuffer.allocate(arr.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        buffer.asFloatBuffer().put(arr)
        return buffer.array()
    }

    private fun byteArrayToFloatArray(bytes: ByteArray): FloatArray {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val floats = FloatArray(bytes.size / 4)
        buffer.asFloatBuffer().get(floats)
        return floats
    }
}
