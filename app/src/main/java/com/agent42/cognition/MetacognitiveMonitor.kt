package com.agent42.cognition

import com.agent42.memory.AgentDatabase
import com.agent42.memory.MetacognitiveEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// ═══════════════════════════════════════════════════════════════
// METACOGNITIVE MONITOR
//
// Metacognition — the agent monitors its own reasoning DURING
// generation, not just after. It watches the LLM output stream for
// problems and can flag them in real-time.
//
// Chunk-level analysis is pure heuristics (no LLM calls) so it
// can run on every token stream chunk without latency.
// Final review can be deeper because it runs once, after generation.
// ═══════════════════════════════════════════════════════════════

/**
 * A detected issue in the agent's own reasoning output.
 */
data class MetacognitiveIssue(
    val issueType: String,
    val description: String,
    val severity: Float,
    val actionTaken: String,
    val chunkText: String? = null
)

/**
 * Aggregated statistics about metacognitive issues.
 */
data class IssueStats(
    val totalIssues: Int,
    val highSeverityCount: Int,
    val byType: Map<String, Int>
)

/**
 * Monitors the agent's reasoning in real-time.
 *
 * - [analyzeChunk] is called for **every** chunk during streaming.
 *   It must be fast — pure heuristics, no LLM calls.
 * - [finalReview] runs once after generation completes and can
 *   afford deeper analysis.
 */
class MetacognitiveMonitor(private val db: AgentDatabase) {

    companion object {
        // Severity thresholds
        private const val MINOR_THRESHOLD = 0.3f
        private const val MODERATE_THRESHOLD = 0.7f

        // Hedging language that signals uncertainty
        private val HEDGING_WORDS = setOf(
            "perhaps", "possibly", "maybe", "might", "could",
            "seems", "appears", "suggests", "likely", "probably",
            "unclear", "uncertain", "approximate", "roughly"
        )

        // Contradiction pairs
        private val CONTRADICTION_PAIRS = listOf(
            setOf("yes", "no"),
            setOf("correct", "incorrect"),
            setOf("true", "false"),
            setOf("right", "wrong"),
            setOf("valid", "invalid")
        )
    }

    // ═══ CHUNK-LEVEL ANALYSIS (FAST, NO LLM) ═══════════════════

    /**
     * Analyze a single [text] chunk of streaming LLM output for problems.
     *
     * [accumulatedText] is the full text generated so far (including this chunk).
     * [interactionId] ties the event to a specific user interaction.
     *
     * Returns a [MetacognitiveIssue] if a problem is detected, or null.
     * This is called for EVERY chunk — it must be O(text length) and
     * must not make network or LLM calls.
     */
    fun analyzeChunk(
        text: String,
        accumulatedText: String,
        interactionId: Long
    ): MetacognitiveIssue? {
        val lowerAccumulated = accumulatedText.lowercase()
        val lowerChunk = text.lowercase()

        // 1. CIRCULAR_LOGIC — same phrase repeated 3+ times
        val circular = detectCircularLogic(lowerAccumulated)
        if (circular != null) {
            return createIssue(
                issueType = "CIRCULAR_LOGIC",
                description = circular,
                baseSeverity = 0.75f,
                chunkText = text
            )
        }

        // 2. CONTRADICTION — yes/no or correct/incorrect for same claim
        val contradiction = detectContradiction(lowerAccumulated)
        if (contradiction != null) {
            return createIssue(
                issueType = "CONTRADICTION",
                description = contradiction,
                baseSeverity = 0.85f,
                chunkText = text
            )
        }

        // 3. HALLUCINATION_INDICATOR — hedging spiked + specific factual claims
        val hallucination = detectHallucinationIndicator(lowerChunk, lowerAccumulated)
        if (hallucination != null) {
            return createIssue(
                issueType = "HALLUCINATION_INDICATOR",
                description = hallucination,
                baseSeverity = 0.65f,
                chunkText = text
            )
        }

        // 4. CONFIDENCE_DROP — sudden shift from confident to hedging
        val confidenceDrop = detectConfidenceDrop(lowerChunk, lowerAccumulated, text)
        if (confidenceDrop != null) {
            return createIssue(
                issueType = "CONFIDENCE_DROP",
                description = confidenceDrop,
                baseSeverity = 0.55f,
                chunkText = text
            )
        }

        // 5. REPETITION_LOOP — identical 5-word sequences > twice
        val repetition = detectRepetitionLoop(lowerAccumulated)
        if (repetition != null) {
            return createIssue(
                issueType = "REPETITION_LOOP",
                description = repetition,
                baseSeverity = 0.6f,
                chunkText = text
            )
        }

        return null
    }

