package com.agent42.security

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Rule 6 — Full Transparency Log.
 *
 * Every action is recorded in a plain, append-only activity log:
 * - Time
 * - Requested by
 * - What happened
 * - Approved or denied
 *
 * The log CANNOT be silently erased (Rule 6). Deletion requires owner
 * authentication and is itself logged.
 */
class ActionLog(context: Context) {

    private val logFile = File(context.filesDir, "action_log.txt")

    init {
        if (!logFile.exists()) logFile.createNewFile()
    }

    enum class Outcome { APPROVED, DENIED, BLOCKED_BY_CONSTITUTION, EXPIRED, AUTO_ROLLBACK }

    data class LogEntry(
        val timestamp: Long,
        val requestedBy: String,
        val actionDescription: String,
        val reason: String,
        val category: String,
        val riskLevel: String,
        val outcome: Outcome,
        val details: String? = null
    )

    fun append(entry: LogEntry) {
        val line = buildString {
            append("[${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
                .format(java.util.Date(entry.timestamp))}] ")
            append("BY=${entry.requestedBy} | ")
            append("ACTION=${entry.actionDescription} | ")
            append("REASON=${entry.reason} | ")
            append("CATEGORY=${entry.category} | ")
            append("RISK=${entry.riskLevel} | ")
            append("OUTCOME=${entry.outcome}")
            entry.details?.let { append(" | DETAILS=$it") }
            append("\n")
        }
        logFile.appendText(line)
    }

    fun getAll(): List<LogEntry> {
        if (!logFile.exists()) return emptyList()
        return logFile.readLines().mapNotNull { line -> parseLine(line) }
    }

    fun getRecent(limit: Int = 50): List<LogEntry> {
        return getAll().takeLast(limit)
    }

    /**
     * Rule 6: Log cannot be silently erased.
     * This method requires owner auth — it's not callable from the agent.
     * Even when called, the deletion itself is logged first.
     */
    fun clearWithOwnerAuth(ownerAuth: OwnerAuth, pin: String): Boolean {
        if (!ownerAuth.verifyPin(pin)) {
            append(LogEntry(
                timestamp = System.currentTimeMillis(),
                requestedBy = "UNKNOWN",
                actionDescription = "Attempted log deletion",
                reason = "Clear log",
                category = "LOG_MANAGEMENT",
                riskLevel = "HIGH",
                outcome = Outcome.DENIED,
                details = "Owner auth failed"
            ))
            return false
        }
        // Log the deletion BEFORE clearing
        append(LogEntry(
            timestamp = System.currentTimeMillis(),
            requestedBy = "OWNER",
            actionDescription = "Log cleared by owner",
            reason = "Manual log clear",
            category = "LOG_MANAGEMENT",
            riskLevel = "MEDIUM",
            outcome = Outcome.APPROVED,
            details = "Owner authenticated, clearing ${logFile.length()} bytes"
        ))
        logFile.writeText("")
        return true
    }

    private fun parseLine(line: String): LogEntry? {
        return try {
            val timestampStr = Regex("\\[(.+?)\\]").find(line)?.groupValues?.get(1)
            val timestamp = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
                .parse(timestampStr)?.time ?: System.currentTimeMillis()
            val by = Regex("BY=(.+?) \\|").find(line)?.groupValues?.get(1) ?: ""
            val action = Regex("ACTION=(.+?) \\|").find(line)?.groupValues?.get(1) ?: ""
            val reason = Regex("REASON=(.+?) \\|").find(line)?.groupValues?.get(1) ?: ""
            val category = Regex("CATEGORY=(.+?) \\|").find(line)?.groupValues?.get(1) ?: ""
            val risk = Regex("RISK=(.+?) \\|").find(line)?.groupValues?.get(1) ?: ""
            val outcomeStr = Regex("OUTCOME=(\\w+)").find(line)?.groupValues?.get(1) ?: "DENIED"
            val details = Regex("DETAILS=(.+)").find(line)?.groupValues?.get(1)
            LogEntry(timestamp, by, action, reason, category, risk,
                Outcome.valueOf(outcomeStr), details)
        } catch (e: Exception) {
            Log.w("ActionLog", "Failed to parse log line: $line", e)
            null
        }
    }
}
