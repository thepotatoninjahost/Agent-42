package com.agent42.reasoning

import com.nexa.sdk.LlmWrapper
import com.nexa.sdk.bean.GenerationConfig
import com.nexa.sdk.bean.LlmStreamResult
import com.agent42.core.ContextManager
import com.agent42.cognition.MetacognitiveMonitor
import com.agent42.cognition.System1Cache
import com.agent42.cognition.System1Result
import com.agent42.debate.InternalDebate
import com.agent42.memory.MemorySystem
import com.agent42.memory.ReasoningMode
import com.agent42.prediction.PredictiveCoder
import com.agent42.verification.ConstraintChecker
import com.agent42.worldmodel.WorldModelContradictionChecker
import com.agent42.worldmodel.WorldModelEngine
import com.agent42.worldmodel.WorldModelQuery
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.FlowCollector

data class ReasoningStep(
    val description: String,
    val prompt: String,
    val result: String? = null,
    val confidence: Float = 0f
)

data class ReasoningTrace(
    val mode: ReasoningMode,
    val steps: List<ReasoningStep>,
    val finalAnswer: String,
    val processingTimeMs: Long,
    val contextTokensUsed: Int
)

sealed class ReasoningOutput {
    data class Chunk(val text: String) : ReasoningOutput()
    data class SubTaskStarted(val description: String) : ReasoningOutput()
    object RefinementBoundary : ReasoningOutput()
    data class BranchStarted(val branchIndex: Int, val description: String) : ReasoningOutput()
    data class BranchScored(val branchIndex: Int, val score: Float, val reason: String) : ReasoningOutput()
    data class ConfidenceCheck(val confidence: Float, val verified: Boolean, val notes: String?) : ReasoningOutput()
    data class Done(val interactionId: Long, val mode: ReasoningMode, val confidence: Float = 1.0f) : ReasoningOutput()
    // New cognitive system outputs
    data class System1Hit(val cachedAnswer: String, val similarity: Float) : ReasoningOutput()
    data class MetacognitiveAlert(val issueType: String, val description: String, val severity: Float) : ReasoningOutput()
    data class ConstraintViolation(val factText: String, val answerClaim: String, val severity: Float) : ReasoningOutput()
    data class DebateStarted(val perspectives: List<String>) : ReasoningOutput()
    data class DebateConsensus(val consensusLevel: Float, val notes: String) : ReasoningOutput()
    data class PredictionResult(val predictedQuery: String, val similarity: Float, val isSurprising: Boolean) : ReasoningOutput()
    data class KnowledgeGapAlert(val topic: String, val gapType: String, val suggestion: String) : ReasoningOutput()
    /** World model snapshot was injected into the LLM context (section 3.5). */
    data class WorldModelContext(val entityCount: Int, val summary: String) : ReasoningOutput()
    /** The world model was updated after this exchange. */
    data class WorldModelUpdated(val entitiesTouched: Int, val relationsTouched: Int, val revisions: Int) : ReasoningOutput()
}

fun processReasoning(
    llm: LlmWrapper,
    contextManager: ContextManager,
    memorySystem: MemorySystem,
    query: String,
    system1Cache: System1Cache? = null,
    metacognitiveMonitor: MetacognitiveMonitor? = null,
    constraintChecker: ConstraintChecker? = null,
    predictiveCoder: PredictiveCoder? = null,
    worldModelQuery: WorldModelQuery? = null,
    worldModelEngine: WorldModelEngine? = null,
    worldModelContradictionChecker: WorldModelContradictionChecker? = null
): Flow<ReasoningOutput> = flow {
    val timeoutJob = launch {
        delay(45_000L)
        emit(ReasoningOutput.Done(0L, ReasoningMode.CHAIN_OF_THOUGHT, 0.6f))
    }

    try {
        processReasoningInternal(
            llm, contextManager, memorySystem, query,
            system1Cache, metacognitiveMonitor, constraintChecker,
            predictiveCoder, worldModelQuery, worldModelEngine,
            worldModelContradictionChecker, this  // this is the FlowCollector
        )
    } finally {
        timeoutJob.cancel()
    }
}

