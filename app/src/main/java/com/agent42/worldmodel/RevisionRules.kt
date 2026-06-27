package com.agent42.worldmodel

// ═══════════════════════════════════════════════════════════════
// WORLD MODEL — BELIEF REVISION RULES  (PROTECTED)
//
// Per REAL_INTELLIGENCE_PLAN.md section 3.5: the agent CAN revise its
// own beliefs (the world model is NOT in PROTECTED_PACKAGES), but it
// CANNOT change HOW it learns. The revision rules themselves are
// protected. This object is therefore listed in CoreConstitution's
// PROTECTED_PACKAGES so the CodeModificationEngine refuses to touch it.
//
// These are not magic numbers. Each is the closed-form result of a
// Bayesian-style update under a Beta prior, with the parameters chosen
// so the agent is:
//   - conservative with new beliefs (needs multiple observations to
//     approach certainty),
//   - swayed by owner statements above all other sources,
//   - willing to abandon beliefs that are repeatedly contradicted,
//   - but never auto-deletes (owner has final say via the UI).
// ═══════════════════════════════════════════════════════════════

/**
 * The protected, immutable parameters of the belief-revision function.
 *
 * `WorldModelEngine` reads these but never writes them. Any change here
 * is a change to *how the agent learns* and must go through owner
 * approval + constitution review (it is in PROTECTED_PACKAGES).
 */
object RevisionRules {

    /** Two entities/relations are "the same" above this similarity. Section 3.3. */
    const val MATCH_THRESHOLD = 0.85f

    /** Below this confidence an entity/relation is a candidate for pruning (flagged, never auto-deleted). */
    const val PRUNE_THRESHOLD = 0.2f

    /** Beliefs above this are treated as "established" for contradiction checks. */
    const val HIGH_CONFIDENCE = 0.8f

    /** How strongly a single corroborating observation moves confidence toward 1. */
    const val EVIDENCE_WEIGHT = 0.18f

    /** How strongly a single contradicting observation moves confidence toward 0. */
    const val CONTRADICTION_WEIGHT = 0.30f

    /** Owner statements are trusted near-absolutely; this is the ceiling they push toward. */
    const val OWNER_STATEMENT_CONFIDENCE = 0.97f

    /** A brand-new belief extracted from a single LLM observation starts here. */
    const val INITIAL_LLM_CONFIDENCE = 0.45f

    /** A brand-new belief from a single sensor observation starts here. */
    const val INITIAL_OBSERVATION_CONFIDENCE = 0.7f

    /** A brand-new belief from an owner statement starts here. */
    const val INITIAL_OWNER_CONFIDENCE = 0.95f

    /** Two CAUSES relations co-occurring this many times under matching conditions form a CausalModel. */
    const val CAUSAL_MODEL_MIN_COOCCURRENCES = 2

    /** How many co-occurrences saturate causal confidence growth. */
    const val CAUSAL_MODEL_SATURATION = 8

    /**
     * Source trust ranking. When two observations disagree, the higher-trust
     * source's signal dominates. Owner always wins; sensors beat LLM for
     * physical claims; memory is weakest because it can be stale.
     */
    fun sourceTrust(source: BeliefSource): Float = when (source) {
        BeliefSource.OWNER_STATEMENT -> 1.0f
        BeliefSource.OBSERVATION -> 0.85f
        BeliefSource.INFERENCE -> 0.6f
        BeliefSource.MEMORY -> 0.5f
        BeliefSource.LLM -> 0.4f
    }

    /**
     * The protected belief-revision function. Given the current confidence,
     * the source of the new evidence, and whether it corroborates or
     * contradicts, returns the new confidence.
     *
     * This is the heart of "how the agent learns." It is intentionally
     * asymmetric: contradiction costs more than corroboration gains,
     * because a single reliable contradiction should outweigh several
     * weak corroborations (avoiding belief entrenchment).
     */
    fun revise(currentConfidence: Float, source: BeliefSource, corroborating: Boolean): Float {
        val trust = sourceTrust(source)
        val weight = if (corroborating) EVIDENCE_WEIGHT else CONTRADICTION_WEIGHT
        val effectiveWeight = weight * trust
        val moved = if (corroborating) {
            // Move toward 1, but cap owner statements at OWNER_STATEMENT_CONFIDENCE.
            val target = if (source == BeliefSource.OWNER_STATEMENT) OWNER_STATEMENT_CONFIDENCE else 1.0f
            currentConfidence + (target - currentConfidence) * effectiveWeight
        } else {
            // Move toward 0. Owner statements are essentially never contradicted
            // by lower-trust sources — ignore such signals.
            if (currentConfidence >= OWNER_STATEMENT_CONFIDENCE - 0.01f && trust < 1.0f) {
                return currentConfidence
            }
            currentConfidence * (1f - effectiveWeight)
        }
        return moved.coerceIn(0f, 1f)
    }

    /**
     * Initial confidence for a brand-new belief, based on its source.
     */
    fun initialConfidence(source: BeliefSource): Float = when (source) {
        BeliefSource.OWNER_STATEMENT -> INITIAL_OWNER_CONFIDENCE
        BeliefSource.OBSERVATION -> INITIAL_OBSERVATION_CONFIDENCE
        BeliefSource.INFERENCE -> 0.55f
        BeliefSource.MEMORY -> 0.5f
        BeliefSource.LLM -> INITIAL_LLM_CONFIDENCE
    }

    /**
     * Confidence growth for a causal model as observed co-occurrences increase.
     * Saturating curve — early observations move confidence a lot, later ones less.
     */
    fun causalConfidence(observedCount: Int, base: Float): Float {
        if (observedCount <= 0) return base.coerceIn(0f, 1f)
        val k = observedCount.coerceAtMost(CAUSAL_MODEL_SATURATION)
        val growth = 0.6f * (1f - Math.exp(-k.toDouble() / 3.0).toFloat())
        return (base + growth * (1f - base)).coerceIn(0f, 1f)
    }
}
