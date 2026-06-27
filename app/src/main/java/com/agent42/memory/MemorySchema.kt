package com.agent42.memory

import androidx.room.*
import kotlinx.coroutines.flow.Flow

// ═══ TYPE CONVERTERS ═══════════════════════════════════════

class AgentConverters {
    @TypeConverter
    fun fromMemoryCategory(value: MemoryCategory): String = value.name
    @TypeConverter
    fun toMemoryCategory(value: String): MemoryCategory = MemoryCategory.valueOf(value)

    @TypeConverter
    fun fromFeedbackType(value: FeedbackType): String = value.name
    @TypeConverter
    fun toFeedbackType(value: String): FeedbackType = FeedbackType.valueOf(value)

    @TypeConverter
    fun fromModuleType(value: ModuleType): String = value.name
    @TypeConverter
    fun toModuleType(value: String): ModuleType = ModuleType.valueOf(value)

    @TypeConverter
    fun fromStringList(value: List<String>?): String = value?.joinToString("|||") ?: ""
    @TypeConverter
    fun toStringList(value: String?): List<String> =
        if (value.isNullOrBlank()) emptyList() else value.split("|||")

    @TypeConverter
    fun fromLongList(value: List<Long>?): String = value?.joinToString("|||") ?: ""
    @TypeConverter
    fun toLongList(value: String?): List<Long> =
        if (value.isNullOrBlank()) emptyList() else value.split("|||").mapNotNull { it.toLongOrNull() }

    @TypeConverter
    fun fromModificationRecordList(value: List<ModificationRecord>?): String {
        if (value.isNullOrEmpty()) return ""
        return value.joinToString("|||REC|||") { rec ->
            "${rec.version}::${rec.timestamp}::${rec.oldCode}::${rec.newCode}::${rec.reason}::${rec.approvedBy}"
        }
    }
    @TypeConverter
    fun toModificationRecordList(value: String?): List<ModificationRecord> {
        if (value.isNullOrBlank()) return emptyList()
        return value.split("|||REC|||").mapNotNull { entry ->
            val parts = entry.split("::", limit = 6)
            if (parts.size == 6) ModificationRecord(
                version = parts[0].toInt(), timestamp = parts[1].toLong(),
                oldCode = parts[2], newCode = parts[3], reason = parts[4], approvedBy = parts[5]
            ) else null
        }
    }
}

// ═══ ENUMS ════════════════════════════════════════════════

enum class MemoryCategory { FACTUAL, PREFERENCE, PATTERN, EPISODIC, SKILL, RELATIONSHIP }
enum class FeedbackType {
    EXPLICIT_THUMBS_UP, EXPLICIT_THUMBS_DOWN,
    IMPLICIT_REPHRASE, IMPLICIT_FOLLOWUP, IMPLICIT_ABORT,
    DWELL_TIME, COPY_PASTED
}
enum class ModuleType {
    PROMPT_TEMPLATE, DECISION_RULES, RESPONSE_FORMATTER,
    REASONING_STRATEGY, CLASSIFICATION_RULES, PERSONA_DEFINITION,
    MEMORY_EXTRACTION_RULES
}
enum class ReasoningMode { DIRECT, CHAIN_OF_THOUGHT, DECOMPOSE, REFLECTIVE, TREE_OF_THOUGHTS }

// UPGRADE 6: Episodic vs Semantic memory separation
// EPISODIC: "What we discussed yesterday" — time-bound, session-specific
// SEMANTIC: "What Kotlin is" — timeless factual knowledge
// PROCEDURAL: "How to do X" — skills and methods
enum class MemoryType { EPISODIC, SEMANTIC, PROCEDURAL }

// UPGRADE 5: Associative memory links — connect related memories in a graph
@Entity(
    tableName = "memory_links",
    primaryKeys = ["sourceMemoryId", "targetMemoryId"],
    indices = [Index("sourceMemoryId"), Index("targetMemoryId")]
)
data class MemoryLink(
    val sourceMemoryId: Long,
    val targetMemoryId: Long,
    val linkType: LinkType,
    val strength: Float = 0.5f,
    val createdAt: Long = System.currentTimeMillis()
)

