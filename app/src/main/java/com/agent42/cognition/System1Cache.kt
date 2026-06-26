package com.agent42.cognition

import com.agent42.memory.AgentDatabase
import com.agent42.memory.ReasoningMode
import com.agent42.memory.System1CacheEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.math.sqrt

// ═══════════════════════════════════════════════════════════════
// SYSTEM 1 / SYSTEM 2 DUAL-PROCESS ARCHITECTURE (Kahneman)
//
// System 1: Fast, automatic, pattern-based. When a query is similar
// to one already answered well, respond instantly from cache.
// Only novel queries trigger System 2 (the full reasoning engine).
//
// This is the whole point: the fast path should be genuinely fast.
// If it's a hit, skip the LLM entirely.
// ═══════════════════════════════════════════════════════════════

/**
 * Result of a System 1 cache lookup.
 */
sealed class System1Result {
    /**
     * Cache hit — return the cached answer immediately.
     */
    data class Hit(
        val answer: String,
        val confidence: Float,
        val cacheId: Long,
        val similarity: Float
    ) : System1Result()

    /**
     * Cache miss — fall through to System 2 (full reasoning).
     */
    data object Miss : System1Result()
}

/**
 * Statistics about System 1 cache performance.
 */
data class CacheStats(
    val entries: Int,
    val totalHits: Int,
    val accuracy: Float
)

/**
 * System 1 Cache implements the fast, automatic path of Kahneman's
 * dual-process theory. Familiar queries are answered instantly;
 * only novel queries are routed to the slower System 2 reasoning engine.
 */
class System1Cache(private val db: AgentDatabase) {

    // In-memory counters for hit/miss statistics (persisted across calls)
    @Volatile
    private var sessionHitCount: Int = 0

    @Volatile
    private var sessionMissCount: Int = 0

    companion object {
        /** Minimum cosine similarity for a semantic match to be trusted. */
        private const val SIMILARITY_THRESHOLD = 0.85f

        /** Normalization: remove punctuation for stable hashing. */
        private val PUNCTUATION_REGEX = Regex("[^a-z0-9\\s]")
    }

    // ═══ FAST PATH ═══════════════════════════════════════════

    /**
     * Attempt to answer [query] from cache without invoking the LLM.
     *
     * 1. Compute SHA-256 hash of the normalized query.
     * 2. Look for an **exact** hash match first.
     * 3. If no exact match, compare embeddings via cosine similarity.
     * 4. Only return a hit if similarity > [SIMILARITY_THRESHOLD]
     *    and the cached entry has positive feedback (wasCorrect != false).
     */
    suspend fun tryFastPath(query: String): System1Result = withContext(Dispatchers.IO) {
        val hash = hashQuery(query)

        // ── Exact match ─────────────────────────────────────────
        val exact = db.system1CacheDao().getByHash(hash)
        if (exact != null && exact.wasCorrect != false) {
            db.system1CacheDao().recordHit(exact.id, System.currentTimeMillis())
            sessionHitCount++
            return@withContext System1Result.Hit(
                answer = exact.answer,
                confidence = exact.confidence,
                cacheId = exact.id,
                similarity = 1.0f
            )
        }

        // Without a query embedding we cannot do semantic matching.
        // The caller can use the overload that accepts a FloatArray
        // to enable semantic fast-path. Exact-match-only is still
        // valuable — many user queries are repeated verbatim.
        sessionMissCount++
        System1Result.Miss
    }

    /**
     * Overload that accepts a pre-computed [queryEmbedding] to enable
     * semantic fast-path matching.
     */
    suspend fun tryFastPath(query: String, queryEmbedding: FloatArray): System1Result =
        withContext(Dispatchers.IO) {
            val hash = hashQuery(query)

            // ── Exact match ─────────────────────────────────────
            val exact = db.system1CacheDao().getByHash(hash)
            if (exact != null && exact.wasCorrect != false) {
                db.system1CacheDao().recordHit(exact.id, System.currentTimeMillis())
                sessionHitCount++
                return@withContext System1Result.Hit(
                    answer = exact.answer,
                    confidence = exact.confidence,
                    cacheId = exact.id,
                    similarity = 1.0f
                )
            }

            // ── Semantic match ──────────────────────────────────
            val candidates = db.system1CacheDao().getEmbeddableEntries(limit = 200)
            var bestMatch: System1CacheEntry? = null
            var bestSimilarity = 0.0f

            for (candidate in candidates) {
                if (candidate.wasCorrect == false) continue
                val candidateEmbedding = candidate.embedding ?: continue
                val candidateFloats = byteArrayToFloatArray(candidateEmbedding)
                val similarity = cosineSimilarity(queryEmbedding, candidateFloats)

                if (similarity > bestSimilarity) {
                    bestSimilarity = similarity
                    bestMatch = candidate
                }
            }

            if (bestMatch != null && bestSimilarity >= SIMILARITY_THRESHOLD) {
                db.system1CacheDao().recordHit(bestMatch.id, System.currentTimeMillis())
                sessionHitCount++
                System1Result.Hit(
                    answer = bestMatch.answer,
                    confidence = bestMatch.confidence * bestSimilarity,
                    cacheId = bestMatch.id,
                    similarity = bestSimilarity
                )
            } else {
                sessionMissCount++
                System1Result.Miss
            }
        }

