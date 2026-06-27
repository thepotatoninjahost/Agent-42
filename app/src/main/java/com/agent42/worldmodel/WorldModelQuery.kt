package com.agent42.worldmodel

import com.agent42.memory.AgentDatabase
import com.nexa.sdk.LlmWrapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.sqrt

// ═══════════════════════════════════════════════════════════════
// WORLD MODEL QUERY — THE READ API
//
// The reasoning engine consults this BEFORE calling the LLM (section 3.4
// + 3.5). Everything here is read-only against the world model tables;
// mutation happens through [WorldModelEngine].
// ═══════════════════════════════════════════════════════════════

/** A node + its incident edges, returned by graph queries. */
data class EntityNeighborhood(
    val entity: WorldEntity,
    val outgoing: List<Pair<WorldRelation, WorldEntity>>,
    val incoming: List<Pair<WorldRelation, WorldEntity>>
)

/** The full belief state for one entity: current confidence + history. */
data class BeliefState(
    val entity: WorldEntity,
    val revisions: List<BeliefRevision>
)

/** A chain of causation rooted at an entity. */
data class CausalChain(
    val root: WorldEntity,
    val steps: List<CausalStep>
) {
    data class CausalStep(
        val causeRelation: WorldRelation,
        val causeEntity: WorldEntity,
        val effectEntity: WorldEntity,
        val model: CausalModel
    )
}

