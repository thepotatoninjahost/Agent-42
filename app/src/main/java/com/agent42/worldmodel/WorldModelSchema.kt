package com.agent42.worldmodel

import androidx.room.*
import kotlinx.coroutines.flow.Flow

// ═══════════════════════════════════════════════════════════════
// WORLD MODEL — ROOM SCHEMA
//
// A persistent, structured knowledge graph the agent maintains and
// reasons OVER before it ever calls the LLM. Not chat memory (that
// already exists in com.agent42.memory). This is a model of reality:
// entities, relations, causal links, temporal state, uncertainty.
//
// Design source: docs/REAL_INTELLIGENCE_PLAN.md section 3.2.
//
// TypeConverters (List<Long>, List<String>) are reused from
// com.agent42.memory.AgentConverters — registered on AgentDatabase.
// ═══════════════════════════════════════════════════════════════

// ═══ ENUMS ═══════════════════════════════════════════════════

/** High-level category of a thing in the world. */
enum class WorldEntityType {
    PERSON, PLACE, OBJECT, EVENT, CONCEPT, SELF, AGENT, ORGANIZATION, TOOL, OTHER
}

/** Where a belief came from — drives how much it is trusted. */
enum class BeliefSource {
    /** Direct sensor reading or phone state. Highest trust for physical facts. */
    OBSERVATION,
    /** The agent inferred this from other beliefs or LLM reasoning. */
    INFERENCE,
    /** The owner stated it directly. Cannot be contradicted by lower-trust sources. */
    OWNER_STATEMENT,
    /** The LLM produced it. Treated as a hypothesis until corroborated. */
    LLM,
    /** Extracted from a stored memory. */
    MEMORY
}

/** Kind of edge between two entities in the graph. */
enum class RelationType {
    IS_A,            // taxonomic: dog is_a animal
    PART_OF,         // mereological: wheel part_of car
    LOCATED_AT,      // spatial: keys located_at kitchen
    OCCURRED_DURING, // temporal: meeting occurred_during morning
    CAUSED_BY,       // causal seed: rain caused_by storm (see CausalModel)
    CAUSES,          // causal seed: storm causes rain
    LIKES,           // affective: owner likes coffee
    DISLIKES,
    USES,            // functional: agent uses tool
    PRODUCES,
    PRECEDES,        // temporal order: lunch precedes dinner
    FOLLOWS,
    RELATED_TO,      // generic associative fallback
    OWNS,            // ownership: owner owns phone
    KNOWS,           // social: personA knows personB
    DEPENDS_ON       // functional dependency
}

/** What kind of target a revision touched. */
enum class RevisionTargetType { ENTITY, RELATION }

// ═══ ENTITIES ════════════════════════════════════════════════

/**
 * A node in the world model: a thing the agent believes exists.
 *
 * `confidence` is a Bayesian-ish belief in [0,1]. It moves toward 1 with
 * corroborating evidence and toward 0 with contradiction (see
 * [WorldModelEngine] revision rules). Below [WorldModelEngine.PRUNE_THRESHOLD]
 * an entity is a candidate for pruning — never auto-deleted; the owner may
 * correct it.
 *
 * `embedding` is optional: when the LLM exposes an embedding method it is
 * populated and used for similarity matching; otherwise matching falls back
 * to token-overlap cosine on the label.
 */
@Entity(
    tableName = "wm_entities",
    indices = [
        Index("type"),
        Index("label"),
        Index("groundedConceptId"),
        Index("confidence"),
        Index("lastUpdated")
    ]
)
data class WorldEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: String,                       // WorldEntityType.name
    val label: String,                      // linguistic label, lowercase canonical
    val canonicalLabel: String = label,     // display form ("Coffee" vs "coffee")
    val groundedConceptId: Long? = null,    // FK → grounded concept (system 2.3, future)
    val confidence: Float = 0.5f,
    val createdAt: Long = System.currentTimeMillis(),
    val lastUpdated: Long = System.currentTimeMillis(),
    val source: String,                     // BeliefSource.name
    val embedding: ByteArray? = null,
    /** Free-text attributes the extractor emitted (JSON). Untyped on purpose. */
    val attributes: String = "{}",
    /** Set true when the engine flags it for owner review (low confidence). */
    val flaggedForReview: Boolean = false
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is WorldEntity) return false
        return id == other.id && label == other.label && type == other.type
    }
    override fun hashCode(): Int = id.hashCode()
}