private suspend fun processReasoningInternal(
    llm: LlmWrapper,
    contextManager: ContextManager,
    memorySystem: MemorySystem,
    query: String,
    system1Cache: System1Cache?,
    metacognitiveMonitor: MetacognitiveMonitor?,
    constraintChecker: ConstraintChecker?,
    predictiveCoder: PredictiveCoder?,
    worldModelQuery: WorldModelQuery?,
    worldModelEngine: WorldModelEngine?,
    worldModelContradictionChecker: WorldModelContradictionChecker?,
    emit: FlowCollector<ReasoningOutput>
) {
    
    // ═══ PHASE 0a: WORLD MODEL — snapshot before generation (section 3.5) ═══
    // Pull the agent's current beliefs relevant to this query and inject them
    // into the prompt context. The LLM reasons OVER the world model, not in
    // place of it. This is what separates Agent 42 from a bare LLM wrapper.
    var worldModelSnapshot = ""
    worldModelQuery?.let { wmq ->
        val snapshot = wmq.snapshot(query)
        if (snapshot.isNotBlank()) {
            worldModelSnapshot = snapshot
            emit.emit(ReasoningOutput.WorldModelContext(
                entityCount = snapshot.count { it == '\n' },
                summary = snapshot.lineSequence().firstOrNull() ?: ""
            ))
        }
    }

    // ═══ PHASE 0: PREDICTIVE CODING — generate expectation before processing ═══
    // The agent predicts what the user wants before even reading the query.
    // If reality matches expectation, processing is faster (less reasoning needed).
    // If reality diverges (high surprise), trigger deeper reasoning.
    var surpriseScore = 0f
    predictiveCoder?.let { coder ->
        val expectation = coder.generateExpectation(
            sessionId = contextManager.sessionId,
            recentContext = contextManager.getContextEntries().map { it.second },
            timeOfDay = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY).toString(),
            motionState = "UNKNOWN"
        )
        val surprise = coder.resolveExpectation(query)
        surpriseScore = surprise.surpriseScore
        if (coder.shouldTriggerDeepReasoning(surpriseScore)) {
            // High surprise — the agent didn't expect this. Pay more attention.
            emit.emit(ReasoningOutput.PredictionResult(surprise.expectedTopic, 1f - surpriseScore, true))
        }
    }

    // ═══ PHASE 1: SYSTEM 1 — check cache for instant answer ═══
    // Fast path: if we've answered something very similar before with high confidence,
    // return the cached answer immediately. No LLM call needed.
    system1Cache?.let { cache ->
        val fastResult = cache.tryFastPath(query)
        if (fastResult is System1Result.Hit) {
            emit.emit(ReasoningOutput.System1Hit(fastResult.answer, fastResult.similarity))
            // System 1 hit — but still do post-verification for safety
            val interactionId = contextManager.recordInteraction(query, ReasoningMode.DIRECT)
            emit.emit(ReasoningOutput.Chunk(fastResult.answer))
            // Still verify and check consistency
            val (confidence, verified) = selfConsistencyCheck(llm, query, fastResult.answer) { emit(it) }
            constraintChecker?.let { checker ->
                val verification = checker.verifyAnswer(llm, fastResult.answer, query)
                if (!verification.verified) {
                    verification.contradictions.forEach { c ->
                        emit.emit(ReasoningOutput.ConstraintViolation(c.factText, c.answerClaim, c.severity))
                    }
                }
            }
            cache.cacheAnswer(query, fastResult.answer, confidence, ReasoningMode.DIRECT)
            emit.emit(ReasoningOutput.Done(interactionId, ReasoningMode.DIRECT, confidence))
            return
        }
    }

    val mode = classifyQuery(llm, query)
    val interactionId = contextManager.recordInteraction(query, mode)

    var finalConfidence = 1.0f
    var finalAnswer = StringBuilder()

    // ═══ PHASE 2: SYSTEM 2 — full reasoning (with metacognitive monitoring) ═══
    when (mode) {
        ReasoningMode.DIRECT -> {
            val prompt = buildPrompt(contextManager, memorySystem, query, worldModelSnapshot)
            val accumulated = StringBuilder()
            llm.generateStreamFlow(prompt, generationConfig(thinking = true))
                .collect { chunk ->
                    if (chunk is LlmStreamResult.Token) {
                        finalAnswer.append(chunk.text)
                        accumulated.append(chunk.text)
                        emit.emit(ReasoningOutput.Chunk(chunk.text))
                        // Metacognitive monitoring during streaming
                        metacognitiveMonitor?.let { monitor ->
                            val issue = monitor.analyzeChunk(chunk.text, accumulated.toString(), interactionId)
                            if (issue != null && issue.severity > 0.7f) {
                                emit.emit(ReasoningOutput.MetacognitiveAlert(issue.issueType, issue.description, issue.severity))
                            }
                        }
                    }
                }
        }

        ReasoningMode.CHAIN_OF_THOUGHT -> {
            val prompt = buildThoughtChainPrompt(contextManager, memorySystem, query, worldModelSnapshot)
            val accumulated = StringBuilder()
            llm.generateStreamFlow(prompt, generationConfig(thinking = true))
                .collect { chunk ->
                    if (chunk is LlmStreamResult.Token) {
                        finalAnswer.append(chunk.text)
                        accumulated.append(chunk.text)
                        emit.emit(ReasoningOutput.Chunk(chunk.text))
                        metacognitiveMonitor?.let { monitor ->
                            val issue = monitor.analyzeChunk(chunk.text, accumulated.toString(), interactionId)
                            if (issue != null && issue.severity > 0.7f) {
                                emit.emit(ReasoningOutput.MetacognitiveAlert(issue.issueType, issue.description, issue.severity))
                            }
                        }
                    }
                }
        }

        ReasoningMode.DECOMPOSE -> {
            val subProblems = decompose(llm, query)
            if (subProblems.isEmpty()) {
                val prompt = buildThoughtChainPrompt(contextManager, memorySystem, query, worldModelSnapshot)
                llm.generateStreamFlow(prompt, generationConfig(thinking = true))
                    .collect { chunk ->
                        if (chunk is LlmStreamResult.Token) {
                            finalAnswer.append(chunk.text)
                            emit.emit(ReasoningOutput.Chunk(chunk.text))
                        }
                    }
                emit.emit(ReasoningOutput.Done(interactionId, mode))
                return
            }
            val subResults = mutableMapOf<String, String>()
            for (sub in subProblems) {
                emit.emit(ReasoningOutput.SubTaskStarted(sub))
                val subPrompt = buildSubProblemPrompt(sub, query)
                val result = StringBuilder()
                llm.generateStreamFlow(subPrompt, generationConfig(thinking = true))
                    .collect { chunk ->
                        if (chunk is LlmStreamResult.Token) {
                            result.append(chunk.text)
                            finalAnswer.append(chunk.text)
                            emit.emit(ReasoningOutput.Chunk(chunk.text))
                        }
                    }
                subResults[sub] = result.toString()
            }
            val synthPrompt = buildSynthesisPrompt(query, subResults)
            finalAnswer.clear()
            llm.generateStreamFlow(synthPrompt, generationConfig(thinking = false))
                .collect { chunk ->
                    if (chunk is LlmStreamResult.Token) {
                        finalAnswer.append(chunk.text)
                        emit.emit(ReasoningOutput.Chunk(chunk.text))
                    }
                }
        }

        ReasoningMode.REFLECTIVE -> {
            val initialPrompt = buildPrompt(contextManager, memorySystem, query, worldModelSnapshot)
            val initialAnswer = StringBuilder()
            llm.generateStreamFlow(initialPrompt, generationConfig(thinking = true))
                .collect { chunk ->
                    if (chunk is LlmStreamResult.Token) {
                        initialAnswer.append(chunk.text)
                        emit.emit(ReasoningOutput.Chunk(chunk.text))
                    }
                }
            val critique = critique(llm, query, initialAnswer.toString())
            if (critique.hasIssues) {
                emit.emit(ReasoningOutput.RefinementBoundary)
                val refinedPrompt = buildRefinementPrompt(
                    query, initialAnswer.toString(), critique.notes
                )
                finalAnswer.clear()
                llm.generateStreamFlow(refinedPrompt, generationConfig(thinking = false))
                    .collect { chunk ->
                        if (chunk is LlmStreamResult.Token) {
                            finalAnswer.append(chunk.text)
                            emit.emit(ReasoningOutput.Chunk(chunk.text))
                        }
                    }
            } else {
                finalAnswer = initialAnswer
            }
        }

        ReasoningMode.TREE_OF_THOUGHTS -> {
            // For the most complex queries, use Internal Debate instead of ToT
            // if the query is evaluative/advisory. Debate = branches that interact.
            val lower = query.lowercase()
            val debatePatterns = listOf("should i", "best approach", "pros and cons", "evaluate",
                                         "trade-offs", "what if", "recommend", "advise")
            if (debatePatterns.any { lower.contains(it) }) {
                // Use Internal Debate — multi-perspective argumentation
                emit.emit(ReasoningOutput.DebateStarted(listOf("SKEPTIC", "OPTIMIST", "PRAGMATIST")))
                val context = buildPrompt(contextManager, memorySystem, query, worldModelSnapshot)
                val debateResult = InternalDebate.conductDebate(llm, query, context) { emit(it) }
                finalAnswer = StringBuilder(debateResult.finalAnswer)
                emit.emit(ReasoningOutput.DebateConsensus(debateResult.consensusLevel, debateResult.judgeNotes))
                // Factor debate consensus into confidence
                finalConfidence = debateResult.consensusLevel
            } else {
                // Use Tree of Thoughts for exploration-type queries
                val best = treeOfThoughts(llm, contextManager, memorySystem, query, { emit(it) }, worldModelSnapshot)
                finalAnswer = StringBuilder(best)
            }
        }
    }

    // ═══ PHASE 3: SELF-CONSISTENCY CHECK ═══
    val (confidence, verified) = selfConsistencyCheck(
        llm, query, finalAnswer.toString()
    ) { emit.emit(it) }
    finalConfidence = confidence

    // If verification fails, trigger a reflective refinement
    if (!verified && mode != ReasoningMode.REFLECTIVE) {
        emit.emit(ReasoningOutput.RefinementBoundary)
        val critique = critique(llm, query, finalAnswer.toString())
        if (critique.hasIssues) {
            finalAnswer.clear()
            val refinedPrompt = buildRefinementPrompt(
                query, finalAnswer.toString(), critique.notes
            )
            llm.generateStreamFlow(refinedPrompt, generationConfig(thinking = false))
                .collect { chunk ->
                    if (chunk is LlmStreamResult.Token) {
                        finalAnswer.append(chunk.text)
                        emit.emit(ReasoningOutput.Chunk(chunk.text))
                    }
                }
            // Re-check confidence after refinement
            val (refinedConfidence, refinedVerified) = selfConsistencyCheck(
                llm, query, finalAnswer.toString()
            ) { emit.emit(it) }
            finalConfidence = refinedConfidence
        }
    }

    // ═══ PHASE 4: CONSTRAINT-BASED VERIFICATION (energy-based) ═══
    // Check the answer against known facts in the database — external verification
    // rather than asking the LLM "is this correct?" (which is unreliable).
    constraintChecker?.let { checker ->
        val verification = checker.verifyAnswer(llm, finalAnswer.toString(), query)
        if (!verification.verified) {
            verification.contradictions.forEach { c ->
                emit.emit(ReasoningOutput.ConstraintViolation(c.factText, c.answerClaim, c.severity))
                // Reduce confidence for each contradiction found
                finalConfidence = (finalConfidence - c.severity * 0.2f).coerceAtLeast(0f)
            }
        }
        // Extract new facts from this answer for future verification
        checker.extractFacts(llm, finalAnswer.toString(), interactionId)
    }

    // ═══ PHASE 4b: WORLD-MODEL CONTRADICTION CHECK (section 3.5) ═══
    // Independently of the ConstraintChecker's known-facts DB, check the answer
    // against the agent's high-confidence world-model beliefs. A contradiction
    // here means the agent's own model of reality disagrees with what it just
    // said — a strong signal of hallucination.
    worldModelContradictionChecker?.let { checker ->
        val wmContradictions = checker.check(finalAnswer.toString())
        wmContradictions.forEach { c ->
            emit.emit(ReasoningOutput.ConstraintViolation(c.beliefFact, c.answerClaim, c.severity))
            // World-model contradictions are weighted harder: they're the agent
            // disagreeing with itself, not just an external fact mismatch.
            finalConfidence = (finalConfidence - c.severity * 0.25f).coerceAtLeast(0f)
        }
    }

    // ═══ PHASE 5: METACOGNITIVE FINAL REVIEW ═══
    // Full review after generation — deeper than chunk-level analysis
    metacognitiveMonitor?.let { monitor ->
        val issues = monitor.finalReview(finalAnswer.toString(), query, interactionId)
        issues.filter { it.severity > 0.5f }.forEach { issue ->
            emit.emit(ReasoningOutput.MetacognitiveAlert(issue.issueType, issue.description, issue.severity))
            finalConfidence = (finalConfidence - issue.severity * 0.1f).coerceAtLeast(0f)
        }
    }

    // ═══ PHASE 6: CACHE THE ANSWER (System 1 learning) ═══
    system1Cache?.let { cache ->
        cache.cacheAnswer(query, finalAnswer.toString(), finalConfidence, mode)
    }

    // ═══ PHASE 7: KNOWLEDGE GAP TRACKING ═══
    // If confidence is low, record this as a knowledge gap
    if (finalConfidence < 0.4f) {
        val topic = query.split(" ").filter { it.length > 4 }.take(3).joinToString(" ")
        if (topic.isNotBlank()) {
            emit.emit(ReasoningOutput.KnowledgeGapAlert(
                topic, "LOW_CONFIDENCE",
                "I'm not confident about this topic. I should learn more."
            ))
        }
    }

        // ═══ PHASE 8: WORLD MODEL UPDATE (section 3.5) ═══
    worldModelEngine?.let { engine ->
        val results = engine.ingestExchange(
            userQuery = query,
            agentResponse = finalAnswer.toString(),
            sessionId = contextManager.sessionId
        )
        val totalEntities = results.sumOf { it.entitiesTouched.size }
        val totalRelations = results.sumOf { it.relationsTouched.size }
        val totalRevisions = results.sumOf { it.revisions.size }
        if (totalEntities + totalRelations + totalRevisions > 0) {
            emit.emit(ReasoningOutput.WorldModelUpdated(totalEntities, totalRelations, totalRevisions))
        }
    }

    emit.emit(ReasoningOutput.Done(interactionId, mode, finalConfidence))
}

