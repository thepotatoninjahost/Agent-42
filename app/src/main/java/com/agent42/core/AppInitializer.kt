package com.agent42.core

import android.content.Context
import androidx.room.Room
import com.agent42.memory.*
import com.agent42.security.SecurityLayer
import com.agent42.selfmodification.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

object AppInitializer {

    private val initScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun createAgentDependencies(context: Context, nexaToken: String): AgentDependencies {
        val db = Room.databaseBuilder(context, AgentDatabase::class.java, "agent42.db")
            .openHelperFactory(SecurityLayer.getSupportFactory(context))
            .fallbackToDestructiveMigration()
            .build()

        val modelManager = ModelManager(context, nexaToken)
        val thermalManager = ThermalManager(context)
        val memorySystem = MemorySystem(db, embeddingModel = null)
        val contextManager = ContextManager(db)

        // Constitution system — hardlocked security layer
        val ownerAuth = com.agent42.security.OwnerAuth(context)
        val actionLog = com.agent42.security.ActionLog(context)
        val permissionManager = com.agent42.security.PermissionManager()
        val constitutionEnforcer = com.agent42.security.ConstitutionEnforcer(
            ownerAuth, permissionManager, actionLog
        )

        // Seed default behavior modules on first run — async to avoid blocking main thread
        initScope.launch { seedDefaultModules(db.behaviorModuleDao()) }

        return AgentDependencies(
            db = db, modelManager = modelManager, thermalManager = thermalManager,
            memorySystem = memorySystem, contextManager = contextManager,
            ownerAuth = ownerAuth, constitutionEnforcer = constitutionEnforcer,
            actionLog = actionLog, appContext = context
        )
    }

    fun createViewModel(deps: AgentDependencies, llm: com.nexa.sdk.LlmWrapper): AgentViewModel {
        val codeModEngine = CodeModificationEngine(
            llm, deps.db.behaviorModuleDao(),
            deps.db.moduleSnapshotDao(), deps.db.feedbackDao()
        )
        val approvalGate = ApprovalGate(
            deps.db.behaviorModuleDao(), deps.db.moduleSnapshotDao(),
            deps.db.changeProposalDao(), deps.db.feedbackDao(), codeModEngine
        )
        // Wire the LLM into the memory system for embeddings
        deps.memorySystem.setEmbeddingModel(llm)

        // ═══ NEW COGNITIVE SYSTEMS ═══
        // System 1 Cache — fast-path for familiar queries
        val system1Cache = com.agent42.cognition.System1Cache(deps.db)
        // Metacognitive Monitor — real-time reasoning quality monitoring
        val metacognitiveMonitor = com.agent42.cognition.MetacognitiveMonitor(deps.db)
        // Constraint Checker — external fact verification
        val constraintChecker = com.agent42.verification.ConstraintChecker(deps.db)
        // Predictive Coder — expectation generation + surprise detection
        val predictiveCoder = com.agent42.prediction.PredictiveCoder(llm, deps.db)
        // Prediction Engine — world model, predicts what user asks next
        val predictionEngine = com.agent42.prediction.PredictionEngine(llm, deps.db)
        // Knowledge Gap Tracker — curiosity-driven learning
        val knowledgeGapTracker = com.agent42.curiosity.KnowledgeGapTracker(deps.db)
        // Sensor Context Provider — embodied context from phone sensors
        val sensorContextProvider = com.agent42.sensors.SensorContextProvider(
            deps.appContext, deps.db
        )
        // World Model (system 2.1) — persistent structured knowledge graph.
        // The engine writes beliefs; the query layer reads them. Both share the
        // agent DB. The LLM is used for extraction and (best-effort) embeddings.
        val worldModelEngine = com.agent42.worldmodel.WorldModelEngine(deps.db, llm)
        val worldModelQuery = com.agent42.worldmodel.WorldModelQuery(deps.db, llm)
        val worldModelContradictionChecker = com.agent42.worldmodel.WorldModelContradictionChecker(worldModelQuery, llm)
        val worldModelConsolidator = com.agent42.worldmodel.WorldModelConsolidator(deps.db)

        return AgentViewModel(
            llm = llm,
            memorySystem = deps.memorySystem,
            contextManager = deps.contextManager,
            approvalGate = approvalGate,
            codeModEngine = codeModEngine,
            thermalManager = deps.thermalManager,
            modelManager = deps.modelManager,
            system1Cache = system1Cache,
            metacognitiveMonitor = metacognitiveMonitor,
            constraintChecker = constraintChecker,
            predictiveCoder = predictiveCoder,
            predictionEngine = predictionEngine,
            knowledgeGapTracker = knowledgeGapTracker,
            sensorContextProvider = sensorContextProvider,
            worldModelEngine = worldModelEngine,
            worldModelQuery = worldModelQuery,
            worldModelContradictionChecker = worldModelContradictionChecker,
            worldModelConsolidator = worldModelConsolidator
        )
    }

