package com.agent42.security

/**
 * Rule 3, 4, 8 — Permission Manager.
 *
 * Manages approval state for proposed actions:
 * - Rule 3: Shows what it plans to do, why, risk level → Approve/Deny
 * - Rule 4: Double confirmation for critical actions (code/settings/financial/account)
 * - Rule 8: Approvals expire after PERMISSION_EXPIRATION_MS (2 minutes)
 *
 * This is HARDLOCKED — part of the security package, cannot be self-modified.
 */
class PermissionManager {

    @Volatile
    private var lockdownMode = false

    private data class PendingApproval(
        val action: ProposedAction,
        val firstConfirmed: Boolean,
        val createdAt: Long,
        var secondConfirmed: Boolean = false,
        var secondConfirmedAt: Long = 0
    )

    @Volatile
    private var pendingApproval: PendingApproval? = null

    /**
     * Rule 7: STOP NOW / Lockdown Mode
     * Immediately halts all pending actions and disables automation.
     */
    fun enableLockdown() {
        lockdownMode = true
        pendingApproval = null
    }

    fun disableLockdown() {
        lockdownMode = false
    }

    fun isLockdownMode(): Boolean = lockdownMode

    fun stopNow() {
        pendingApproval = null
    }

    /**
     * Submit a proposed action for approval.
     * Returns the action details to display to the owner (Rule 3).
     */
    fun requestApproval(action: ProposedAction): ApprovalRequest {
        if (lockdownMode) {
            return ApprovalRequest(
                action = action,
                requiresDoubleConfirmation = false,
                blocked = true,
                blockReason = "Lockdown mode is active"
            )
        }

        val needsDouble = CoreConstitution.needsDoubleConfirmation(action.category)
        val needsSandbox = CoreConstitution.needsSandboxTest(action.category)

        if (needsSandbox && action.sandboxResult == null) {
            return ApprovalRequest(
                action = action,
                requiresDoubleConfirmation = needsDouble,
                blocked = true,
                blockReason = "Sandbox test required before approval (Rule 5)"
            )
        }

        pendingApproval = PendingApproval(
            action = action,
            firstConfirmed = false,
            createdAt = System.currentTimeMillis()
        )

        return ApprovalRequest(
            action = action,
            requiresDoubleConfirmation = needsDouble,
            blocked = false,
            blockReason = null
        )
    }

    /**
     * First confirmation (Rule 3: Approve/Deny).
     * For non-critical actions, this is the only confirmation needed.
     * For critical actions, this triggers the second confirmation screen (Rule 4).
     */
    fun firstConfirm(approved: Boolean): FirstConfirmResult {
        val pending = pendingApproval ?: return FirstConfirmResult(false, false, "No pending action")

        // Rule 8: Check expiration
        if (isExpired(pending.createdAt)) {
            pendingApproval = null
            return FirstConfirmResult(false, false, "Approval expired (Rule 8)")
        }

        if (!approved) {
            pendingApproval = null
            return FirstConfirmResult(false, false, "Denied by owner")
        }

        pendingApproval = pending.copy(firstConfirmed = true)

        // Rule 4: Critical actions need second confirmation
        if (CoreConstitution.needsDoubleConfirmation(pending.action.category)) {
            return FirstConfirmResult(true, needsSecond = true, "First confirmation received — second confirmation required")
        }

        // Non-critical: single confirmation is enough
        val action = pending.action
        pendingApproval = null
        return FirstConfirmResult(true, needsSecond = false, "Approved")
    }

    /**
     * Second confirmation (Rule 4: "Are you sure?").
     * Only called for critical actions.
     */
    fun secondConfirm(approved: Boolean): Boolean {
        val pending = pendingApproval ?: return false

        if (!pending.firstConfirmed) return false

        // Rule 8: Check expiration
        if (isExpired(pending.createdAt)) {
            pendingApproval = null
            return false
        }

        val result = approved
        pendingApproval = null
        return result
    }

    /**
     * Rule 8: Check if a pending approval has expired.
     */
    fun isPendingExpired(): Boolean {
        val pending = pendingApproval ?: return true
        return isExpired(pending.createdAt)
    }

    private fun isExpired(createdAt: Long): Boolean {
        return (System.currentTimeMillis() - createdAt) > CoreConstitution.PERMISSION_EXPIRATION_MS
    }

    fun hasPendingApproval(): Boolean = pendingApproval != null && !isPendingExpired()

    fun getPendingAction(): ProposedAction? = pendingApproval?.action
}

data class ApprovalRequest(
    val action: ProposedAction,
    val requiresDoubleConfirmation: Boolean,
    val blocked: Boolean,
    val blockReason: String?
)

data class FirstConfirmResult(
    val approved: Boolean,
    val needsSecond: Boolean,
    val message: String
)
