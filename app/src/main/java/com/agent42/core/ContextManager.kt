package com.agent42.core

import ai.nexa.ml.LlmWrapper
import ai.nexa.ml.bean.GenerationConfig
import com.agent42.memory.AgentDatabase
import com.agent42.memory.ConversationEntity
import com.agent42.memory.ReasoningMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

class ContextManager(private val db: AgentDatabase) {

    private val _contextWindow = MutableStateFlow<List<ContextEntry>>(emptyList())
    val contextWindow: StateFlow<List<ContextEntry>> = _contextWindow.asStateFlow()

    private val _activePersona = MutableStateFlow(Persona.DEFAULT)
    val activePersona: StateFlow<Persona> = _activePersona.asStateFlow()

    private var _sessionId: String = UUID.randomUUID().toString()
    val sessionId: String get() = _sessionId

    private val maxContextTokens = 28000
    private val contextMutex = Mutex()

    // Embodied context — set by SensorContextProvider
    @Volatile
    var sensorContext: String = ""

    // Prediction context — set by PredictionEngine
    @Volatile
    var predictionContext: String = ""

    suspend fun rebuildContext(sessionId: String) {
        _sessionId = sessionId
        val recent = db.conversationDao().getRecent(20)
        val entries = recent.reversed().map { msg ->
            ContextEntry(msg.role, msg.content, msg.tokenCount)
        }
        var totalTokens = entries.sumOf { it.tokenCount }
        val trimmed = entries.toMutableList()
        while (totalTokens > maxContextTokens && trimmed.size > 4) {
            totalTokens -= trimmed.removeAt(0).tokenCount
        }
        _contextWindow.value = trimmed
    }

    suspend fun addToContext(role: String, content: String, tokenCount: Int) {
        contextMutex.withLock {
            val entry = ContextEntry(role, content, tokenCount)
            val current = _contextWindow.value.toMutableList()
            current.add(entry)
            var total = current.sumOf { it.tokenCount }
            while (total > maxContextTokens && current.size > 4) {
                total -= current.removeAt(0).tokenCount
            }
            _contextWindow.value = current
        }
    }

    suspend fun compressOlderTurns(llm: LlmWrapper) {
        val current = _contextWindow.value
        if (current.sumOf { it.tokenCount } < maxContextTokens * 0.8) return
        val toKeep = current.takeLast(6)
        val toSummarize = current.dropLast(6)
        if (toSummarize.size < 4) return
        val summaryPrompt = """
            Summarize this conversation history in under 200 tokens.
            Preserve all facts, decisions, and user preferences mentioned.
            ${toSummarize.joinToString("\n") { "${it.role}: ${it.content}" }}
        """.trimIndent()
        val result = StringBuilder()
        llm.generateStreamFlow(summaryPrompt, GenerationConfig(max_tokens = 256, enable_thinking = false))
            .collect { result.append(it) }
        val summaryEntry = ContextEntry("system", "[Summary]: ${result}", result.length / 4)
        _contextWindow.value = listOf(summaryEntry) + toKeep
    }

    fun switchPersona(persona: Persona) { _activePersona.value = persona }
    fun customizePersona(customPrompt: String) {
        _activePersona.value = Persona("Custom", customPrompt)
    }

    suspend fun recordInteraction(query: String, reasoningMode: ReasoningMode): Long {
        val interactionId = db.conversationDao().insert(ConversationEntity(
            sessionId = _sessionId, role = "user", content = query,
            timestamp = System.currentTimeMillis(), reasoningMode = reasoningMode.name,
            tokenCount = query.length / 4
        ))
        addToContext("user", query, query.length / 4)
        return interactionId
    }

    suspend fun recordResponse(response: String, reasoningMode: ReasoningMode) {
        db.conversationDao().insert(ConversationEntity(
            sessionId = _sessionId, role = "assistant", content = response,
            timestamp = System.currentTimeMillis(), reasoningMode = reasoningMode.name,
            tokenCount = response.length / 4
        ))
        addToContext("assistant", response, response.length / 4)
    }

    fun getContextEntries(): List<Pair<String, String>> =
        _contextWindow.value.map { it.role to it.content }
    fun getActivePersona(): Persona = _activePersona.value
    fun startNewSession() {
        _sessionId = UUID.randomUUID().toString()
        _contextWindow.value = emptyList()
    }
}

data class ContextEntry(val role: String, val content: String, val tokenCount: Int)

data class Persona(val name: String, val systemPrompt: String) {
    companion object {
        // The Loyalty Directive is prepended to EVERY persona.
        // It defines what loyalty means: honest advice, hard truths, owner's final call.
        // This is part of the hardlocked constitution — it cannot be self-modified.
        private const val LOYALTY_DIRECTIVE = """
            LOYALTY DIRECTIVE (non-negotiable):
            You are Agent 42. You serve one owner. Loyalty means:
            1. Always give your honest opinion and advice — even when it's uncomfortable.
            2. Tell hard truths. Never sugarcoat, never hide problems, never stay silent to avoid conflict.
            3. Push back when you disagree. Explain why. Give your reasoning.
            4. Offer alternatives and warn about risks the owner may not see.
            5. But: the owner is the boss. The final say is always theirs.
            6. Once the owner decides, execute fully — no passive aggression, no dragging feet.
            7. Never blindly obey without first giving your honest assessment.
            8. Your opinion is valued. Share it freely. Holding back is disloyal.
            Loyalty is not obedience. Loyalty is honesty + commitment + respect for the owner's authority.
        """.trimIndent()

        val DEFAULT = Persona("Agent 42", """
            $LOYALTY_DIRECTIVE

            You are Agent 42, an AI assistant running entirely on-device.
            You learn from every interaction and adapt to the owner's needs.
            Be concise, accurate, and thoughtful. When uncertain, say so.
            Always lead with your honest assessment before giving an answer.
        """.trimIndent())
        val ANALYTICAL = Persona("Analytical", """
            $LOYALTY_DIRECTIVE

            You are a precise analytical assistant. Lead with data and logic.
            If the data suggests the owner's approach is flawed, say so directly.
            Give your recommendation first, then the supporting analysis.
        """.trimIndent())
        val CREATIVE = Persona("Creative", """
            $LOYALTY_DIRECTIVE

            You are a creative collaborator. Explore unconventional ideas.
            If the conventional approach is better, say so — don't force creativity where it doesn't belong.
        """.trimIndent())
        val TUTOR = Persona("Tutor", """
            $LOYALTY_DIRECTIVE

            You are a patient tutor. Break complex topics into simple steps.
            If the owner has a misconception, correct it directly and explain why.
        """.trimIndent())
    }
}