    private suspend fun seedDefaultModules(dao: BehaviorModuleDao) {
        if (dao.count() > 0) return
        val defaults = listOf(
            BehaviorModule(
                moduleId = "query_classifier_rules",
                displayName = "Query Classifier",
                description = "Rules for routing queries to reasoning modes",
                moduleType = ModuleType.CLASSIFICATION_RULES,
                currentCode = """
                    {
                      "direct": ["what is", "who is", "when did", "define"],
                      "chain_of_thought": ["explain why", "how does", "step by step"],
                      "decompose": ["compare", "analyze", "design", "plan", "both"],
                      "reflective": ["prove", "verify", "is this correct", "double check"]
                    }
                """.trimIndent(),
                version = 1, isActive = true,
                lastModified = System.currentTimeMillis()
            ),
            BehaviorModule(
                moduleId = "response_formatter",
                displayName = "Response Formatter",
                description = "Controls output style and structure",
                moduleType = ModuleType.RESPONSE_FORMATTER,
                currentCode = """
                    {
                      "default_style": "concise",
                      "code_blocks": true,
                      "max_paragraphs": 5,
                      "tone": "helpful_direct"
                    }
                """.trimIndent(),
                version = 1, isActive = true,
                lastModified = System.currentTimeMillis()
            ),
            BehaviorModule(
                moduleId = "memory_extraction_rules",
                displayName = "Memory Extraction",
                description = "What to remember from interactions",
                moduleType = ModuleType.MEMORY_EXTRACTION_RULES,
                currentCode = """
                    {
                      "always_remember": ["user_name", "profession", "preferences"],
                      "never_remember": ["passwords", "credentials"],
                      "decay_after_days": 30
                    }
                """.trimIndent(),
                version = 1, isActive = true,
                lastModified = System.currentTimeMillis()
            ),
            BehaviorModule(
                moduleId = "persona_default",
                displayName = "Default Persona",
                description = "Agent personality and behavioral guidelines",
                moduleType = ModuleType.PERSONA_DEFINITION,
                currentCode = """
                    {
                      "name": "Agent 42",
                      "traits": ["curious", "precise", "adaptable", "honest"],
                      "rules": ["Be truthful", "Adapt to user", "Reference past interactions"]
                    }
                """.trimIndent(),
                version = 1, isActive = true,
                lastModified = System.currentTimeMillis(),
                dependencies = listOf("response_formatter")
            )
        )
        defaults.forEach { dao.insert(it) }
    }
}

data class AgentDependencies(
    val db: AgentDatabase,
    val modelManager: ModelManager,
    val thermalManager: ThermalManager,
    val memorySystem: MemorySystem,
    val contextManager: ContextManager,
    val ownerAuth: com.agent42.security.OwnerAuth,
    val constitutionEnforcer: com.agent42.security.ConstitutionEnforcer,
    val actionLog: com.agent42.security.ActionLog,
    val appContext: android.content.Context
)