enum class LinkType {
    RELATED,       // Topically related
    CAUSED_BY,     // One memory caused/led to another
    CONTRADICTS,   // One memory contradicts another
    BUILDS_ON,     // One memory extends another
    CORRECTS       // One memory corrects an earlier one
}

// UPGRADE 8: Cross-session active topics
@Entity(tableName = "active_topics")
data class ActiveTopic(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val topic: String,
    val sessionId: String,
    val createdAt: Long,
    val lastActiveAt: Long,
    val resolved: Boolean = false,
    val unresolvedQuestion: String? = null
)

// ═══ DATA CLASSES ═════════════════════════════════════════

data class ModificationRecord(
    val version: Int, val timestamp: Long,
    val oldCode: String, val newCode: String,
    val reason: String, val approvedBy: String
)

data class FailurePattern(
    val queryPattern: String, val strategyUsed: String,
    val failureCount: Int, val feedbackSignals: List<String>,
    val exampleQuery: String, val exampleResponse: String,
    val rephrasedQuery: String?
)

data class StrategyEffectiveness(val strategyName: String, val avgSignal: Float, val count: Int)

data class NegativeFeedbackRow(
    val queryPattern: String,
    val strategyUsed: String?,
    val failureCount: Int,
    val signalType: FeedbackType,
    val exampleQuery: String,
    val exampleResponse: String,
    val rephrasedQuery: String?
)

// ═══ ENTITIES ═════════════════════════════════════════════

@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: String, val role: String, val content: String,
    val timestamp: Long, val reasoningMode: String?,
    val tokenCount: Int, val embedding: ByteArray? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ConversationEntity) return false
        return id == other.id
    }
    override fun hashCode(): Int = id.hashCode()
}

@Entity(tableName = "memories", indices = [Index("category"), Index("importance"), Index("memoryType")])
data class MemoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val category: MemoryCategory,
    val content: String,
    val sourceInteractionId: Long? = null,
    val importance: Float,
    val lastAccessed: Long,
    val accessCount: Int,
    val embedding: ByteArray? = null,
    val createdAt: Long,
    val tags: List<String> = emptyList(),
    // UPGRADE 6: Episodic vs Semantic memory separation
    val memoryType: MemoryType = MemoryType.SEMANTIC,
    // UPGRADE 4: Memory consolidation — track merge history
    val mergedFrom: List<Long> = emptyList(),
    // UPGRADE 8: Cross-session — link to session
    val sessionId: String? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MemoryEntity) return false
        return id == other.id
    }
    override fun hashCode(): Int = id.hashCode()
}

@Entity(tableName = "user_profile")
data class UserProfileEntity(
    @PrimaryKey val key: String, val value: String,
    val confidence: Float, val updatedAt: Long
)

@Entity(tableName = "strategy_weights", indices = [Index("strategyName")])
data class StrategyWeightEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val strategyName: String, val queryTypePattern: String,
    val weight: Float, val usesCount: Int,
    val positiveFeedback: Int, val negativeFeedback: Int,
    val lastUsed: Long
)

@Entity(tableName = "feedback_signals")
data class FeedbackEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val interactionId: Long, val signalType: FeedbackType,
    val signalValue: Float, val timestamp: Long, val notes: String? = null
)

@Entity(tableName = "behavior_modules")
data class BehaviorModule(
    @PrimaryKey val moduleId: String,
    val displayName: String, val description: String,
    val moduleType: ModuleType, val currentCode: String,
    val version: Int, val isActive: Boolean,
    val lastModified: Long,
    val modificationHistory: List<ModificationRecord> = emptyList(),
    val dependencies: List<String> = emptyList()
)

@Entity(tableName = "module_snapshots")
data class ModuleSnapshot(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val moduleId: String, val snapshotCode: String,
    val version: Int, val timestamp: Long, val label: String
)

@Entity(tableName = "change_proposals")
data class ChangeProposalEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val proposalId: String, val type: String,
    val moduleId: String, val moduleName: String,
    val currentCode: String, val proposedCode: String,
    val diagnosis: String, val reasoning: String,
    val evidence: String, val riskLevel: String, val riskNotes: String,
    val priority: Float, val status: String, val createdAt: Long,
    val userNotes: String? = null
)

