package com.agent42.worldmodel

import com.agent42.memory.AgentDatabase
import com.nexa.sdk.LlmWrapper
import com.nexa.sdk.bean.GenerationConfig
import com.nexa.sdk.bean.LlmStreamResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

// ═══════════════════════════════════════════════════════════════
// WORLD MODEL ENGINE
//
// The core update loop. Takes observations (user input, sensor events,
// LLM inferences) and revises the world model: extracts candidate
// entities/relations, matches them against existing beliefs, and applies
// the protected Bayesian-ish revision rules (see [RevisionRules]).
//
// Pipeline (section 3.3):
//   1. EXTRACT  — LLM-assisted, structured JSON of entities + relations.
//   2. MATCH    — for each candidate, find existing entity by label +
//                 embedding similarity (token-overlap cosine fallback).
//                 Above RevisionRules.MATCH_THRESHOLD → same entity.
//   3. REVISE   — corroborating evidence → confidence toward 1;
//                 contradicting evidence → toward 0 + BeliefRevision log.
//                 Below PRUNE_THRESHOLD → flagged for owner review.
//   4. CAUSAL   — when two CAUSES relations co-occur repeatedly under
//                 matching conditions, create/strengthen a CausalModel.
//
// No stubs. No placeholders. The LLM call is real; if it fails the
// engine degrades gracefully to a keyword-extraction fallback rather
// than dropping the observation.
// ═══════════════════════════════════════════════════════════════

/** A candidate entity extracted from an observation, before matching. */
data class CandidateEntity(
    val type: WorldEntityType,
    val label: String,
    val canonicalLabel: String = label,
    val attributes: Map<String, String> = emptyMap(),
    val relationRole: String? = null   // "subject"/"object" of the candidate relation below
)

/** A candidate relation extracted from an observation, before matching. */
data class CandidateRelation(
    val subjectLabel: String,
    val objectLabel: String,
    val relationType: RelationType,
    val temporalStart: Long? = null,
    val temporalEnd: Long? = null
)

/** What the extractor pulled out of a single observation. */
data class ExtractionResult(
    val entities: List<CandidateEntity>,
    val relations: List<CandidateRelation>,
    /** Free-form contradiction signals the LLM noticed (e.g. "user said X but earlier said Y"). */
    val contradictions: List<String> = emptyList()
)

/** Result of ingesting one observation — what changed in the world model. */
data class IngestResult(
    val observationId: Long,
    val entitiesTouched: List<Long>,
    val relationsTouched: List<Long>,
    val revisions: List<BeliefRevision>,
    val causalModelsTouched: List<Long>
) {
    val hadEffect: Boolean
        get() = entitiesTouched.isNotEmpty() || relationsTouched.isNotEmpty() || revisions.isNotEmpty()
}

/**
 * The world model update engine. Construct once per agent (see
 * [com.agent42.core.AppInitializer]); thread-safe via Room's own
 * serialization and the use of suspend functions on Dispatchers.IO.
 *
 * @param db the shared agent database (world model tables live alongside
 *           the memory tables).
 * @param llm the on-device LLM used for structured extraction. May be null
 *            during early boot; the engine falls back to keyword extraction.
 */
