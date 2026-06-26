package com.agent42.selfmodification

import com.nexa.sdk.LlmWrapper
import com.nexa.sdk.bean.GenerationConfig
import com.nexa.sdk.bean.LlmStreamResult
import com.agent42.memory.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class CodeModificationEngine(
    private val llm: LlmWrapper,
    private val moduleDao: BehaviorModuleDao,
    private val snapshotDao: ModuleSnapshotDao,
    private val feedbackDao: FeedbackDao
) {
    private var recentRejections = mutableListOf<Triple<String, Int, String>>()

    companion object {
        private val AUDIT_INTERVAL = 7 * 24 * 60 * 60 * 1000L
    }

    suspend fun analyzeAndPropose(): List<ChangeProposal> = withContext(Dispatchers.IO) {
        val proposals = mutableListOf<ChangeProposal>()
        val sinceTimestamp = System.currentTimeMillis() - (3 * 24 * 60 * 60 * 1000L)
        val negativeRows = feedbackDao.getNegativeFeedbackPatterns(sinceTimestamp)
        if (negativeRows.isNotEmpty()) proposals.addAll(analyzeFailures(negativeRows))
        proposals.addAll(analyzeRephrasePatterns())
        val modulesNeedingReview = moduleDao.getActiveModules()
            .filter { System.currentTimeMillis() - it.lastModified > AUDIT_INTERVAL }
        if (modulesNeedingReview.isNotEmpty()) proposals.addAll(auditModules(modulesNeedingReview))
        val contradictions = detectContradictions(moduleDao.getActiveModules())
        if (contradictions.isNotEmpty()) proposals.addAll(contradictions)
        // Rule 5/Rule 9: Filter out any proposals that touch protected packages
        proposals.filter { proposal ->
            com.agent42.security.CoreConstitution.PROTECTED_PACKAGES.none { pkg ->
                proposal.moduleId.contains(pkg, ignoreCase = true) ||
                proposal.moduleName.contains(pkg, ignoreCase = true)
            }
        }.sortedByDescending { it.priority }
    }

    private suspend fun analyzeFailures(rows: List<NegativeFeedbackRow>): List<ChangeProposal> {
        val proposals = mutableListOf<ChangeProposal>()
        for (row in rows) {
            val module = identifyResponsibleModule(row.strategyUsed) ?: continue
            val diagnosisPrompt = """
                You are debugging your own behavior.
                FAILURE PATTERN:
                - Query: "${row.queryPattern.take(200)}"
                - Strategy: ${row.strategyUsed ?: "unknown"}
                - Failed ${row.failureCount} times in last 3 days
                MODULE: ${module.moduleId}
                CURRENT CODE:
                ${module.currentCode}
                DIAGNOSIS: <root cause>
                PROPOSED_CHANGE: <new code>
                REASONING: <why this fixes it>
                RISK: <LOW|MEDIUM|HIGH>
                RISK_NOTES: <what could go wrong>
            """.trimIndent()
            val result = StringBuilder()
            llm.generateStreamFlow(diagnosisPrompt, GenerationConfig(maxTokens = 2048))
                .collect { chunk -> if (chunk is LlmStreamResult.Token) result.append(chunk.text) }
            val parsed = parseDiagnosis(result.toString())
            if (parsed != null) {
                proposals.add(ChangeProposal(
                    proposalId = "fix_${module.moduleId}_${System.currentTimeMillis()}",
                    type = ProposalType.FIX, moduleId = module.moduleId,
                    moduleName = module.displayName,
                    currentCode = module.currentCode, proposedCode = parsed.proposedCode,
                    diagnosis = parsed.diagnosis, reasoning = parsed.reasoning,
                    riskLevel = parsed.riskLevel, riskNotes = parsed.riskNotes,
                    evidence = "Failed ${row.failureCount} times",
                    priority = row.failureCount.toFloat(),
                    status = ProposalStatus.PENDING, createdAt = System.currentTimeMillis()
                ))
            }
        }
        return proposals
    }

    private suspend fun analyzeRephrasePatterns(): List<ChangeProposal> {
        val module = moduleDao.getById("query_classifier_rules") ?: return emptyList()
        return listOf(ChangeProposal(
            proposalId = "rephrase_review_${System.currentTimeMillis()}",
            type = ProposalType.OPTIMIZATION, moduleId = "query_classifier_rules",
            moduleName = module.displayName,
            currentCode = module.currentCode, proposedCode = module.currentCode,
            diagnosis = "Users frequently rephrase queries — routing may be wrong",
            reasoning = "Rephrase patterns suggest query classification needs review",
            evidence = "Based on recent feedback analysis",
            riskLevel = RiskLevel.LOW, riskNotes = "Low risk — review only",
            priority = 0.6f, status = ProposalStatus.PENDING,
            createdAt = System.currentTimeMillis()
        ))
    }

    private suspend fun auditModules(modules: List<BehaviorModule>): List<ChangeProposal> {
        val auditPrompt = """
            Audit these behavioral modules for issues.
            ${modules.joinToString("\n\n") { "### ${it.moduleId} (v${it.version})\n${it.currentCode}" }}
            For each issue: MODULE: <id> | ISSUE: <desc> | PROPOSED: <code> | SEVERITY: <level> | REASONING: <why>
        """.trimIndent()
        val result = StringBuilder()
        llm.generateStreamFlow(auditPrompt, GenerationConfig(maxTokens = 4096))
            .collect { chunk -> if (chunk is LlmStreamResult.Token) result.append(chunk.text) }
        return parseAuditResults(result.toString(), modules)
    }

    private suspend fun detectContradictions(modules: List<BehaviorModule>): List<ChangeProposal> {
        val contradictionPrompt = """
            Check these modules for contradictions.
            ${modules.joinToString("\n\n") { "${it.moduleId}:\n${it.currentCode}" }}
            For each: MODULE_A: <id> | MODULE_B: <id> | CONFLICT: <desc> | PROPOSED_CHANGE: <code>
        """.trimIndent()
        val result = StringBuilder()
        llm.generateStreamFlow(contradictionPrompt, GenerationConfig(maxTokens = 2048))
            .collect { chunk -> if (chunk is LlmStreamResult.Token) result.append(chunk.text) }
        return parseContradictions(result.toString(), modules)
    }

    suspend fun proposeOptimization(moduleId: String, observation: String): ChangeProposal =
        withContext(Dispatchers.IO) {
            val module = moduleDao.getById(moduleId)
                ?: throw IllegalArgumentException("Module not found: $moduleId")
            val optimizePrompt = """
                Improve this module. Keep changes minimal.
                Module: ${module.moduleId}
                Current code: ${module.currentCode}
                Observation: "$observation"
                PROPOSED_CHANGE: <new code>
                REASONING: <why>
                RISK: <LOW|MEDIUM|HIGH>
                RISK_NOTES: <downsides>
            """.trimIndent()
            val result = StringBuilder()
            llm.generateStreamFlow(optimizePrompt, GenerationConfig(maxTokens = 2048))
                .collect { chunk -> if (chunk is LlmStreamResult.Token) result.append(chunk.text) }
            val parsed = parseDiagnosis(result.toString())
            ChangeProposal(
                proposalId = "opt_${moduleId}_${System.currentTimeMillis()}",
                type = ProposalType.OPTIMIZATION, moduleId = moduleId,
                moduleName = module.displayName,
                currentCode = module.currentCode,
                proposedCode = parsed?.proposedCode ?: module.currentCode,
                diagnosis = observation, reasoning = parsed?.reasoning ?: "",
                riskLevel = parsed?.riskLevel ?: RiskLevel.MEDIUM,
                riskNotes = parsed?.riskNotes ?: "", evidence = observation,
                priority = 0.5f, status = ProposalStatus.PENDING,
                createdAt = System.currentTimeMillis()
            )
        }

    fun recordRejection(moduleId: String, proposedCode: String, reason: String) {
        recentRejections.add(Triple(moduleId, proposedCode.hashCode(), reason))
        if (recentRejections.size > 50) recentRejections = recentRejections.takeLast(50).toMutableList()
    }

    private suspend fun identifyResponsibleModule(strategyUsed: String?): BehaviorModule? {
        if (strategyUsed == null) return null
        return runCatching {
            val moduleId = when (strategyUsed) {
                "DIRECT", "CHAIN_OF_THOUGHT", "DECOMPOSE", "REFLECTIVE" -> "query_classifier_rules"
                else -> "response_formatter"
            }
            moduleDao.getById(moduleId)
        }.getOrNull()
    }

    data class ParsedDiagnosis(
        val diagnosis: String, val proposedCode: String,
        val reasoning: String, val riskLevel: RiskLevel, val riskNotes: String
    )

    private fun parseDiagnosis(text: String): ParsedDiagnosis? {
        var diagnosis = ""; var proposedCode = ""; var reasoning = ""
        var riskLevel = RiskLevel.MEDIUM; var riskNotes = ""
        var section = ""; val builder = StringBuilder()
        for (line in text.lines()) {
            val upper = line.trim().uppercase()
            when {
                upper.startsWith("DIAGNOSIS:") -> { section = "D"; builder.clear(); builder.append(line.substringAfter(":").trim()) }
                upper.startsWith("PROPOSED_CHANGE:") -> { section = "P"; builder.clear(); builder.append(line.substringAfter(":").trim()) }
                upper.startsWith("REASONING:") -> { section = "R"; builder.clear(); builder.append(line.substringAfter(":").trim()) }
                upper.startsWith("RISK:") -> {
                    val r = line.substringAfter(":").trim().uppercase()
                    riskLevel = try { RiskLevel.valueOf(r) } catch (e: Exception) { RiskLevel.MEDIUM }
                }
                upper.startsWith("RISK_NOTES:") -> { section = "N"; builder.clear(); builder.append(line.substringAfter(":").trim()) }
                line.isNotBlank() && section.isNotEmpty() -> builder.append("\n").append(line)
            }
        }
        when (section) {
            "D" -> diagnosis = builder.toString().trim()
            "P" -> proposedCode = builder.toString().trim()
            "R" -> reasoning = builder.toString().trim()
            "N" -> riskNotes = builder.toString().trim()
        }
        if (proposedCode.isBlank()) return null
        return ParsedDiagnosis(diagnosis, proposedCode, reasoning, riskLevel, riskNotes)
    }

    private fun parseAuditResults(text: String, modules: List<BehaviorModule>): List<ChangeProposal> {
        val proposals = mutableListOf<ChangeProposal>()
        val blocks = text.split(Regex("(?=^MODULE:)", RegexOption.MULTILINE))
        for (block in blocks) {
            val moduleId = Regex("MODULE:\\s*(\\S+)", RegexOption.IGNORE_CASE).find(block)?.groupValues?.get(1)
            val issue = Regex("ISSUE:\\s*(.+)", RegexOption.IGNORE_CASE).find(block)?.groupValues?.get(1)
            val proposed = Regex("PROPOSED:\\s*(.+)", RegexOption.IGNORE_CASE).find(block)?.groupValues?.get(1)
            val severity = Regex("SEVERITY:\\s*(\\w+)", RegexOption.IGNORE_CASE).find(block)?.groupValues?.get(1)
            if (moduleId != null && proposed != null && issue != null) {
                val module = modules.find { it.moduleId == moduleId }
                val risk = try { RiskLevel.valueOf(severity?.uppercase() ?: "MEDIUM") }
                    catch (e: Exception) { RiskLevel.MEDIUM }
                proposals.add(ChangeProposal(
                    proposalId = "audit_${moduleId}_${System.currentTimeMillis()}",
                    type = ProposalType.OPTIMIZATION, moduleId = moduleId,
                    moduleName = module?.displayName ?: moduleId,
                    currentCode = module?.currentCode ?: "", proposedCode = proposed,
                    diagnosis = issue, reasoning = "", evidence = "Self-audit",
                    riskLevel = risk, riskNotes = "",
                    priority = when (risk) { RiskLevel.HIGH -> 0.9f; RiskLevel.MEDIUM -> 0.5f; RiskLevel.LOW -> 0.3f },
                    status = ProposalStatus.PENDING, createdAt = System.currentTimeMillis()
                ))
            }
        }
        return proposals
    }

    private fun parseContradictions(text: String, modules: List<BehaviorModule>): List<ChangeProposal> {
        val proposals = mutableListOf<ChangeProposal>()
        val blocks = text.split(Regex("(?=^MODULE_A:)", RegexOption.MULTILINE))
        for (block in blocks) {
            val moduleB = Regex("MODULE_B:\\s*(\\S+)", RegexOption.IGNORE_CASE).find(block)?.groupValues?.get(1)
            val conflict = Regex("CONFLICT:\\s*(.+)", RegexOption.IGNORE_CASE).find(block)?.groupValues?.get(1)
            val proposed = Regex("PROPOSED_CHANGE:\\s*(.+)", RegexOption.IGNORE_CASE).find(block)?.groupValues?.get(1)
            if (moduleB != null && proposed != null && conflict != null) {
                val module = modules.find { it.moduleId == moduleB }
                proposals.add(ChangeProposal(
                    proposalId = "contra_${moduleB}_${System.currentTimeMillis()}",
                    type = ProposalType.CONTRADICTION, moduleId = moduleB,
                    moduleName = module?.displayName ?: moduleB,
                    currentCode = module?.currentCode ?: "", proposedCode = proposed,
                    diagnosis = "Contradiction: $conflict",
                    reasoning = "Module $moduleB conflicts", evidence = conflict,
                    riskLevel = RiskLevel.MEDIUM, riskNotes = "Resolving contradiction",
                    priority = 0.7f, status = ProposalStatus.PENDING,
                    createdAt = System.currentTimeMillis()
                ))
            }
        }
        return proposals
    }
}
