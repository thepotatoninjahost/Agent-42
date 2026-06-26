package com.agent42.curiosity

import ai.nexa.ml.LlmWrapper
import ai.nexa.ml.bean.GenerationConfig
import com.agent42.memory.AgentDatabase
import com.agent42.memory.KnowledgeGapEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withContext

/**
 * Curiosity-Driven Learning
 *
 * Research concept: Rather than being a passive responder, the agent actively identifies
 * areas where its knowledge is weak and surfaces them to the user. This makes the agent
 * feel alive — it has things it wants to learn, just like a person.
 *
 * When the agent answers with low confidence, receives negative feedback, or notices
 * repeated failures on a topic, it records a "knowledge gap". Over time these gaps
 * drive proactive suggestions: "I've been struggling with X — would you like to help
 * me learn?" This transforms the user from a mere question-asker into a teacher,
 * deepening the relationship and improving the agent's competence.
 *
 * Gap types:
 *   SHALLOW_KNOWLEDGE    — knows a little, not enough to be reliable
 *   MISSING_TOPIC        — no memories at all on this subject
 *   REPEATED_FAILURE     — keeps getting it wrong
 *   LOW_CONFIDENCE       — answers but is consistently uncertain
 */

data class KnowledgeGap(
    val topic: String,
    val gapType: String,
    val confidence: Float,
    val failureCount: Int,
    val notes: String?
)

data class GapReport(
    val totalGaps: Int,
    val topGaps: List<KnowledgeGap>,
    val resolvedCount: Int
)

class KnowledgeGapTracker(private val db: AgentDatabase) {

    companion object {
        const val GAP_SHALLOW_KNOWLEDGE = "SHALLOW_KNOWLEDGE"
        const val GAP_MISSING_TOPIC = "MISSING_TOPIC"
        const val GAP_REPEATED_FAILURE = "REPEATED_FAILURE"
        const val GAP_LOW_CONFIDENCE = "LOW_CONFIDENCE"

        private const val PROACTIVE_FAILURE_THRESHOLD = 2
        private const val PROACTIVE_CONFIDENCE_THRESHOLD = 0.3f
    }

    /**
     * Record a failure or low-confidence answer for a topic.
     * If a gap already exists, increments failureCount and updates confidence.
     * Otherwise creates a new gap entry.
     */
    suspend fun recordFailure(
        topic: String,
        confidence: Float,
        query: String
    ) = withContext(Dispatchers.IO) {
        val existing = db.knowledgeGapDao().searchByKeyword(topic).firstOrNull()
        val now = System.currentTimeMillis()
        if (existing != null) {
            val newCount = existing.failureCount + 1
            val newConfidence = minOf(existing.confidence, confidence)
            val updated = existing.copy(
                failureCount = newCount,
                confidence = newConfidence,
                lastFailedAt = now,
                notes = existing.notes?.let { "$it | $query" } ?: query
            )
            db.knowledgeGapDao().update(updated)
        } else {
            val gapType = when {
                confidence < 0.2f -> GAP_MISSING_TOPIC
                confidence < 0.4f -> GAP_LOW_CONFIDENCE
                else -> GAP_SHALLOW_KNOWLEDGE
            }
            val entity = KnowledgeGapEntity(
                topic = topic,
                gapType = gapType,
                confidence = confidence,
                failureCount = 1,
                lastFailedAt = now,
                notes = query
            )
            db.knowledgeGapDao().insert(entity)
        }
    }

