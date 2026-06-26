package com.agent42.security

/**
 * The ConstitutionEnforcer is the FINAL gate before any action executes.
 *
 * Every action — whether from the reasoning engine, self-modification engine,
 * or automation — must pass through enforce() first.
 *
 * This is HARDLOCKED. The self-modification engine cannot modify this file.
 * It sits in com.agent42.security which is in CoreConstitution.PROTECTED_PACKAGES.
 */
class ConstitutionEnforcer(
    private val ownerAuth: OwnerAuth,
    private val permissionManager: PermissionManager,
    private val actionLog: ActionLog
) {

    /**
     * Check a proposed action against the full constitution.
     * Returns EnforcementResult:
     * - Allowed: action passes all hard rules, proceeds to approval flow
     *   (may include warnings from Rule 12 that the owner will see)
     * - Blocked: action violates a hard rule (1, 5, 7, 9, 10, 11)
     *   Rule 12 is NOT a hard block — it generates a warning the owner can override.
     */
    fun enforce(action: ProposedAction): EnforcementResult {
        val ownerVerified = ownerAuth.isBiometricValid()
        val hasApproval = permissionManager.hasPendingApproval()
        val sandboxTested = action.sandboxResult != null
        val isLockdown = permissionManager.isLockdownMode()

        val violations = CoreConstitution.checkAction(
            action = action,
            ownerVerified = ownerVerified,
            hasActiveApproval = hasApproval,
            sandboxTested = sandboxTested,
            isLockdownMode = isLockdown
        )

        // Separate hard violations (block) from soft warnings (show but allow)
        val hardViolations = violations.filter { it.rule != CoreConstitution.Rule.RULE_12_SAFETY_BOUNDARY }
        val warnings = violations.filter { it.rule == CoreConstitution.Rule.RULE_12_SAFETY_BOUNDARY }

        if (hardViolations.isNotEmpty()) {
            // Log the blocked attempt (Rule 6: Full Transparency)
            actionLog.append(ActionLog.LogEntry(
                timestamp = System.currentTimeMillis(),
                requestedBy = if (ownerVerified) "OWNER" else "UNKNOWN",
                actionDescription = action.description,
                reason = action.reason,
                category = action.category.name,
                riskLevel = action.riskLevel.name,
                outcome = ActionLog.Outcome.BLOCKED_BY_CONSTITUTION,
                details = hardViolations.joinToString("; ") { "${it.rule}: ${it.description}" }
            ))
            return EnforcementResult.Blocked(hardViolations)
        }

        // Action passes hard rules — proceed to approval flow.
        // If there are Rule 12 warnings, they'll be shown to the owner
        // who makes the final decision.
        val approvalRequest = permissionManager.requestApproval(action)
        return EnforcementResult.Allowed(approvalRequest, warnings)
    }

    /**
     * Rule 7: STOP NOW — immediately halt everything.
     */
    fun stopNow() {
        permissionManager.stopNow()
        actionLog.append(ActionLog.LogEntry(
            timestamp = System.currentTimeMillis(),
            requestedBy = "OWNER",
            actionDescription = "STOP NOW issued",
            reason = "Manual stop",
            category = "STOP_CONTROL",
            riskLevel = "LOW",
            outcome = ActionLog.Outcome.APPROVED
        ))
    }

    /**
     * Rule 7: Lockdown Mode — disable all automation until re-enabled.
     */
    fun enableLockdown() {
        permissionManager.enableLockdown()
        actionLog.append(ActionLog.LogEntry(
            timestamp = System.currentTimeMillis(),
            requestedBy = "OWNER",
            actionDescription = "Lockdown mode enabled",
            reason = "Manual lockdown",
            category = "STOP_CONTROL",
            riskLevel = "HIGH",
            outcome = ActionLog.Outcome.APPROVED
        ))
    }

    fun disableLockdown() {
        permissionManager.disableLockdown()
        actionLog.append(ActionLog.LogEntry(
            timestamp = System.currentTimeMillis(),
            requestedBy = "OWNER",
            actionDescription = "Lockdown mode disabled",
            reason = "Manual unlock",
            category = "STOP_CONTROL",
            riskLevel = "MEDIUM",
            outcome = ActionLog.Outcome.APPROVED
        ))
    }

    fun isLockdownMode(): Boolean = permissionManager.isLockdownMode()
}

sealed class EnforcementResult {
    /**
     * Action passes all hard rules. Proceeds to approval flow.
     * warnings contains Rule 12 safety warnings the owner will see and can override.
     */
    data class Allowed(
        val approvalRequest: ApprovalRequest,
        val warnings: List<CoreConstitution.RuleViolation> = emptyList()
    ) : EnforcementResult()

    /** Action violates a hard rule and cannot proceed. */
    data class Blocked(val violations: List<CoreConstitution.RuleViolation>) : EnforcementResult()
}
