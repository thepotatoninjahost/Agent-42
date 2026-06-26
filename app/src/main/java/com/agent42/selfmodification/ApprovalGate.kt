package com.agent42.selfmodification

import com.agent42.memory.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

enum class ProposalType { FIX, OPTIMIZATION, NEW_FEATURE, REMOVAL, CONTRADICTION }
enum class RiskLevel { LOW, MEDIUM, HIGH }
enum class ProposalStatus { PENDING, APPROVED, REJECTED, DEFERRED, APPLIED, ROLLED_BACK }

data class ChangeProposal(
    val proposalId: String, val type: ProposalType,
    val moduleId: String, val moduleName: String,
    val currentCode: String, val proposedCode: String,
    val diagnosis: String, val reasoning: String,
    val evidence: String, val riskLevel: RiskLevel, val riskNotes: String,
    val priority: Float, var status: ProposalStatus, val createdAt: Long,
    var userNotes: String? = null
) {
    fun toEntity() = ChangeProposalEntity(
        proposalId = proposalId, type = type.name, moduleId = moduleId,
        moduleName = moduleName, currentCode = currentCode, proposedCode = proposedCode,
        diagnosis = diagnosis, reasoning = reasoning, evidence = evidence,
        riskLevel = riskLevel.name, riskNotes = riskNotes, priority = priority,
        status = status.name, createdAt = createdAt, userNotes = userNotes
    )
}

fun ChangeProposalEntity.toDomain() = ChangeProposal(
    proposalId = proposalId, type = ProposalType.valueOf(type), moduleId = moduleId,
    moduleName = moduleName, currentCode = currentCode, proposedCode = proposedCode,
    diagnosis = diagnosis, reasoning = reasoning, evidence = evidence,
    riskLevel = RiskLevel.valueOf(riskLevel), riskNotes = riskNotes, priority = priority,
    status = ProposalStatus.valueOf(status), createdAt = createdAt, userNotes = userNotes
)

data class ApplyResult(val success: Boolean, val message: String)
data class ProposalRecord(
    val proposal: ChangeProposal, val status: ProposalStatus,
    val timestamp: Long, val userReason: String? = null
)

