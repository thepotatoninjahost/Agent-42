package com.agent42.core

import com.nexa.sdk.LlmWrapper
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.agent42.memory.*
import com.agent42.selfmodification.*
import com.agent42.reasoning.*
import com.agent42.cognition.System1Cache
import com.agent42.cognition.MetacognitiveMonitor
import com.agent42.curiosity.KnowledgeGapTracker
import com.agent42.prediction.PredictionEngine
import com.agent42.prediction.PredictiveCoder
import com.agent42.sensors.SensorContextProvider
import com.agent42.verification.ConstraintChecker
import com.agent42.worldmodel.WorldModelContradictionChecker
import com.agent42.worldmodel.WorldModelEngine
import com.agent42.worldmodel.WorldModelQuery
import com.agent42.worldmodel.WorldModelStats
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class AgentViewModel(
    private val llm: LlmWrapper,
    private val memorySystem: MemorySystem,
    private val contextManager: ContextManager,
    private val approvalGate: ApprovalGate,
    private val codeModEngine: CodeModificationEngine,
    private val thermalManager: ThermalManager,
    private val modelManager: ModelManager,
    // New cognitive systems
    private val system1Cache: System1Cache? = null,
    private val metacognitiveMonitor: MetacognitiveMonitor? = null,
    private val constraintChecker: ConstraintChecker? = null,
    private val predictiveCoder: PredictiveCoder? = null,
    private val predictionEngine: PredictionEngine? = null,
    private val knowledgeGapTracker: KnowledgeGapTracker? = null,
    private val sensorContextProvider: SensorContextProvider? = null,
    private val worldModelEngine: WorldModelEngine? = null,
    private val worldModelQuery: WorldModelQuery? = null,
    private val worldModelContradictionChecker: WorldModelContradictionChecker? = null,
    private val worldModelConsolidator: com.agent42.worldmodel.WorldModelConsolidator? = null
) : ViewModel() {

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _isThinking = MutableStateFlow(false)
    val isThinking: StateFlow<Boolean> = _isThinking.asStateFlow()

    private val _currentTrace = MutableStateFlow<ReasoningMode?>(null)
    val currentTrace: StateFlow<ReasoningMode?> = _currentTrace.asStateFlow()

    private val _modelStatus = MutableStateFlow(ModelStatus.LOADING)
    val modelStatus: StateFlow<ModelStatus> = _modelStatus.asStateFlow()

    private val _thermalStatus = MutableStateFlow(ThermalStatus.NORMAL)
    val thermalStatus: StateFlow<ThermalStatus> = _thermalStatus.asStateFlow()

    private val _allMemories = MutableStateFlow<List<MemoryEntity>>(emptyList())
    val allMemories: StateFlow<List<MemoryEntity>> = _allMemories.asStateFlow()

    private val _strategyWeights = MutableStateFlow<List<StrategyWeightEntity>>(emptyList())
    val strategyWeights: StateFlow<List<StrategyWeightEntity>> = _strategyWeights.asStateFlow()

    private val _memoryCount = MutableStateFlow(0)
    val memoryCount: StateFlow<Int> = _memoryCount.asStateFlow()

    private val _totalInteractions = MutableStateFlow(0)
    val totalInteractions: StateFlow<Int> = _totalInteractions.asStateFlow()

    private val _recentReflections = MutableStateFlow<List<MemoryEntity>>(emptyList())
    val recentReflections: StateFlow<List<MemoryEntity>> = _recentReflections.asStateFlow()

    val pendingProposals = approvalGate.pendingProposals
    val decisionHistory = approvalGate.decisionHistory

    // New cognitive system state flows
    val knowledgeGaps = MutableStateFlow<List<String>>(emptyList())
    val system1Stats = MutableStateFlow<String>("")
    val predictionAccuracy = MutableStateFlow<Float>(0f)
    val metacognitiveAlerts = MutableStateFlow<List<String>>(emptyList())
    // World model (system 2.1)
    private val _worldModelStats = MutableStateFlow<WorldModelStats?>(null)
    val worldModelStats: StateFlow<WorldModelStats?> = _worldModelStats.asStateFlow()
    private val _worldModelRevisions = MutableStateFlow<List<com.agent42.worldmodel.BeliefRevision>>(emptyList())
    val worldModelRevisions: StateFlow<List<com.agent42.worldmodel.BeliefRevision>> = _worldModelRevisions.asStateFlow()
    private val _worldModelEntities = MutableStateFlow<List<com.agent42.worldmodel.WorldEntity>>(emptyList())
    val worldModelEntities: StateFlow<List<com.agent42.worldmodel.WorldEntity>> = _worldModelEntities.asStateFlow()

    private val isProcessing = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * The active reasoning job, if any. Held so the owner can cancel a
     * runaway response via STOP NOW (Rule 7). Null when idle.
     */
    private var reasoningJob: kotlinx.coroutines.Job? = null

    /**
     * STOP NOW (Rule 7) — cancel any in-flight reasoning so the owner can
     * interrupt an agent that won't stop. The reasoning flow's
     * CancellationException path finalizes the partial message and releases
     * the isProcessing lock. Safe to call when idle (no-op).
     */
    fun cancelReasoning() {
        reasoningJob?.cancel()
    }

    init {
        viewModelScope.launch {
            approvalGate.loadDecisionHistory()
            // Only load model if not already loaded (MainActivity may have loaded it)
            if (!modelManager.isModelLoaded()) {
                modelManager.loadModel(
                    onProgress = { _modelStatus.value = ModelStatus.LOADING },
                    onComplete = { _modelStatus.value = ModelStatus.READY },
                    onError = { _modelStatus.value = ModelStatus.ERROR }
                )
            } else {
                _modelStatus.value = ModelStatus.READY
            }
            refreshData()
            loadUnresolvedTopics()
            loadCognitiveStats()
            schedulePeriodicTasks()
        }
    }

    fun sendQuery(query: String) {
        if (!isProcessing.compareAndSet(false, true)) return
        reasoningJob = viewModelScope.launch {
            _isThinking.value = true
            _currentTrace.value = null
            _messages.value = _messages.value + ChatMessage(
                id = System.currentTimeMillis(), role = "user", content = query,
                timestamp = System.currentTimeMillis(), reasoningMode = null
            )
            val assistantId = System.currentTimeMillis() + 1
            _messages.value = _messages.value + ChatMessage(
                id = assistantId, role = "assistant", content = "",
                timestamp = System.currentTimeMillis(), reasoningMode = null, isStreaming = true
            )
            var interactionId: Long = -1
            var reasoningMode: ReasoningMode = ReasoningMode.DIRECT
            val responseBuilder = StringBuilder()
            try {
                // UPGRADE 9: World Model — resolve previous prediction against actual query
                predictionEngine?.resolvePrediction(contextManager.sessionId, query)
                // UPGRADE 15: Embodied Context — capture sensor snapshot and feed to context
                sensorContextProvider?.let { sensor ->
                    sensor.captureSnapshot(contextManager.sessionId)
                    contextManager.sensorContext = sensor.getContextString()
                }
                val foreman = com.agent42.cognition.ExecutiveController { llm }
                processReasoning(
                    llm, contextManager, memorySystem, query,
                    system1Cache = system1Cache,
                    metacognitiveMonitor = metacognitiveMonitor,
                    constraintChecker = constraintChecker,
                    predictiveCoder = predictiveCoder,
                    worldModelQuery = worldModelQuery,
                    worldModelEngine = worldModelEngine,
                    worldModelContradictionChecker = worldModelContradictionChecker,
                    executiveController = foreman
                ).collect { output ->
                    when (output) {
                        is ReasoningOutput.Chunk -> {
                            responseBuilder.append(output.text)
                            _messages.value = _messages.value.map { msg ->
                                if (msg.id == assistantId) msg.copy(content = msg.content + output.text)
                                else msg
                            }
                        }
                        is ReasoningOutput.SubTaskStarted -> {}
                        ReasoningOutput.RefinementBoundary -> {
                            _messages.value = _messages.value.map { msg ->
                                if (msg.id == assistantId) msg.copy(content = "") else msg
                            }
                            responseBuilder.clear()
                        }
                        is ReasoningOutput.Done -> {
                            interactionId = output.interactionId
                            reasoningMode = output.mode
                            _currentTrace.value = output.mode
                            if (output.confidence < 0.6f) {
                                _messages.value = _messages.value.map { msg ->
                                    if (msg.id == assistantId) {
                                        msg.copy(
                                            content = "⚠ I'm not fully confident in this answer " +
                                                "(${(output.confidence * 100).toInt()}% confidence). " +
                                                "Please verify independently.\n\n" + msg.content,
                                            isError = false
                                        )
                                    } else msg
                                }
                            }
                            // Record knowledge gap if confidence is low
                            if (output.confidence < 0.4f) {
                                knowledgeGapTracker?.recordFailure(
                                    topic = query.split(" ").filter { it.length > 4 }.take(3).joinToString(" "),
                                    confidence = output.confidence,
                                    query = query
                                )
                            }
                            // Record feedback to System 1 cache
                            if (output.confidence >= 0.6f) {
                                system1Cache?.recordFeedback(query, wasCorrect = true)
                            }
                        }
                        is ReasoningOutput.BranchStarted -> {}
                        is ReasoningOutput.BranchScored -> {}
                        is ReasoningOutput.ConfidenceCheck -> {}
                        // New cognitive system outputs
                        is ReasoningOutput.System1Hit -> {
                            // Instant cache hit — no LLM was called
                        }
                        is ReasoningOutput.MetacognitiveAlert -> {
                            metacognitiveAlerts.value = metacognitiveAlerts.value + 
                                "[${output.issueType}] ${output.description}"
                        }
                        is ReasoningOutput.ConstraintViolation -> {
                            // A known fact was contradicted — flag it
                            metacognitiveAlerts.value = metacognitiveAlerts.value +
                                "⚠ Contradicts known fact: ${output.factText}"
                        }
                        is ReasoningOutput.DebateStarted -> {}
                        is ReasoningOutput.DebateConsensus -> {
                            if (output.consensusLevel < 0.4f) {
                                metacognitiveAlerts.value = metacognitiveAlerts.value +
                                    "Low consensus across perspectives (${(output.consensusLevel * 100).toInt()}%)"
                            }
                        }
                        is ReasoningOutput.PredictionResult -> {}
                        is ReasoningOutput.KnowledgeGapAlert -> {
                            knowledgeGaps.value = knowledgeGaps.value + output.suggestion
                        }
                        is ReasoningOutput.WorldModelContext -> {
                            // World-model snapshot was injected into the LLM prompt.
                            // Surface a light note so the owner can see the agent is
                            // reasoning over its beliefs, not just generating.
                            metacognitiveAlerts.value = metacognitiveAlerts.value +
                                "World model: ${output.entityCount} beliefs injected into context"
                        }
                        is ReasoningOutput.WorldModelUpdated -> {
                            // The world model was revised from this exchange.
                            metacognitiveAlerts.value = metacognitiveAlerts.value +
                                "World model updated: ${output.entitiesTouched} entities, " +
                                "${output.relationsTouched} relations, ${output.revisions} revisions"
                        }
                    }
                }
                val response = responseBuilder.toString()
                contextManager.recordResponse(response, reasoningMode)
                launch(Dispatchers.IO) {
                    memorySystem.extractAndStore(query, response, interactionId, llm)
                    // UPGRADE 9: World Model — predict what user asks next after each interaction
                    predictionEngine?.let { engine ->
                        val recentQueries = contextManager.getContextEntries()
                            .filter { it.first == "user" }
                            .map { it.second }
                            .takeLast(5)
                        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
                        val timeOfDay = when (hour) {
                            in 5..8 -> "early morning"
                            in 8..12 -> "morning"
                            in 12..17 -> "afternoon"
                            in 17..21 -> "evening"
                            else -> "late night"
                        }
                        engine.predictNextQuery(contextManager.sessionId, recentQueries, timeOfDay)
                    }
                    refreshData()
                }
            } catch (e: CancellationException) {
                // STOP NOW (owner cancelled). Finalize the partial assistant
                // message instead of leaving it in isStreaming=true forever.
                // (The generic Exception branch below is skipped because we
                // re-throw, so we handle UI cleanup here.)
                _messages.value = _messages.value.map { msg ->
                    if (msg.id == assistantId) {
                        val partial = responseBuilder.toString().trim()
                        msg.copy(
                            content = if (partial.isEmpty()) "_(stopped)_" else "$partial\n\n_(stopped by owner)_",
                            isStreaming = false,
                            isError = false
                        )
                    } else msg
                }
                throw e
            }
            catch (e: Exception) {
                _messages.value = _messages.value.map { msg ->
                    if (msg.id == assistantId) msg.copy(
                        content = "Error: ${e.message}", isStreaming = false, isError = true
                    ) else msg
                }
            } finally {
                _isThinking.value = false
                _currentTrace.value = null
                isProcessing.set(false)
                reasoningJob = null
            }
        }
    }

    fun recordFeedback(interactionId: Long, positive: Boolean, correction: String? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            memorySystem.recordFeedback(
                interactionId,
                if (positive) FeedbackType.EXPLICIT_THUMBS_UP else FeedbackType.EXPLICIT_THUMBS_DOWN,
                if (positive) 1.0f else -1.0f,
                userNotes = correction
            )
            val mode = _currentTrace.value
            if (mode != null) {
                memorySystem.updateStrategyWeights(
                    strategyName = mode.name, queryPattern = "general",
                    feedback = FeedbackEntity(
                        interactionId = interactionId,
                        signalType = if (positive) FeedbackType.EXPLICIT_THUMBS_UP else FeedbackType.EXPLICIT_THUMBS_DOWN,
                        signalValue = if (positive) 1.0f else -1.0f,
                        timestamp = System.currentTimeMillis(),
                        notes = correction
                    )
                )
            }
            // Store correction as a memory so the agent learns from it
            if (!positive && correction != null && correction.isNotBlank()) {
                memorySystem.extractAndStore(
                    query = "User correction for interaction $interactionId",
                    response = correction,
                    interactionId = interactionId,
                    llm = llm
                )
                // Mark the System 1 cache entry as incorrect so it won't be used again
                system1Cache?.recordFeedback(
                    _messages.value.lastOrNull { it.role == "user" }?.content ?: "",
                    wasCorrect = false
                )
            }
            if (positive) {
                system1Cache?.recordFeedback(
                    _messages.value.lastOrNull { it.role == "user" }?.content ?: "",
                    wasCorrect = true
                )
            }
            if (!positive) approvalGate.scanForProposals()
        }
    }

    fun approveProposal(proposalId: String) =
        viewModelScope.launch { approvalGate.approve(proposalId) }
    fun approveWithEdits(proposalId: String, editedCode: String) =
        viewModelScope.launch { approvalGate.approveWithEdits(proposalId, editedCode) }
    fun rejectProposal(proposalId: String, reason: String) =
        viewModelScope.launch { approvalGate.reject(proposalId, reason) }
    fun deferProposal(proposalId: String) =
        viewModelScope.launch { approvalGate.defer(proposalId, System.currentTimeMillis() + 86400000L) }

    fun switchPersona(persona: Persona) { contextManager.switchPersona(persona) }
    fun startNewConversation() {
        // UPGRADE 8: Save unresolved topics before starting new session
        viewModelScope.launch(Dispatchers.IO) {
            val recentMessages = _messages.value
            if (recentMessages.isNotEmpty()) {
                val lastQuery = recentMessages.lastOrNull { it.role == "user" }?.content
                if (lastQuery != null) {
                    memorySystem.saveActiveTopic(
                        topic = lastQuery.take(200),
                        sessionId = contextManager.sessionId,
                        unresolvedQuestion = null
                    )
                }
            }
        }
        contextManager.startNewSession()
        _messages.value = emptyList()
    }

    /**
     * UPGRADE 8: Load unresolved topics from previous sessions.
     * Called on startup to restore context continuity.
     */
    val unresolvedTopics = MutableStateFlow<List<String>>(emptyList())
    fun loadUnresolvedTopics() {
        viewModelScope.launch(Dispatchers.IO) {
            val topics = memorySystem.getUnresolvedTopics()
            unresolvedTopics.value = topics.map { topic ->
                buildString {
                    append(topic.topic)
                    topic.unresolvedQuestion?.let { append(" — unresolved: $it") }
                }
            }
        }
    }

    fun resolveTopic(topicIndex: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            val topics = memorySystem.getUnresolvedTopics()
            if (topicIndex < topics.size) {
                memorySystem.resolveTopic(topics[topicIndex].id)
            }
        }
    }

    private fun schedulePeriodicTasks() {
        viewModelScope.launch {
            while (isActive) {
                delay(24 * 60 * 60 * 1000L)
                if (_thermalStatus.value == ThermalStatus.NORMAL) {
                    memorySystem.runReflection(llm)
                    memorySystem.consolidateMemories(llm)
                    // New cognitive maintenance
                    system1Cache?.prune()
                    constraintChecker?.pruneUnreliableFacts()
                    knowledgeGapTracker?.identifyGaps(
                        llm,
                        memorySystem.getAllMemories().map { it.content }
                    )
                    // World model consolidation (section 3.3): merge duplicates,
                    // flag stale low-confidence beliefs, recalibrate by recency.
                    worldModelConsolidator?.consolidate()
                    refreshData()
                    loadCognitiveStats()
                }
            }
        }
        viewModelScope.launch {
            while (isActive) {
                delay(7 * 24 * 60 * 60 * 1000L)
                approvalGate.scanForProposals()
            }
        }
        viewModelScope.launch {
            while (isActive) {
                delay(30_000)
                _thermalStatus.value = thermalManager.check()
            }
        }
    }

    private suspend fun loadCognitiveStats() = withContext(Dispatchers.IO) {
        system1Cache?.let { cache ->
            val stats = cache.getCacheStats()
            system1Stats.value = "${stats.entries} cached | ${stats.totalHits} hits | ${(stats.accuracy * 100).toInt()}% accuracy"
        }
        predictionEngine?.let { engine ->
            predictionAccuracy.value = engine.getPredictionAccuracy()
        }
        knowledgeGapTracker?.let { tracker ->
            val suggestions = tracker.getProactiveSuggestions()
            knowledgeGaps.value = suggestions
        }
        refreshWorldModelStats()
    }

    /** Pull current world-model stats + recent revisions + top entities into the UI flows. */
    suspend fun refreshWorldModelStats() = withContext(Dispatchers.IO) {
        worldModelQuery?.let { wmq ->
            _worldModelStats.value = wmq.stats()
            _worldModelRevisions.value = wmq.recentRevisions(30)
            _worldModelEntities.value = wmq.topEntities(100)
        }
    }

    /**
     * Owner-driven correction of a single world-model entity belief. Called from
     * the World Model screen. Bypasses the Bayesian loop and stamps the belief
     * as [BeliefSource.OWNER_STATEMENT]; logs a BeliefRevision for the audit trail.
     */
    fun correctWorldModelEntity(entityId: Long, newConfidence: Float, note: String) {
        viewModelScope.launch(Dispatchers.IO) {
            worldModelEngine?.ownerCorrectEntity(entityId, newConfidence, note)
            refreshWorldModelStats()
        }
    }

    private suspend fun refreshData() = withContext(Dispatchers.IO) {
        _allMemories.value = memorySystem.getAllMemories()
        _memoryCount.value = memorySystem.getMemoryCount()
        _totalInteractions.value = memorySystem.getInteractionCount()
        _strategyWeights.value = memorySystem.getAllStrategyWeights()
        _recentReflections.value = memorySystem.getReflections(5)
    }
}

data class ChatMessage(
    val id: Long, val role: String, val content: String,
    val timestamp: Long, val reasoningMode: String?,
    val isStreaming: Boolean = false, val isError: Boolean = false
)

enum class ModelStatus { LOADING, READY, ERROR }