/**
 * A directed edge: `subjectId` --[relationType]--> `objectId`.
 * Confidence and evidenceCount track how often this link has been
 * observed or inferred.
 */
@Entity(
    tableName = "wm_relations",
    indices = [
        Index("subjectId"),
        Index("objectId"),
        Index("relationType"),
        Index("confidence")
    ]
)
data class WorldRelation(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val subjectId: Long,
    val objectId: Long,
    val relationType: String,           // RelationType.name
    val confidence: Float = 0.5f,
    val evidenceCount: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val lastConfirmed: Long = System.currentTimeMillis(),
    /** Optional temporal window for time-bound relations (OCCURRED_DURING, etc). */
    val temporalStart: Long? = null,
    val temporalEnd: Long? = null,
    /** Origin of this edge, mirroring WorldEntity.source semantics. */
    val source: String = BeliefSource.INFERENCE.name,
    val flaggedForReview: Boolean = false
)

/**
 * A causal model: when `causeRelation` (type CAUSES) co-occurs with
 * `effectRelation` repeatedly under the same [conditions], the engine
 * strengthens this model. Used by [WorldModelQuery.causalChain].
 */
@Entity(
    tableName = "wm_causal_models",
    indices = [Index("causeRelationId"), Index("effectRelationId"), Index("confidence")]
)
data class CausalModel(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val causeRelationId: Long,          // FK → WorldRelation (type CAUSES)
    val effectRelationId: Long,         // FK → WorldRelation describing the effect
    val conditions: String,             // JSON of preconditions
    val confidence: Float = 0.5f,
    val observedCount: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val lastObserved: Long = System.currentTimeMillis()
)

/**
 * Immutable audit log of every belief change. Lets the owner (and the
 * agent itself) trace WHY a confidence moved. Required by section 3.4
 * `beliefState` and section 3.5 (the revision rules are protected; the
 * audit trail proves they were followed).
 */