class ApprovalGate(
    private val moduleDao: BehaviorModuleDao,
    private val snapshotDao: ModuleSnapshotDao,
    private val proposalDao: ChangeProposalDao,
    private val feedbackDao: FeedbackDao,
    private val modificationEngine: CodeModificationEngine
) {
    private val _pendingProposals = MutableStateFlow<List<ChangeProposal>>(emptyList())
    val pendingProposals: StateFlow<List<ChangeProposal>> = _pendingProposals.asStateFlow()

    private val _decisionHistory = MutableStateFlow<List<ProposalRecord>>(emptyList())
    val decisionHistory: StateFlow<List<ProposalRecord>> = _decisionHistory.asStateFlow()

    companion object {
        private val SUPPRESSION_WINDOW = 24 * 60 * 60 * 1000L
    }

    suspend fun loadDecisionHistory() = withContext(Dispatchers.IO) {
        val since = System.currentTimeMillis() - SUPPRESSION_WINDOW
        val rejected = proposalDao.getRecentRejected(since).map { it.toDomain() }
        _decisionHistory.value = rejected.map { proposal ->
            ProposalRecord(proposal, ProposalStatus.REJECTED, proposal.createdAt, proposal.userNotes)
        }
    }

    suspend fun scanForProposals() = withContext(Dispatchers.IO) {
        val newProposals = modificationEngine.analyzeAndPropose()
        if (newProposals.isNotEmpty()) {
            newProposals.forEach { proposalDao.insert(it.toEntity()) }
            val recentlyRejected = _decisionHistory.value
                .filter { it.status == ProposalStatus.REJECTED &&
                    System.currentTimeMillis() - it.timestamp < SUPPRESSION_WINDOW }
                .map { it.proposal.moduleId to it.proposal.proposedCode.hashCode() }
            val filtered = newProposals.filter { p ->
                recentlyRejected.none { (mid, hash) ->
                    mid == p.moduleId && hash == p.proposedCode.hashCode()
                }
            }
            _pendingProposals.value = (_pendingProposals.value + filtered)
                .sortedByDescending { it.priority }
        }
        refreshFromDb()
    }

    suspend fun refreshFromDb() {
        _pendingProposals.value = proposalDao.getPending().map { it.toDomain() }
    }

    suspend fun approve(proposalId: String, userComment: String? = null): ApplyResult =
        withContext(Dispatchers.IO) {
            val proposal = _pendingProposals.value.find { it.proposalId == proposalId }
                ?: return@withContext ApplyResult(false, "Proposal not found")
            applyProposal(proposal.copy(status = ProposalStatus.APPROVED, userNotes = userComment))
        }

    suspend fun approveWithEdits(proposalId: String, editedCode: String, userComment: String? = null): ApplyResult =
        withContext(Dispatchers.IO) {
            val proposal = _pendingProposals.value.find { it.proposalId == proposalId }
                ?: return@withContext ApplyResult(false, "Proposal not found")
            applyProposal(proposal.copy(
                proposedCode = editedCode, status = ProposalStatus.APPROVED,
                userNotes = userComment ?: "User edited before approving"
            ))
        }

    suspend fun reject(proposalId: String, reason: String) = withContext(Dispatchers.IO) {
        val proposal = _pendingProposals.value.find { it.proposalId == proposalId }
        if (proposal != null) {
            proposalDao.updateStatus(proposalId, ProposalStatus.REJECTED.name)
            _decisionHistory.value = _decisionHistory.value + ProposalRecord(
                proposal, ProposalStatus.REJECTED, System.currentTimeMillis(), reason
            )
            _pendingProposals.value = _pendingProposals.value.filterNot { it.proposalId == proposalId }
            modificationEngine.recordRejection(proposal.moduleId, proposal.proposedCode, reason)
        }
    }

    fun defer(proposalId: String, until: Long) {
        _pendingProposals.value = _pendingProposals.value.map {
            if (it.proposalId == proposalId) it.copy(status = ProposalStatus.DEFERRED) else it
        }
    }

    private suspend fun applyProposal(proposal: ChangeProposal): ApplyResult {
        return try {
            val module = moduleDao.getById(proposal.moduleId)
                ?: return ApplyResult(false, "Module not found")
            snapshotDao.insert(ModuleSnapshot(
                moduleId = module.moduleId, snapshotCode = module.currentCode,
                version = module.version, timestamp = System.currentTimeMillis(),
                label = "Pre-change: ${proposal.diagnosis.take(80)}"
            ))
            val updated = module.copy(
                currentCode = proposal.proposedCode,
                version = module.version + 1,
                lastModified = System.currentTimeMillis(),
                modificationHistory = module.modificationHistory + ModificationRecord(
                    version = module.version + 1, timestamp = System.currentTimeMillis(),
                    oldCode = module.currentCode, newCode = proposal.proposedCode,
                    reason = proposal.reasoning, approvedBy = "user"
                )
            )
            moduleDao.update(updated)
            proposalDao.updateStatus(proposal.proposalId, ProposalStatus.APPLIED.name)
            _decisionHistory.value = _decisionHistory.value + ProposalRecord(
                proposal, ProposalStatus.APPLIED, System.currentTimeMillis()
            )
            _pendingProposals.value = _pendingProposals.value
                .filterNot { it.proposalId == proposal.proposalId }
            ApplyResult(true, "Applied to ${module.moduleId} (v${updated.version})")
        } catch (e: Exception) {
            ApplyResult(false, "Failed: ${e.message}")
        }
    }

    suspend fun rollback(moduleId: String, reason: String): ApplyResult = withContext(Dispatchers.IO) {
        val module = moduleDao.getById(moduleId)
            ?: return@withContext ApplyResult(false, "Module not found")
        val snapshot = snapshotDao.getLatestForModule(moduleId)
            ?: return@withContext ApplyResult(false, "No snapshot for rollback")
        snapshotDao.insert(ModuleSnapshot(
            moduleId = moduleId, snapshotCode = module.currentCode,
            version = module.version, timestamp = System.currentTimeMillis(),
            label = "Pre-rollback (failed version)"
        ))
        moduleDao.update(module.copy(
            currentCode = snapshot.snapshotCode,
            version = module.version + 1,
            lastModified = System.currentTimeMillis(),
            modificationHistory = module.modificationHistory + ModificationRecord(
                version = module.version + 1, timestamp = System.currentTimeMillis(),
                oldCode = module.currentCode, newCode = snapshot.snapshotCode,
                reason = "ROLLED BACK: $reason", approvedBy = "user"
            )
        ))
        ApplyResult(true, "Rolled back to v${snapshot.version}")
    }

    suspend fun monitorPostChangeHealth(
        moduleId: String, checkAfterMs: Long = 24 * 60 * 60 * 1000L
    ) = withContext(Dispatchers.IO) {
        delay(checkAfterMs)
        val since = System.currentTimeMillis() - checkAfterMs
        val recentFeedback = feedbackDao.getFeedbackSince(since)
        val avgSignal = if (recentFeedback.isEmpty()) 0f
        else recentFeedback.map { it.signalValue }.average().toFloat()
        if (avgSignal < -0.2f) {
            rollback(moduleId, "Auto-rollback: avg feedback $avgSignal")
        }
    }
}