class WorldModelQuery(
    private val db: AgentDatabase,
    private val llm: LlmWrapper? = null
) {

    private val entityDao get() = db.worldEntityDao()
    private val relationDao get() = db.worldRelationDao()
    private val causalDao get() = db.causalModelDao()
    private val revisionDao get() = db.beliefRevisionDao()

    // ═══ ENTITY LOOKUP ════════════════════════════════════════

    /** Top-k entities relevant to [query], by similarity to the query text. */
    suspend fun relevantEntities(query: String, k: Int = 8): List<WorldEntity> =
        withContext(Dispatchers.IO) {
            val queryEmbedding = embed(query)
            val pool = entityDao.getTopEntities(limit = 200)
            if (pool.isEmpty()) return@withContext emptyList()
            val scored = pool.map { entity ->
                val score = if (queryEmbedding != null && entity.embedding != null) {
                    val entEmb = byteArrayToFloats(entity.embedding!!)
                    cosine(queryEmbedding, entEmb)
                } else {
                    tokenOverlap(query, entity.label + " " + entity.canonicalLabel)
                }
                entity to (score * 0.7f + entity.confidence * 0.3f)
            }.sortedByDescending { it.second }
            scored.take(k).map { it.first }
        }

    suspend fun getById(id: Long): WorldEntity? = withContext(Dispatchers.IO) {
        entityDao.getById(id)
    }

    suspend fun searchByKeyword(keyword: String, limit: Int = 20): List<WorldEntity> =
        withContext(Dispatchers.IO) { entityDao.searchByKeyword(keyword, limit) }

    /** Top entities by confidence — for the World Model UI screen. */
    suspend fun topEntities(limit: Int = 100): List<WorldEntity> =
        withContext(Dispatchers.IO) { entityDao.getTopEntities(limit) }

    /** Entities flagged for owner review (low confidence or type contradictions). */
    suspend fun flaggedEntities(): List<WorldEntity> =
        withContext(Dispatchers.IO) { entityDao.getFlagged() }

    // ═══ RELATIONS ════════════════════════════════════════════

    suspend fun relationsFor(entityId: Long): EntityNeighborhood =
        withContext(Dispatchers.IO) {
            val entity = entityDao.getById(entityId) ?: return@withContext emptyNeighborhood(entityId)
            val rels = relationDao.getForEntity(entityId)
            val ids = rels.flatMap { listOf(it.subjectId, it.objectId) }.distinct()
            val byId = entityDao.getByIds(ids).associateBy { it.id }
            val outgoing = mutableListOf<Pair<WorldRelation, WorldEntity>>()
            val incoming = mutableListOf<Pair<WorldRelation, WorldEntity>>()
            for (rel in rels) {
                if (rel.subjectId == entityId) {
                    byId[rel.objectId]?.let { outgoing += rel to it }
                } else if (rel.objectId == entityId) {
                    byId[rel.subjectId]?.let { incoming += rel to it }
                }
            }
            EntityNeighborhood(entity, outgoing, incoming)
        }

    /** All relations of a given type involving [entityId] (either direction). */
    suspend fun relationsOfType(entityId: Long, type: RelationType): List<WorldRelation> =
        withContext(Dispatchers.IO) {
            relationDao.getForEntity(entityId).filter { it.relationType == type.name }
        }

    // ═══ CAUSAL ══════════════════════════════════════════════

    /**
     * Walk the causal graph outward from [rootId]. Each step is a CAUSES
     * relation whose model exists and has confidence above [minConfidence].
     * Bounded by [maxDepth] to keep it tractable on-device.
     */
    suspend fun causalChain(rootId: Long, maxDepth: Int = 4, minConfidence: Float = 0.3f): CausalChain =
        withContext(Dispatchers.IO) {
            val root = entityDao.getById(rootId)
                ?: return@withContext CausalChain(
                    WorldEntity(id = rootId, type = WorldEntityType.OTHER.name, label = "?", source = BeliefSource.INFERENCE.name),
                    emptyList()
                )
            val steps = mutableListOf<CausalChain.CausalStep>()
            // Keep entity ids and relation ids in separate sets — they share a key
            // space (autoGenerate) and would false-positive collide if mixed.
            val visitedEntities = mutableSetOf<Long>(rootId)
            val visitedRelations = mutableSetOf<Long>()
            var frontier = listOf(rootId)
            for (depth in 0 until maxDepth) {
                if (frontier.isEmpty()) break
                val nextFrontier = mutableListOf<Long>()
                for (current in frontier) {
                    val causes = relationDao.getOutgoing(current, RelationType.CAUSES.name)
                    for (cause in causes) {
                        if (cause.id in visitedRelations) continue
                        val model = causalDao.findByCause(cause.id) ?: continue
                        if (model.confidence < minConfidence) continue
                        val effectEntity = entityDao.getById(cause.objectId) ?: continue
                        if (effectEntity.id in visitedEntities) continue
                        visitedRelations += cause.id
                        val causeEntity = entityDao.getById(current) ?: continue
                        steps.add(
                            CausalChain.CausalStep(
                                causeRelation = cause,
                                causeEntity = causeEntity,
                                effectEntity = effectEntity,
                                model = model
                            )
                        )
                        visitedEntities += effectEntity.id
                        nextFrontier += effectEntity.id
                    }
                }
                frontier = nextFrontier
            }
            CausalChain(root, steps)
        }

    // ═══ BELIEF STATE ════════════════════════════════════════

    suspend fun beliefState(entityId: Long): BeliefState = withContext(Dispatchers.IO) {
        val entity = entityDao.getById(entityId) ?: return@withContext BeliefState(
            WorldEntity(id = entityId, type = WorldEntityType.OTHER.name, label = "?", source = BeliefSource.INFERENCE.name),
            emptyList()
        )
        val revisions = revisionDao.getForTarget(RevisionTargetType.ENTITY.name, entityId)
        BeliefState(entity, revisions)
    }

    /** Recent revisions across the whole model — for the UI audit trail. */
    suspend fun recentRevisions(limit: Int = 50): List<BeliefRevision> =
        withContext(Dispatchers.IO) { revisionDao.getRecent(limit) }

    // ═══ SNAPSHOT (for LLM context injection) ═════════════════

    /**
     * A bounded textual snapshot of the currently-relevant world state, ready
     * to prepend to the LLM prompt. Section 3.4 + 3.5 integration point.
     *
     * Picks the top entities by relevance to [query], renders each with its
     * confidence, source, and high-confidence relations. Strictly bounded by
     * [maxChars] so it never blows up the context window.
     */
    suspend fun snapshot(query: String, maxChars: Int = 1200): String = withContext(Dispatchers.IO) {
        val entities = relevantEntities(query, k = 6)
        if (entities.isEmpty()) return@withContext ""

        val sb = StringBuilder()
        sb.appendLine("## World Model — current beliefs relevant to \"$query\"")
        for (entity in entities) {
            if (sb.length > maxChars) break
            val confPct = (entity.confidence * 100).toInt()
            sb.appendLine("- [${entity.type.lowercase()}] ${entity.canonicalLabel} " +
                "(${confPct}% confident, source=${entity.source.lowercase()})")
            val rels = relationDao.getForEntity(entity.id)
                .filter { it.confidence >= RevisionRules.HIGH_CONFIDENCE - 0.15f }
                .take(4)
            for (rel in rels) {
                val otherId = if (rel.subjectId == entity.id) rel.objectId else rel.subjectId
                val other = entityDao.getById(otherId)
                if (other == null) continue
                val arrow = if (rel.subjectId == entity.id) "→" else "←"
                val dir = if (rel.subjectId == entity.id) "${rel.relationType.lowercase()} $arrow ${other.canonicalLabel}"
                          else "${other.canonicalLabel} $arrow ${rel.relationType.lowercase()}"
                sb.appendLine("    · $dir (${(rel.confidence * 100).toInt()}%)")
                if (sb.length > maxChars) break
            }
        }
        sb.toString().take(maxChars)
    }

    // ═══ HIGH-CONFIDENCE FACTS (for ConstraintChecker extension) ═══

    /**
     * High-confidence world-model facts rendered as natural-language sentences.
     * Section 3.5: ConstraintChecker pulls these to extend its known-facts DB.
     */
    suspend fun highConfidenceFacts(minConfidence: Float = RevisionRules.HIGH_CONFIDENCE, limit: Int = 50): List<String> =
        withContext(Dispatchers.IO) {
            val rels = relationDao.getHighConfidence(minConfidence, limit)
            val entityIds = rels.flatMap { listOf(it.subjectId, it.objectId) }.distinct()
            val byId = entityDao.getByIds(entityIds).associateBy { it.id }
            rels.mapNotNull { rel ->
                val s = byId[rel.subjectId] ?: return@mapNotNull null
                val o = byId[rel.objectId] ?: return@mapNotNull null
                renderFact(s, rel, o)
            }
        }

    private fun renderFact(subject: WorldEntity, rel: WorldRelation, obj: WorldEntity): String {
        val verb = when (RelationType.valueOf(rel.relationType)) {
            RelationType.IS_A -> "is a"
            RelationType.PART_OF -> "is part of"
            RelationType.LOCATED_AT -> "is located at"
            RelationType.OCCURRED_DURING -> "occurred during"
            RelationType.CAUSED_BY -> "was caused by"
            RelationType.CAUSES -> "causes"
            RelationType.LIKES -> "likes"
            RelationType.DISLIKES -> "dislikes"
            RelationType.USES -> "uses"
            RelationType.PRODUCES -> "produces"
            RelationType.PRECEDES -> "precedes"
            RelationType.FOLLOWS -> "follows"
            RelationType.RELATED_TO -> "is related to"
            RelationType.OWNS -> "owns"
            RelationType.KNOWS -> "knows"
            RelationType.DEPENDS_ON -> "depends on"
        }
        return "${subject.canonicalLabel} $verb ${obj.canonicalLabel}."
    }

    // ═══ STATS (for UI + drives) ══════════════════════════════

    suspend fun stats(): WorldModelStats = withContext(Dispatchers.IO) {
        WorldModelStats(
            entityCount = entityDao.count(),
            relationCount = relationDao.count(),
            causalModelCount = causalDao.count(),
            revisionCount = revisionDao.count(),
            flaggedCount = entityDao.getFlagged().size,
            byType = WorldEntityType.values().associateWith { entityDao.countByType(it.name) }
        )
    }

    // ═══ HELPERS ═════════════════════════════════════════════

    private fun emptyNeighborhood(id: Long) = EntityNeighborhood(
        WorldEntity(id = id, type = WorldEntityType.OTHER.name, label = "?", source = BeliefSource.INFERENCE.name),
        emptyList(), emptyList()
    )

    private fun embed(text: String): FloatArray? {
        val model = llm ?: return null
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

    private fun byteArrayToFloats(bytes: ByteArray): FloatArray {
        val buf = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val out = FloatArray(bytes.size / 4)
        buf.asFloatBuffer().get(out)
        return out
    }

    private val STOPWORDS = setOf(
        "the","a","an","is","are","was","were","be","of","to","in","on","at","by","for","with","and","or",
        "i","you","he","she","it","we","they","what","which","who","when","where","why","how","my","your",
        "this","that","these","those"
    )

    private fun tokenOverlap(a: String, b: String): Float {
        val sa = a.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length > 1 && it !in STOPWORDS }.toSet()
        val sb = b.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length > 1 && it !in STOPWORDS }.toSet()
        if (sa.isEmpty() || sb.isEmpty()) return 0f
        val inter = sa.intersect(sb).size
        if (inter == 0) return 0f
        return inter.toFloat() / (sqrt(sa.size.toFloat()) * sqrt(sb.size.toFloat()))
    }
}

/** Aggregate stats for the World Model UI screen. */
data class WorldModelStats(
    val entityCount: Int,
    val relationCount: Int,
    val causalModelCount: Int,
    val revisionCount: Int,
    val flaggedCount: Int,
    val byType: Map<WorldEntityType, Int>
)