    // ═══ FINAL REVIEW (POST-GENERATION) ════════════════════════

    /**
     * A more thorough review after generation completes.
     *
     * This runs once per response, so it can afford deeper heuristics
     * (or an LLM call, if one is injected in a future version).
     * Currently it re-runs all chunk heuristics on the full text and
     * adds a coherence check.
     */
    suspend fun finalReview(
        fullText: String,
        query: String,
        interactionId: Long
    ): List<MetacognitiveIssue> = withContext(Dispatchers.Default) {
        val issues = mutableListOf<MetacognitiveIssue>()
        val lowerFull = fullText.lowercase()

        // Re-run chunk detectors on the complete text with higher confidence
        detectCircularLogic(lowerFull)?.let {
            issues += createIssue("CIRCULAR_LOGIC", it, 0.8f, null)
        }
        detectContradiction(lowerFull)?.let {
            issues += createIssue("CONTRADICTION", it, 0.9f, null)
        }
        detectHallucinationIndicator(lowerFull, lowerFull)?.let {
            issues += createIssue("HALLUCINATION_INDICATOR", it, 0.7f, null)
        }
        detectRepetitionLoop(lowerFull)?.let {
            issues += createIssue("REPETITION_LOOP", it, 0.65f, null)
        }

        // Additional final-review-only checks
        detectOffTopic(fullText, query)?.let {
            issues += createIssue("OFF_TOPIC", it, 0.6f, null)
        }
        detectUnfinishedAnswer(fullText)?.let {
            issues += createIssue("UNFINISHED", it, 0.5f, null)
        }

        // Persist every detected issue to the database
        issues.forEach { issue ->
            db.metacognitiveDao().insert(
                MetacognitiveEvent(
                    interactionId = interactionId,
                    issueType = issue.issueType,
                    description = issue.description,
                    severity = issue.severity,
                    detectedAt = System.currentTimeMillis(),
                    actionTaken = issue.actionTaken,
                    chunkText = issue.chunkText
                )
            )
        }

        issues
    }

    // ═══ STATISTICS ════════════════════════════════════════════

    /**
     * Return counts of metacognitive issues grouped by type,
     * plus the number of high-severity issues.
     */
    suspend fun getIssueStats(since: Long): IssueStats = withContext(Dispatchers.IO) {
        val typeCounts = db.metacognitiveDao().getIssueTypeCounts(since)
        val highSeverity = db.metacognitiveDao().getHighSeverityCount(since)
        val total = typeCounts.sumOf { it.count }
        IssueStats(
            totalIssues = total,
            highSeverityCount = highSeverity,
            byType = typeCounts.associate { it.issueType to it.count }
        )
    }

    // ═══ HEURISTIC DETECTORS ═════════════════════════════════

    /**
     * Detect if any phrase of 4+ words appears 3+ times in the text.
     */
    private fun detectCircularLogic(text: String): String? {
        val words = text.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.size < 12) return null // need at least 3 repetitions of 4 words

        val phraseCounts = mutableMapOf<String, Int>()
        for (i in 0..words.size - 4) {
            val phrase = words.subList(i, i + 4).joinToString(" ")
            phraseCounts[phrase] = phraseCounts.getOrDefault(phrase, 0) + 1
        }

        val offender = phraseCounts.entries
            .filter { it.value >= 3 }
            .maxByOrNull { it.value }