// ═══ DAOs ═════════════════════════════════════════════════

@Dao
interface ConversationDao {
    @Query("SELECT * FROM conversations WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    fun getBySession(sessionId: String): Flow<List<ConversationEntity>>
    @Query("SELECT * FROM conversations ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecent(limit: Int): List<ConversationEntity>
    @Insert
    suspend fun insert(message: ConversationEntity): Long
    @Query("DELETE FROM conversations WHERE timestamp < :before")
    suspend fun purgeOlderThan(before: Long): Int
    @Query("SELECT * FROM conversations WHERE role = 'user' AND timestamp > :since ORDER BY timestamp DESC")
    suspend fun getRecentUserMessages(since: Long): List<ConversationEntity>
    @Query("SELECT COUNT(*) FROM conversations WHERE role = 'user'")
    suspend fun getInteractionCount(): Int
}

@Dao
interface MemoryDao {
    @Query("SELECT * FROM memories WHERE category = :category ORDER BY importance DESC LIMIT :limit")
    suspend fun getByCategory(category: MemoryCategory, limit: Int = 20): List<MemoryEntity>
    @Query("SELECT * FROM memories WHERE importance > :minImportance ORDER BY importance DESC LIMIT :limit")
    suspend fun getTopMemories(minImportance: Float = 0.3f, limit: Int = 50): List<MemoryEntity>
    @Insert
    suspend fun insert(memory: MemoryEntity): Long
    @Update
    suspend fun update(memory: MemoryEntity)
    @Query("UPDATE memories SET importance = importance * :decayFactor WHERE lastAccessed < :before")
    suspend fun decayImportance(decayFactor: Float, before: Long): Int
    @Query("UPDATE memories SET lastAccessed = :now, accessCount = accessCount + 1 WHERE id = :id")
    suspend fun touch(id: Long, now: Long)
    @Query("DELETE FROM memories WHERE importance < :threshold")
    suspend fun purgeWeakMemories(threshold: Float = 0.1f): Int
    @Query("SELECT * FROM memories ORDER BY importance DESC")
    fun observeAll(): Flow<List<MemoryEntity>>
    @Query("SELECT * FROM memories ORDER BY importance DESC LIMIT :limit")
    suspend fun getRecentMemories(limit: Int): List<MemoryEntity>
    @Query("SELECT COUNT(*) FROM memories")
    suspend fun getCount(): Int
    @Query("SELECT * FROM memories WHERE category = 'SKILL' AND content LIKE 'Reflection:%' ORDER BY createdAt DESC LIMIT :limit")
    suspend fun getReflections(limit: Int): List<MemoryEntity>
    @Query("SELECT * FROM memories WHERE content LIKE '%' || :keyword || '%' ORDER BY importance DESC LIMIT :limit")
    suspend fun searchByKeyword(keyword: String, limit: Int): List<MemoryEntity>
    // UPGRADE 6: Query by memory type
    @Query("SELECT * FROM memories WHERE memoryType = :type ORDER BY importance DESC LIMIT :limit")
    suspend fun getByType(type: MemoryType, limit: Int = 20): List<MemoryEntity>
    @Query("SELECT * FROM memories WHERE memoryType = 'EPISODIC' ORDER BY createdAt DESC LIMIT :limit")
    suspend fun getRecentEpisodic(limit: Int = 10): List<MemoryEntity>
    @Query("SELECT * FROM memories WHERE memoryType = 'SEMANTIC' AND importance > :minImportance ORDER BY importance DESC LIMIT :limit")
    suspend fun getTopSemantic(minImportance: Float = 0.3f, limit: Int = 20): List<MemoryEntity>
    // UPGRADE 4: For consolidation — find similar memories by content overlap
    @Query("SELECT * FROM memories WHERE category = :category AND memoryType = :type AND id != :excludeId ORDER BY importance DESC")
    suspend fun getSameCategoryAndType(category: MemoryCategory, type: MemoryType, excludeId: Long): List<MemoryEntity>
    @Query("SELECT * FROM memories WHERE importance < :threshold AND accessCount = 0 AND lastAccessed < :before")
    suspend fun getDecayedUnused(threshold: Float, before: Long): List<MemoryEntity>
    // UPGRADE 8: Cross-session
    @Query("SELECT * FROM memories WHERE sessionId = :sessionId ORDER BY createdAt DESC")
    suspend fun getBySession(sessionId: String): List<MemoryEntity>
}

@Dao
interface MemoryLinkDao {
    @Insert
    suspend fun insert(link: MemoryLink): Long
    @Insert
    suspend fun insertAll(links: List<MemoryLink>)
    @Query("SELECT * FROM memory_links WHERE sourceMemoryId = :memoryId OR targetMemoryId = :memoryId")
    suspend fun getLinks(memoryId: Long): List<MemoryLink>
    @Query("SELECT * FROM memory_links WHERE sourceMemoryId = :memoryId")
    suspend fun getOutgoingLinks(memoryId: Long): List<MemoryLink>
    @Query("SELECT targetMemoryId FROM memory_links WHERE sourceMemoryId = :memoryId AND linkType = :type AND strength > :minStrength")
    suspend fun getLinkedMemories(memoryId: Long, type: LinkType, minStrength: Float = 0.3f): List<Long>
    @Query("DELETE FROM memory_links WHERE strength < :threshold")
    suspend fun purgeWeakLinks(threshold: Float = 0.1f): Int
    @Query("UPDATE memory_links SET strength = strength + :increment WHERE sourceMemoryId = :sourceId AND targetMemoryId = :targetId")
    suspend fun strengthenLink(sourceId: Long, targetId: Long, increment: Float)
    @Query("SELECT COUNT(*) FROM memory_links")
    suspend fun getCount(): Int
}

@Dao
interface ActiveTopicDao {
    @Insert
    suspend fun insert(topic: ActiveTopic): Long
    @Update
    suspend fun update(topic: ActiveTopic)
    @Query("SELECT * FROM active_topics WHERE resolved = 0 ORDER BY lastActiveAt DESC LIMIT :limit")
    suspend fun getUnresolved(limit: Int = 10): List<ActiveTopic>
    @Query("SELECT * FROM active_topics ORDER BY lastActiveAt DESC LIMIT :limit")
    fun observeAll(limit: Int = 20): Flow<List<ActiveTopic>>
    @Query("UPDATE active_topics SET resolved = 1 WHERE id = :id")
    suspend fun markResolved(id: Long)
    @Query("UPDATE active_topics SET lastActiveAt = :timestamp WHERE id = :id")
    suspend fun touch(id: Long, timestamp: Long)
    @Query("DELETE FROM active_topics WHERE resolved = 1 AND lastActiveAt < :before")
    suspend fun purgeOldResolved(before: Long): Int
}

@Dao
interface StrategyDao {
    @Query("SELECT * FROM strategy_weights WHERE queryTypePattern LIKE '%' || :pattern || '%' ORDER BY weight DESC")
    suspend fun getStrategiesForPattern(pattern: String): List<StrategyWeightEntity>
    @Insert
    suspend fun insert(strategy: StrategyWeightEntity): Long
    @Update
    suspend fun update(strategy: StrategyWeightEntity)
    @Query("SELECT * FROM strategy_weights ORDER BY weight DESC")
    fun observeAll(): Flow<List<StrategyWeightEntity>>
    @Query("SELECT * FROM strategy_weights ORDER BY weight DESC")
    suspend fun getAll(): List<StrategyWeightEntity>
}

@Dao
interface FeedbackDao {
    @Insert
    suspend fun insert(feedback: FeedbackEntity): Long
    @Query("SELECT AVG(signalValue) FROM feedback_signals WHERE interactionId = :interactionId")
    suspend fun getAverageSignal(interactionId: Long): Float?
    @Query("""
        SELECT c.reasoningMode AS strategyName,
               AVG(f.signalValue) AS avgSignal,
               COUNT(*) AS count
        FROM feedback_signals f
        JOIN conversations c ON f.interactionId = c.id
        WHERE f.timestamp > :since AND c.reasoningMode IS NOT NULL
        GROUP BY c.reasoningMode
    """)
    suspend fun getStrategyEffectiveness(since: Long): List<StrategyEffectiveness>
    @Query("""
        SELECT c.content AS queryPattern,
               c.reasoningMode AS strategyUsed,
               COUNT(*) AS failureCount,
               f.signalType AS signalType,
               c.content AS exampleQuery,
               c.content AS exampleResponse,
               NULL AS rephrasedQuery
        FROM feedback_signals f
        JOIN conversations c ON f.interactionId = c.id
        WHERE f.signalValue < 0 AND f.timestamp > :sinceTimestamp
        GROUP BY c.reasoningMode
        HAVING COUNT(*) >= 2
        ORDER BY failureCount DESC
    """)
    suspend fun getNegativeFeedbackPatterns(sinceTimestamp: Long): List<NegativeFeedbackRow>
    @Query("SELECT * FROM feedback_signals WHERE timestamp > :since ORDER BY timestamp DESC")
    suspend fun getFeedbackSince(since: Long): List<FeedbackEntity>
}

@Dao
interface BehaviorModuleDao {
    @Query("SELECT * FROM behavior_modules WHERE isActive = 1")
    suspend fun getActiveModules(): List<BehaviorModule>
    @Query("SELECT * FROM behavior_modules WHERE moduleId = :id")
    suspend fun getById(id: String): BehaviorModule?
    @Query("SELECT COUNT(*) FROM behavior_modules")
    suspend fun count(): Int
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(module: BehaviorModule)
    @Update
    suspend fun update(module: BehaviorModule)
    @Query("UPDATE behavior_modules SET isActive = :active WHERE moduleId = :id")
    suspend fun setActive(id: String, active: Boolean)
    @Query("SELECT * FROM behavior_modules ORDER BY lastModified DESC")
    fun observeAll(): Flow<List<BehaviorModule>>
}

@Dao
interface ModuleSnapshotDao {
    @Insert
    suspend fun insert(snapshot: ModuleSnapshot): Long
    @Query("SELECT * FROM module_snapshots WHERE moduleId = :moduleId ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLatestForModule(moduleId: String): ModuleSnapshot?
    @Query("SELECT * FROM module_snapshots WHERE moduleId = :moduleId ORDER BY timestamp DESC")
    suspend fun getHistoryForModule(moduleId: String): List<ModuleSnapshot>
}

@Dao
interface ChangeProposalDao {
    @Insert
    suspend fun insert(proposal: ChangeProposalEntity): Long
    @Update
    suspend fun update(proposal: ChangeProposalEntity)
    @Query("SELECT * FROM change_proposals WHERE status = 'PENDING' ORDER BY priority DESC")
    suspend fun getPending(): List<ChangeProposalEntity>
    @Query("SELECT * FROM change_proposals WHERE status = :status ORDER BY createdAt DESC")
    suspend fun getByStatus(status: String): List<ChangeProposalEntity>
    @Query("UPDATE change_proposals SET status = :status WHERE proposalId = :proposalId")
    suspend fun updateStatus(proposalId: String, status: String)
    @Query("DELETE FROM change_proposals WHERE status IN ('REJECTED', 'ROLLED_BACK') AND createdAt < :before")
    suspend fun purgeOld(before: Long): Int
    @Query("SELECT * FROM change_proposals WHERE status = 'REJECTED' AND createdAt > :since ORDER BY createdAt DESC")
    suspend fun getRecentRejected(since: Long): List<ChangeProposalEntity>
}

// ═══ UPGRADE 9-14: NEW COGNITIVE SYSTEM ENTITIES ══════════

// UPGRADE 9: Internal World Model — predict what user asks next
@Entity(tableName = "predictions")
data class PredictionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: String,
    val predictedQuery: String,
    val actualQuery: String? = null,
    val predictedAt: Long,
    val resolvedAt: Long? = null,
    val similarityScore: Float = 0f,
    val wasCorrect: Boolean = false,
    val contextSnapshot: String = ""
)

// UPGRADE 10: System 1 Cache — fast-path for familiar queries
@Entity(tableName = "system1_cache", indices = [Index("queryHash")])
data class System1CacheEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val queryHash: String,
    val queryText: String,
    val answer: String,
    val embedding: ByteArray? = null,
    val confidence: Float = 0f,
    val hitCount: Int = 0,
    val createdAt: Long,
    val lastUsedAt: Long,
    val reasoningMode: String,
    val wasCorrect: Boolean? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is System1CacheEntry) return false
        return id == other.id
    }
    override fun hashCode(): Int = id.hashCode()
}

