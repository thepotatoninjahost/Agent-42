package com.agent42.prediction

import com.nexa.sdk.LlmWrapper
import com.nexa.sdk.bean.GenerationConfig
import com.nexa.sdk.bean.LlmStreamResult
import com.agent42.memory.AgentDatabase
import com.agent42.memory.ExpectationEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext

/**
 * Predictive Coding — inspired by neuroscience (Friston's Free Energy Principle / Predictive Processing).
 *
 * The brain generates expectations about incoming sensory input, then only processes
 * what is surprising (prediction error). This is metabolically efficient: most input
 * is predicted away, and only the residual "surprise" drives learning and attention.
 *
 * This module implements that architecture: before the user speaks, the agent generates
 * an expectation (topic + intent). When the actual query arrives, it computes surprise
 * (prediction error). High surprise triggers deeper reasoning (System 2).
 */
class PredictiveCoder(
    private val llm: LlmWrapper,
    private val database: AgentDatabase
) {

    companion object {
        private const val DEEP_REASONING_THRESHOLD = 0.7f
    }

    /**
     * Generate an expectation about the user's upcoming query BEFORE they speak.
     * Considers temporal patterns (morning = planning, evening = reflection),
     * recent conversation topics, and motion state (walking = brief, sitting = longer).
     *
     * @param sessionId Current conversation session
     * @param recentContext Last few conversation turns for topic continuity
     * @param timeOfDay e.g. "morning", "afternoon", "evening", "night"
     * @param motionState e.g. "walking", "sitting", "driving", "standing"
     * @return Expectation with expected topic, intent, and confidence
     */
    suspend fun generateExpectation(
        sessionId: String,
        recentContext: List<String>,
        timeOfDay: String,
        motionState: String
    ): Expectation = withContext(Dispatchers.IO) {
        val prompt = buildString {
            appendLine("You are a predictive coding module generating expectations about the user's next query.")
            appendLine()
            appendLine("Recent context:")
            recentContext.takeLast(5).forEach { appendLine("- $it") }
            appendLine()
            appendLine("Time of day: $timeOfDay")
            appendLine("Motion state: $motionState")
            appendLine()
            appendLine("Temporal patterns:")
            appendLine("- Morning users often plan, schedule, or ask about the day ahead.")
            appendLine("- Evening users often reflect, summarise, or ask about what happened.")
            appendLine("- Walking users tend to ask brief, quick questions.")
            appendLine("- Sitting users tend to ask longer, more detailed questions.")
            appendLine()
            appendLine("Predict the user's likely next topic and intent.")
            appendLine("Respond with ONLY a JSON object in this exact format:")
            appendLine("{\"topic\": \"brief topic label\", \"intent\": \"brief intent label\", \"confidence\": 0.X}")
        }

        val config = GenerationConfig(maxTokens = 128)

        val raw = StringBuilder()
        llm.generateStreamFlow(prompt, config).collect { chunk ->
            if (chunk is LlmStreamResult.Token) raw.append(chunk.text)
        }

        val text = raw.toString().trim()
        val topic = extractJsonField(text, "topic") ?: "unknown"
        val intent = extractJsonField(text, "intent") ?: "unknown"
        val confidence = extractJsonFloat(text, "confidence") ?: 0.5f

        val entity = ExpectationEntity(
            sessionId = sessionId,
            expectedTopic = topic,
            expectedIntent = intent,
            createdAt = System.currentTimeMillis()
        )
        database.expectationDao().insert(entity)

        Expectation(expectedTopic = topic, expectedIntent = intent, confidence = confidence)
    }

    /**
     * Resolve an expectation against the actual user query.
     * Computes surprise score (0 = totally expected, 1 = totally unexpected).
     *
     * @param actualQuery What the user actually said
     * @return SurpriseResult with surprise score and surprise flag
     */
    suspend fun resolveExpectation(
        actualQuery: String
    ): SurpriseResult = withContext(Dispatchers.IO) {
        val pending = database.expectationDao().getLatestUnresolved()
            ?: return@withContext SurpriseResult(
                surpriseScore = 1f,
                expectedTopic = "unknown",
                actualQuery = actualQuery,
                isSurprising = true
            )

        val surprise = computeSurprise(pending.expectedTopic, pending.expectedIntent, actualQuery)
        val isSurprising = surprise > 0.5f

        val resolved = pending.copy(
            actualQuery = actualQuery,
            surpriseScore = surprise,
            resolvedAt = System.currentTimeMillis()
        )
        database.expectationDao().update(resolved)

        SurpriseResult(
            surpriseScore = surprise,
            expectedTopic = pending.expectedTopic,
            actualQuery = actualQuery,
            isSurprising = isSurprising
        )
    }

    /**
     * Calibration score: how well do expectations match reality over time?
     * Returns average (1 - surpriseScore) over recent resolved expectations.
     * 1.0 = perfectly calibrated, 0.0 = completely miscalibrated.
     */
    suspend fun getCalibrationScore(): Float = withContext(Dispatchers.IO) {
        val since = System.currentTimeMillis() - (30L * 24 * 60 * 60 * 1000) // 30 days
        val avgSurprise = database.expectationDao().getAverageSurprise(since) ?: return@withContext 0f
        (1f - avgSurprise).coerceIn(0f, 1f)
    }

    /**
     * If surprise exceeds threshold, trigger deeper reasoning (System 2).
     * High prediction error means the fast heuristic system (System 1) is insufficient.
     */
    fun shouldTriggerDeepReasoning(surpriseScore: Float): Boolean {
        return surpriseScore > DEEP_REASONING_THRESHOLD
    }

    /**
     * Compute surprise by comparing expected topic/intent to actual query.
     * Uses keyword overlap: low overlap = high surprise.
     */
    private fun computeSurprise(expectedTopic: String, expectedIntent: String, actualQuery: String): Float {
        val expectedWords = (expectedTopic + " " + expectedIntent).lowercase()
            .split(Regex("\\s+"))
            .filter { it.length > 2 }
            .toSet()

        val actualWords = actualQuery.lowercase()
            .split(Regex("\\s+"))
            .filter { it.length > 2 }
            .toSet()

        if (expectedWords.isEmpty() && actualWords.isEmpty()) return 0f
        if (expectedWords.isEmpty() || actualWords.isEmpty()) return 1f

        val intersection = expectedWords.intersect(actualWords).size
        val union = expectedWords.union(actualWords).size
        val overlap = if (union == 0) 0f else intersection.toFloat() / union.toFloat()

        // Surprise = 1 - overlap, but boost surprise when actual is completely off-topic
        return (1f - overlap).coerceIn(0f, 1f)
    }

    private fun extractJsonField(text: String, field: String): String? {
        val regex = Regex(""""?$field"?\s*:\s*"([^"]+)""", RegexOption.IGNORE_CASE)
        return regex.find(text)?.groupValues?.get(1)
    }

    private fun extractJsonFloat(text: String, field: String): Float? {
        val regex = Regex(""""?$field"?\s*:\s*([\d.]+)""", RegexOption.IGNORE_CASE)
        return regex.find(text)?.groupValues?.get(1)?.toFloatOrNull()
    }
}

/**
 * An expectation about the user's upcoming query.
 */
data class Expectation(
    val expectedTopic: String,
    val expectedIntent: String,
    val confidence: Float
)

/**
 * Result of resolving an expectation against reality.
 */
data class SurpriseResult(
    val surpriseScore: Float,
    val expectedTopic: String,
    val actualQuery: String,
    val isSurprising: Boolean
)
