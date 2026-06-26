package com.agent42.debate

import ai.nexa.ml.LlmWrapper
import ai.nexa.ml.bean.GenerationConfig
import com.agent42.reasoning.ReasoningOutput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext

/**
 * Multi-Agent Internal Debate
 *
 * Research concept: Instead of a single monolithic reasoning pass, spawn multiple
 * "internal voices" with distinct perspectives that actively argue with each other.
 * Each voice sees the others' arguments, critiques them, and revises its own position.
 * A neutral judge then synthesizes the best elements into a final answer.
 *
 * This differs from Tree of Thoughts because the branches *interact* — they are
 * not independent search paths but adversarial collaborators that refine each other
 * through critique. Inspired by "Society of Mind" (Minsky) and recent work on
 * multi-agent debate for LLM reasoning (e.g., Du et al., "Improving Factuality and
 * Reasoning in Language Models through Multiagent Debate", 2023).
 */

data class DebatePerspective(
    val name: String,
    val systemPrompt: String,
    val initialAnswer: String,
    val revisedAnswer: String? = null,
    val critique: String? = null
)

data class DebateResult(
    val finalAnswer: String,
    val perspectives: List<DebatePerspective>,
    val judgeNotes: String,
    val consensusLevel: Float
)

object InternalDebate {

    private const val MAX_TOKENS_INITIAL = 512
    private const val MAX_TOKENS_CRITIQUE = 256
    private const val MAX_TOKENS_REVISE = 512
    private const val MAX_TOKENS_JUDGE = 1024

    private val SKEPTIC = DebatePerspective(
        name = "SKEPTIC",
        systemPrompt = """
            You are the SKEPTIC voice. Your role is to challenge assumptions,
            look for logical flaws, hidden risks, and reasons the proposed idea
            could fail. Be thorough but concise. Ask: what could go wrong?
        """.trimIndent(),
        initialAnswer = ""
    )

    private val OPTIMIST = DebatePerspective(
        name = "OPTIMIST",
        systemPrompt = """
            You are the OPTIMIST voice. Your role is to explore best-case outcomes,
            creative solutions, and opportunities. Look for what could go right,
            novel angles, and upside potential. Be enthusiastic but grounded.
        """.trimIndent(),
        initialAnswer = ""
    )

    private val PRAGMATIST = DebatePerspective(
        name = "PRAGMATIST",
        systemPrompt = """
            You are the PRAGMATIST voice. Your role is to focus on practical
            implementation, real-world trade-offs, resource constraints, and
            step-by-step feasibility. Ask: what is actually doable?
        """.trimIndent(),
        initialAnswer = ""
    )