// UPGRADE 11: Metacognitive Monitor — track reasoning issues detected
@Entity(tableName = "metacognitive_events")
data class MetacognitiveEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val interactionId: Long,
    val issueType: String,
    val description: String,
    val severity: Float,
    val detectedAt: Long,
    val actionTaken: String,
    val chunkText: String? = null
)

// UPGRADE 12: Predictive Coding — expectations vs reality
@Entity(tableName = "expectations")
data class ExpectationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: String,
    val expectedTopic: String,
    val expectedIntent: String,
    val actualQuery: String? = null,
    val surpriseScore: Float = 0f,
    val createdAt: Long,
    val resolvedAt: Long? = null
)

// UPGRADE 13: Knowledge Gap Tracker — areas where agent is weak
@Entity(tableName = "knowledge_gaps", indices = [Index("topic")])
data class KnowledgeGapEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val topic: String,
    val gapType: String,
    val confidence: Float = 0f,
    val failureCount: Int = 0,
    val lastFailedAt: Long,
    val resolvedAt: Long? = null,
    val notes: String? = null
)

// UPGRADE 14: Constraint Checker — known facts for external verification
@Entity(tableName = "known_facts", indices = [Index("category")])
data class KnownFactEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val fact: String,
    val category: String,
    val source: String,
    val confidence: Float = 1f,
    val createdAt: Long,
    val violatedCount: Int = 0,
    val embedding: ByteArray? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is KnownFactEntity) return false
        return id == other.id
    }
    override fun hashCode(): Int = id.hashCode()
}

