package com.agent42.sensors

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import com.agent42.memory.AgentDatabase
import com.agent42.memory.SensorSnapshotEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Calendar

/**
 * Embodied Context
 *
 * Research concept: The agent is not a disembodied chatbot — it runs on a physical
 * device carried by a human through the real world. By reading the phone's sensors,
 * the agent grounds its reasoning in physical reality: time of day, motion, battery,
 * ambient light. This produces contextually appropriate behavior.
 *
 * A user walking in bright daylight wants brief, actionable responses.
 * A user sitting at home late at night may appreciate longer, reflective answers.
 * A dying battery means the agent should conserve power and be terse.
 *
 * Privacy-first design: we do NOT access GPS/fine location. Coarse location is only
 * used if explicitly provided by the host Activity (e.g., "home", "office"). Motion
 * state is fed by the Activity via setMotionState() so this class stays testable
 * without needing real sensor registration.
 */

class SensorContextProvider(
    private val context: Context,
    private val db: AgentDatabase
) {

    companion object {
        const val MOTION_SITTING = "SITTING"
        const val MOTION_WALKING = "WALKING"
        const val MOTION_RUNNING = "RUNNING"
        const val MOTION_DRIVING = "DRIVING"
        const val MOTION_STATIONARY = "STATIONARY"
        const val MOTION_UNKNOWN = "UNKNOWN"

        private const val BATTERY_CONSERVE_THRESHOLD = 20
    }

    @Volatile
    private var currentMotionState: String = MOTION_UNKNOWN

    @Volatile
    private var currentCoarseLocation: String? = null

    @Volatile
    private var lastSnapshot: SensorSnapshotEntity? = null

    /**
     * Set the motion state from the host Activity (e.g., based on accelerometer).
     * This keeps the provider testable without real sensor registration.
     */
    fun setMotionState(state: String) {
        currentMotionState = state
    }

    /**
     * Set a coarse location label from the host Activity (e.g., "home", "office").
     * We deliberately do NOT access GPS directly for privacy.
     */
    fun setCoarseLocation(location: String?) {
        currentCoarseLocation = location
    }

    /**
     * Read current sensor state, store it in the database, and return the entity.
     */
    suspend fun captureSnapshot(sessionId: String): SensorSnapshotEntity = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val calendar = Calendar.getInstance().apply { timeInMillis = now }

        val timeOfDay = categorizeTimeOfDay(calendar.get(Calendar.HOUR_OF_DAY))
        val dayOfWeek = calendar.getDisplayName(Calendar.DAY_OF_WEEK, Calendar.LONG, java.util.Locale.getDefault()) ?: "Unknown"
        val batteryInfo = readBatteryStatus()

        val snapshot = SensorSnapshotEntity(
            timestamp = now,
            timeOfDay = timeOfDay,
            dayOfWeek = dayOfWeek,
            coarseLocation = currentCoarseLocation,
            motionState = currentMotionState,
            ambientLight = -1f, // Light sensor not directly read here; Activity can extend if needed
            isCharging = batteryInfo.isCharging,
            batteryLevel = batteryInfo.level,
            sessionId = sessionId
        )

        db.sensorSnapshotDao().insert(snapshot)
        lastSnapshot = snapshot
        snapshot
    }

    /**
     * Build a human-readable context string for injection into LLM prompts.
     * Example: "Context: It's Tuesday evening. The user appears to be walking.
     *           Phone is not charging (45% battery)."
     */
    fun getContextString(): String {
        val latest = getLatestSnapshotBlocking()
        return if (latest != null) {
            buildString {
                append("Context: It's ${latest.dayOfWeek} ${latest.timeOfDay}")
                if (latest.motionState != MOTION_UNKNOWN) {
                    append(". The user appears to be ${latest.motionState.lowercase()}")
                }
                append(". Phone is ${if (latest.isCharging) "charging" else "not charging"}")
                if (latest.batteryLevel >= 0) {
                    append(" (${latest.batteryLevel}% battery)")
                }
                if (!latest.coarseLocation.isNullOrBlank()) {
                    append(". Location: ${latest.coarseLocation}")
                }
                append(".")
            }
        } else {
            "Context: No recent sensor data available."
        }
    }

    /**
     * Return a short behavioral suggestion based on current sensor context.
     * This can be used to adjust response length, tone, or proactivity.
     */
    fun getBehavioralHint(): String {
        val latest = getLatestSnapshotBlocking() ?: return "No sensor context available."

        // Battery conservation overrides everything
        if (shouldConservePower()) {
            return "Conserve power — keep responses extremely brief."
        }

        return when (latest.motionState) {
            MOTION_WALKING, MOTION_RUNNING ->
                "Keep responses brief — user is on the move."
            MOTION_DRIVING ->
                "Keep responses very brief and actionable — user is driving."
            MOTION_SITTING, MOTION_STATIONARY ->
                "Can use longer responses — user is stationary."
            else -> {
                // Fall back to time-of-day hints
                when (latest.timeOfDay) {
                    "late night" -> "User may be winding down — gentle, concise tone."
                    "early morning" -> "User may be busy starting the day — be efficient."
                    else -> "No strong behavioral hint from sensors."
                }
            }
        }
    }

    /**
     * Get the most recent sensor snapshot from the database.
     */
    suspend fun getLatestSnapshot(): SensorSnapshotEntity? = withContext(Dispatchers.IO) {
        val fromDb = db.sensorSnapshotDao().getLatest()
        if (fromDb != null) {
            lastSnapshot = fromDb
        }
        fromDb
    }

    /**
     * Return true if battery is low and not charging — the agent should conserve power.
     */
    fun shouldConservePower(): Boolean {
        val battery = readBatteryStatus()
        return battery.level in 0..<BATTERY_CONSERVE_THRESHOLD && !battery.isCharging
    }

    // ═══ INTERNALS ═══════════════════════════════════════════

    private fun getLatestSnapshotBlocking(): SensorSnapshotEntity? {
        // For synchronous use from getContextString / getBehavioralHint,
        // we read from the in-memory cached snapshot. Callers that need
        // fresh data from the database should use the suspend getLatestSnapshot().
        return lastSnapshot
    }

    private fun categorizeTimeOfDay(hour: Int): String {
        return when (hour) {
            in 5..7 -> "early morning"
            in 8..11 -> "morning"
            in 12..16 -> "afternoon"
            in 17..20 -> "evening"
            else -> "late night"
        }
    }

    private data class BatteryStatus(val level: Int, val isCharging: Boolean)

    private fun readBatteryStatus(): BatteryStatus {
        val intentFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val batteryStatus: Intent? = context.registerReceiver(null, intentFilter)

        return if (batteryStatus != null) {
            val level = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            val pct = if (scale > 0) (level * 100 / scale) else -1

            val status = batteryStatus.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING
                    || status == BatteryManager.BATTERY_STATUS_FULL

            BatteryStatus(pct, isCharging)
        } else {
            BatteryStatus(-1, false)
        }
    }
}
