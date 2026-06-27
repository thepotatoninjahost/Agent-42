package com.agent42.worldmodel

import com.agent42.memory.AgentDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.exp

// ═══════════════════════════════════════════════════════════════
// WORLD MODEL CONSOLIDATOR
//
// Section 3.3 "Consolidation": a periodic background job that
//   · merges duplicate entities (same label, same type → one),
//   · prunes low-confidence unconfirmed entries older than N days
//     (flagged, never auto-deleted — owner has final say),
//   · recalibrates confidence based on recency (beliefs not
//     corroborated in a long time decay slightly),
//   · merges duplicate relations and renormalizes their evidence.
//
// Runs on the same periodic loop as the other cognitive maintenance
// tasks (see AgentViewModel.schedulePeriodicTasks). All mutations go
// through the DAOs and log BeliefRevisions so the audit trail is
// intact.
// ═══════════════════════════════════════════════════════════════

/** Result of one consolidation pass — surfaced to the UI / logs. */
data class ConsolidationResult(
    val entitiesMerged: Int,
    val entitiesFlagged: Int,
    val relationsMerged: Int,
    val relationsFlagged: Int,
    val confidencesRecalibrated: Int
) {
    val totalChanges: Int get() = entitiesMerged + entitiesFlagged + relationsMerged + relationsFlagged + confidencesRecalibrated
}