// UPGRADE 15: Sensor snapshots for embodied context
@Entity(tableName = "sensor_snapshots")
data class SensorSnapshotEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val timeOfDay: String,
    val dayOfWeek: String,
    val coarseLocation: String? = null,
    val motionState: String,
    val ambientLight: Float = -1f,
    val isCharging: Boolean = false,
    val batteryLevel: Int = -1,
    val sessionId: String
)

// ═══ NEW DAOs ════════════════════════════════════════════

@Dao
interface PredictionDao {
    @Insert
    suspend fun insert(prediction: PredictionEntity): Long
    @Update
    suspend fun update(prediction: PredictionEntity)
    @Query("SELECT * FROM predictions WHERE resolvedAt IS NULL AND sessionId = :sessionId ORDER BY predictedAt DESC LIMIT 1")
    suspend fun getLatestUnresolved(sessionId: String): PredictionEntity?
    @Query("SELECT * FROM predictions WHERE resolvedAt IS NOT NULL ORDER BY resolvedAt DESC LIMIT :limit")
    suspend fun getResolved(limit: Int = 100): List<PredictionEntity>
    @Query("SELECT AVG(similarityScore) FROM predictions WHERE resolvedAt IS NOT NULL AND resolvedAt > :since")
    suspend fun getAverageAccuracy(since: Long): Float?
    @Query("SELECT COUNT(*) FROM predictions WHERE wasCorrect = 1 AND resolvedAt > :since")
    suspend fun getCorrectCount(since: Long): Int
    @Query("SELECT COUNT(*) FROM predictions WHERE resolvedAt IS NOT NULL AND resolvedAt > :since")
    suspend fun getTotalCount(since: Long): Int
}

