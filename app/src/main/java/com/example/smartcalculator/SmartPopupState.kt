package com.example.smartcalculator

/**
 * Shared, process-wide flag for whether the Smart popup (or its minimized bubble)
 * is currently active. [SmartAccessibilityService] gates all captures on this so
 * that no input is taken when the user has closed Smart mode.
 */
object SmartPopupState {
    @Volatile
    var isOpen: Boolean = false

    /**
     * Timestamp (System.currentTimeMillis) of the last full-clear initiated from the popup.
     * [SmartAccessibilityService] uses this to enforce a short cooldown so a long-press
     * Clear doesn't immediately re-capture from a queued event.
     * Set synchronously from the popup so it does not depend on broadcast delivery.
     */
    @Volatile
    var lastClearMs: Long = 0L

    /**
     * True while the user is manually editing the Smart expression line via the
     * inline keyboard editor. Captures from the accessibility service are suspended
     * during this window so background imports don't overwrite the user's typing.
     */
    @Volatile
    var isEditorOpen: Boolean = false
}
