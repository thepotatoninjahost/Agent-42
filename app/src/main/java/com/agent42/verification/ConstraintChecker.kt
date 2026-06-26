package com.agent42.verification

import com.nexa.sdk.LlmWrapper
import com.nexa.sdk.bean.GenerationConfig
import com.nexa.sdk.bean.LlmStreamResult
import com.agent42.memory.AgentDatabase
import com.agent42.memory.KnownFactDao
import com.agent42.memory.KnownFactEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext

/**
 * Energy-Based Verification
 *
 * Research concept: Instead of asking the LLM "is this correct?" (which is unreliable
 * because the LLM can hallucinate the verification itself), we check the answer against
 * a database of *known facts* extracted over time. This is external, grounded verification
 * — the "energy" of a claim is low when it aligns with stored facts and high when it
 * contradicts them. Inspired by energy-based models and retrieval-augmented fact-checking.
 *
 * Facts are extracted from the agent's own answers, stored with confidence scores,
 * and their reliability is tracked over time. Facts that are repeatedly contradicted
 * are pruned, preventing a polluted fact base.
 */

data class Contradiction(
    val factId: Long,
    val factText: String,
    val answerClaim: String,
    val severity: Float
)

data class VerificationResult(
    val verified: Boolean,
    val contradictions: List<Contradiction>,
    val confidence: Float,
    val notes: String?
)

class ConstraintChecker(private val db: AgentDatabase) {

    private val factDao: KnownFactDao get() = db.knownFactDao()

    private val extractionConfig = GenerationConfig(maxTokens = 512)

    private val verificationConfig = GenerationConfig(maxTokens = 128)

    /**
     * Uses the LLM to extract concrete, verifiable factual claims from the
     * agent's own answer text. Each extracted fact is stored as a [KnownFactEntity]
     * in the local database for future verification.
     *
     * @return List of extracted fact strings.
     */
    suspend fun extractFacts(
        llm: LlmWrapper,
        text: String,
        interactionId: Long
    ): List<String> = withContext(Dispatchers.IO) {
        val prompt = """
            Extract concrete, verifiable factual claims from the following text.
            Rules:
            - Only extract facts, not opinions or predictions.
            - Each fact must be a single, self-contained sentence.
            - If no verifiable facts are present, respond with "NONE".

            Text:
            $text

            Respond with one fact per line. No numbering, no bullets, no extra commentary.
        """.trimIndent()

        val sb = StringBuilder()
        llm.generateStreamFlow(prompt, extractionConfig).collect { chunk -> if (chunk is LlmStreamResult.Token) sb.append(chunk.text) }

        val raw = sb.toString().trim()
        if (raw.contains("NONE", ignoreCase = true)) return@withContext emptyList<String>()

        val facts = raw.lines()
            .map { it.trim() }
            .filter { it.isNotBlank() && it.length > 10 }
            .distinct()

        val now = System.currentTimeMillis()
        facts.forEach { fact ->
            val entity = KnownFactEntity(
                fact = fact,
                category = inferCategory(fact),
                source = "interaction:$interactionId",
                confidence = 0.8f,
                createdAt = now
            )
            factDao.insert(entity)
        }

        facts
    }

    /**
     * Checks the given [answer] against all known facts in the database.
     * For each claim extracted from the answer, searches known facts in the
     * same category and uses the LLM to detect contradictions.
     *
     * @return [VerificationResult] with contradiction list and confidence score.
     */
    suspend fun verifyAnswer(
        llm: LlmWrapper,
        answer: String,
        query: String
    ): VerificationResult = withContext(Dispatchers.IO) {
        val totalFacts = factDao.getCount()
        if (totalFacts == 0) {
            return@withContext VerificationResult(
                verified = true,
                contradictions = emptyList(),
                confidence = 0.5f,
                notes = "No known facts to verify against yet."
            )
        }

        // Step 1: Extract claims from the answer
        val claims = extractClaimsLocally(answer)
        if (claims.isEmpty()) {
            return@withContext VerificationResult(
                verified = true,
                contradictions = emptyList(),
                confidence = 1.0f,
                notes = "No verifiable claims in answer."
            )
        }

        // Step 2: Check each claim against known facts
        val contradictions = mutableListOf<Contradiction>()
        var checkedCount = 0

        for (claim in claims) {
            val category = inferCategory(claim)
            val relevantFacts = factDao.getByCategory(category, limit = 20)
                .ifEmpty { factDao.getTopFacts(limit = 20) }

            for (fact in relevantFacts) {
                checkedCount++
                if (hasKeywordOverlap(claim, fact.fact)) {
                    val isContradiction = checkContradictionWithLlm(llm, claim, fact.fact)
                    if (isContradiction) {
                        contradictions.add(
                            Contradiction(
                                factId = fact.id,
                                factText = fact.fact,
                                answerClaim = claim,
                                severity = 1.0f - fact.confidence
                            )
                        )
                    }
                }
            }
        }

        val confidence = if (checkedCount > 0) {
            1.0f - (contradictions.size.toFloat() / checkedCount)
        } else {
            1.0f
        }

        VerificationResult(
            verified = contradictions.isEmpty(),
            contradictions = contradictions,
            confidence = confidence.coerceIn(0f, 1f),
            notes = if (contradictions.isNotEmpty()) {
                "Found ${contradictions.size} contradiction(s) against known facts."
            } else {
                "Answer consistent with known facts."
            }
        )
    }