@Dao
interface System1CacheDao {
    @Insert
    suspend fun insert(entry: System1CacheEntry): Long
    @Update
    suspend fun update(entry: System1CacheEntry)
    @Query("SELECT * FROM system1_cache WHERE queryHash = :hash LIMIT 1")
    suspend fun getByHash(hash: String): System1CacheEntry?
    @Query("SELECT * FROM system1_cache ORDER BY hitCount DESC LIMIT :limit")
    suspend fun getTopEntries(limit: Int = 50): List<System1CacheEntry>
    @Query("SELECT * FROM system1_cache WHERE embedding IS NOT NULL ORDER BY hitCount DESC LIMIT :limit")
    suspend fun getEmbeddableEntries(limit: Int = 200): List<System1CacheEntry>
    @Query("UPDATE system1_cache SET hitCount = hitCount + 1, lastUsedAt = :timestamp WHERE id = :id")
    suspend fun recordHit(id: Long, timestamp: Long)
    @Query("UPDATE system1_cache SET wasCorrect = :correct WHERE id = :id")
    suspend fun recordCorrectness(id: Long, correct: Boolean)
    @Query("DELETE FROM system1_cache WHERE lastUsedAt < :before AND hitCount < :minHits")
    suspend fun purgeStale(before: Long, minHits: Int = 2): Int
    @Query("SELECT COUNT(*) FROM system1_cache")
    suspend fun getCount(): Int
}