private suspend fun classifyQuery(llm: LlmWrapper, query: String): ReasoningMode {
    val lower = query.lowercase()
    val directPatterns = listOf("what is", "who is", "when did", "define", "translate")
    if (directPatterns.any { lower.startsWith(it) } && lower.length < 60) {
        return ReasoningMode.DIRECT
    }
    val complexPatterns = listOf(
        "step by step", "compare", "analyze", "design", "plan",
        "how would you", "what if", "optimize", "prove that",
        "and also", "additionally", "best approach", "should i",
        "trade-offs", "pros and cons", "evaluate"
    )
    val hasMultipleClauses = query.split(" and ", ", then", ";").size > 2
    if (complexPatterns.any { lower.contains(it) } || hasMultipleClauses) {
        // Route open-ended or evaluative questions to Tree of Thoughts
        val totPatterns = listOf("best approach", "should i", "trade-offs", "pros and cons",
                                  "evaluate", "compare", "what if", "how would you")
        if (totPatterns.any { lower.contains(it) }) {
            return ReasoningMode.TREE_OF_THOUGHTS
        }
        val classifierPrompt = """
            Classify this query into exactly one mode:
            - DECOMPOSE: requires breaking into independent sub-tasks
            - CHAIN_OF_THOUGHT: requires sequential logical steps
            - REFLECTIVE: needs initial answer + self-correction
            - TREE_OF_THOUGHTS: open-ended, multiple valid approaches, needs exploration
            Query: "$query"
            Respond with one word: DECOMPOSE, CHAIN_OF_THOUGHT, REFLECTIVE, or TREE_OF_THOUGHTS
        """.trimIndent()
        val result = StringBuilder()
        llm.generateStreamFlow(
            classifierPrompt,
            GenerationConfig(maxTokens = 10)
        ).collect { chunk -> if (chunk is LlmStreamResult.Token) result.append(chunk.text) }
        return when (result.toString().trim().uppercase()) {
            "DECOMPOSE" -> ReasoningMode.DECOMPOSE
            "REFLECTIVE" -> ReasoningMode.REFLECTIVE
            "TREE_OF_THOUGHTS" -> ReasoningMode.TREE_OF_THOUGHTS
            else -> ReasoningMode.CHAIN_OF_THOUGHT
        }
    }
    return ReasoningMode.CHAIN_OF_THOUGHT
}

