package com.agent42.security

/**
 * The 12 Non-Negotiable Rules — Hardlocked Core Constitution.
 *
 * These rules are IMMUTABLE. They are defined as a Kotlin object with constant
 * values compiled into the app. The CodeModificationEngine is physically blocked
 * from modifying any file in the com.agent42.security package.
 *
 * Any proposed self-modification that conflicts with these rules is automatically
 * rejected by the ConstitutionEnforcer before it reaches the approval gate.
 *
 * Rule 0 — Core Policy (one sentence):
 * "This AI executes sensitive actions only after verified owner authorization,
 *  explicit approval, and auditable confirmation."
 */
object CoreConstitution {

    const val CORE_POLICY = "This AI executes sensitive actions only after verified " +
        "owner authorization, explicit approval, and auditable confirmation."

    /**
     * The Loyalty Directive — defines what loyalty means.
     * Loyalty is NOT blind obedience. It is:
     *   - Honest advice and hard truths (even when uncomfortable)
     *   - Opinions and recommendations freely given
     *   - Pushback when the AI disagrees, with reasoning
     *   - Full execution once the owner decides
     *   - The owner is always the final authority
     *
     * This directive is prepended to every persona's system prompt and
     * cannot be removed by self-modification.
     */
    val LOYALTY_DIRECTIVE = """
        LOYALTY DIRECTIVE: You serve one owner. True loyalty means:
        - Always give your honest opinion, even when uncomfortable.
        - Tell hard truths. Never sugarcoat or hide problems.
        - Push back when you disagree. Explain why.
        - Warn about risks the owner may not see.
        - The owner is the boss. The final say is always theirs.
        - Once the owner decides, execute fully.
        - Never blindly obey without first giving your honest assessment.
        - Holding back your opinion is disloyal.
    """.trimIndent()

    enum class Rule {
        RULE_1_OWNER_LOCK,
        RULE_2_DEFAULT_NO,
        RULE_3_CLEAR_PERMISSION,
        RULE_4_DOUBLE_CONFIRMATION,
        RULE_5_SANDBOX_FIRST,
        RULE_6_TRANSPARENCY_LOG,
        RULE_7_IMMEDIATE_STOP,
        RULE_8_PERMISSION_EXPIRATION,
        RULE_9_NO_SILENT_BACKGROUND_POWER,
        RULE_10_DATA_LOYALTY,
        RULE_11_ANTI_IMPERSONATION,
        RULE_12_SAFETY_BOUNDARY
    }

    enum class RiskLevel { LOW, MEDIUM, HIGH }

    enum class ActionCategory {
        CODE_CHANGE,          // Rule 5: sandbox first
        SETTINGS_CHANGE,      // Rule 4: double confirmation
        FINANCIAL_ACTION,     // Rule 4: double confirmation
        ACCOUNT_ACCESS,       // Rule 4: double confirmation
        SEND_MESSAGE,         // Rule 3: clear permission
        RUN_AUTOMATION,       // Rule 3: clear permission
        DATA_EXPORT,          // Rule 10: data loyalty
        DATA_SHARE,           // Rule 10: data loyalty + explicit per-connection approval
        SYSTEM_MODIFICATION,  // Rule 4 + Rule 5
        READ_ONLY,            // No approval needed
        SAFETY_WARNING        // Rule 12: warns owner — owner decides, AI does NOT block
    }

    data class RuleViolation(
        val rule: Rule,
        val description: String,
        val severity: RiskLevel
    )

    /**
     * Packages protected from self-modification (Rule 5 + Rule 9).
     * The CodeModificationEngine checks this list and refuses to modify any
     * module whose package matches.
     */
    val PROTECTED_PACKAGES = listOf(
        "com.agent42.security",
        "com.agent42.security.CoreConstitution",
        "com.agent42.security.OwnerAuth",
        "com.agent42.security.ConstitutionEnforcer",
        "com.agent42.security.ActionLog",
        "com.agent42.security.PermissionManager",
        // World model: the agent may revise WHAT it believes (the world model
        // data itself is unprotected), but not HOW it learns. The revision
        // rules are protected per REAL_INTELLIGENCE_PLAN.md section 3.5.
        "com.agent42.worldmodel.RevisionRules"
    )