        return offender?.let {
            "Phrase '${it.key}' repeated ${it.value} times — possible circular reasoning."
        }
    }

    /**
     * Detect if the text contains contradictory affirmative/negative
     * markers for the same underlying claim.
     *
     * Heuristic: presence of both words in any contradiction pair.
     */
    private fun detectContradiction(text: String): String? {
        val tokens = text.split(Regex("[^a-z]+")).filter { it.isNotBlank() }.toSet()
        for (pair in CONTRADICTION_PAIRS) {
            val hasFirst = tokens.contains(pair.first())
            val hasSecond = tokens.contains(pair.last())
            if (hasFirst && hasSecond) {
                return "Text contains both '${pair.first()}' and '${pair.last()}' — possible contradiction."
            }
        }
        return null
    }

    /**
     * Detect a spike in hedging language combined with specific factual claims.
     *
     * Heuristic: >= 3 hedging words AND presence of digits or capitalized
     * proper nouns (simple proxy for "specific factual claim").
     */
    private fun detectHallucinationIndicator(chunk: String, accumulated: String): String? {
        val hedgingCount = HEDGING_WORDS.count { chunk.contains(it) }
        if (hedgingCount < 3) return null

        // Check for specific factual claims: digits, dates, or capitalized words
        val hasSpecificClaim = chunk.contains(Regex("\\d")) ||
            chunk.contains(Regex("\\b[A-Z][a-z]+\\b"))

        return if (hasSpecificClaim) {
            "High hedging ($hedgingCount words) combined with specific claims — possible hallucination."
        } else null
    }

    /**
     * Detect a sudden shift from confident language to hedging.
     *
     * Heuristic: if the chunk contains hedging words and the preceding
     * portion of the accumulated text had very few hedging words.
     */
    private fun detectConfidenceDrop(
        chunk: String,
        accumulated: String,
        originalChunk: String
    ): String? {
        val chunkHedges = HEDGING_WORDS.count { chunk.contains(it) }
        if (chunkHedges == 0) return null

        // Remove the chunk from accumulated to get "before" state
        val before = accumulated.removeSuffix(originalChunk.lowercase())
        val beforeHedges = HEDGING_WORDS.count { before.contains(it) }

        // If the chunk introduces hedging where there was little before,
        // and the chunk is short enough to be a sudden shift
        return if (beforeHedges <= 1 && chunkHedges >= 2 && originalChunk.length < 200) {
            "Sudden shift to hedging language within this chunk — confidence drop detected."
        } else null
    }

    /**
     * Detect identical 5-word sequences appearing more than twice.
     */
    private fun detectRepetitionLoop(text: String): String? {
        val words = text.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.size < 15) return null // need 3 occurrences of 5 words

        val seqCounts = mutableMapOf<String, Int>()
        for (i in 0..words.size - 5) {
            val seq = words.subList(i, i + 5).joinToString(" ")
            seqCounts[seq] = seqCounts.getOrDefault(seq, 0) + 1
        }

        val offender = seqCounts.entries
            .filter { it.value > 2 }
            .maxByOrNull { it.value }

        return offender?.let {
            "5-word sequence '${it.key}' repeated ${it.value} times — repetition loop."
        }
    }

    /**
     * Final-review-only: detect if the answer drifts away from the query topic.
     * Simple keyword overlap heuristic.
     */
    private fun detectOffTopic(fullText: String, query: String): String? {
        val queryWords = query.lowercase()
            .split(Regex("[^a-z]+"))
            .filter { it.length > 3 }
            .toSet()
        if (queryWords.isEmpty()) return null

        val textWords = fullText.lowercase()
            .split(Regex("[^a-z]+"))
            .filter { it.length > 3 }
            .toSet()

        val overlap = queryWords.intersect(textWords).size
        val ratio = overlap.toFloat() / queryWords.size

        return if (ratio < 0.15f && fullText.length > 100) {
            "Answer shares few keywords with query — possible off-topic drift."
        } else null
    }

    /**
     * Final-review-only: detect if the answer ends abruptly.
     */
    private fun detectUnfinishedAnswer(fullText: String): String? {
        val trimmed = fullText.trim()
        val unfinishedMarkers = listOf(
            "...", "etc.", "and so on", "in conclusion", "to summarize",
            "finally", "lastly"
        )
        val hasMarker = unfinishedMarkers.any { trimmed.lowercase().contains(it) }
        val endsCleanly = trimmed.endsWith(".") || trimmed.endsWith("!") || trimmed.endsWith("?") || trimmed.endsWith("\"")
        return if (!endsCleanly && trimmed.length > 50 && !hasMarker) {
            "Answer does not end with terminal punctuation — may be unfinished."
        } else null
    }

    // ═══ ISSUE FACTORY ═════════════════════════════════════════

    /**
     * Create a [MetacognitiveIssue] with the appropriate [actionTaken]
     * based on severity band.
     */
    private fun createIssue(
        issueType: String,
        description: String,
        baseSeverity: Float,
        chunkText: String?
    ): MetacognitiveIssue {
        val severity = baseSeverity.coerceIn(0.0f, 1.0f)
        val action = when {
            severity < MINOR_THRESHOLD -> "LOGGED"
            severity < MODERATE_THRESHOLD -> "FLAGGED"
            else -> "ALERT"
        }
        return MetacognitiveIssue(
            issueType = issueType,
            description = description,
            severity = severity,
            actionTaken = action,
            chunkText = chunkText
        )
    }
}