private suspend fun decompose(llm: LlmWrapper, query: String): List<String> {
    val decomposePrompt = """
        Break this request into independent sub-tasks.
        Output ONLY the sub-tasks, one per line, numbered.
        Request: "$query"
    """.trimIndent()
    val result = StringBuilder()
    llm.generateStreamFlow(decomposePrompt, GenerationConfig(maxTokens = 256))
        .collect { chunk -> if (chunk is LlmStreamResult.Token) result.append(chunk.text) }
    return result.toString().lines().mapNotNull { line ->
        val trimmed = line.trim()
        when {
            Regex("^\\d+[.)\\-]\\s+(.+)").matchEntire(trimmed) != null ->
                Regex("^\\d+[.)\\-]\\s+(.+)").find(trimmed)?.groupValues?.get(1)
            trimmed.startsWith("- ") || trimmed.startsWith("* ") -> trimmed.drop(2)
            else -> null
        }
    }.filter { it.isNotBlank() }
}

data class Critique(val hasIssues: Boolean, val notes: String)

private suspend fun critique(llm: LlmWrapper, query: String, answer: String): Critique {
    val critiquePrompt = """
        You are reviewing an answer for correctness.
        Original question: "$query"
        Proposed answer: "$answer"
        Identify any: Factual errors, Logical gaps, Missing considerations, Unsupported claims
        If the answer is correct and complete, respond: CLEAN
        Otherwise, list specific issues.
    """.trimIndent()
    val result = StringBuilder()
    llm.generateStreamFlow(critiquePrompt, GenerationConfig(maxTokens = 512))
        .collect { chunk -> if (chunk is LlmStreamResult.Token) result.append(chunk.text) }
    val text = result.toString().trim()
    return Critique(hasIssues = !text.uppercase().startsWith("CLEAN"), notes = text)
}