    // ═══ CACHE MAINTENANCE ═══════════════════════════════════

    /**
     * Store a new [answer] in the System 1 cache so future identical
     * or semantically similar queries can be answered instantly.
     */
    suspend fun cacheAnswer(
        query: String,
        answer: String,
        confidence: Float,
        mode: ReasoningMode,
        embedding: FloatArray? = null
    ) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val entry = System1CacheEntry(
            queryHash = hashQuery(query),
            queryText = query,
            answer = answer,
            embedding = embedding?.let { floatArrayToByteArray(it) },
            confidence = confidence,
            hitCount = 0,
            createdAt = now,
            lastUsedAt = now,
            reasoningMode = mode.name,
            wasCorrect = null // unknown until feedback arrives
        )
        db.system1CacheDao().insert(entry)
    }

    /**
     * Record user feedback about whether a cached answer was correct.
     * This updates the [wasCorrect] flag so the cache can learn
     * which entries are trustworthy.
     */
    suspend fun recordFeedback(query: String, wasCorrect: Boolean) = withContext(Dispatchers.IO) {
        val hash = hashQuery(query)
        val entry = db.system1CacheDao().getByHash(hash)
        if (entry != null) {
            db.system1CacheDao().recordCorrectness(entry.id, wasCorrect)
        }
    }

    /**
     * Return current cache statistics: number of entries, total hits
     * across this session, and estimated accuracy.
     */
    suspend fun getCacheStats(): CacheStats = withContext(Dispatchers.IO) {
        val entries = db.system1CacheDao().getCount()
        val totalHits = sessionHitCount
        val totalAttempts = sessionHitCount + sessionMissCount
        val accuracy = if (totalAttempts > 0) {
            sessionHitCount.toFloat() / totalAttempts
        } else 0.0f
        CacheStats(entries = entries, totalHits = totalHits, accuracy = accuracy)
    }

    /**
     * Remove stale entries that haven't been used in 7 days and have
     * fewer than 2 hits. This keeps the cache lean and relevant.
     */
    suspend fun prune() = withContext(Dispatchers.IO) {
        val sevenDaysAgo = System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000
        db.system1CacheDao().purgeStale(before = sevenDaysAgo, minHits = 2)
    }

    // ═══ INTERNAL UTILITIES ══════════════════════════════════

    /**
     * Normalize [query] (lowercase, trim, strip punctuation) then
     * compute its SHA-256 digest as a hex string.
     */
    private fun hashQuery(query: String): String {
        val normalized = query.lowercase().trim()
            .replace(PUNCTUATION_REGEX, "")
            .replace(Regex("\\s+"), " ")
        val digest = MessageDigest.getInstance("SHA-256").digest(normalized.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    /**
     * Cosine similarity between two float vectors.
     * Returns 0.0f for empty or mismatched vectors.
     */
    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size || a.isEmpty()) return 0.0f
        var dotProduct = 0.0
        var normA = 0.0
        var normB = 0.0
        for (i in a.indices) {
            dotProduct += a[i] * b[i]
            normA += a[i] * a[i].toDouble()
            normB += b[i] * b[i].toDouble()
        }
        val denominator = sqrt(normA) * sqrt(normB)
        return if (denominator == 0.0) 0.0f else (dotProduct / denominator).toFloat()
    }

    private fun floatArrayToByteArray(arr: FloatArray): ByteArray {
        val buffer = ByteBuffer.allocate(arr.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        buffer.asFloatBuffer().put(arr)
        return buffer.array()
    }

    private fun byteArrayToFloatArray(bytes: ByteArray): FloatArray {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val floats = FloatArray(bytes.size / 4)
        buffer.asFloatBuffer().get(floats)
        return floats
    }
}