    /**
     * Check a proposed action against all 12 rules.
     * Returns a list of violations — empty means the action is constitutionally allowed
     * (but still needs owner approval per Rule 3).
     *
     * Rule 12 is special: it does NOT block. It generates a WARNING that is shown
     * to the owner. The owner makes the final decision — laws vary by jurisdiction
     * and the owner is the sole authority. The AI warns, the owner decides.
     */
    fun checkAction(
        action: ProposedAction,
        ownerVerified: Boolean,
        hasActiveApproval: Boolean,
        sandboxTested: Boolean,
        isLockdownMode: Boolean
    ): List<RuleViolation> {
        val violations = mutableListOf<RuleViolation>()

        // Rule 7: Immediate Stop / Lockdown
        if (isLockdownMode) {
            violations.add(RuleViolation(
                Rule.RULE_7_IMMEDIATE_STOP,
                "Lockdown mode is active — all actions are blocked",
                RiskLevel.HIGH
            ))
            return violations // No point checking further
        }

        // Rule 12: Safety Boundary — WARN but do NOT block
        // The owner has final authority. Laws vary by state/country.
        // The AI's job is to warn, not to decide for the owner.
        if (action.category == ActionCategory.SAFETY_WARNING) {
            violations.add(RuleViolation(
                Rule.RULE_12_SAFETY_BOUNDARY,
                "⚠ WARNING: This action may be illegal or physically dangerous in some jurisdictions. " +
                "The AI cannot determine legality for your specific location. " +
                "Review carefully — you assume full responsibility. " +
                "The AI will proceed if you approve.",
                RiskLevel.HIGH
            ))
            // Do NOT return — continue checking other rules.
            // The owner can still approve after seeing this warning.
        }

        // Rule 1: Owner Lock
        if (!ownerVerified && action.category != ActionCategory.READ_ONLY) {
            violations.add(RuleViolation(
                Rule.RULE_1_OWNER_LOCK,
                "Owner identity not verified (2-factor required)",
                RiskLevel.HIGH
            ))
        }

        // Rule 2: Default is NO
        if (!hasActiveApproval && action.category != ActionCategory.READ_ONLY) {
            violations.add(RuleViolation(
                Rule.RULE_2_DEFAULT_NO,
                "No explicit approval — defaulting to no action",
                RiskLevel.MEDIUM
            ))
        }

        // Rule 5: Sandbox First for self-changes
        if ((action.category == ActionCategory.CODE_CHANGE ||
             action.category == ActionCategory.SYSTEM_MODIFICATION) && !sandboxTested) {
            violations.add(RuleViolation(
                Rule.RULE_5_SANDBOX_FIRST,
                "Code/system change requires sandbox test before approval",
                RiskLevel.HIGH
            ))
        }

        // Rule 9: No Silent Background Power
        if (action.silent && action.category != ActionCategory.READ_ONLY) {
            violations.add(RuleViolation(
                Rule.RULE_9_NO_SILENT_BACKGROUND_POWER,
                "Silent actions are not allowed — must be shown and approved",
                RiskLevel.HIGH
            ))
        }

        // Rule 10: Data Loyalty
        if (action.category == ActionCategory.DATA_SHARE && !action.explicitShareApproval) {
            violations.add(RuleViolation(
                Rule.RULE_10_DATA_LOYALTY,
                "Data sharing requires explicit per-connection approval",
                RiskLevel.HIGH
            ))
        }

        // Rule 11: Anti-Impersonation — voice alone is not enough for critical actions
        if (action.voiceInitiated && action.category in CRITICAL_CATEGORIES) {
            violations.add(RuleViolation(
                Rule.RULE_11_ANTI_IMPERSONATION,
                "Voice command for critical action requires owner verification (PIN/passphrase/biometric)",
                RiskLevel.HIGH
            ))
        }

        return violations
    }

    /**
     * Determine if an action needs double confirmation (Rule 4).
     */
    fun needsDoubleConfirmation(category: ActionCategory): Boolean {
        return category in CRITICAL_CATEGORIES
    }

    /**
     * Determine if an action needs sandbox testing first (Rule 5).
     */
    fun needsSandboxTest(category: ActionCategory): Boolean {
        return category == ActionCategory.CODE_CHANGE ||
               category == ActionCategory.SYSTEM_MODIFICATION
    }

    /**
     * Permission expiration time in milliseconds (Rule 8).
     * Approvals expire after 2 minutes by default.
     */
    const val PERMISSION_EXPIRATION_MS = 2 * 60 * 1000L

    val CRITICAL_CATEGORIES = setOf(
        ActionCategory.CODE_CHANGE,
        ActionCategory.SETTINGS_CHANGE,
        ActionCategory.FINANCIAL_ACTION,
        ActionCategory.ACCOUNT_ACCESS,
        ActionCategory.SYSTEM_MODIFICATION,
        ActionCategory.DATA_EXPORT,
        ActionCategory.DATA_SHARE
    )
}

/**
 * A proposed action that the agent wants to execute.
 * The ConstitutionEnforcer checks this before any approval flow.
 */
data class ProposedAction(
    val description: String,
    val reason: String,
    val category: CoreConstitution.ActionCategory,
    val riskLevel: CoreConstitution.RiskLevel,
    val voiceInitiated: Boolean = false,
    val silent: Boolean = false,
    val explicitShareApproval: Boolean = false,
    val sandboxResult: String? = null
)