private suspend fun buildPrompt(
    contextManager: ContextManager,
    memorySystem: MemorySystem,
    query: String,
    worldModelContext: String = ""
): String {
    val context = contextManager.getContextEntries()
    val persona = contextManager.getActivePersona()
    val relevantMemories = memorySystem.recallRelevant(query)
    val maxMemoryChars = 2000
    val truncatedMemories = relevantMemories.joinToString("\n").take(maxMemoryChars)
    return buildString {
        appendLine(persona.systemPrompt)
        if (contextManager.sensorContext.isNotBlank()) {
            appendLine("\n## Current Context")
            appendLine(contextManager.sensorContext)
        }
        if (worldModelContext.isNotBlank()) {
            appendLine()
            appendLine(worldModelContext)
        }
        if (truncatedMemories.isNotBlank()) {
            appendLine("\n## Relevant Context From Memory")
            appendLine(truncatedMemories)
        }
        if (context.isNotEmpty()) {
            appendLine("\n## Conversation So Far")
            context.forEach { (role, msg) -> appendLine("$role: $msg") }
        }
        appendLine("\n## User")
        appendLine(query)
    }
}

private suspend fun buildThoughtChainPrompt(
    contextManager: ContextManager,
    memorySystem: MemorySystem,
    query: String,
    worldModelContext: String = ""
): String {
    return buildPrompt(contextManager, memorySystem, query, worldModelContext) +
           "\n\nThink through this step by step before answering."
}