@Entity(
    tableName = "wm_belief_revisions",
    indices = [Index("targetType"), Index("targetId"), Index("timestamp")]
)
data class BeliefRevision(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val targetType: String,             // RevisionTargetType.name
    val targetId: Long,
    val oldConfidence: Float,
    val newConfidence: Float,
    val reason: String,                 // machine-readable short code, e.g. "CORROBORATION"
    val evidence: String,               // human-readable description
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * A raw observation the engine has ingested (or will ingest). Kept so the
 * owner can see what fed a belief, and so consolidation can re-derive.
 */
@Entity(
    tableName = "wm_observations",
    indices = [Index("timestamp"), Index("source")]
)
data class Observation(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val source: String,                 // "sensor" | "user_input" | "llm_inference" | "external"
    val raw: String,                    // raw observation text/JSON
    val extractedEntities: List<Long> = emptyList(),
    val extractedRelations: List<Long> = emptyList(),
    val sessionId: String? = null,
    /** True once the engine has processed it. */
    val ingested: Boolean = false
)

// ═══ DAOs ════════════════════════════════════════════════════

@Dao
interface WorldEntityDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: WorldEntity): Long

    @Update
    suspend fun update(entity: WorldEntity)

    @Update
    suspend fun updateAll(entities: List<WorldEntity>)

    @Query("SELECT * FROM wm_entities WHERE id = :id")
    suspend fun getById(id: Long): WorldEntity?

    @Query("SELECT * FROM wm_entities WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<Long>): List<WorldEntity>

    @Query("SELECT * FROM wm_entities WHERE label = :label COLLATE NOCASE LIMIT 1")
    suspend fun findByLabel(label: String): WorldEntity?

    @Query("SELECT * FROM wm_entities WHERE type = :type ORDER BY confidence DESC, lastUpdated DESC")
    suspend fun getByType(type: String): List<WorldEntity>

    @Query("SELECT * FROM wm_entities WHERE label LIKE '%' || :keyword || '%' COLLATE NOCASE ORDER BY confidence DESC LIMIT :limit")
    suspend fun searchByKeyword(keyword: String, limit: Int): List<WorldEntity>

    @Query("SELECT * FROM wm_entities ORDER BY confidence DESC, lastUpdated DESC LIMIT :limit")
    suspend fun getTopEntities(limit: Int): List<WorldEntity>

    @Query("SELECT * FROM wm_entities WHERE confidence < :threshold AND flaggedForReview = 0 ORDER BY lastUpdated ASC")
    suspend fun getPruneCandidates(threshold: Float): List<WorldEntity>

    @Query("SELECT * FROM wm_entities WHERE flaggedForReview = 1 ORDER BY lastUpdated DESC")
    suspend fun getFlagged(): List<WorldEntity>

    @Query("SELECT * FROM wm_entities ORDER BY lastUpdated DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<WorldEntity>>

    @Query("SELECT * FROM wm_entities ORDER BY confidence DESC, lastUpdated DESC")
    fun observeAll(): Flow<List<WorldEntity>>

    @Query("SELECT COUNT(*) FROM wm_entities")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM wm_entities WHERE type = :type")
    suspend fun countByType(type: String): Int

    @Query("DELETE FROM wm_entities WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("UPDATE wm_entities SET flaggedForReview = :flagged WHERE id = :id")
    suspend fun setFlagged(id: Long, flagged: Boolean)

    @Query("UPDATE wm_entities SET confidence = :confidence, lastUpdated = :ts WHERE id = :id")
    suspend fun updateConfidence(id: Long, confidence: Float, ts: Long)

    @Query("UPDATE wm_entities SET groundedConceptId = :conceptId WHERE id = :id")
    suspend fun setGroundedConcept(id: Long, conceptId: Long?)
}

@Dao
interface WorldRelationDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(relation: WorldRelation): Long

    @Update
    suspend fun update(relation: WorldRelation)

    @Query("SELECT * FROM wm_relations WHERE id = :id")
    suspend fun getById(id: Long): WorldRelation?

    @Query("SELECT * FROM wm_relations WHERE subjectId = :id OR objectId = :id ORDER BY confidence DESC, lastConfirmed DESC")
    suspend fun getForEntity(id: Long): List<WorldRelation>

    @Query("SELECT * FROM wm_relations WHERE subjectId = :subjectId AND objectId = :objectId AND relationType = :type LIMIT 1")
    suspend fun findExact(subjectId: Long, objectId: Long, type: String): WorldRelation?

    @Query("SELECT * FROM wm_relations WHERE subjectId = :subjectId AND relationType = :type ORDER BY confidence DESC")
    suspend fun getOutgoing(subjectId: Long, type: String): List<WorldRelation>

    @Query("SELECT * FROM wm_relations WHERE objectId = :objectId AND relationType = :type ORDER BY confidence DESC")
    suspend fun getIncoming(objectId: Long, type: String): List<WorldRelation>

    @Query("SELECT * FROM wm_relations WHERE relationType = :type ORDER BY confidence DESC, evidenceCount DESC")
    suspend fun getByType(type: String): List<WorldRelation>

    @Query("SELECT * FROM wm_relations WHERE confidence >= :minConfidence ORDER BY confidence DESC, evidenceCount DESC LIMIT :limit")
    suspend fun getHighConfidence(minConfidence: Float, limit: Int): List<WorldRelation>

    @Query("SELECT * FROM wm_relations WHERE confidence < :threshold AND flaggedForReview = 0")
    suspend fun getPruneCandidates(threshold: Float): List<WorldRelation>

    @Query("UPDATE wm_relations SET confidence = :confidence, evidenceCount = :evidenceCount, lastConfirmed = :ts WHERE id = :id")
    suspend fun updateEvidence(id: Long, confidence: Float, evidenceCount: Int, ts: Long)

    @Query("UPDATE wm_relations SET flaggedForReview = :flagged WHERE id = :id")
    suspend fun setFlagged(id: Long, flagged: Boolean)

    @Query("DELETE FROM wm_relations WHERE id = :id")
    suspend fun deleteById(id: Long)

    /** Delete causal models that reference [relationId] on either side. */
    @Query("DELETE FROM wm_causal_models WHERE causeRelationId = :relationId OR effectRelationId = :relationId")
    suspend fun deleteCausalModelsForRelation(relationId: Long)

    @Query("DELETE FROM wm_relations WHERE subjectId = :entityId OR objectId = :entityId")
    suspend fun deleteForEntity(entityId: Long): Int

    @Query("SELECT COUNT(*) FROM wm_relations")
    suspend fun count(): Int
}

@Dao
interface CausalModelDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(model: CausalModel): Long

    @Update
    suspend fun update(model: CausalModel)

    @Query("SELECT * FROM wm_causal_models WHERE id = :id")
    suspend fun getById(id: Long): CausalModel?

    @Query("SELECT * FROM wm_causal_models WHERE causeRelationId = :relationId LIMIT 1")
    suspend fun findByCause(relationId: Long): CausalModel?

    @Query("SELECT * FROM wm_causal_models WHERE effectRelationId = :relationId")
    suspend fun findByEffect(relationId: Long): List<CausalModel>

    @Query("SELECT * FROM wm_causal_models ORDER BY confidence DESC, observedCount DESC")
    suspend fun getAll(): List<CausalModel>

    @Query("UPDATE wm_causal_models SET confidence = :confidence, observedCount = :count, lastObserved = :ts WHERE id = :id")
    suspend fun updateObservation(id: Long, confidence: Float, count: Int, ts: Long)

    @Query("SELECT COUNT(*) FROM wm_causal_models")
    suspend fun count(): Int
}

