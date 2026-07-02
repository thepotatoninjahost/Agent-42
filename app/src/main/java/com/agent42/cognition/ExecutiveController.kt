package com.agent42.cognition

import com.nexa.sdk.LlmWrapper
import kotlin.math.sqrt

// ═══════════════════════════════════════════════════════════════
// EXECUTIVE CONTROLLER — THE FOREMAN
//
// One job: the gate decision. After the Thinker produces (or refines)
// an answer, the Foreman decides whether to:
//   - RESPOND     : the answer is on-topic and substantial — stop, emit it.
//   - REFOCUS     : the answer drifted off-topic — re-inject the task once.
//   - CONTINUE    : the Thinker is still producing new, relevant material.
//
// The decision is driven by SEMANTIC signals, not a fixed cap or a timer:
//   - relevance  = cosine(answer, query)   — is it on-topic?
//   - novelty    = 1 - cosine(newPass, accumulated) — is there new information?
//
// A hard pass-count ceiling exists ONLY as a safety valve against genuine
// runaway (see [maxPasses]). It is NOT the primary control — the primary
// control is the novelty/relevance gate, so productive thinking is never
// caged. This is the executive function whose absence caused the agent to
// "never stop reasoning" (perseveration).
//
// Embeddings are obtained via the same reflection idiom used by
// WorldModelQuery/MemorySystem (the SDK exposes generateEmbedding/embed).
// If embeddings are unavailable, the Foreman falls back to token-overlap
// cosine so it still functions — never silently does nothing.
// ═══════════════════════════════════════════════════════════════

/** The Foreman's one output. */
sealed class ForemanDecision {
    /** Answer is good enough — stop thinking and respond. */
    object Respond : ForemanDecision()
    /** Answer drifted off-topic — re-inject the original task, then continue. */
    class Refocus(val reason: String) : ForemanDecision()
    /** Still producing new, relevant material — keep going. */
    object Continue : ForemanDecision()
}

class ExecutiveController(private val llm: () -> LlmWrapper?) {

    /** Safety-valve ceiling on Thinker passes. High, so it only catches runaway. */
    private val maxPasses: Int = 10

    /** Below this answer/query cosine, the answer is considered off-topic. */
    private val relevanceThreshold: Float = 0.35f

    /** Minimum answer length (chars) before "respond" is even considered —
     *  guards against responding to a near-empty first pass. */
    private val minAnswerChars: Int = 40

    /**
     * The gate decision after a Thinker pass.
     *
     * @param query          the owner's original request
     * @param answer         the Thinker's accumulated answer so far
     * @param passCount      how many Thinker passes have run (0-indexed)
     * @param prevAnswer     the answer before this pass (for novelty), or null
     */
    fun decide(
        query: String,
        answer: String,
        passCount: Int,
        prevAnswer: String? = null
    ): ForemanDecision {
        // Safety valve — only fires on genuine runaway, never on normal reasoning.
        if (passCount >= maxPasses) return ForemanDecision.Respond

        val trimmed = answer.trim()

        // Not enough material yet — keep going (unless we've somehow produced
        // nothing across many passes, in which case respond rather than loop).
        if (trimmed.length < minAnswerChars) {
            return if (passCount >= 2) ForemanDecision.Respond else ForemanDecision.Continue
        }

        val qEmb = embed(query) ?: return relevanceFallback(query, trimmed, passCount)
        val aEmb = embed(trimmed) ?: return relevanceFallback(query, trimmed, passCount)
        val relevance = cosine(aEmb, qEmb)

        // Off-topic → refocus (re-inject the task), not kill. One re-injection
        // per drift; the caller controls how many refocuses it tolerates.
        if (relevance < relevanceThreshold) {
            return ForemanDecision.Refocus(
                "answer relevance ${"%.2f".format(relevance)} below threshold " +
                    "${"%.2f".format(relevanceThreshold)} — re-inject original task"
            )
        }

        // On-topic and substantial. If this pass added little new meaning vs the
        // previous pass, the Thinker is repeating itself → respond. Otherwise
        // it's still productive → continue.
        if (prevAnswer != null && prevAnswer.trim().length >= minAnswerChars) {
            val prevEmb = embed(prevAnswer.trim())
            if (prevEmb != null) {
                val similarity = cosine(aEmb, prevEmb)
                // High similarity to the previous pass = no new information.
                if (similarity > 0.85f) return ForemanDecision.Respond
            }
        }

        // On-topic, substantial, and (if comparable) still producing new info.
        // For a first sufficient pass with no prior to compare, respond — the
        // answer is on-topic and complete enough; don't pad.
        return if (passCount == 0) ForemanDecision.Respond else ForemanDecision.Continue
    }

    /** Token-overlap fallback when embeddings are unavailable. */
    private fun relevanceFallback(query: String, answer: String, passCount: Int): ForemanDecision {
        val rel = tokenOverlap(answer, query)
        if (rel < 0.15f) {
            return ForemanDecision.Refocus(
                "token-overlap relevance ${"%.2f".format(rel)} too low — re-inject task"
            )
        }
        return if (passCount == 0) ForemanDecision.Respond else ForemanDecision.Continue
    }

    // ── Embedding + cosine (same idiom as WorldModelQuery / MemorySystem) ──

    private fun embed(text: String): FloatArray? {
        val model = llm() ?: return null
        return try {
            val method = model.javaClass.methods.firstOrNull {
                it.name == "generateEmbedding" || it.name == "embed"
            }
            when (val result = method?.invoke(model, text)) {
                is FloatArray -> result
                is List<*> -> result.filterIsInstance<Float>().toFloatArray()
                else -> null
            }
        } catch (e: Exception) { null }
    }

    private fun cosine(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size || a.isEmpty()) return 0f
        var dot = 0.0; var na = 0.0; var nb = 0.0
        for (i in a.indices) {
            dot += a[i] * b[i]
            na += a[i] * a[i].toDouble()
            nb += b[i] * b[i].toDouble()
        }
        val denom = sqrt(na) * sqrt(nb)
        return if (denom == 0.0) 0f else (dot / denom).toFloat()
    }

    private fun tokenOverlap(a: String, b: String): Float {
        val ta = a.lowercase().split(Regex("\\W+")).filter { it.length > 2 }.toSet()
        val tb = b.lowercase().split(Regex("\\W+")).filter { it.length > 2 }.toSet()
        if (ta.isEmpty() || tb.isEmpty()) return 0f
        val inter = ta.intersect(tb).size.toFloat()
        val union = ta.union(tb).size.toFloat()
        return if (union == 0f) 0f else inter / union
    }
}