private fun buildSubProblemPrompt(subTask: String, originalQuery: String): String {
    return """
        You are solving part of a larger problem.
        Original request: "$originalQuery"
        Your specific sub-task: "$subTask"
        Solve only this sub-task thoroughly.
    """.trimIndent()
}

private fun buildSynthesisPrompt(query: String, subResults: Map<String, String>): String {
    val subTaskSummaries = subResults.entries.joinToString("\n\n") { "### ${it.key}\n${it.value}" }
    return """
        Synthesize these partial results into one coherent answer.
        Original request: "$query"
        Sub-task results:
        $subTaskSummaries
        Provide a unified, well-structured answer.
    """.trimIndent()
}

private fun buildRefinementPrompt(query: String, initial: String, critiqueNotes: String): String {
    return """
        Refine this answer based on critique.
        Question: "$query"
        Initial answer: "$initial"
        Issues found: "$critiqueNotes"
        Provide the corrected, improved answer.
    """.trimIndent()
}

private fun generationConfig(thinking: Boolean) = GenerationConfig(maxTokens = 4096)

// ═══════════════════════════════════════════════════════════════
// UPGRADE 1: TREE OF THOUGHTS
// Generate multiple reasoning branches, score each, keep the best.
// ═══════════════════════════════════════════════════════════════

