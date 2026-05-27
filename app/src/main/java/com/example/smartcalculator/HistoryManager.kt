package com.example.smartcalculator

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Singleton that holds the Smart Mode calculation history for the current session.
 * Both [FloatingWindowService] and [SmartAccessibilityService] read/write this object.
 *
 * Sessions:
 *  - _entries = current in-progress entries
 *  - _completedSessions = list of past completed sessions (each a list of entries)
 *  - A session is "completed" when the user long-presses Undo (All Clear).
 *  - Completed sessions persist even when the Smart popup is closed/reopened.
 */
object HistoryManager {

    data class Entry(
        val value: Double,
        val source: String = "",
        val timestamp: Long = System.currentTimeMillis(),
        var isChecked: Boolean = false
    )

    private val _entries = mutableListOf<Entry>()

    val entries: List<Entry> get() = _entries.toList()

    val total: Double get() = _entries.sumOf { it.value }

    val count: Int get() = _entries.size

    // List of completed sessions — persists across popup open/close
    private val _completedSessions = mutableListOf<List<Entry>>()
    val completedSessions: List<List<Entry>> get() = _completedSessions

    fun addCompletedSession(session: List<Entry>) {
        if (session.isNotEmpty()) {
            _completedSessions.add(session.toList())
        }
    }

    fun clearCompletedSessions() {
        _completedSessions.clear()
    }

    /** Called whenever the history changes — UI updates hook here. */
    var onChanged: (() -> Unit)? = null

    fun addEntry(value: Double, source: String = "") {
        _entries.add(Entry(value, source))
        onChanged?.invoke()
    }

    /** Called after clearing — UI and service can hook here to reset their state. */
    var onCleared: (() -> Unit)? = null

    var isExpressionChecked: Boolean = false

    /**
     * Clears only the current session entries.
     * Completed sessions are preserved (they persist across popup open/close).
     */
    fun clear() {
        _entries.clear()
        isExpressionChecked = false
        onChanged?.invoke()
        onCleared?.invoke()
    }

    /**
     * Clears only the current session entries and sets a single new entry.
     */
    fun clearAndSetSingle(value: Double, source: String = "") {
        _entries.clear()
        _entries.add(Entry(value, source))
        isExpressionChecked = false
        onChanged?.invoke()
        onCleared?.invoke()
    }

    /**
     * Ends the current session: saves current entries to completedSessions, then clears entries.
     * Call this on long-press Undo (All Clear) in Smart mode.
     */
    fun endCurrentSession() {
        if (_entries.isNotEmpty()) {
            _completedSessions.add(_entries.toList())
        }
        _entries.clear()
        isExpressionChecked = false
        onChanged?.invoke()
        onCleared?.invoke()
    }

    /**
     * Full reset: clears both current entries and all completed sessions.
     * Use only when a completely fresh start is needed.
     */
    fun clearAll() {
        _entries.clear()
        _completedSessions.clear()
        isExpressionChecked = false
        onChanged?.invoke()
        onCleared?.invoke()
    }

    /** Removes the last added entry. Returns the removed value, or null if empty. */
    fun removeLast(): Double? {
        if (_entries.isEmpty()) return null
        val removed = _entries.removeAt(_entries.lastIndex)
        onChanged?.invoke()
        return removed.value
    }

    /** Removes the last entry matching the given value. Returns true if removed. */
    fun removeEntry(value: Double): Boolean {
        val index = _entries.indexOfLast { it.value == value }
        if (index >= 0) {
            _entries.removeAt(index)
            onChanged?.invoke()
            return true
        }
        return false
    }

    fun hasEntries() = _entries.isNotEmpty()

    fun hasAnyHistory() = _entries.isNotEmpty() || _completedSessions.isNotEmpty()

    /**
     * Returns a readable expression string like "100 + 250.5 + 30"
     */
    fun expressionString(): String {
        if (_entries.isEmpty()) return "—"
        return _entries.joinToString(" + ") { fmt(it.value) }
    }

    /**
     * Returns a compact calculator-pasteable expression like "100+250.5+30"
     * that can be directly pasted into any standard calculator app.
     */
    fun calcExpressionString(): String {
        if (_entries.isEmpty()) return "0"
        return _entries.joinToString("+") { fmt(it.value) }
    }

    fun formattedTotal(): String = fmt(total)

    internal fun fmt(v: Double): String {
        if (v.isNaN() || v.isInfinite()) return "Error"
        val bd = BigDecimal(v).setScale(10, RoundingMode.HALF_UP).stripTrailingZeros()
        val plain = bd.toPlainString()
        return if (plain.contains('.') && plain.length > 10)
            BigDecimal(v).setScale(4, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
        else plain
    }
}