@Dao
interface MetacognitiveDao {
    @Insert
    suspend fun insert(event: MetacognitiveEvent): Long
    @Query("SELECT * FROM metacognitive_events WHERE interactionId = :interactionId ORDER BY detectedAt ASC")
    suspend fun getForInteraction(interactionId: Long): List<MetacognitiveEvent>
    @Query("SELECT issueType, COUNT(*) as count FROM metacognitive_events WHERE detectedAt > :since GROUP BY issueType ORDER BY count DESC")
    suspend fun getIssueTypeCounts(since: Long): List<IssueTypeCount>
    @Query("SELECT COUNT(*) FROM metacognitive_events WHERE severity > 0.7 AND detectedAt > :since")
    suspend fun getHighSeverityCount(since: Long): Int
    @Query("DELETE FROM metacognitive_events WHERE detectedAt < :before")
    suspend fun purgeOlderThan(before: Long): Int
}

data class IssueTypeCount(val issueType: String, val count: Int)

@Dao
interface ExpectationDao {
    @Insert
    suspend fun insert(expectation: ExpectationEntity): Long
    @Update
    suspend fun update(expectation: ExpectationEntity)
    @Query("SELECT * FROM expectations WHERE resolvedAt IS NOT NULL ORDER BY resolvedAt DESC LIMIT :limit")
    suspend fun getResolved(limit: Int = 50): List<ExpectationEntity>
    @Query("SELECT AVG(surpriseScore) FROM expectations WHERE resolvedAt IS NOT NULL AND resolvedAt > :since")
    suspend fun getAverageSurprise(since: Long): Float?
    @Query("SELECT * FROM expectations WHERE resolvedAt IS NULL ORDER BY createdAt DESC LIMIT 1")
    suspend fun getLatestUnresolved(): ExpectationEntity?
}

@Dao
interface KnowledgeGapDao {
    @Insert
    suspend fun insert(gap: KnowledgeGapEntity): Long
    @Update
    suspend fun update(gap: KnowledgeGapEntity)
    @Query("SELECT * FROM knowledge_gaps WHERE resolvedAt IS NULL ORDER BY failureCount DESC, confidence ASC LIMIT :limit")
    suspend fun getUnresolved(limit: Int = 20): List<KnowledgeGapEntity>
    @Query("SELECT * FROM knowledge_gaps WHERE topic LIKE '%' || :keyword || '%' AND resolvedAt IS NULL LIMIT 5")
    suspend fun searchByKeyword(keyword: String): List<KnowledgeGapEntity>
    @Query("UPDATE knowledge_gaps SET failureCount = failureCount + 1, lastFailedAt = :timestamp, confidence = :confidence WHERE id = :id")
    suspend fun recordFailure(id: Long, timestamp: Long, confidence: Float)
    @Query("UPDATE knowledge_gaps SET resolvedAt = :timestamp WHERE id = :id")
    suspend fun markResolved(id: Long, timestamp: Long)
    @Query("SELECT COUNT(*) FROM knowledge_gaps WHERE resolvedAt IS NULL")
    suspend fun getUnresolvedCount(): Int
}