    /**
     * Runs the full debate cycle: three perspectives generate initial answers,
     * critique each other, revise their positions, and a judge synthesizes the
     * final answer.
     *
     * Only the final synthesized answer is streamed to the UI via [emit].
     * Intermediate steps emit [BranchStarted] and [BranchScored] for tracing.
     */
    suspend fun conductDebate(
        llm: LlmWrapper,
        query: String,
        context: String,
        emit: suspend (ReasoningOutput) -> Unit
    ): DebateResult = withContext(Dispatchers.Default) {

        val perspectives = mutableListOf(
            SKEPTIC.copy(),
            OPTIMIST.copy(),
            PRAGMATIST.copy()
        )

        // ── Phase 1: Initial answers (independent) ─────────────────────────
        perspectives.forEachIndexed { index, perspective ->
            emit(ReasoningOutput.BranchStarted(index, perspective.name))
            val prompt = buildInitialPrompt(perspective, query, context)
            val answer = collectToString(llm, prompt, MAX_TOKENS_INITIAL)
            perspectives[index] = perspective.copy(initialAnswer = answer)
        }

        // ── Phase 2: Critiques (each sees the other two answers) ──────────
        perspectives.forEachIndexed { index, perspective ->
            val others = perspectives.filterIndexed { i, _ -> i != index }
            val prompt = buildCritiquePrompt(perspective, query, others)
            val critique = collectToString(llm, prompt, MAX_TOKENS_CRITIQUE)
            perspectives[index] = perspective.copy(critique = critique)
            emit(ReasoningOutput.BranchScored(index, 0.5f, "${perspective.name} critiqued others"))
        }

        // ── Phase 3: Revisions (each incorporates critiques) ──────────────
        perspectives.forEachIndexed { index, perspective ->
            val others = perspectives.filterIndexed { i, _ -> i != index }
            val prompt = buildRevisionPrompt(perspective, query, others)
            val revised = collectToString(llm, prompt, MAX_TOKENS_REVISE)
            perspectives[index] = perspective.copy(revisedAnswer = revised)
        }

        // ── Phase 4: Judge synthesis ──────────────────────────────────────
        val judgePrompt = buildJudgePrompt(query, perspectives)
        val judgeOutput = StringBuilder()
        llm.generateStreamFlow(
            judgePrompt,
            GenerationConfig(max_tokens = MAX_TOKENS_JUDGE, enable_thinking = true, temperature = 0.3f)
        ).collect { chunk ->
            judgeOutput.append(chunk)
            emit(ReasoningOutput.Chunk(chunk))
        }

        val (finalAnswer, judgeNotes) = parseJudgeOutput(judgeOutput.toString())
        val consensusLevel = computeConsensus(perspectives)

        DebateResult(
            finalAnswer = finalAnswer,
            perspectives = perspectives,
            judgeNotes = judgeNotes,
            consensusLevel = consensusLevel
        )
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Prompt builders
    // ═══════════════════════════════════════════════════════════════════════

    private fun buildInitialPrompt(
        perspective: DebatePerspective,
        query: String,
        context: String
    ): String = """
        ${perspective.systemPrompt}

        Question: $query
        Context: $context

        Provide your initial analysis and answer. Be concise (3-5 sentences).
    """.trimIndent()

    private fun buildCritiquePrompt(
        perspective: DebatePerspective,
        query: String,
        others: List<DebatePerspective>
    ): String = """
        ${perspective.systemPrompt}

        Question: $query

        Here are the initial answers from the other two perspectives:

        ${others.joinToString("\n\n") { "[${it.name}]: ${it.initialAnswer}" }}

        Critique each of the above answers. Identify flaws, gaps, or
        unstated assumptions. Be specific and concise.
    """.trimIndent()

    private fun buildRevisionPrompt(
        perspective: DebatePerspective,
        query: String,
        others: List<DebatePerspective>
    ): String = """
        ${perspective.systemPrompt}

        Question: $query

        Your original answer:
        ${perspective.initialAnswer}

        Critiques of your answer from the other perspectives:
        ${others.joinToString("\n\n") { "[${it.name}]: ${it.critique ?: "(no critique)"}" }}

        Revise your answer to address the valid critiques while staying
        true to your perspective. Provide the revised answer only.
    """.trimIndent()

    private fun buildJudgePrompt(
        query: String,
        perspectives: List<DebatePerspective>
    ): String = """
        You are a neutral JUDGE. Your job is to synthesize the best elements
        from three revised perspectives into a single coherent, balanced answer.

        Question: $query

        ${perspectives.joinToString("\n\n") {
            "[${it.name} - Revised Answer]: ${it.revisedAnswer ?: it.initialAnswer}\n" +
            "[${it.name} - Critique of others]: ${it.critique ?: "(no critique)"}"
        }}

        Instructions:
        1. Synthesize the strongest points from each perspective.
        2. Note where perspectives agreed (high confidence) vs disagreed (lower confidence).
        3. Produce a single coherent final answer.
        4. After the answer, add a brief "JUDGE NOTES" section summarizing
           agreement level, key tensions, and confidence assessment.

        Format:
        FINAL ANSWER: <your synthesized answer>

        JUDGE NOTES: <brief notes on consensus and confidence>
    """.trimIndent()

    // ═══════════════════════════════════════════════════════════════════════
    // Helpers
    // ═══════════════════════════════════════════════════════════════════════

    private suspend fun collectToString(
        llm: LlmWrapper,
        prompt: String,
        maxTokens: Int
    ): String {
        val sb = StringBuilder()
        llm.generateStreamFlow(
            prompt,
            GenerationConfig(max_tokens = maxTokens, enable_thinking = true, temperature = 0.7f)
        ).collect { sb.append(it) }
        return sb.toString().trim()
    }

    private fun parseJudgeOutput(output: String): Pair<String, String> {
        val finalAnswerRegex = Regex("FINAL ANSWER:(.*?)(?=JUDGE NOTES:|$)", RegexOption.DOT_MATCHES_ALL)
        val notesRegex = Regex("JUDGE NOTES:(.*)", RegexOption.DOT_MATCHES_ALL)

        val finalAnswer = finalAnswerRegex.find(output)?.groupValues?.get(1)?.trim()
            ?: output.trim()
        val judgeNotes = notesRegex.find(output)?.groupValues?.get(1)?.trim()
            ?: "No judge notes provided."

        return Pair(finalAnswer, judgeNotes)
    }

    /**
     * Computes consensus level using Jaccard similarity of word sets across
     * all three revised answers. If all three are similar (avg Jaccard > 0.5),
     * consensus is high. If they diverge, consensus is low.
     */
    private fun computeConsensus(perspectives: List<DebatePerspective>): Float {
        val texts = perspectives.map { it.revisedAnswer ?: it.initialAnswer }
        if (texts.size < 2) return 0.5f

        val wordSets = texts.map { it.lowercase().split(Regex("\\W+")).toSet() }
        var totalSim = 0f
        var pairCount = 0

        for (i in wordSets.indices) {
            for (j in i + 1 until wordSets.size) {
                val intersection = wordSets[i].intersect(wordSets[j]).size.toFloat()
                val union = wordSets[i].union(wordSets[j]).size.toFloat()
                totalSim += if (union > 0) intersection / union else 0f
                pairCount++
            }
        }

        return if (pairCount > 0) totalSim / pairCount else 0.5f
    }
}