    /**
     * Use the LLM to analyze recent memories and identify topics where
     * knowledge is thin, uncertain, or repeatedly wrong.
     */
    suspend fun identifyGaps(
        llm: LlmWrapper,
        recentMemories: List<String>
    ): List<KnowledgeGap> = withContext(Dispatchers.IO) {
        if (recentMemories.isEmpty()) return@withContext emptyList()

        val prompt = buildString {
            appendLine("You are Agent 42 reviewing your own recent memories to find knowledge weaknesses.")
            appendLine("Review the following memories and identify topics where your knowledge is:")
            appendLine("  - shallow (you know a little but not enough)")
            appendLine("  - missing (no relevant memories)")
            appendLine("  - repeatedly wrong (contradictions or errors)")
            appendLine("  - uncertain (low confidence or hedged answers)")
            appendLine()
            appendLine("For each gap, output one line in this exact format:")
            appendLine("GAP|topic|gap_type|confidence_0_to_1|brief_note")
            appendLine()
            appendLine("gap_type must be one of: SHALLOW_KNOWLEDGE, MISSING_TOPIC, REPEATED_FAILURE, LOW_CONFIDENCE")
            appendLine()
            appendLine("Memories:")
            recentMemories.forEachIndexed { i, mem ->
                appendLine("${i + 1}. $mem")
            }
            appendLine()
            appendLine("Gaps found:")
        }

        val config = GenerationConfig(max_tokens = 512, enable_thinking = true)
        val rawOutput = llm.generateStreamFlow(prompt, config).toList().joinToString("")

        parseGapLines(rawOutput)
    }

    /**
     * Return human-readable proactive suggestions for the user.
     * Only suggests gaps that have failed multiple times or have very low confidence.
     */
    suspend fun getProactiveSuggestions(): List<String> = withContext(Dispatchers.IO) {
        val unresolved = db.knowledgeGapDao().getUnresolved(limit = 50)
        val worthy = unresolved.filter {
            it.failureCount >= PROACTIVE_FAILURE_THRESHOLD || it.confidence < PROACTIVE_CONFIDENCE_THRESHOLD
        }
        worthy.map { gap ->
            when (gap.gapType) {
                GAP_MISSING_TOPIC ->
                    "I've never encountered \"${gap.topic}\" before — would you like to teach me about it?"
                GAP_REPEATED_FAILURE ->
                    "I've been struggling with \"${gap.topic}\" (${gap.failureCount} times). Could you help me understand it better?"
                GAP_LOW_CONFIDENCE ->
                    "I often feel uncertain about \"${gap.topic}\". A quick explanation from you would really help."
                GAP_SHALLOW_KNOWLEDGE ->
                    "I know a little about \"${gap.topic}\", but not enough to be reliable. Want to deepen my knowledge?"
                else ->
                    "I'd like to learn more about \"${gap.topic}\". Would you mind helping me?"
            }
        }
    }

    /**
     * Mark a gap as resolved when the agent successfully handles a previously difficult topic.
     */
    suspend fun markGapResolved(topic: String) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val matches = db.knowledgeGapDao().searchByKeyword(topic)
        matches.forEach { gap ->
            db.knowledgeGapDao().markResolved(gap.id, now)
        }
    }

    /**
     * Build a summary report of knowledge gaps for display in the UI.
     */
    suspend fun getGapReport(): GapReport = withContext(Dispatchers.IO) {
        val unresolved = db.knowledgeGapDao().getUnresolved(limit = 10)
        val totalUnresolved = db.knowledgeGapDao().getUnresolvedCount()
        // NOTE: DAO does not expose a resolved-count query, so resolvedCount
        // is set to 0 as a conservative default. A future schema upgrade could
        // add SELECT COUNT(*) FROM knowledge_gaps WHERE resolvedAt IS NOT NULL.
        GapReport(
            totalGaps = totalUnresolved,
            topGaps = unresolved.map { it.toKnowledgeGap() },
            resolvedCount = 0
        )
    }

    // ═══ INTERNALS ═══════════════════════════════════════════

    private fun parseGapLines(output: String): List<KnowledgeGap> {
        return output.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("GAP|") }
            .mapNotNull { line ->
                val parts = line.removePrefix("GAP|").split("|", limit = 4)
                if (parts.size >= 4) {
                    val topic = parts[0].trim()
                    val gapType = parts[1].trim()
                    val confidence = parts[2].trim().toFloatOrNull() ?: 0.5f
                    val notes = parts.getOrNull(3)?.trim()
                    if (topic.isNotBlank() && gapType.isNotBlank()) {
                        KnowledgeGap(topic, gapType, confidence, 0, notes)
                    } else null
                } else null
            }
            .toList()
    }

    private fun KnowledgeGapEntity.toKnowledgeGap(): KnowledgeGap {
        return KnowledgeGap(
            topic = topic,
            gapType = gapType,
            confidence = confidence,
            failureCount = failureCount,
            notes = notes
        )
    }
}