    /**
     * Records that a known fact was contradicted by incrementing its
     * violation count. Facts with high violation counts will be pruned
     * by [pruneUnreliableFacts].
     */
    suspend fun recordContradiction(factId: Long) = withContext(Dispatchers.IO) {
        factDao.recordViolation(factId)
    }

    /**
     * Returns facts with high confidence and low violation count,
     * i.e. the most reliable subset of the knowledge base.
     */
    suspend fun getReliableFacts(limit: Int): List<KnownFactEntity> = withContext(Dispatchers.IO) {
        factDao.getTopFacts(limit)
            .filter { it.confidence >= 0.7f && it.violatedCount <= 2 }
    }

    /**
     * Removes facts that have been violated too many times — they were
     * probably wrong to begin with (either hallucinated during extraction
     * or context-dependent). This keeps the fact base clean and trustworthy.
     */
    suspend fun pruneUnreliableFacts() = withContext(Dispatchers.IO) {
        factDao.purgeUnreliable(threshold = 0.3f, minViolations = 3)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Internal helpers
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Simple local claim extraction: split answer into sentences and
     * filter for ones that look like factual assertions.
     */
    private fun extractClaimsLocally(answer: String): List<String> {
        return answer.split(Regex("[.!?]\\s+"))
            .map { it.trim() }
            .filter { it.length > 15 }
            .filter { sentence ->
                // Heuristic: factual sentences often contain numbers, dates,
                // or specific named entities. Skip questions and opinions.
                val lower = sentence.lowercase()
                !lower.startsWith("i think") &&
                !lower.startsWith("i believe") &&
                !lower.startsWith("in my opinion") &&
                !lower.endsWith("?")
            }
    }

    /**
     * Keyword overlap heuristic: if claim and fact share no significant
     * words, skip the expensive LLM contradiction check.
     */
    private fun hasKeywordOverlap(claim: String, fact: String): Boolean {
        val claimWords = claim.lowercase().split(Regex("\\W+")).toSet() - stopWords
        val factWords = fact.lowercase().split(Regex("\\W+")).toSet() - stopWords
        return claimWords.intersect(factWords).size >= 2
    }

    /**
     * Uses the LLM to determine if a claim contradicts a known fact.
     * Prompt is constrained to yes/no with brief explanation for reliability.
     */
    private suspend fun checkContradictionWithLlm(
        llm: LlmWrapper,
        claim: String,
        fact: String
    ): Boolean {
        val prompt = """
            Does the following statement contradict the known fact?
            Answer with ONLY "YES" or "NO", followed by a one-sentence explanation.

            Known fact: "$fact"
            Statement to check: "$claim"
        """.trimIndent()

        val sb = StringBuilder()
        llm.generateStreamFlow(prompt, verificationConfig).collect { chunk -> if (chunk is LlmStreamResult.Token) sb.append(chunk.text) }
        val response = sb.toString().trim().lowercase()
        return response.startsWith("yes")
    }

    /**
     * Infers a broad category for a fact/claim based on keyword matching.
     * Used to narrow the search space in the fact database.
     */
    private fun inferCategory(text: String): String {
        val lower = text.lowercase()
        return when {
            lower.containsAny(listOf("date", "year", "century", "ago", "january", "february", "march", "april", "may", "june", "july", "august", "september", "october", "november", "december")) -> "temporal"
            lower.containsAny(listOf("number", "percent", "million", "billion", "thousand", "hundred", "dozen", "score", "1", "2", "3", "4", "5", "6", "7", "8", "9", "0")) -> "quantitative"
            lower.containsAny(listOf("located in", "capital", "country", "city", "river", "mountain", "ocean", "continent")) -> "geographic"
            lower.containsAny(listOf("discovered", "invented", "founded", "built", "created", "developed")) -> "historical"
            lower.containsAny(listOf("causes", "treats", "symptom", "disease", "medicine", "protein", "cell", "organ")) -> "biomedical"
            else -> "general"
        }
    }

    private fun String.containsAny(keywords: List<String>): Boolean =
        keywords.any { this.contains(it) }

    private val stopWords = setOf(
        "the", "a", "an", "is", "are", "was", "were", "be", "been",
        "being", "have", "has", "had", "do", "does", "did", "will",
        "would", "could", "should", "may", "might", "must", "shall",
        "can", "need", "dare", "ought", "used", "to", "of", "in",
        "for", "on", "with", "at", "by", "from", "as", "into",
        "through", "during", "before", "after", "above", "below",
        "between", "under", "again", "further", "then", "once",
        "here", "there", "when", "where", "why", "how", "all",
        "each", "few", "more", "most", "other", "some", "such",
        "no", "nor", "not", "only", "own", "same", "so", "than",
        "too", "very", "just", "and", "but", "if", "or", "because",
        "until", "while", "this", "that", "these", "those", "i",
        "me", "my", "myself", "we", "our", "you", "your", "he",
        "him", "his", "she", "her", "it", "its", "they", "them",
        "their", "what", "which", "who", "whom", "am"
    )
}