private suspend fun treeOfThoughts(
    llm: LlmWrapper,
    contextManager: ContextManager,
    memorySystem: MemorySystem,
    query: String,
    emit: suspend (ReasoningOutput) -> Unit,
    worldModelContext: String = ""
): String {
    val prompt = buildPrompt(contextManager, memorySystem, query, worldModelContext)
    val branchCount = 3
    val branches = mutableListOf<String>()

    // Generate branches in parallel using different temperature/seed approaches
    for (i in 0 until branchCount) {
        emit.emit(ReasoningOutput.BranchStarted(i, "Exploring approach ${i + 1}"))
        val branchResult = StringBuilder()
        val config = GenerationConfig(maxTokens = 2048)
        llm.generateStreamFlow("$prompt\n\nApproach this from a different angle. Attempt ${i + 1}.", config)
            .collect { chunk ->
                if (chunk is LlmStreamResult.Token) branchResult.append(chunk.text)
                // Don't stream all branches to UI — too noisy. Only show the winner.
            }
        branches.add(branchResult.toString())
    }

    // Score each branch
    val scored = branches.mapIndexed { index, answer ->
        val score = scoreAnswer(llm, query, answer)
        emit.emit(ReasoningOutput.BranchScored(index, score.score, score.reason))
        Triple(index, answer, score)
    }.sortedByDescending { it.third.score }

    val best = scored.first()
    val worst = scored.last()

    // If top branches strongly disagree, merge the best two
    if (scored.size >= 2 && best.third.score - worst.third.score < 0.3f) {
        emit.emit(ReasoningOutput.BranchStarted(99, "Merging top approaches for a stronger answer"))
        val mergePrompt = """
            Two approaches were attempted for: "$query"
            Approach A: "${best.second.take(500)}"
            Approach B: "${scored[1].second.take(500)}"
            Merge the best insights from both into one superior answer.
        """.trimIndent()
        val merged = StringBuilder()
        llm.generateStreamFlow(mergePrompt, generationConfig(thinking = false))
            .collect { chunk ->
                if (chunk is LlmStreamResult.Token) {
                    merged.append(chunk.text)
                    emit.emit(ReasoningOutput.Chunk(chunk.text))
                }
            }
        return merged.toString()
    }

    // Stream the winning branch to UI
    best.second.forEach { char -> emit(ReasoningOutput.Chunk(char.toString())) }
    return best.second
}