class WorldModelConsolidator(
    private val db: AgentDatabase,
    /** Beliefs older than this (ms) with no recent corroboration decay. */
    private val staleAfterMs: Long = 14L * 24 * 60 * 60 * 1000,    // 14 days
    /** Decay factor applied per stale period. */
    private val staleDecayFactor: Float = 0.92f,
    /** How similar two same-type entities must be to merge. */
    private val mergeThreshold: Float = RevisionRules.MATCH_THRESHOLD
) {

    private val entityDao get() = db.worldEntityDao()
    private val relationDao get() = db.worldRelationDao()
    private val causalDao get() = db.causalModelDao()
    private val revisionDao get() = db.beliefRevisionDao()
    private val observationDao get() = db.observationDao()

    /**
     * Run one full consolidation pass. Safe to call repeatedly; each step is
     * idempotent. Returns a summary of what changed.
     */
    suspend fun consolidate(): ConsolidationResult = withContext(Dispatchers.IO) {
        val mergedEntities = mergeDuplicateEntities()
        val flaggedEntities = flagStaleLowConfidenceEntities()
        val mergedRelations = mergeDuplicateRelations()
        val flaggedRelations = flagStaleLowConfidenceRelations()
        val recalibrated = recalibrateConfidencesByRecency()
        // Observation table grows forever otherwise; keep recent + unprocessed.
        observationDao.purgeOlderThan(System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000)

        ConsolidationResult(
            entitiesMerged = mergedEntities,
            entitiesFlagged = flaggedEntities,
            relationsMerged = mergedRelations,
            relationsFlagged = flaggedRelations,
            confidencesRecalibrated = recalibrated
        )
    }

    // ═══ MERGE DUPLICATE ENTITIES ═════════════════════════════

    /**
     * Find same-type entities with label similarity ≥ [mergeThreshold] and
     * merge the lower-confidence one into the higher-confidence one: remap its
     * relations, union its attributes, combine evidence, then delete it.
     */
    private suspend fun mergeDuplicateEntities(): Int = withContext(Dispatchers.IO) {
        var merged = 0
        val byType = WorldEntityType.values().associateWith { entityDao.getByType(it.name) }
        for ((_, entities) in byType) {
            if (entities.size < 2) continue
            val removed = BooleanArray(entities.size)
            for (i in entities.indices) {
                if (removed[i]) continue
                val keeper = entities[i]
                for (j in (i + 1) until entities.size) {
                    if (removed[j]) continue
                    val dup = entities[j]
                    val sim = labelSimilarity(keeper.label, dup.label, keeper.embedding, dup.embedding)
                    if (sim >= mergeThreshold) {
                        mergeEntityInto(keeper, dup)
                        removed[j] = true
                        merged++
                    }
                }
            }
        }
        merged
    }

    private suspend fun mergeEntityInto(keeper: WorldEntity, dup: WorldEntity) {
        val now = System.currentTimeMillis()
        // Remap every relation that pointed at `dup` to point at `keeper`.
        val rels = relationDao.getForEntity(dup.id)
        for (rel in rels) {
            val newSubject = if (rel.subjectId == dup.id) keeper.id else rel.subjectId
            val newObject = if (rel.objectId == dup.id) keeper.id else rel.objectId
            // Skip self-loops introduced by the merge.
            if (newSubject == newObject) {
                // Clean up any causal models that referenced this relation first.
                relationDao.deleteCausalModelsForRelation(rel.id)
                relationDao.deleteById(rel.id)
                continue
            }
            // If an identical relation already exists on keeper, fold evidence.
            val existing = relationDao.findExact(newSubject, newObject, rel.relationType)
            if (existing != null && existing.id != rel.id) {
                val combinedConf = maxOf(existing.confidence, rel.confidence)
                val combinedEv = existing.evidenceCount + rel.evidenceCount
                relationDao.updateEvidence(existing.id, combinedConf, combinedEv, now)
                // The folded relation is going away — drop causal models that
                // referenced it (its evidence has been absorbed into `existing`).
                relationDao.deleteCausalModelsForRelation(rel.id)
                relationDao.deleteById(rel.id)
            } else {
                relationDao.update(rel.copy(subjectId = newSubject, objectId = newObject))
            }
        }
        // Re-point any causal models referencing dup's relations are left as-is
        // (their cause/effect relation ids still resolve); orphaned ones get
        // cleaned when their underlying relation is deleted above.
        // Union attributes.
        val keeperAttrs = parseAttrs(keeper.attributes)
        val dupAttrs = parseAttrs(dup.attributes)
        val unioned = keeperAttrs.toMutableMap().apply { putAll(dupAttrs) }
        // Combine aliases.
        val aliases = (keeperAttrs["aliases"]?.split(',') ?: emptyList()) +
            (dupAttrs["aliases"]?.split(',') ?: emptyList()) + listOf(dup.label)
        unioned["aliases"] = aliases.filter { it.isNotBlank() }.distinct().joinToString(",")
        // Keeper absorbs dup's confidence (take the max — they're the same belief).
        val newConf = maxOf(keeper.confidence, dup.confidence)
        entityDao.update(
            keeper.copy(
                confidence = newConf,
                attributes = attrsToJson(unioned),
                lastUpdated = now
            )
        )
        // Delete the duplicate.
        // (deleteForEntity already cleared its relations; safe to remove the row.)
        entityDao.deleteById(dup.id)
        revisionDao.insert(
            BeliefRevision(
                targetType = RevisionTargetType.ENTITY.name,
                targetId = keeper.id,
                oldConfidence = keeper.confidence,
                newConfidence = newConf,
                reason = "CONSOLIDATION_MERGE",
                evidence = "merged duplicate '${dup.label}' (id=${dup.id}) into '${keeper.label}'"
            )
        )
    }

    // ═══ FLAG STALE LOW-CONFIDENCE ════════════════════════════

    /** Flag (don't delete) entities below PRUNE_THRESHOLD that haven't been touched in [staleAfterMs]. */
    private suspend fun flagStaleLowConfidenceEntities(): Int = withContext(Dispatchers.IO) {
        val candidates = entityDao.getPruneCandidates(RevisionRules.PRUNE_THRESHOLD)
        val cutoff = System.currentTimeMillis() - staleAfterMs
        var flagged = 0
        for (ent in candidates) {
            if (ent.lastUpdated < cutoff && !ent.flaggedForReview) {
                entityDao.setFlagged(ent.id, true)
                revisionDao.insert(
                    BeliefRevision(
                        targetType = RevisionTargetType.ENTITY.name,
                        targetId = ent.id,
                        oldConfidence = ent.confidence,
                        newConfidence = ent.confidence,
                        reason = "STALE_FLAGGED",
                        evidence = "unconfirmed for >${staleAfterMs / (24*60*60*1000)}d, confidence ${(ent.confidence*100).toInt()}%"
                    )
                )
                flagged++
            }
        }
        flagged
    }

    private suspend fun flagStaleLowConfidenceRelations(): Int = withContext(Dispatchers.IO) {
        val candidates = relationDao.getPruneCandidates(RevisionRules.PRUNE_THRESHOLD)
        val cutoff = System.currentTimeMillis() - staleAfterMs
        var flagged = 0
        for (rel in candidates) {
            if (rel.lastConfirmed < cutoff && !rel.flaggedForReview) {
                relationDao.setFlagged(rel.id, true)
                flagged++
            }
        }
        flagged
    }

    // ═══ MERGE DUPLICATE RELATIONS ════════════════════════════

    /**
     * Find relations with identical (subject, object, type) that slipped past
     * the ingest-time dedup (e.g. created before a merge) and fold them.
     */
    private suspend fun mergeDuplicateRelations(): Int = withContext(Dispatchers.IO) {
        var merged = 0
        // Cheap scan: load higher-confidence relations, group by signature.
        val all = relationDao.getHighConfidence(0f, 500)
        val bySignature = all.groupBy { Triple(it.subjectId, it.objectId, it.relationType) }
        for ((_, group) in bySignature) {
            if (group.size < 2) continue
            val keeper = group.maxByOrNull { it.evidenceCount }!!
            val now = System.currentTimeMillis()
            for (dup in group) {
                if (dup.id == keeper.id) continue
                val combinedConf = maxOf(keeper.confidence, dup.confidence)
                val combinedEv = keeper.evidenceCount + dup.evidenceCount
                relationDao.updateEvidence(keeper.id, combinedConf, combinedEv, now)
                // Drop causal models referencing the duplicate before deleting it,
                // so no wm_causal_models row is left pointing at a gone relation.
                relationDao.deleteCausalModelsForRelation(dup.id)
                relationDao.deleteById(dup.id)
                merged++
            }
        }
        merged
    }

    // ═══ RECENCY RECALIBRATION ════════════════════════════════

    /**
     * Beliefs not corroborated in [staleAfterMs] decay slightly. This is the
     * "use it or lose it" pressure that keeps the model from accumulating
     * stale certainty. Owner-stamped beliefs are exempt (owner statements are
     * permanent until the owner changes them).
     */
    private suspend fun recalibrateConfidencesByRecency(): Int = withContext(Dispatchers.IO) {
        val cutoff = System.currentTimeMillis() - staleAfterMs
        // Pull entities that haven't been touched recently and aren't owner-stamped.
        var count = 0
        // getTopEntities gives us a bounded scan window.
        for (ent in entityDao.getTopEntities(300)) {
            if (ent.source == BeliefSource.OWNER_STATEMENT.name) continue
            if (ent.lastUpdated >= cutoff) continue
            if (ent.confidence <= 0.05f) continue   // already near-zero; leave it
            val periodsStale = ((System.currentTimeMillis() - ent.lastUpdated).toFloat() / staleAfterMs).toInt().coerceAtLeast(1)
            val decay = staleDecayFactor.pow(periodsStale)
            val newConf = (ent.confidence * decay).coerceIn(0f, 1f)
            if (kotlin.math.abs(newConf - ent.confidence) < 0.005f) continue
            entityDao.updateConfidence(ent.id, newConf, System.currentTimeMillis())
            revisionDao.insert(
                BeliefRevision(
                    targetType = RevisionTargetType.ENTITY.name,
                    targetId = ent.id,
                    oldConfidence = ent.confidence,
                    newConfidence = newConf,
                    reason = "RECENCY_DECAY",
                    evidence = "uncorroborated ${periodsStale}× stale period(s)"
                )
            )
            count++
        }
        count
    }

    // ═══ HELPERS ══════════════════════════════════════════════

    private fun labelSimilarity(
        a: String, b: String, embA: ByteArray?, embB: ByteArray?
    ): Float {
        if (embA != null && embB != null && embA.size == embB.size) {
            val fa = toFloats(embA); val fb = toFloats(embB)
            var dot = 0.0; var na = 0.0; var nb = 0.0
            for (i in fa.indices) { dot += fa[i]*fb[i]; na += fa[i]*fa[i]; nb += fb[i]*fb[i] }
            val denom = kotlin.math.sqrt(na) * kotlin.math.sqrt(nb)
            if (denom > 0) return (((dot/denom).toFloat() + 1f) / 2f).coerceIn(0f, 1f)
        }
        // Token-overlap cosine fallback.
        val sa = a.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length > 1 }.toSet()
        val sb = b.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length > 1 }.toSet()
        if (sa.isEmpty() || sb.isEmpty()) return 0f
        val inter = sa.intersect(sb).size
        if (inter == 0) return 0f
        return inter.toFloat() / (kotlin.math.sqrt(sa.size.toFloat()) * kotlin.math.sqrt(sb.size.toFloat()))
    }

    private fun toFloats(bytes: ByteArray): FloatArray {
        val buf = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val out = FloatArray(bytes.size / 4)
        buf.asFloatBuffer().get(out)
        return out
    }

    private fun parseAttrs(json: String): Map<String, String> {
        val out = mutableMapOf<String, String>()
        for (m in Regex("\"([^\"]+)\"\\s*:\\s*\"([^\"]*)\"").findAll(json)) {
            out[m.groupValues[1]] = m.groupValues[2]
        }
        return out
    }

    private fun attrsToJson(attrs: Map<String, String>): String {
        if (attrs.isEmpty()) return "{}"
        return attrs.entries.joinToString(prefix = "{", postfix = "}") { (k, v) ->
            "\"${k.replace("\\","\\\\").replace("\"","\\\"")}\":\"${v.replace("\\","\\\\").replace("\"","\\\"")}\""
        }
    }

    /** kotlin.math.pow on Float returns Double; this keeps the call site tidy. */
    private fun Float.pow(n: Int): Float = exp(n * kotlin.math.ln(this.toDouble())).toFloat()
}