@Dao
interface KnownFactDao {
    @Insert
    suspend fun insert(fact: KnownFactEntity): Long
    @Update
    suspend fun update(fact: KnownFactEntity)
    @Query("SELECT * FROM known_facts WHERE category = :category ORDER BY confidence DESC LIMIT :limit")
    suspend fun getByCategory(category: String, limit: Int = 50): List<KnownFactEntity>
    @Query("SELECT * FROM known_facts ORDER BY confidence DESC LIMIT :limit")
    suspend fun getTopFacts(limit: Int = 100): List<KnownFactEntity>
    @Query("SELECT * FROM known_facts WHERE embedding IS NOT NULL LIMIT :limit")
    suspend fun getEmbeddableFacts(limit: Int = 500): List<KnownFactEntity>
    @Query("UPDATE known_facts SET violatedCount = violatedCount + 1 WHERE id = :id")
    suspend fun recordViolation(id: Long)
    @Query("SELECT COUNT(*) FROM known_facts")
    suspend fun getCount(): Int
    @Query("DELETE FROM known_facts WHERE confidence < :threshold AND violatedCount > :minViolations")
    suspend fun purgeUnreliable(threshold: Float = 0.3f, minViolations: Int = 3): Int
}

@Dao
interface SensorSnapshotDao {
    @Insert
    suspend fun insert(snapshot: SensorSnapshotEntity): Long
    @Query("SELECT * FROM sensor_snapshots ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLatest(): SensorSnapshotEntity?
    @Query("SELECT * FROM sensor_snapshots WHERE sessionId = :sessionId ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getBySession(sessionId: String, limit: Int = 10): List<SensorSnapshotEntity>
    @Query("SELECT motionState, COUNT(*) as count FROM sensor_snapshots WHERE timestamp > :since GROUP BY motionState ORDER BY count DESC")
    suspend fun getMotionDistribution(since: Long): List<MotionStateCount>
    @Query("DELETE FROM sensor_snapshots WHERE timestamp < :before")
    suspend fun purgeOlderThan(before: Long): Int
}

data class MotionStateCount(val motionState: String, val count: Int)

// ═══ DATABASE ═════════════════════════════════════════════

@Database(
    entities = [
        ConversationEntity::class, MemoryEntity::class,
        UserProfileEntity::class, StrategyWeightEntity::class,
        FeedbackEntity::class, BehaviorModule::class,
        ModuleSnapshot::class, ChangeProposalEntity::class,
        MemoryLink::class, ActiveTopic::class,
        PredictionEntity::class, System1CacheEntry::class,
        MetacognitiveEvent::class, ExpectationEntity::class,
        KnowledgeGapEntity::class, KnownFactEntity::class,
        SensorSnapshotEntity::class,
        // World model (system 2.1) — see com.agent42.worldmodel.WorldModelSchema
        com.agent42.worldmodel.WorldEntity::class,
        com.agent42.worldmodel.WorldRelation::class,
        com.agent42.worldmodel.CausalModel::class,
        com.agent42.worldmodel.BeliefRevision::class,
        com.agent42.worldmodel.Observation::class
    ],
    version = 4,
    exportSchema = false
)
@TypeConverters(AgentConverters::class)
abstract class AgentDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDao
    abstract fun memoryDao(): MemoryDao
    abstract fun strategyDao(): StrategyDao
    abstract fun feedbackDao(): FeedbackDao
    abstract fun behaviorModuleDao(): BehaviorModuleDao
    abstract fun moduleSnapshotDao(): ModuleSnapshotDao
    abstract fun changeProposalDao(): ChangeProposalDao
    abstract fun memoryLinkDao(): MemoryLinkDao
    abstract fun activeTopicDao(): ActiveTopicDao
    // New cognitive system DAOs
    abstract fun predictionDao(): PredictionDao
    abstract fun system1CacheDao(): System1CacheDao
    abstract fun metacognitiveDao(): MetacognitiveDao
    abstract fun expectationDao(): ExpectationDao
    abstract fun knowledgeGapDao(): KnowledgeGapDao
    abstract fun knownFactDao(): KnownFactDao
    abstract fun sensorSnapshotDao(): SensorSnapshotDao
    // World model DAOs (system 2.1)
    abstract fun worldEntityDao(): com.agent42.worldmodel.WorldEntityDao
    abstract fun worldRelationDao(): com.agent42.worldmodel.WorldRelationDao
    abstract fun causalModelDao(): com.agent42.worldmodel.CausalModelDao
    abstract fun beliefRevisionDao(): com.agent42.worldmodel.BeliefRevisionDao
    abstract fun observationDao(): com.agent42.worldmodel.ObservationDao
}
