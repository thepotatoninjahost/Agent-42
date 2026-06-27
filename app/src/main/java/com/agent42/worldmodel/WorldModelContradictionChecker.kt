package com.agent42.worldmodel

import com.nexa.sdk.LlmWrapper
import com.nexa.sdk.bean.GenerationConfig
import com.nexa.sdk.bean.LlmStreamResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// ═══════════════════════════════════════════════════════════════
// WORLD MODEL CONTRADICTION CHECKER
//
// Section 3.5 integration: "when a contradiction is detected in LLM
// output, check it against world-model beliefs; emit a ConstraintViolation
// if the LLM contradicts a high-confidence belief."
//
// Implemented as a standalone collaborator rather than baked into
// MetacognitiveMonitor so the monitor's constructor (and its callers in
// AppInitializer) stay unchanged. ReasoningEngine calls this in its
// verification phase and lifts the results into ConstraintViolation
// emissions.
// ═══════════════════════════════════════════════════════════════

/** A single contradiction between an LLM answer and a world-model belief. */
data class WorldModelContradiction(
    val beliefFact: String,
    val answerClaim: String,
    val severity: Float,
    val entityType: String,   // "entity" or "relation"
    val targetId: Long
)

class WorldModelContradictionChecker(
    private val query: WorldModelQuery,
    private val llm: LlmWrapper? = null
) {

    private val config = GenerationConfig(maxTokens = 128)

    /**
     * Scan [answer] against the agent's high-confidence world-model beliefs.
     * Returns one [WorldModelContradiction] per belief the answer appears to
     * contradict.
     *
     * Two paths:
     *  1. If the LLM is available, ask it to judge each (belief, answer) pair —
     *     this catches semantic contradictions ("I'm in Paris" vs belief "owner
     *     is in Tokyo") that keyword overlap would miss.
     *  2. Otherwise, fall back to a negation/keyword heuristic: if the answer
     *     contains a negated form of the belief's subject+object, flag it.
     */
    suspend fun check(answer: String): List<WorldModelContradiction> = withContext(Dispatchers.IO) {
        if (answer.isBlank()) return@withContext emptyList()
        val facts = query.highConfidenceFacts(
            minConfidence = RevisionRules.HIGH_CONFIDENCE,
            limit = 40
        )
        if (facts.isEmpty()) return@withContext emptyList()

        val contradictions = mutableListOf<WorldModelContradiction>()
        for (fact in facts) {
            if (llm != null) {
                val verdict = llmJudgesContradiction(llm, fact, answer)
                if (verdict.contradicts) {
                    contradictions.add(
                        WorldModelContradiction(
                            beliefFact = fact,
                            answerClaim = verdict.claim,
                            severity = verdict.severity,
                            entityType = "fact",
                            targetId = -1L
                        )
                    )
                }
            } else if (heuristicContradiction(fact, answer)) {
                contradictions.add(
                    WorldModelContradiction(
                        beliefFact = fact,
                        answerClaim = answer.take(200),
                        severity = 0.6f,
                        entityType = "fact",
                        targetId = -1L
                    )
                )
            }
        }
        contradictions
    }

    private suspend fun llmJudgesContradiction(
        llm: LlmWrapper,
        belief: String,
        answer: String
    ): Verdict = withContext(Dispatchers.IO) {
        val prompt = """
            You are a strict fact-checker. A belief the agent holds with high confidence is:
            BELIEF: "$belief"
            An answer the agent just produced is:
            ANSWER: "${answer.take(600)}"
            Does the ANSWER contradict the BELIEF? A contradiction means the answer asserts
            something that cannot be true if the belief is true (or vice versa). Mere
            omission is NOT a contradiction.
            Respond with ONLY JSON: {"contradicts": true|false, "claim": "the contradicting sentence from the answer, or empty", "severity": 0.0-1.0}
        """.trimIndent()
        val sb = StringBuilder()
        try {
            llm.generateStreamFlow(prompt, config).collect { chunk ->
                if (chunk is LlmStreamResult.Token) sb.append(chunk.text)
            }
        } catch (e: Exception) {
            return@withContext Verdict(false, "", 0f)
        }
        val text = sb.toString().trim()
        val contradicts = Regex(""""?contradicts"?\s*:\s*(true|false)""", RegexOption.IGNORE_CASE)
            .find(text)?.groupValues?.get(1)?.lowercase() == "true"
        val claim = Regex(""""?claim"?\s*:\s*"((?:[^"\\]|\\.)*)"""").find(text)?.groupValues?.get(1)
            ?.replace("\\\"", "\"") ?: ""
        val severity = Regex(""""?severity"?\s*:\s*([\d.]+)""").find(text)?.groupValues?.get(1)
            ?.toFloatOrNull() ?: 0.7f
        Verdict(contradicts, claim, severity.coerceIn(0f, 1f))
    }

    /**
     * Heuristic fallback: does [answer] contain a negated restatement of [belief]?
     * Crude but honest — looks for "not"/"isn't"/"never" near the belief's keywords.
     */
    private fun heuristicContradiction(belief: String, answer: String): Boolean {
        val b = belief.lowercase()
        val a = answer.lowercase()
        // Pull the subject + object out of "X <verb> Y." form.
        val parts = b.removeSuffix(".").split(" ", limit = 3)
        if (parts.size < 3) return false
        val subject = parts[0]
        val obj = parts.last().trim()
        if (subject.length < 3 || obj.length < 3) return false
        if (!a.contains(subject) || !a.contains(obj)) return false
        val negations = listOf(" not ", "n't ", " never ", " no ", "false", "incorrect", "wrong")
        return negations.any { a.contains(it) }
    }

    private data class Verdict(val contradicts: Boolean, val claim: String, val severity: Float)
}