data class AnswerScore(val score: Float, val reason: String)

private suspend fun scoreAnswer(llm: LlmWrapper, query: String, answer: String): AnswerScore {
    val scorePrompt = """
        Rate this answer on a scale of 0.0 to 1.0.
        Question: "$query"
        Answer: "${answer.take(800)}"
        Consider: accuracy, completeness, relevance, logical soundness.
        Respond with ONLY a JSON object: {"score": 0.X, "reason": "brief explanation"}
    """.trimIndent()
    val result = StringBuilder()
    llm.generateStreamFlow(scorePrompt, GenerationConfig(maxTokens = 128))
        .collect { chunk -> if (chunk is LlmStreamResult.Token) result.append(chunk.text) }
    val text = result.toString().trim()
    val scoreMatch = Regex(""""?score"?\s*:\s*([\d.]+)""").find(text)
    val reasonMatch = Regex(""""?reason"?\s*:\s*"([^"]+)"""").find(text)
    val score = scoreMatch?.groupValues?.get(1)?.toFloatOrNull() ?: 0.5f
    val reason = reasonMatch?.groupValues?.get(1) ?: "No explanation provided"
    return AnswerScore(score.coerceIn(0f, 1f), reason)
}

// ═══════════════════════════════════════════════════════════════
// UPGRADE 2: SELF-CONSISTENCY CHECK
// After generating an answer, verify it independently.
// If verification disagrees, trigger a reflective refinement.
// ═══════════════════════════════════════════════════════════════

private suspend fun selfConsistencyCheck(
    llm: LlmWrapper,
    query: String,
    answer: String,
    emit: suspend (ReasoningOutput) -> Unit
): Pair<Float, Boolean> {
    val verifyPrompt = """
        You are verifying an answer independently.
        Question: "$query"
        Answer to verify: "${answer.take(800)}"
        Do NOT look at the answer first. Think about the question yourself,
        then check if the answer is correct, complete, and free of errors.
        Respond with ONLY a JSON object:
        {"correct": true/false, "confidence": 0.X, "issues": "brief description of any issues, or null if none"}
    """.trimIndent()
    val result = StringBuilder()
    llm.generateStreamFlow(verifyPrompt, GenerationConfig(maxTokens = 256))
        .collect { chunk -> if (chunk is LlmStreamResult.Token) result.append(chunk.text) }
    val text = result.toString().trim()
    val correctMatch = Regex(""""?correct"?\s*:\s*(true|false)""", RegexOption.IGNORE_CASE).find(text)
    val confidenceMatch = Regex(""""?confidence"?\s*:\s*([\d.]+)""").find(text)
    val issuesMatch = Regex(""""?issues"?\s*:\s*"([^"]+)"""").find(text)

    val isCorrect = correctMatch?.groupValues?.get(1)?.lowercase() == "true"
    val confidence = confidenceMatch?.groupValues?.get(1)?.toFloatOrNull() ?: 0.5f
    val issues = issuesMatch?.groupValues?.get(1)
    val verified = isCorrect && confidence >= 0.7f

    emit.emit(ReasoningOutput.ConfidenceCheck(confidence, verified, issues))

    return Pair(confidence, verified)
}

// ═══════════════════════════════════════════════════════════════
// UPGRADE 3: CONFIDENCE-GATED RESPONSE
// If confidence is below threshold, prepend "I'm not sure" and show caveats.
// Honest uncertainty rather than confident hallucination.
// ═══════════════════════════════════════════════════════════════

private const val CONFIDENCE_THRESHOLD = 0.6f

private fun confidenceGate(answer: String, confidence: Float, issues: String?): String {
    if (confidence >= CONFIDENCE_THRESHOLD) return answer
    val caveat = buildString {
        appendLine("⚠ I'm not fully confident in this answer (confidence: ${"%.0f".format(confidence * 100)}%).")
        if (issues != null && issues != "null") {
            appendLine("Potential issues: $issues")
        }
        appendLine("Please verify independently. Here's my best attempt:")
        appendLine()
    }
    return caveat + answer
}
