package com.example.smartcalculator

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Singleton that persists Manual Calculator history across popup open/close cycles.
 *
 * Session logic:
 *  - Each calculator instance (id=1 or id=2) gets its own session list.
 *  - A "session" = one list of calculation result strings.
 *  - Session ends when AC is pressed → current session saved, new one starts.
 *  - History persists even when the popup is closed.
 */
object ManualHistoryManager {

    data class ManualSession(
        val instanceId: Int,
        val entries: List<String>,   // formatted expressions like "10 + 5 = 15"
        val timestamp: Long = System.currentTimeMillis()
    )

    // Per-instance: current in-progress lines (before AC)
    private val currentLines = mutableMapOf<Int, MutableList<String>>()

    // Per-instance: completed sessions
    private val completedSessions = mutableMapOf<Int, MutableList<ManualSession>>()

    /** Adds a calculation line to the current session for this instance. */
    fun addLine(instanceId: Int, line: String) {
        currentLines.getOrPut(instanceId) { mutableListOf() }.add(line)
    }

    /** Returns current in-progress lines for the given instance. */
    fun getCurrentLines(instanceId: Int): List<String> =
        currentLines[instanceId]?.toList() ?: emptyList()

    /**
     * Ends the current session for this instance (called on AC press).
     * Saves current lines to completedSessions and clears current.
     */
    fun endSession(instanceId: Int) {
        val lines = currentLines[instanceId]
        if (!lines.isNullOrEmpty()) {
            val session = ManualSession(instanceId, lines.toList())
            completedSessions.getOrPut(instanceId) { mutableListOf() }.add(session)
            lines.clear()
        }
    }

    /** Returns all completed sessions for this instance (newest first). */
    fun getCompletedSessions(instanceId: Int): List<ManualSession> =
        completedSessions[instanceId]?.toList()?.reversed() ?: emptyList()

    /** Returns true if there is any history (current or completed) for this instance. */
    fun hasHistory(instanceId: Int): Boolean =
        !currentLines[instanceId].isNullOrEmpty() ||
        !completedSessions[instanceId].isNullOrEmpty()

    /** Clears all history (current + completed) for this instance. */
    fun clearAll(instanceId: Int) {
        currentLines[instanceId]?.clear()
        completedSessions[instanceId]?.clear()
    }

    /** Formats a Double for display. */
    fun fmt(v: Double): String {
        if (v.isNaN() || v.isInfinite()) return "Error"
        val bd = BigDecimal(v).setScale(10, RoundingMode.HALF_UP).stripTrailingZeros()
        val plain = bd.toPlainString()
        return if (plain.contains('.') && plain.length > 10)
            BigDecimal(v).setScale(4, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
        else plain
    }
}