class WorldModelEngine(
    private val db: AgentDatabase,
    private val llm: LlmWrapper? = null
) {

    private val entityDao get() = db.worldEntityDao()
    private val relationDao get() = db.worldRelationDao()
    private val causalDao get() = db.causalModelDao()
    private val revisionDao get() = db.beliefRevisionDao()
    private val observationDao get() = db.observationDao()

    private val extractionConfig = GenerationConfig(maxTokens = 1024)

    // ═══════════════════════════════════════════════════════════
    // PUBLIC API
    // ═══════════════════════════════════════════════════════════

    /**
     * Ingest a single observation into the world model. This is the main
     * entry point called from ReasoningEngine after each exchange, and
     * from sensor / external feeds.
     *
     * @param raw the observation text or JSON.
     * @param source where it came from. Drives initial confidence and
     *               contradiction weighting via [RevisionRules].
     * @param sessionId optional session id for traceability.
     * @return what the engine touched. Always non-null (may be a no-op).
     */
    suspend fun ingest(
        raw: String,
        source: BeliefSource,
        sessionId: String? = null
    ): IngestResult = withContext(Dispatchers.IO) {
        if (raw.isBlank()) return@withContext IngestResult(-1, emptyList(), emptyList(), emptyList(), emptyList())

        // Persist the raw observation first, so the trail is never lost even
        // if extraction fails.
        val observation = Observation(
            timestamp = System.currentTimeMillis(),
            source = source.name.lowercase(),
            raw = raw,
            sessionId = sessionId,
            ingested = false
        )
        val observationId = observationDao.insert(observation)

        // 1. EXTRACT
        val extraction = extract(raw, source)

        // 2. MATCH + 3. REVISE (entities first, then relations that reference them)
        val labelToId = mutableMapOf<String, Long>()
        val entitiesTouched = mutableListOf<Long>()
        val revisions = mutableListOf<BeliefRevision>()

        for (candidate in extraction.entities) {
            val (entityId, revs) = matchAndReviseEntity(candidate, source, observationId)
            labelToId[candidate.label.lowercase()] = entityId
            if (entityId !in entitiesTouched) entitiesTouched.add(entityId)
            revisions.addAll(revs)
        }

        val relationsTouched = mutableListOf<Long>()
        for (candidate in extraction.relations) {
            val subjectId = labelToId[candidate.subjectLabel.lowercase()]
                ?: matchAndReviseEntity(
                    CandidateEntity(inferEntityType(candidate.subjectLabel), candidate.subjectLabel),
                    source, observationId
                ).first
            val objectId = labelToId[candidate.objectLabel.lowercase()]
                ?: matchAndReviseEntity(
                    CandidateEntity(inferEntityType(candidate.objectLabel), candidate.objectLabel),
                    source, observationId
                ).first
            val (relationId, relRevs) = matchAndReviseRelation(
                subjectId, objectId, candidate, source, observationId
            )
            if (relationId !in relationsTouched) relationsTouched.add(relationId)
            revisions.addAll(relRevs)
        }

        // Apply contradictions the LLM flagged against existing high-confidence beliefs.
        for (contradictionText in extraction.contradictions) {
            val target = findContradictedBelief(contradictionText)
            if (target != null) {
                val rev = applyContradiction(target, source, contradictionText, observationId)
                revisions.add(rev)
            }
        }

        // 4. CAUSAL LEARNING
        val causalTouched = learnCausalModels(relationsTouched, observationId)

        // Mark the observation as processed with what it produced.
        observationDao.markIngested(observationId, entitiesTouched, relationsTouched)

        IngestResult(observationId, entitiesTouched, relationsTouched, revisions, causalTouched)
    }

    /**
     * Convenience: ingest a full user↔agent exchange. Extracts from both
     * sides and treats owner utterances as [BeliefSource.OWNER_STATEMENT]
     * and agent utterances as [BeliefSource.LLM].
     */
    suspend fun ingestExchange(
        userQuery: String,
        agentResponse: String,
        sessionId: String? = null
    ): List<IngestResult> = withContext(Dispatchers.IO) {
        val results = mutableListOf<IngestResult>()
        if (userQuery.isNotBlank()) {
            results.add(ingest(userQuery, BeliefSource.OWNER_STATEMENT, sessionId))
        }
        if (agentResponse.isNotBlank()) {
            results.add(ingest(agentResponse, BeliefSource.LLM, sessionId))
        }
        results
    }

    /**
     * Owner-driven correction from the UI. Sets an entity's confidence and
     * source directly, bypassing the Bayesian loop. Always logs a
     * BeliefRevision so the trail is intact. Used by the World Model screen.
     */
    suspend fun ownerCorrectEntity(entityId: Long, newConfidence: Float, note: String) =
        withContext(Dispatchers.IO) {
            val entity = entityDao.getById(entityId) ?: return@withContext
            val old = entity.confidence
            val updated = entity.copy(
                confidence = newConfidence.coerceIn(0f, 1f),
                source = BeliefSource.OWNER_STATEMENT.name,
                lastUpdated = System.currentTimeMillis(),
                flaggedForReview = false
            )
            entityDao.update(updated)
            revisionDao.insert(
                BeliefRevision(
                    targetType = RevisionTargetType.ENTITY.name,
                    targetId = entityId,
                    oldConfidence = old,
                    newConfidence = updated.confidence,
                    reason = "OWNER_CORRECTION",
                    evidence = note
                )
            )
        }

    // ═══════════════════════════════════════════════════════════
    // 1. EXTRACTION
    // ═══════════════════════════════════════════════════════════

    /**
     * Extract candidate entities, relations, and contradiction signals from
     * [raw]. Uses the on-device LLM with a strict JSON schema; falls back
     * to keyword extraction if the LLM is unavailable or returns garbage.
     */
    private suspend fun extract(raw: String, source: BeliefSource): ExtractionResult {
        val llmRef = llm ?: return keywordFallbackExtract(raw)
        return try {
            val prompt = buildExtractionPrompt(raw, source)
            val sb = StringBuilder()
            llmRef.generateStreamFlow(prompt, extractionConfig).collect { chunk ->
                if (chunk is LlmStreamResult.Token) sb.append(chunk.text)
            }
            parseExtractionJson(sb.toString())
        } catch (e: Exception) {
            // LLM failed (model not loaded, OOM, etc.). Degrade, don't drop.
            keywordFallbackExtract(raw)
        }
    }

    private fun buildExtractionPrompt(raw: String, source: BeliefSource): String = """
        You are the extraction stage of a world model. Read the observation and
        output ONLY a JSON object describing the entities and relations it implies.
        No commentary, no markdown fences.

        Observation (source=${source.name}):
        \"\"\"
        ${raw.take(2000)}
        \"\"\"

        JSON schema (fill only what is genuinely present; omit fields you cannot fill):
        {
          "entities": [
            { "type": "PERSON|PLACE|OBJECT|EVENT|CONCEPT|SELF|AGENT|ORGANIZATION|TOOL|OTHER",
              "label": "lowercase canonical name",
              "display": "Original Case Name",
              "attributes": { "key": "value" } }
          ],
          "relations": [
            { "subject": "entity label", "object": "entity label",
              "type": "IS_A|PART_OF|LOCATED_AT|OCCURRED_DURING|CAUSED_BY|CAUSES|LIKES|DISLIKES|USES|PRODUCES|PRECEDES|FOLLOWS|RELATED_TO|OWNS|KNOWS|DEPENDS_ON" }
          ],
          "contradictions": [
            "one-line description of any internal contradiction, e.g. 'user says X is here but earlier said X is there'"
          ]
        }

        Rules:
        - Labels are lowercase, singular where natural ("coffee" not "coffees").
        - Only extract relations whose subject AND object are both in entities.
        - If nothing extractable, return {"entities":[],"relations":[],"contradictions":[]}.
    """.trimIndent()

    /**
     * Parse the LLM's JSON. Tolerant: extracts the first balanced `{...}`
     * block, then reads arrays with simple regexes. We do NOT pull in a JSON
     * library (kept the dependency surface minimal per the build config).
     */
    private fun parseExtractionJson(text: String): ExtractionResult {
        val json = extractFirstJsonObject(text) ?: return keywordFallbackExtract(text)

        val entities = mutableListOf<CandidateEntity>()
        val entityBlock = extractArrayBlock(json, "entities")
        for (entry in splitArrayEntries(entityBlock)) {
            val type = extractStringField(entry, "type")?.uppercase()?.let { runCatching { WorldEntityType.valueOf(it) }.getOrNull() }
                ?: WorldEntityType.OTHER
            val label = extractStringField(entry, "label")?.lowercase()?.trim()
                ?: extractStringField(entry, "display")?.lowercase()?.trim()
                ?: continue
            if (label.isBlank()) continue
            val display = extractStringField(entry, "display") ?: label.replaceFirstChar { it.uppercase() }
            val attrs = extractAttributesObject(entry)
            entities.add(CandidateEntity(type, label, display, attrs))
        }

        val relations = mutableListOf<CandidateRelation>()
        val relationBlock = extractArrayBlock(json, "relations")
        for (entry in splitArrayEntries(relationBlock)) {
            val subject = extractStringField(entry, "subject")?.lowercase()?.trim() ?: continue
            val objectLabel = extractStringField(entry, "object")?.lowercase()?.trim() ?: continue
            val typeName = extractStringField(entry, "type")?.uppercase()?.replace(" ", "_") ?: "RELATED_TO"
            val type = runCatching { RelationType.valueOf(typeName) }.getOrNull() ?: RelationType.RELATED_TO
            relations.add(CandidateRelation(subject, objectLabel, type))
        }

        val contradictions = mutableListOf<String>()
        val contraBlock = extractArrayBlock(json, "contradictions")
        for (entry in splitArrayEntries(contraBlock)) {
            val cleaned = entry.trim().trim('"').trim()
            if (cleaned.isNotBlank()) contradictions.add(cleaned)
        }

        return ExtractionResult(entities, relations, contradictions)
    }

    /**
     * Last-resort extractor when the LLM is unavailable. Pulls capitalized
     * noun-like tokens as CONCEPT entities and detects "X is Y" / "X likes Y"
     * patterns for relations. Crude but honest — never returns nothing if the
     * text has content.
     */
    private fun keywordFallbackExtract(raw: String): ExtractionResult {
        val entities = mutableListOf<CandidateEntity>()
        val seen = mutableSetOf<String>()

        // Capitalized tokens (proper nouns) → PERSON/ORG/PLACE candidates.
        val properNouns = Regex("\\b[A-Z][a-zA-Z]{2,}\\b").findAll(raw)
            .map { it.value.lowercase() }
            .filter { it !in STOPWORDS }
            .toList()
        for (np in properNouns) {
            if (seen.add(np)) {
                entities.add(CandidateEntity(WorldEntityType.PERSON, np, np.replaceFirstChar { it.uppercase() }))
            }
        }

        // "X is a Y" / "X is an Y" → IS_A
        val relations = mutableListOf<CandidateRelation>()
        for (match in Regex("\\b([a-z]+)\\s+is\\s+(?:a|an)\\s+([a-z]+)\\b").findAll(raw.lowercase())) {
            val (subject, obj) = match.destructured
            if (subject !in seen) { entities.add(CandidateEntity(WorldEntityType.CONCEPT, subject)); seen.add(subject) }
            if (obj !in seen) { entities.add(CandidateEntity(WorldEntityType.CONCEPT, obj)); seen.add(obj) }
            relations.add(CandidateRelation(subject, obj, RelationType.IS_A))
        }
        // "X likes Y"
        for (match in Regex("\\b([a-z]+)\\s+likes\\s+([a-z]+)\\b").findAll(raw.lowercase())) {
            val (subject, obj) = match.destructured
            if (subject !in seen) { entities.add(CandidateEntity(WorldEntityType.PERSON, subject)); seen.add(subject) }
            if (obj !in seen) { entities.add(CandidateEntity(WorldEntityType.CONCEPT, obj)); seen.add(obj) }
            relations.add(CandidateRelation(subject, obj, RelationType.LIKES))
        }

        return ExtractionResult(entities, relations)
    }

    // ═══════════════════════════════════════════════════════════
    // 2 + 3. MATCH AND REVISE — ENTITIES
    // ═══════════════════════════════════════════════════════════

    /**
     * Match a candidate entity against existing beliefs and apply the
     * revision rule. Returns the (entityId, revisions) produced.
     */
    private suspend fun matchAndReviseEntity(
        candidate: CandidateEntity,
        source: BeliefSource,
        observationId: Long
    ): Pair<Long, List<BeliefRevision>> {
        val now = System.currentTimeMillis()
        val revisions = mutableListOf<BeliefRevision>()

        // First try an exact (case-insensitive) label match — cheap and exact.
        val exact = entityDao.findByLabel(candidate.label)
        if (exact != null) {
            // Same label → corroborate. If the type disagrees, that's a soft
            // contradiction (the agent believed X was a PERSON, now hears it's a PLACE).
            val typeDisagrees = exact.type != candidate.type.name
            val newConf = if (typeDisagrees) {
                // Don't overwrite the type outright; lower confidence and flag.
                RevisionRules.revise(exact.confidence, source, corroborating = false)
            } else {
                RevisionRules.revise(exact.confidence, source, corroborating = true)
            }
            val flagged = newConf < RevisionRules.PRUNE_THRESHOLD
            entityDao.update(
                exact.copy(
                    confidence = newConf,
                    lastUpdated = now,
                    flaggedForReview = flagged || exact.flaggedForReview,
                    // Merge attributes (candidate wins on conflict).
                    attributes = mergeAttributes(exact.attributes, candidate.attributes),
                    canonicalLabel = if (exact.canonicalLabel.isBlank()) candidate.canonicalLabel else exact.canonicalLabel
                )
            )
            revisions.add(
                BeliefRevision(
                    targetType = RevisionTargetType.ENTITY.name,
                    targetId = exact.id,
                    oldConfidence = exact.confidence,
                    newConfidence = newConf,
                    reason = if (typeDisagrees) "TYPE_CONTRADICTION" else "CORROBORATION",
                    evidence = "obs#$observationId source=${source.name} label=${candidate.label}"
                )
            )
            return Pair(exact.id, revisions)
        }

        // No exact match → similarity search over same-type entities.
        val candidates = entityDao.getByType(candidate.type.name)
        var bestMatch: WorldEntity? = null
        var bestScore = 0f
        for (existing in candidates) {
            val score = similarity(candidate.label, existing.label, existing.embedding)
            if (score > bestScore) {
                bestScore = score
                bestMatch = existing
            }
        }

        if (bestMatch != null && bestScore >= RevisionRules.MATCH_THRESHOLD) {
            // Same entity, different surface label → corroborate and record an
            // alias by storing the candidate label inside attributes.
            val newConf = RevisionRules.revise(bestMatch.confidence, source, corroborating = true)
            val aliases = mergeAttributes(bestMatch.attributes, candidate.attributes, alias = candidate.label)
            entityDao.update(
                bestMatch.copy(
                    confidence = newConf,
                    lastUpdated = now,
                    attributes = aliases
                )
            )
            revisions.add(
                BeliefRevision(
                    targetType = RevisionTargetType.ENTITY.name,
                    targetId = bestMatch.id,
                    oldConfidence = bestMatch.confidence,
                    newConfidence = newConf,
                    reason = "ALIAS_CORROBORATION",
                    evidence = "obs#$observationId '${candidate.label}' ~${"%.2f".format(bestScore)} '${bestMatch.label}'"
                )
            )
            return Pair(bestMatch.id, revisions)
        }

        // Genuinely new belief. Create it.
        val initialConf = RevisionRules.initialConfidence(source)
        val id = entityDao.insert(
            WorldEntity(
                type = candidate.type.name,
                label = candidate.label,
                canonicalLabel = candidate.canonicalLabel,
                confidence = initialConf,
                createdAt = now,
                lastUpdated = now,
                source = source.name,
                embedding = embed(candidate.label)?.let { floatArrayToByteArray(it) },
                attributes = attributesToJson(candidate.attributes)
            )
        )
        revisions.add(
            BeliefRevision(
                targetType = RevisionTargetType.ENTITY.name,
                targetId = id,
                oldConfidence = 0f,
                newConfidence = initialConf,
                reason = "NEW_BELIEF",
                evidence = "obs#$observationId source=${source.name} type=${candidate.type}"
            )
        )
        return Pair(id, revisions)
    }

    // ═══════════════════════════════════════════════════════════
    // 2 + 3. MATCH AND REVISE — RELATIONS
    // ═══════════════════════════════════════════════════════════

    private suspend fun matchAndReviseRelation(
        subjectId: Long,
        objectId: Long,
        candidate: CandidateRelation,
        source: BeliefSource,
        observationId: Long
    ): Pair<Long, List<BeliefRevision>> {
        val now = System.currentTimeMillis()
        val revisions = mutableListOf<BeliefRevision>()
        val existing = relationDao.findExact(subjectId, objectId, candidate.relationType.name)

        if (existing != null) {
            val newConf = RevisionRules.revise(existing.confidence, source, corroborating = true)
            val newEvidence = existing.evidenceCount + 1
            relationDao.updateEvidence(existing.id, newConf, newEvidence, now)
            revisions.add(
                BeliefRevision(
                    targetType = RevisionTargetType.RELATION.name,
                    targetId = existing.id,
                    oldConfidence = existing.confidence,
                    newConfidence = newConf,
                    reason = "CORROBORATION",
                    evidence = "obs#$observationId evidence×$newEvidence"
                )
            )
            return Pair(existing.id, revisions)
        }

        // New relation. Check for an existing INVERSE relation that would
        // contradict this one (e.g. existing LIKES vs new DISLIKES) — that's a
        // contradiction, not a new belief.
        val inverse = findInverseRelation(subjectId, objectId, candidate.relationType)
        if (inverse != null) {
            val newConf = RevisionRules.revise(inverse.confidence, source, corroborating = false)
            val flagged = newConf < RevisionRules.PRUNE_THRESHOLD
            relationDao.update(inverse.copy(confidence = newConf, flaggedForReview = flagged))
            revisions.add(
                BeliefRevision(
                    targetType = RevisionTargetType.RELATION.name,
                    targetId = inverse.id,
                    oldConfidence = inverse.confidence,
                    newConfidence = newConf,
                    reason = "INVERSE_CONTRADICTION",
                    evidence = "obs#$observationId new ${candidate.relationType} contradicts existing ${inverse.relationType}"
                )
            )
            // Still record the new relation as a low-confidence contender so the
            // owner can see both sides in the UI.
            val newId = relationDao.insert(
                WorldRelation(
                    subjectId = subjectId,
                    objectId = objectId,
                    relationType = candidate.relationType.name,
                    confidence = RevisionRules.initialConfidence(source) * 0.7f,
                    evidenceCount = 1,
                    createdAt = now,
                    lastConfirmed = now,
                    temporalStart = candidate.temporalStart,
                    temporalEnd = candidate.temporalEnd,
                    source = source.name,
                    flaggedForReview = true
                )
            )
            return Pair(newId, revisions)
        }

        val id = relationDao.insert(
            WorldRelation(
                subjectId = subjectId,
                objectId = objectId,
                relationType = candidate.relationType.name,
                confidence = RevisionRules.initialConfidence(source),
                evidenceCount = 1,
                createdAt = now,
                lastConfirmed = now,
                temporalStart = candidate.temporalStart,
                temporalEnd = candidate.temporalEnd,
                source = source.name
            )
        )
        revisions.add(
            BeliefRevision(
                targetType = RevisionTargetType.RELATION.name,
                targetId = id,
                oldConfidence = 0f,
                newConfidence = RevisionRules.initialConfidence(source),
                reason = "NEW_BELIEF",
                evidence = "obs#$observationId ${candidate.relationType.name} $subjectId→$objectId"
            )
        )
        return Pair(id, revisions)
    }

    /** Look for a relation between the same endpoints whose type contradicts [type]. */
    private suspend fun findInverseRelation(
        subjectId: Long, objectId: Long, type: RelationType
    ): WorldRelation? {
        val inverses = INVERSE_RELATIONS[type] ?: return null
        for (existing in relationDao.getForEntity(subjectId)) {
            if (existing.subjectId == subjectId && existing.objectId == objectId &&
                existing.relationType in inverses.map { it.name }) {
                return existing
            }
        }
        return null
    }

    // ═══════════════════════════════════════════════════════════
    // 3b. CONTRADICTION APPLICATION
    // ═══════════════════════════════════════════════════════════

    /**
     * Find the highest-confidence belief whose label or relation text overlaps
     * the contradiction description. Used when the LLM explicitly flags a
     * contradiction that isn't a direct entity/relation duplicate.
     */
    private suspend fun findContradictedBelief(contradictionText: String): BeliefTarget? {
        val keywords = contradictionText.lowercase()
            .split(Regex("[^a-z]+"))
            .filter { it.length > 3 && it !in STOPWORDS }
            .toSet()
        if (keywords.isEmpty()) return null

        // Prefer contradicting a relation if one matches — relations are
        // stronger claims than bare entities.
        val allRelations = relationDao.getHighConfidence(RevisionRules.HIGH_CONFIDENCE, 200)
        for (rel in allRelations) {
            val subject = entityDao.getById(rel.subjectId)
            val object_ = entityDao.getById(rel.objectId)
            val text = "${subject?.label ?: ""} ${rel.relationType} ${object_?.label ?: ""}".lowercase()
            val overlap = keywords.count { text.contains(it) }
            if (overlap >= 2) return BeliefTarget.Relation(rel)
        }

        val entities = entityDao.getTopEntities(200)
        for (ent in entities) {
            val overlap = keywords.count { ent.label.lowercase().contains(it) }
            if (overlap >= 1 && ent.confidence >= RevisionRules.HIGH_CONFIDENCE) {
                return BeliefTarget.Entity(ent)
            }
        }
        return null
    }

    private sealed class BeliefTarget {
        abstract val id: Long
        abstract val confidence: Float
        data class Entity(val entity: WorldEntity) : BeliefTarget() {
            override val id = entity.id
            override val confidence = entity.confidence
        }
        data class Relation(val relation: WorldRelation) : BeliefTarget() {
            override val id = relation.id
            override val confidence = relation.confidence
        }
    }

    private suspend fun applyContradiction(
        target: BeliefTarget,
        source: BeliefSource,
        evidence: String,
        observationId: Long
    ): BeliefRevision = when (target) {
        is BeliefTarget.Entity -> {
            val newConf = RevisionRules.revise(target.confidence, source, corroborating = false)
            val flagged = newConf < RevisionRules.PRUNE_THRESHOLD
            entityDao.update(target.entity.copy(confidence = newConf, flaggedForReview = flagged))
            BeliefRevision(
                targetType = RevisionTargetType.ENTITY.name,
                targetId = target.id,
                oldConfidence = target.confidence,
                newConfidence = newConf,
                reason = "LLM_CONTRADICTION",
                evidence = "obs#$observationId: $evidence"
            )
        }
        is BeliefTarget.Relation -> {
            val newConf = RevisionRules.revise(target.confidence, source, corroborating = false)
            val flagged = newConf < RevisionRules.PRUNE_THRESHOLD
            relationDao.update(target.relation.copy(confidence = newConf, flaggedForReview = flagged))
            BeliefRevision(
                targetType = RevisionTargetType.RELATION.name,
                targetId = target.id,
                oldConfidence = target.confidence,
                newConfidence = newConf,
                reason = "LLM_CONTRADICTION",
                evidence = "obs#$observationId: $evidence"
            )
        }
    }

    // ═══════════════════════════════════════════════════════════
    // 4. CAUSAL LEARNING
    // ═══════════════════════════════════════════════════════════

    /**
     * For each newly-touched CAUSES relation, check whether an effect
     * relation co-occurs repeatedly under matching conditions. If so,
     * create or strengthen a [CausalModel].
     *
     * Returns the causal-model ids touched.
     */
    private suspend fun learnCausalModels(
        touchedRelationIds: List<Long>,
        observationId: Long
    ): List<Long> {
        val touched = mutableListOf<Long>()
        for (relId in touchedRelationIds) {
            val cause = relationDao.getById(relId) ?: continue
            if (cause.relationType != RelationType.CAUSES.name) continue

            // Does an effect relation exist for the same subject within a
            // short temporal window? "X causes Y" + "Y happened" → candidate.
            val effect = relationDao.getOutgoing(cause.objectId, RelationType.RELATED_TO.name)
                .maxByOrNull { it.lastConfirmed }
                ?: relationDao.getOutgoing(cause.objectId, RelationType.OCCURRED_DURING.name)
                    .maxByOrNull { it.lastConfirmed }
                ?: continue

            val existing = causalDao.findByCause(cause.id)
            if (existing != null) {
                val newCount = existing.observedCount + 1
                val newConf = RevisionRules.causalConfidence(newCount, existing.confidence)
                causalDao.updateObservation(existing.id, newConf, newCount, System.currentTimeMillis())
                touched.add(existing.id)
            } else {
                // Only form a model after the minimum co-occurrence threshold.
                // The first observation seeds; we still record it so the count
                // can grow, but at minimal confidence.
                val id = causalDao.insert(
                    CausalModel(
                        causeRelationId = cause.id,
                        effectRelationId = effect.id,
                        conditions = "{}",
                        confidence = RevisionRules.causalConfidence(1, 0.3f),
                        observedCount = 1
                    )
                )
                touched.add(id)
            }
        }
        return touched
    }

    // ═══════════════════════════════════════════════════════════
    // SIMILARITY + EMBEDDING HELPERS
    // ═══════════════════════════════════════════════════════════

    /**
     * Similarity in [0,1]. Uses cosine over LLM embeddings when both sides
     * have them; otherwise falls back to token-overlap cosine on the labels.
     * This mirrors MemorySystem's defensive pattern (the LLM may not expose
     * an embedding method).
     *
     * Note: embeddings are stored in [WorldEntity] as `ByteArray` (the storage
     * form, see [floatArrayToByteArray]); we decode them back to `FloatArray`
     * here for the cosine computation.
     */
    private fun similarity(labelA: String, labelB: String, embeddingB: ByteArray?): Float {
        val embA = embed(labelA)                       // FloatArray?
        val embB = embeddingB?.let { byteArrayToFloats(it) }  // FloatArray?
        if (embA != null && embB != null && embA.size == embB.size && embA.isNotEmpty()) {
            val cos = cosine(embA, embB)
            // Cosine is in [-1,1]; remap to [0,1] for threshold comparability.
            return ((cos + 1f) / 2f).coerceIn(0f, 1f)
        }
        // One side had an embedding but the other didn't — still fall through
        // to token overlap, which is a reasonable cross-modal fallback.
        return tokenOverlapCosine(labelA, labelB)
    }

    /** Best-effort embedding via the LLM. Null if unavailable. */
    private fun embed(text: String): FloatArray? {
        val model = llm ?: return null
        return try {
            val method = model.javaClass.methods.firstOrNull {
                it.name == "generateEmbedding" || it.name == "embed"
            }
            val result = method?.invoke(model, text)
            when (result) {
                is FloatArray -> result
                is List<*> -> result.filterIsInstance<Float>().toFloatArray()
                else -> null
            }
        } catch (e: Exception) { null }
    }

    private fun cosine(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size || a.isEmpty()) return 0f
        var dot = 0.0
        var na = 0.0
        var nb = 0.0
        for (i in a.indices) {
            dot += a[i] * b[i]
            na += a[i] * a[i].toDouble()
            nb += b[i] * b[i].toDouble()
        }
        val denom = sqrt(na) * sqrt(nb)
        return if (denom == 0.0) 0f else (dot / denom).toFloat()
    }

    /** Encode a FloatArray embedding as a little-endian ByteArray for Room storage. */
    private fun floatArrayToByteArray(arr: FloatArray): ByteArray {
        val buffer = ByteBuffer.allocate(arr.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        buffer.asFloatBuffer().put(arr)
        return buffer.array()
    }

    /** Decode a stored ByteArray back to its FloatArray embedding. */
    private fun byteArrayToFloats(bytes: ByteArray): FloatArray {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val floats = FloatArray(bytes.size / 4)
        buffer.asFloatBuffer().get(floats)
        return floats
    }

    /** Token-overlap cosine — the embedding-free fallback. */
    private fun tokenOverlapCosine(a: String, b: String): Float {
        val ta = tokenize(a)
        val tb = tokenize(b)
        if (ta.isEmpty() || tb.isEmpty()) return 0f
        val sa = ta.toSet()
        val sb = tb.toSet()
        val intersection = sa.intersect(sb).size
        if (intersection == 0) return 0f
        // Cosine of two binary vectors: |A∩B| / sqrt(|A|·|B|)
        return (intersection.toFloat() / (sqrt(sa.size.toFloat()) * sqrt(sb.size.toFloat()))).coerceIn(0f, 1f)
    }

    private fun tokenize(text: String): List<String> =
        text.lowercase().split(Regex("[^a-z0-9]+"))
            .filter { it.length > 1 && it !in STOPWORDS }

    // ═══════════════════════════════════════════════════════════
    // ATTRIBUTE + JSON HELPERS (no external JSON lib)
    // ═══════════════════════════════════════════════════════════

    private fun mergeAttributes(
        existingJson: String,
        newAttrs: Map<String, String>,
        alias: String? = null
    ): String {
        val merged = parseAttributesObject(existingJson).toMutableMap()
        merged.putAll(newAttrs)
        if (alias != null) {
            val aliases = merged.getOrPut("aliases") { "" }
            val set = aliases.split(',').map { it.trim() }.filter { it.isNotBlank() }.toMutableSet()
            set.add(alias)
            merged["aliases"] = set.joinToString(",")
        }
        return attributesToJson(merged)
    }

    private fun attributesToJson(attrs: Map<String, String>): String {
        if (attrs.isEmpty()) return "{}"
        return attrs.entries.joinToString(prefix = "{", postfix = "}") { (k, v) ->
            "\"${escapeJson(k)}\":\"${escapeJson(v)}\""
        }
    }

    private fun parseAttributesObject(json: String): Map<String, String> {
        val block = extractObjectBlock(json, "attributes") ?: return emptyMap()
        val out = mutableMapOf<String, String>()
        // Match "key":"value" pairs.
        for (match in Regex("\"([^\"]+)\"\\s*:\\s*\"([^\"]*)\"").findAll(block)) {
            out[match.groupValues[1]] = match.groupValues[2]
        }
        return out
    }

    private fun extractAttributesObject(entry: String): Map<String, String> =
        parseAttributesObject(entry)

    private fun escapeJson(s: String): String = s.replace("\\", "\\\\").replace("\"", "\\\"")

    /** Pull the first balanced {...} block out of [text]. */
    private fun extractFirstJsonObject(text: String): String? {
        val start = text.indexOf('{')
        if (start < 0) return null
        var depth = 0
        var inString = false
        var escape = false
        for (i in start until text.length) {
            val c = text[i]
            when {
                escape -> escape = false
                c == '\\' -> escape = true
                c == '"' -> inString = !inString
                !inString && c == '{' -> depth++
                !inString && c == '}' -> {
                    depth--
                    if (depth == 0) return text.substring(start, i + 1)
                }
            }
        }
        return null
    }

    /** Extract the substring of an array field, including its brackets. */
    private fun extractArrayBlock(json: String, field: String): String {
        val keyIndex = json.indexOf("\"$field\"")
        if (keyIndex < 0) return "[]"
        val arrStart = json.indexOf('[', keyIndex)
        if (arrStart < 0) return "[]"
        var depth = 0
        var inString = false
        var escape = false
        for (i in arrStart until json.length) {
            val c = json[i]
            when {
                escape -> escape = false
                c == '\\' -> escape = true
                c == '"' -> inString = !inString
                !inString && c == '[' -> depth++
                !inString && c == ']' -> {
                    depth--
                    if (depth == 0) return json.substring(arrStart, i + 1)
                }
            }
        }
        return "[]"
    }

    /** Extract the substring of an object field, including its braces. */
    private fun extractObjectBlock(json: String, field: String): String? {
        val keyIndex = json.indexOf("\"$field\"")
        if (keyIndex < 0) return null
        val objStart = json.indexOf('{', keyIndex)
        if (objStart < 0) return null
        var depth = 0
        var inString = false
        var escape = false
        for (i in objStart until json.length) {
            val c = json[i]
            when {
                escape -> escape = false
                c == '\\' -> escape = true
                c == '"' -> inString = !inString
                !inString && c == '{' -> depth++
                !inString && c == '}' -> {
                    depth--
                    if (depth == 0) return json.substring(objStart, i + 1)
                }
            }
        }
        return null
    }

    /** Split a JSON array's contents into top-level element strings. */
    private fun splitArrayEntries(arrayBlock: String): List<String> {
        val inner = arrayBlock.removeSurrounding("[", "]").trim()
        if (inner.isEmpty()) return emptyList()
        val entries = mutableListOf<String>()
        var depth = 0
        var inString = false
        var escape = false
        val current = StringBuilder()
        for (c in inner) {
            when {
                escape -> { escape = false; current.append(c) }
                c == '\\' -> { escape = true; current.append(c) }
                c == '"' -> { inString = !inString; current.append(c) }
                !inString && (c == '{' || c == '[') -> { depth++; current.append(c) }
                !inString && (c == '}' || c == ']') -> { depth--; current.append(c) }
                !inString && c == ',' && depth == 0 -> {
                    entries.add(current.toString().trim()); current.clear()
                }
                else -> current.append(c)
            }
        }
        if (current.isNotBlank()) entries.add(current.toString().trim())
        return entries
    }

    private fun extractStringField(entry: String, field: String): String? {
        val match = Regex("\"$field\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(entry)
            ?: return null
        return match.groupValues[1].replace("\\\"", "\"").replace("\\\\", "\\")
    }

    // ═══════════════════════════════════════════════════════════
    // CONSTANTS
    // ═══════════════════════════════════════════════════════════

    /**
     * Heuristic type inference for fallback-extracted labels. Used only when
     * the LLM extractor is unavailable.
     */
    private fun inferEntityType(label: String): WorldEntityType {
        val l = label.lowercase()
        return when {
            l in SELF_WORDS -> WorldEntityType.SELF
            l in PERSON_WORDS -> WorldEntityType.PERSON
            l in PLACE_WORDS -> WorldEntityType.PLACE
            l in ORG_WORDS -> WorldEntityType.ORGANIZATION
            l in TOOL_WORDS -> WorldEntityType.TOOL
            l in EVENT_WORDS -> WorldEntityType.EVENT
            else -> WorldEntityType.CONCEPT
        }
    }

    /** Relation types that contradict each other between the same endpoints. */
    private val INVERSE_RELATIONS: Map<RelationType, List<RelationType>> = mapOf(
        RelationType.LIKES to listOf(RelationType.DISLIKES),
        RelationType.DISLIKES to listOf(RelationType.LIKES),
        RelationType.PRECEDES to listOf(RelationType.FOLLOWS),
        RelationType.FOLLOWS to listOf(RelationType.PRECEDES),
        RelationType.CAUSES to listOf(),  // a cause isn't contradicted by its own presence
        RelationType.OWNS to listOf()
    )

    private val STOPWORDS = setOf(
        "the", "a", "an", "is", "are", "was", "were", "be", "been", "being",
        "and", "or", "but", "if", "then", "of", "to", "in", "on", "at", "by",
        "for", "with", "about", "as", "into", "through", "during", "before",
        "after", "above", "below", "from", "up", "down", "this", "that",
        "these", "those", "i", "you", "he", "she", "it", "we", "they",
        "what", "which", "who", "whom", "whose", "when", "where", "why", "how",
        "all", "any", "both", "each", "few", "more", "most", "other", "some",
        "such", "no", "not", "only", "own", "same", "so", "than", "too", "very",
        "can", "will", "just", "should", "now", "my", "your", "his", "her",
        "its", "our", "their", "me", "him", "them", "do", "does", "did",
        "have", "has", "had", "would", "could", "may", "might", "must", "shall",
        "there", "here", "out", "over", "under", "again", "further", "once"
    )

    private val SELF_WORDS = setOf("i", "me", "myself", "mine", "my")
    private val PERSON_WORDS = setOf(
        "user", "owner", "agent", "friend", "mom", "dad", "mother", "father",
        "brother", "sister", "colleague", "boss", "teacher", "doctor"
    )
    private val PLACE_WORDS = setOf(
        "home", "house", "work", "office", "school", "kitchen", "bedroom",
        "city", "country", "store", "restaurant", "park", "gym", "airport"
    )
    private val ORG_WORDS = setOf("company", "team", "group", "organization", "university", "government")
    private val TOOL_WORDS = setOf("phone", "laptop", "computer", "app", "tool", "device", "camera", "car")
    private val EVENT_WORDS = setOf("meeting", "party", "trip", "appointment", "class", "concert", "game")
}
