package com.agent42.prediction

import ai.nexa.ml.LlmWrapper
import ai.nexa.ml.bean.GenerationConfig
import com.agent42.memory.AgentDatabase
import com.agent42.memory.PredictionEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext

/**
 * Internal World Model — inspired by Yann LeCun's JEPA (Joint Embedding Predictive Architecture).
 *
 * The agent maintains an internal model of the user's likely next query. It predicts,
 * scores its own accuracy, and identifies "surprises" (prediction errors) that signal
 * the world model needs updating. This is the foundation of self-supervised learning:
 * the agent learns by predicting its inputs, not by predicting labels.
 *
 * Key insight: The brain is primarily a prediction machine. Conscious attention is
 * triggered by prediction error (surprise). This engine replicates that mechanism.
 */
class PredictionEngine(
    private val llm: LlmWrapper,
    private val database: AgentDatabase
) {

    companion object {
        private const val SIMILARITY_THRESHOLD = 0.3f
        private const val SURPRISE_THRESHOLD = 0.3f
        private const val ROLLING_WINDOW_SIZE = 100
    }

    /**
     * Predict what the user will ask next, based on conversation flow and temporal patterns.
     * Stores the prediction in the database for later resolution.
     *
     * @param sessionId Current conversation session identifier
     * @param recentQueries Last N user queries in chronological order
     * @param timeOfDay e.g. "morning", "afternoon", "evening", "night"
     * @return The predicted next query string
     */
    suspend fun predictNextQuery(
        sessionId: String,
        recentQueries: List<String>,
        timeOfDay: String
    ): String = withContext(Dispatchers.IO) {
        val contextSnapshot = recentQueries.takeLast(5).joinToString("\n") { "User: $it" }

        val prompt = buildString {
            appendLine("You are an internal world model predicting the user's next message.")
            appendLine("Recent conversation:")
            recentQueries.takeLast(5).forEach { appendLine("- $it") }
            appendLine()
            appendLine("Time of day: $timeOfDay")
            appendLine()
            appendLine("People often ask follow-up questions, reference earlier topics, or shift to related topics.")
            appendLine("Predict the user's next message in 1-2 sentences. Be specific and natural.")
            appendLine("Respond with ONLY the predicted message, no quotes or explanations.")
        }

        val config = GenerationConfig(
            max_tokens = 128,
            enable_thinking = false,
            temperature = 0.4f
        )

        val predictedQuery = StringBuilder()
        llm.generateStreamFlow(prompt, config).collect { chunk ->
            predictedQuery.append(chunk)
        }

        val cleanPrediction = predictedQuery.toString().trim().replace(""", "").replace(""", "")

        val entity = PredictionEntity(
            sessionId = sessionId,
            predictedQuery = cleanPrediction,
            predictedAt = System.currentTimeMillis(),
            contextSnapshot = contextSnapshot
        )
        database.predictionDao().insert(entity)

        cleanPrediction
    }

    /**
     * Resolve a pending prediction against the actual user query.
     * Computes Jaccard similarity (word overlap) and flags surprises.
     *
     * @param sessionId Current conversation session
     * @param actualQuery What the user actually said
     * @return PredictionResult with similarity, correctness, and surprise flag
     */
    suspend fun resolvePrediction(
        sessionId: String,
        actualQuery: String
    ): PredictionResult = withContext(Dispatchers.IO) {
        val pending = database.predictionDao().getLatestUnresolved(sessionId)
            ?: return@withContext PredictionResult(
                similarityScore = 0f,
                wasCorrect = false,
                isSurprising = true,
                predictedQuery = "",
                actualQuery = actualQuery
            )

        val similarity = jaccardSimilarity(pending.predictedQuery, actualQuery)
        val wasCorrect = similarity > SIMILARITY_THRESHOLD
        val isSurprising = !wasCorrect

        val resolved = pending.copy(
            actualQuery = actualQuery,
            resolvedAt = System.currentTimeMillis(),
            similarityScore = similarity,
            wasCorrect = wasCorrect
        )
        database.predictionDao().update(resolved)

        PredictionResult(
            similarityScore = similarity,
            wasCorrect = wasCorrect,
            isSurprising = isSurprising,
            predictedQuery = pending.predictedQuery,
            actualQuery = actualQuery
        )
    }

    /**
     * Rolling accuracy over the last [ROLLING_WINDOW_SIZE] resolved predictions.
     * Returns 0f if no data available.
     */
    suspend fun getPredictionAccuracy(): Float = withContext(Dispatchers.IO) {
        val recent = database.predictionDao().getResolved(ROLLING_WINDOW_SIZE)
        if (recent.isEmpty()) return@withContext 0f

        val correct = recent.count { it.wasCorrect }
        correct.toFloat() / recent.size.toFloat()
    }

    /**
     * Low similarity means the world model was wrong — the agent should pay more attention.
     * This is the attention mechanism driven by prediction error.
     */
    suspend fun shouldPayMoreAttention(similarityScore: Float): Boolean {
        return similarityScore < SURPRISE_THRESHOLD
    }

    /**
     * Retrieve recent resolved predictions for UI or debugging.
     */
    suspend fun getRecentPredictions(limit: Int): List<PredictionEntity> = withContext(Dispatchers.IO) {
        database.predictionDao().getResolved(limit)
    }

    /**
     * Jaccard similarity: |A ∩ B| / |A ∪ B| over tokenised word sets.
     * Simple, fast, and effective for short text overlap.
     */
    private fun jaccardSimilarity(a: String, b: String): Float {
        val setA = a.lowercase().split(Regex("\\s+")).filter { it.length > 2 }.toSet()
        val setB = b.lowercase().split(Regex("\\s+")).filter { it.length > 2 }.toSet()
        if (setA.isEmpty() && setB.isEmpty()) return 1f
        if (setA.isEmpty() || setB.isEmpty()) return 0f

        val intersection = setA.intersect(setB).size
        val union = setA.union(setB).size
        return if (union == 0) 0f else intersection.toFloat() / union.toFloat()
    }
}

/**
 * Result of resolving a prediction against reality.
 */
data class PredictionResult(
    val similarityScore: Float,
    val wasCorrect: Boolean,
    val isSurprising: Boolean,
    val predictedQuery: String,
    val actualQuery: String
)