@Dao
interface BeliefRevisionDao {
    @Insert
    suspend fun insert(revision: BeliefRevision): Long

    @Query("SELECT * FROM wm_belief_revisions WHERE targetType = :type AND targetId = :id ORDER BY timestamp DESC")
    suspend fun getForTarget(type: String, id: Long): List<BeliefRevision>

    @Query("SELECT * FROM wm_belief_revisions ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecent(limit: Int): List<BeliefRevision>

    @Query("SELECT * FROM wm_belief_revisions ORDER BY timestamp DESC")
    fun observeRecent(): Flow<List<BeliefRevision>>

    @Query("SELECT COUNT(*) FROM wm_belief_revisions")
    suspend fun count(): Int
}

@Dao
interface ObservationDao {
    @Insert
    suspend fun insert(observation: Observation): Long

    @Update
    suspend fun update(observation: Observation)

    @Query("SELECT * FROM wm_observations WHERE id = :id")
    suspend fun getById(id: Long): Observation?

    @Query("SELECT * FROM wm_observations WHERE ingested = 0 ORDER BY timestamp ASC LIMIT :limit")
    suspend fun getUnprocessed(limit: Int): List<Observation>

    @Query("SELECT * FROM wm_observations ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecent(limit: Int): List<Observation>

    @Query("UPDATE wm_observations SET ingested = 1, extractedEntities = :entityIds, extractedRelations = :relationIds WHERE id = :id")
    suspend fun markIngested(id: Long, entityIds: List<Long>, relationIds: List<Long>)

    @Query("SELECT COUNT(*) FROM wm_observations")
    suspend fun count(): Int

    @Query("DELETE FROM wm_observations WHERE timestamp < :before AND ingested = 1")
    suspend fun purgeOlderThan(before: Long): Int
}
