package com.example.smartcalculator

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * WhatsApp capture with **selection mode** driven by [TYPE_WINDOW_CONTENT_CHANGED]:
 *
 * - [isSelectionMode] arms on [TYPE_VIEW_TEXT_SELECTION_CHANGED].
 * - While true, each [TYPE_WINDOW_CONTENT_CHANGED] triggers a full [rootInActiveWindow] scan for
 *   [isSelected] / [isChecked] nodes; for each chat row, child [TextView] text is parsed.
 * - Numbers: `[0-9.]+` with **≤ 4 digit characters** per token (phones/timestamps reduced).
 * - Vibration (**50 ms**) only when a **new** (row,value) pair is committed (dedup across churn).
 * - [TYPE_WINDOW_STATE_CHANGED]: if the action-mode / selection bar is gone, clears [isSelectionMode].
 * - Outside selection mode, normal click/focus/source handling still applies.
 */
class SmartAccessibilityService : AccessibilityService() {

    companion object {
        private val WHATSAPP_PACKAGES = setOf("com.whatsapp", "com.whatsapp.w4b")

        private val TIME_REGEX = Regex(
            """\b\d{1,2}:\d{2}(?::\d{2})?(?:\s*[ap]m)?\b""",
            RegexOption.IGNORE_CASE
        )

        private val FORMATTED_PHONE_REGEX = Regex(
            """(?:\+\d{1,4}[\s\-])\d{2,5}[\s\-]\d{4,8}""" +
            "|" +
            """0\d{1,4}[\s\-]\d{4,8}(?:[\s\-]\d{2,6})?"""
        )

        private val NUMBER_REGEX = Regex("""[0-9০-৯.]+""")

        private const val MIN_MONEY_DIGITS = 3
        private const val MAX_MONEY_DIGITS = 5

        private const val NODE_CAPTURE_GUARD_MS = 100L
        /** After a full-clear from the popup, suppress captures briefly, then auto-reset. */
        private const val CLEAR_COOLDOWN_MS = 2000L
        private const val CONTENT_DEEP_SCAN_MIN_INTERVAL_MS = 120L
        /** Block identical value commits within this window to absorb duplicates from sibling nodes. */
        private const val SAME_VALUE_GUARD_MS = 200L
        /** Suppress click-triggered capture for this long after a scroll, to avoid false imports. */
        private const val POST_SCROLL_SUPPRESS_MS = 400L
        private const val VIBRATE_MS = 50L
        private const val MAX_COLLECT_DEPTH = 12
        private const val MAX_ANCESTOR_WALK = 16
        private const val MAX_ROOT_SCAN_DEPTH = 24
        private const val MAX_ACTION_MODE_SCAN_DEPTH = 22

        private const val LOG_TAG = "SmartCalc"
        private const val HIERARCHY_MAX_ANCESTORS = 24
        private const val HIERARCHY_SUBTREE_DEPTH = 8
        private const val HIERARCHY_MAX_LINES = 120
    }

    private var wasWhatsApp = false

    /** True after chat text selection / multi-select entry. */
    private var isSelectionMode = false

    /** Dedup during selection mode: `"$physicalKey|$value"` already committed → no repeat vibrate/add. */
    private val committedSelectionTuples = mutableSetOf<String>()

    private val lastSelectedKeys = mutableSetOf<String>()
    private val lastCaptureAtByKey = mutableMapOf<String, Long>()

    private var lastContentDeepScanMs = 0L

    private var lastCommitValue: Double = Double.NaN
    private var lastCommitMs: Long = 0L
    private var lastScrollMs: Long = 0L
    private val handler = Handler(Looper.getMainLooper())

    private val clearReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                FloatingWindowService.ACTION_CLEAR_SMART -> {
                    resetSelectionModeState()
                    lastSelectedKeys.clear()
                    lastCaptureAtByKey.clear()
                    lastCommitValue = Double.NaN
                    lastCommitMs = 0L
                    // Defensive: any racey accessibility event from the popup long-press
                    // that re-arms selection-mode is wiped after the cooldown so new
                    // WhatsApp clicks resume capture within 2 seconds.
                    handler.postDelayed({
                        resetSelectionModeState()
                        lastSelectedKeys.clear()
                        lastCaptureAtByKey.clear()
                    }, CLEAR_COOLDOWN_MS)
                }
                FloatingWindowService.ACTION_UNDO_SMART -> {
                    if (committedSelectionTuples.isNotEmpty()) {
                        val lastElement = committedSelectionTuples.last()
                        committedSelectionTuples.remove(lastElement)
                    }
                }
            }
        }
    }

    private fun resetSelectionModeState() {
        isSelectionMode = false
        committedSelectionTuples.clear()
        lastContentDeepScanMs = 0L
        lastCommitValue = Double.NaN
        lastCommitMs = 0L
    }

    override fun onServiceConnected() {
        val filter = IntentFilter().apply {
            addAction(FloatingWindowService.ACTION_CLEAR_SMART)
            addAction(FloatingWindowService.ACTION_UNDO_SMART)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            registerReceiver(clearReceiver, filter, RECEIVER_NOT_EXPORTED)
        else
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(clearReceiver, filter)
        configureService()
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(clearReceiver)
    }

    private fun configureService() {
        serviceInfo = serviceInfo?.apply {
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            eventTypes =
                AccessibilityEvent.TYPE_VIEW_LONG_CLICKED            or
                AccessibilityEvent.TYPE_VIEW_CLICKED                 or
                AccessibilityEvent.TYPE_VIEW_SCROLLED                or
                AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED  or
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED         or
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            notificationTimeout = 0
            packageNames = null
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return

        if (Log.isLoggable(LOG_TAG, Log.DEBUG)) {
            Log.d(LOG_TAG, "Event Type: ${event.eventType}, Class: ${event.className}, Text: ${event.text}")
        }

        if (Log.isLoggable(LOG_TAG, Log.DEBUG)) {
            event.source?.let { src ->
                try {
                    logClickedNodeHierarchy(src)
                } finally {
                    src.recycle()
                }
            } ?: Log.d(LOG_TAG, "Event source: null")
        }

        val pkg = event.packageName?.toString() ?: return

        // Ignore click/long-click/text-selection events generated by our own popup
        // (e.g. the Undo/Clear buttons) — they must never alter selection-mode state.
        if (pkg == packageName) {
            when (event.eventType) {
                AccessibilityEvent.TYPE_VIEW_CLICKED,
                AccessibilityEvent.TYPE_VIEW_LONG_CLICKED,
                AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED -> return
            }
        }

        when (event.eventType) {

            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val isWA = pkg in WHATSAPP_PACKAGES
                if (!isWA && wasWhatsApp) {
                    resetSelectionModeState()
                    lastSelectedKeys.clear()
                    lastCaptureAtByKey.clear()
                    sendBroadcast(Intent(FloatingWindowService.ACTION_DOCK))
                }
                if (!isWA) {
                    resetSelectionModeState()
                    lastSelectedKeys.clear()
                } else {
                    val root = rootInActiveWindow
                    if (root != null) {
                        try {
                            if (isSelectionMode && !rootShowsSelectionActionChrome(root)) {
                                handler.postDelayed({
                                    val delayedRoot = rootInActiveWindow
                                    if (delayedRoot != null) {
                                        try {
                                            if (isSelectionMode && !rootShowsSelectionActionChrome(delayedRoot)) {
                                                resetSelectionModeState()
                                                lastSelectedKeys.clear()
                                                lastCaptureAtByKey.clear()
                                            }
                                        } finally {
                                            delayedRoot.recycle()
                                        }
                                    } else {
                                        resetSelectionModeState()
                                        lastSelectedKeys.clear()
                                        lastCaptureAtByKey.clear()
                                    }
                                }, 250L)
                            }
                        } finally {
                            root.recycle()
                        }
                    }
                }
                wasWhatsApp = isWA
            }

            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                // Action-driven capture is handled in click/long-click events; ignore content updates to prevent scroll-capture.
            }

            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                if (pkg in WHATSAPP_PACKAGES) lastScrollMs = System.currentTimeMillis()
            }

            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED -> {
                // Capture highlighted/selected text from ANY app
                val now = System.currentTimeMillis()
                if (now - lastScrollMs < 800L) return
                // Only capture when the user has actually highlighted text (non-empty range).
                val src = event.source ?: return
                try {
                    // Ignore selections inside editable input fields (keyboards / EditText /
                    // WhatsApp reply box etc.) so typed/pasted text never becomes input.
                    val isEditable = try { src.isEditable } catch (_: Exception) { false }
                    val clsName = src.className?.toString().orEmpty()
                    if (isEditable || clsName.contains("EditText", ignoreCase = true)) return
                    val selected = extractSelectedText(src)
                    if (selected.isNotBlank()) {
                        val value = parseNumericValue(selected)
                        if (value != null) commitCapture(value, pkg)
                    }
                } finally {
                    src.recycle()
                }
            }

            AccessibilityEvent.TYPE_VIEW_LONG_CLICKED -> {
                // Long press on ANY app enters selection mode (WhatsApp-style multi-select)
                val src = event.source ?: return
                try {
                    if (isActionModeCloseButton(src)) {
                        resetSelectionModeState()
                        lastSelectedKeys.clear()
                        lastCaptureAtByKey.clear()
                        return
                    }
                    isSelectionMode = true
                    processClickOrLongClickInSelectionMode(src, pkg)
                } finally {
                    src.recycle()
                }
            }

            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                // In selection mode, single click adds items from ANY app
                val src = event.source ?: return
                var recycleSrc = true
                try {
                    if (isActionModeCloseButton(src)) {
                        resetSelectionModeState()
                        lastSelectedKeys.clear()
                        lastCaptureAtByKey.clear()
                        return
                    }
                    if (isSelectionMode) {
                        processClickOrLongClickInSelectionMode(src, pkg)
                    } else {
                        // Normal click: only capture from WhatsApp (preserve existing behaviour)
                        if (pkg in WHATSAPP_PACKAGES) {
                            src.recycle()
                            recycleSrc = false
                            tryCaptureWithActiveScan(event, pkg)
                        }
                    }
                } finally {
                    if (recycleSrc) src.recycle()
                }
            }

        }
    }

    override fun onInterrupt() = Unit

    /** Logs ancestors (leaf → root) then a depth-capped subtree under the event source. */
    private fun logClickedNodeHierarchy(leaf: AccessibilityNodeInfo) {
        Log.d(LOG_TAG, "=== Event source: ancestors (leaf → root) ===")
        var n: AccessibilityNodeInfo? = leaf
        var depth = 0
        while (n != null && depth < HIERARCHY_MAX_ANCESTORS) {
            Log.d(LOG_TAG, "[$depth] ${formatNodeForLog(n)}")
            val parent = n.parent
            if (n != leaf) n.recycle()
            n = parent
            depth++
        }
        n?.recycle()

        Log.d(LOG_TAG, "=== Event source: subtree (depth ≤ $HIERARCHY_SUBTREE_DEPTH, max $HIERARCHY_MAX_LINES lines) ===")
        val counter = intArrayOf(0)
        logNodeSubtreeForDebug(leaf, 0, counter)
    }

    private fun formatNodeForLog(node: AccessibilityNodeInfo): String {
        val r = Rect()
        node.getBoundsInScreen(r)
        val id = node.viewIdResourceName ?: ""
        val cls = node.className ?: ""
        val txt = node.text ?: ""
        val desc = node.contentDescription ?: ""
        val sel = try {
            node.isSelected
        } catch (_: Exception) {
            false
        }
        val chk = try {
            node.isChecked
        } catch (_: Exception) {
            false
        }
        val focused = try {
            node.isAccessibilityFocused
        } catch (_: Exception) {
            false
        }
        return "class=$cls id=$id text=$txt desc=$desc sel=$sel chk=$chk a11yFocused=$focused bounds=$r"
    }

    private fun logNodeSubtreeForDebug(node: AccessibilityNodeInfo, depth: Int, lineCount: IntArray) {
        if (depth > HIERARCHY_SUBTREE_DEPTH || lineCount[0] >= HIERARCHY_MAX_LINES) return
        Log.d(LOG_TAG, "${"  ".repeat(depth * 2)}${formatNodeForLog(node)}")
        lineCount[0]++
        val cc = node.childCount
        for (i in 0 until cc) {
            val ch = node.getChild(i) ?: continue
            logNodeSubtreeForDebug(ch, depth + 1, lineCount)
            ch.recycle()
        }
    }

    /**
     * Full-tree scan from [rootInActiveWindow] for selected/checked nodes; TextView-only text per node.
     */
    private fun deepScanSelectedNodesAndCapture(pkg: String) {
        if (!isSelectionMode) return
        val root = rootInActiveWindow ?: return
        val selectedCopies = mutableMapOf<String, AccessibilityNodeInfo>()
        try {
            gatherSelectedOrCheckedNodes(root, 0, selectedCopies)
            // No selected/checked nodes means multi-select was dismissed without a close-button
            // event (e.g. back-press). Auto-reset so scroll no longer triggers scans.
            if (selectedCopies.isEmpty()) {
                resetSelectionModeState()
                return
            }
            for ((_, node) in selectedCopies) {
                try {
                    if (isWhatsAppBackOrCancel(node)) continue
                    val bounds = Rect()
                    node.getBoundsInScreen(bounds)
                    if (isStatusBarOverlap(bounds)) continue
                    if (!isChatContentNode(node)) continue

                    val raw = collectTextFromTextViewsDeep(node).trim()
                    if (raw.isBlank()) continue

                    val value = parseNumericValue(raw) ?: continue
                    val key = physicalKey(node)
                    val tuple = selectionTuple(key, value)
                    if (tuple in committedSelectionTuples) continue
                    committedSelectionTuples.add(tuple)
                    commitCapture(value, pkg)
                } catch (_: Exception) {
                }
            }
        } finally {
            for (n in selectedCopies.values) {
                try {
                    n.recycle()
                } catch (_: Exception) {
                }
            }
            root.recycle()
        }
    }

    private fun selectionTuple(physicalKey: String, value: Double): String =
        "$physicalKey|$value"

    /** Heuristic: action mode / selection toolbar still present. */
    private fun rootShowsSelectionActionChrome(root: AccessibilityNodeInfo): Boolean {
        var found = false
        fun dfs(n: AccessibilityNodeInfo, d: Int) {
            if (found || d > MAX_ACTION_MODE_SCAN_DEPTH) return
            val id = n.viewIdResourceName?.lowercase().orEmpty()
            val cls = n.className?.toString()?.lowercase().orEmpty()
            if (id.contains("action_mode_bar") ||
                id.contains("action_mode") ||
                id.contains("selection_action") ||
                cls.contains("actionmode")) {
                found = true
                return
            }
            val cc = n.childCount
            for (i in 0 until cc) {
                val ch = n.getChild(i) ?: continue
                dfs(ch, d + 1)
                ch.recycle()
            }
        }
        dfs(root, 0)
        return found
    }

    private fun isActionModeCloseButton(node: AccessibilityNodeInfo): Boolean {
        val id = node.viewIdResourceName?.lowercase().orEmpty()
        return id.contains("action_mode_close_button")
    }

    private fun tryCaptureWithActiveScan(event: AccessibilityEvent, pkg: String) {
        val root = rootInActiveWindow ?: return
        var focus: AccessibilityNodeInfo? = null
        val selectedCopies = mutableMapOf<String, AccessibilityNodeInfo>()
        try {
            focus = root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
            gatherSelectedOrCheckedNodes(root, 0, selectedCopies)

            val currentKeys = selectedCopies.keys.toSet()
            val newlySelectedKeys = currentKeys - lastSelectedKeys
            lastSelectedKeys.clear()
            lastSelectedKeys.addAll(currentKeys)

            val source = event.source
            try {
                for (key in newlySelectedKeys) {
                    val n = selectedCopies[key] ?: continue
                    tryProcessCandidate(event, n, pkg)
                }
                focus?.let { tryProcessCandidate(event, it, pkg) }
                source?.let { tryProcessCandidate(event, it, pkg) }
            } finally {
                source?.recycle()
            }
        } finally {
            for (n in selectedCopies.values) {
                try {
                    n.recycle()
                } catch (_: Exception) {
                }
            }
            focus?.recycle()
            root.recycle()
        }
    }

    private fun gatherSelectedOrCheckedNodes(
        node: AccessibilityNodeInfo,
        depth: Int,
        out: MutableMap<String, AccessibilityNodeInfo>
    ) {
        if (depth > MAX_ROOT_SCAN_DEPTH) return
        val marked = try {
            node.isSelected || node.isChecked
        } catch (_: Exception) {
            false
        }
        if (marked) {
            val key = physicalKey(node)
            out[key]?.recycle()
            out[key] = AccessibilityNodeInfo.obtain(node)
        }
        val cc = node.childCount
        for (i in 0 until cc) {
            val ch = node.getChild(i) ?: continue
            gatherSelectedOrCheckedNodes(ch, depth + 1, out)
            ch.recycle()
        }
    }

    private fun tryProcessCandidate(event: AccessibilityEvent, node: AccessibilityNodeInfo, pkg: String): Boolean {
        if (isWhatsAppBackOrCancel(node)) {
            lastSelectedKeys.clear()
            lastCaptureAtByKey.clear()
            return false
        }

        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        if (isStatusBarOverlap(bounds)) return false
        if (!isChatContentNode(node)) return false

        val raw = rawTextFromSource(event, node).trim()
        if (raw.isBlank()) return false

        val value = parseNumericValue(raw) ?: return false

        val key = physicalKey(node)
        if (isSelectionMode) {
            val tuple = selectionTuple(key, value)
            if (tuple in committedSelectionTuples) return false
            committedSelectionTuples.add(tuple)
        } else {
            val now = System.currentTimeMillis()
            val prev = lastCaptureAtByKey[key] ?: 0L
            if (now - prev < NODE_CAPTURE_GUARD_MS) return false
            lastCaptureAtByKey[key] = now
        }

        commitCapture(value, pkg)
        return true
    }

    private fun rawTextFromSource(event: AccessibilityEvent, node: AccessibilityNodeInfo): String {
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED) {
            val es = event.source
            if (es != null) {
                try {
                    if (samePhysicalNode(es, node)) {
                        val sel = extractSelectedText(es)
                        if (sel.isNotBlank()) return sel
                    }
                } finally {
                    es.recycle()
                }
            } else {
                val sel = extractSelectedText(node)
                if (sel.isNotBlank()) return sel
            }
        }

        // If the clicked node is inside a quoted/reply-preview block,
        // walk up to the parent message container and extract only
        // the reply text (collectTextFromTextViewsDeep skips quoted children).
        if (isNodeInsideQuotedBlock(node)) {
            val msgContainer = walkUpToMessageContainer(node)
            if (msgContainer != null) {
                try {
                    val text = collectTextFromTextViewsDeep(msgContainer)
                    if (text.isNotBlank()) return text
                } finally {
                    msgContainer.recycle()
                }
            }
        }

        val fromTv = collectTextFromTextViewsDeep(node)
        if (fromTv.isNotBlank()) return fromTv
        return collectAllText(node)
    }

    private fun samePhysicalNode(a: AccessibilityNodeInfo, b: AccessibilityNodeInfo): Boolean {
        if (a == b) return true
        return physicalKey(a) == physicalKey(b)
    }

    private fun isChatContentNode(source: AccessibilityNodeInfo): Boolean {
        val chain = ArrayList<AccessibilityNodeInfo>(MAX_ANCESTOR_WALK)
        var n: AccessibilityNodeInfo? = source
        var d = 0
        var hasList = false
        var hasAnchor = false
        while (n != null && d < MAX_ANCESTOR_WALK) {
            chain.add(n)
            val id = n.viewIdResourceName?.lowercase().orEmpty()
            val cls = n.className?.toString()?.lowercase().orEmpty()
            if (id.contains("conversation_contact") ||
                id.contains("contact_name") ||
                id.contains("home_toolbar") ||
                id.contains("search_toolbar")) {
                recycleChainExceptSource(chain, source)
                return false
            }
            if (cls.contains("recyclerview") || cls.contains("listview")) hasList = true
            if (isMessageAnchorId(id)) hasAnchor = true
            n = n.parent
            d++
        }
        recycleChainExceptSource(chain, source)
        return hasList || hasAnchor
    }

    private fun recycleChainExceptSource(chain: List<AccessibilityNodeInfo>, source: AccessibilityNodeInfo) {
        for (node in chain) {
            if (node != source) node.recycle()
        }
    }

    private fun isMessageAnchorId(id: String): Boolean =
        id.contains("message_text") ||
        id.contains("quoted_message") || id.contains("quoted_text") ||
        id.contains("conversation_row") || id.contains("message_row") ||
        id.contains("link_preview") || id.contains("caption") ||
        id.contains("media_text") ||
        (id.contains("message") && !id.contains("notification") && !id.contains("count"))

    private fun isWhatsAppBackOrCancel(node: AccessibilityNodeInfo): Boolean {
        val id = node.viewIdResourceName?.lowercase().orEmpty()
        val desc = node.contentDescription?.toString()?.lowercase().orEmpty()
        val text = node.text?.toString()?.lowercase().orEmpty()
        return id.contains("back") ||
            id.contains("cancel") ||
            id.contains("close") ||
            id.contains("action_mode_close") ||
            id.contains("navigate_up") ||
            desc.contains("back") ||
            desc.contains("cancel") ||
            text == "cancel" ||
            text == "back"
    }

    private fun statusBarHeightPx(): Int {
        val resId = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (resId > 0) resources.getDimensionPixelSize(resId) else 0
    }

    private fun isStatusBarOverlap(rect: Rect): Boolean {
        val h = statusBarHeightPx()
        if (h <= 0) return false
        return rect.top < h && rect.bottom > 0
    }

    private fun physicalKey(node: AccessibilityNodeInfo): String {
        val r = Rect()
        node.getBoundsInScreen(r)
        return "${node.viewIdResourceName}|$r"
    }

    private fun commitCapture(value: Double, pkg: String) {
        // Gate: only capture when the Smart popup (or its minimized bubble) is active.
        if (!SmartPopupState.isOpen) return
        // Suspend captures while the user is hand-editing the expression line.
        if (SmartPopupState.isEditorOpen) return
        val now = System.currentTimeMillis()
        // Brief cooldown after a full-clear so a long-press release doesn't immediately recapture.
        // Uses shared static state set synchronously by the popup (independent of broadcast).
        if (now - SmartPopupState.lastClearMs < CLEAR_COOLDOWN_MS) return
        if (value == lastCommitValue && now - lastCommitMs < SAME_VALUE_GUARD_MS) return
        lastCommitValue = value
        lastCommitMs = now

        HistoryManager.addEntry(value, source = pkg.substringAfterLast("."))
        sendBroadcast(Intent(FloatingWindowService.ACTION_ADD_NUMBER).apply {
            putExtra(FloatingWindowService.EXTRA_NUMBER, value)
            putExtra(FloatingWindowService.EXTRA_SOURCE, pkg)
        })
        vibrate()
    }

    private fun vibrate() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vm.defaultVibrator.vibrate(
                    VibrationEffect.createOneShot(VIBRATE_MS, VibrationEffect.DEFAULT_AMPLITUDE)
                )
            } else {
                @Suppress("DEPRECATION")
                val vib = getSystemService(VIBRATOR_SERVICE) as Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vib.vibrate(
                        VibrationEffect.createOneShot(VIBRATE_MS, VibrationEffect.DEFAULT_AMPLITUDE)
                    )
                } else {
                    @Suppress("DEPRECATION")
                    vib.vibrate(VIBRATE_MS)
                }
            }
        } catch (_: Exception) {}
    }

    private fun collectAllText(
        node: AccessibilityNodeInfo,
        depth: Int = 0,
        acc: StringBuilder = StringBuilder()
    ): String {
        if (depth > MAX_COLLECT_DEPTH) return acc.toString().trim()
        // Skip quoted/reply-preview blocks so only the actual reply text is captured
        if (depth > 0 && isQuotedPreviewNode(node)) return acc.toString().trim()
        node.text?.toString()?.trim()?.takeIf { it.isNotBlank() }?.let { frag ->
            if (acc.isNotEmpty()) acc.append(' ')
            acc.append(frag)
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectAllText(child, depth + 1, acc)
            child.recycle()
        }
        return acc.toString().trim()
    }

    private fun collectTextFromTextViewsDeep(
        node: AccessibilityNodeInfo,
        depth: Int = 0,
        acc: StringBuilder = StringBuilder()
    ): String {
        if (depth > MAX_COLLECT_DEPTH) return acc.toString().trim()
        // Skip quoted/reply-preview blocks so only the actual reply text is captured
        if (depth > 0 && isQuotedPreviewNode(node)) return acc.toString().trim()
        val cls = node.className?.toString()?.lowercase().orEmpty()
        if (cls.contains("textview")) {
            node.text?.toString()?.trim()?.takeIf { it.isNotBlank() }?.let { frag ->
                if (acc.isNotEmpty()) acc.append(' ')
                acc.append(frag)
            }
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectTextFromTextViewsDeep(child, depth + 1, acc)
            child.recycle()
        }
        return acc.toString().trim()
    }

    /**
     * Returns true if the node is a WhatsApp quoted/reply-preview container.
     * These nodes typically have viewIdResourceName containing 'quoted' or 'reply_preview'.
     * We skip these to avoid mixing quoted message text with the actual reply text.
     */
    private fun isQuotedPreviewNode(node: AccessibilityNodeInfo): Boolean {
        val id = node.viewIdResourceName?.lowercase().orEmpty()
        return id.contains("quoted") || id.contains("reply_preview") ||
               id.contains("quote_layout") || id.contains("quoted_message")
    }

    /**
     * Checks whether [node] itself is a quoted-preview node,
     * or is nested inside one.
     */
    private fun isNodeInsideQuotedBlock(node: AccessibilityNodeInfo): Boolean {
        if (isQuotedPreviewNode(node)) return true
        val ancestors = mutableListOf<AccessibilityNodeInfo>()
        var n: AccessibilityNodeInfo? = node.parent
        var found = false
        while (n != null && ancestors.size < 8) {
            ancestors.add(n)
            if (isQuotedPreviewNode(n)) { found = true; break }
            n = n.parent
        }
        for (a in ancestors) a.recycle()
        return found
    }

    /**
     * Walks up from [node] past quoted-preview ancestors and returns the first
     * non-quoted ancestor (the message container). This lets
     * [collectTextFromTextViewsDeep] skip the quoted children and extract
     * only the reply text. Caller must recycle the returned node.
     */
    private fun walkUpToMessageContainer(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val chain = mutableListOf<AccessibilityNodeInfo>()
        var n: AccessibilityNodeInfo? = node.parent
        var d = 0
        var lastQuoteIdx = -1
        while (n != null && d < MAX_ANCESTOR_WALK) {
            chain.add(n)
            if (isQuotedPreviewNode(n)) lastQuoteIdx = chain.size - 1
            n = n.parent
            d++
        }
        // The message container is the node right after the last quoted ancestor
        val resultIdx = if (lastQuoteIdx >= 0 && lastQuoteIdx + 1 < chain.size)
            lastQuoteIdx + 1 else -1
        var result: AccessibilityNodeInfo? = null
        for (i in chain.indices) {
            if (i == resultIdx) {
                result = chain[i] // caller will recycle
            } else {
                chain[i].recycle()
            }
        }
        return result
    }

    /** Converts Bengali digits (০-৯) to ASCII digits (0-9). */
    private fun convertBengaliToEnglishDigits(input: String): String = buildString(input.length) {
        for (c in input) {
            when (c) {
                '০' -> append('0')
                '১' -> append('1')
                '২' -> append('2')
                '৩' -> append('3')
                '৪' -> append('4')
                '৫' -> append('5')
                '৬' -> append('6')
                '৭' -> append('7')
                '৮' -> append('8')
                '৯' -> append('9')
                else -> append(c)
            }
        }
    }

    private fun extractSelectedText(node: AccessibilityNodeInfo): String {
        val text  = node.text?.toString() ?: return ""
        val start = node.textSelectionStart
        val end   = node.textSelectionEnd
        if (start < 0 || end <= start || end > text.length) return ""
        return text.substring(start, end)
    }

    /**
     * Parses a money amount out of [raw]. Rules:
     *  - Only tokens with [MIN_MONEY_DIGITS]–[MAX_MONEY_DIGITS] digits qualify (money range).
     *  - Two qualifying tokens adjacent via a lone `/` or `*` form a ratio/expression and
     *    are BOTH discarded (e.g. "756/656" → null).
     *  - Consecutive qualifying tokens adjacent via lone `+` form an addition chain whose
     *    sum is the result for that chain (e.g. "744+655" → 1399, "1;500+884" → 1384).
     *  - When multiple chains / standalone tokens remain, the rightmost (last) chain wins
     *    to preserve previous "last match" semantics.
     *  - Non-qualifying tokens (1–2 digit, 6+ digit) are ignored and do NOT break/extend
     *    chains of qualifying tokens around them.
     */
    private fun parseNumericValue(raw: String): Double? {
        // Convert Bengali digits to English first, then sanitize
        val withEnglishDigits = convertBengaliToEnglishDigits(raw)
        val sanitized = withEnglishDigits
            .replace(TIME_REGEX, " ")
            .replace(FORMATTED_PHONE_REGEX, " ")
            .replace(",", "")
            .trim()
        if (sanitized.isBlank()) return null

        data class Tok(val value: Double, val digits: Int, val start: Int, val end: Int)

        val tokens = NUMBER_REGEX.findAll(sanitized).mapNotNull { m ->
            val digits = m.value.count { it.isDigit() }
            if (digits == 0) null
            else m.value.toDoubleOrNull()?.let { v -> Tok(v, digits, m.range.first, m.range.last + 1) }
        }.toList()
        if (tokens.isEmpty()) return null

        fun qualifies(t: Tok) = t.digits in MIN_MONEY_DIGITS..MAX_MONEY_DIGITS

        // Indices (into `tokens`) of qualifying tokens, in order.
        val qualIdx = tokens.indices.filter { qualifies(tokens[it]) }
        if (qualIdx.isEmpty()) return null

        // Returns the trimmed operator string between two consecutive (in `tokens`) tokens,
        // or null if the tokens are not consecutive in the token list.
        fun opBetween(aIdx: Int, bIdx: Int): String? {
            if (bIdx != aIdx + 1) return null
            return sanitized.substring(tokens[aIdx].end, tokens[bIdx].start).trim()
        }

        // Step 1: exclude pairs of qualifying tokens joined by a lone `/` or `*`.
        val excluded = mutableSetOf<Int>()
        for (k in 0 until qualIdx.size - 1) {
            val a = qualIdx[k]
            val b = qualIdx[k + 1]
            val op = opBetween(a, b) ?: continue
            if (op == "/" || op == "*") {
                excluded.add(a); excluded.add(b)
            }
        }

        val remaining = qualIdx.filter { it !in excluded }
        if (remaining.isEmpty()) return null

        // Step 2: group remaining into `+`-chains. Two qualifying tokens belong to the same
        // chain iff they are consecutive in `tokens` AND joined by a lone `+`.
        val chains = mutableListOf<MutableList<Int>>()
        var current = mutableListOf<Int>()
        for (idx in remaining) {
            if (current.isEmpty()) {
                current.add(idx)
            } else {
                val prev = current.last()
                val op = opBetween(prev, idx)
                if (op == "+") {
                    current.add(idx)
                } else {
                    chains.add(current)
                    current = mutableListOf(idx)
                }
            }
        }
        if (current.isNotEmpty()) chains.add(current)

        // Step 3: pick the last (rightmost) chain; sum its tokens.
        val lastChain = chains.last()
        val value = lastChain.sumOf { tokens[it].value }
        return if (value.isFinite()) value else null
    }

    private fun findMessageContainer(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var n: AccessibilityNodeInfo? = node
        var d = 0
        while (n != null && d < MAX_ANCESTOR_WALK) {
            val id = n.viewIdResourceName?.lowercase().orEmpty()
            if (isMessageAnchorId(id)) {
                return AccessibilityNodeInfo.obtain(n)
            }
            val parent = n.parent
            if (n != node) n.recycle()
            n = parent
            d++
        }
        n?.recycle()
        return null
    }

    private fun getMessageTextAndValue(node: AccessibilityNodeInfo): Pair<String, Double>? {
        val container = findMessageContainer(node) ?: return null
        try {
            val text = collectTextFromTextViewsDeep(container).trim()
            if (text.isBlank()) return null
            val value = parseNumericValue(text) ?: return null
            return Pair(text, value)
        } finally {
            container.recycle()
        }
    }

    private fun isNodeOrFamilySelected(node: AccessibilityNodeInfo): Boolean {
        if (try { node.isSelected || node.isChecked } catch (_: Exception) { false }) {
            return true
        }
        var parent = node.parent
        var d = 0
        while (parent != null && d < MAX_ANCESTOR_WALK) {
            if (try { parent.isSelected || parent.isChecked } catch (_: Exception) { false }) {
                parent.recycle()
                return true
            }
            val p = parent.parent
            parent.recycle()
            parent = p
            d++
        }
        parent?.recycle()
        return false
    }

    private fun isNodeWithTextSelected(targetText: String): Boolean {
        val root = rootInActiveWindow ?: return false
        var isSelected = false
        fun dfs(n: AccessibilityNodeInfo, d: Int) {
            if (isSelected || d > MAX_ROOT_SCAN_DEPTH) return
            val text = collectTextFromTextViewsDeep(n).trim()
            if (text == targetText) {
                if (isNodeOrFamilySelected(n)) {
                    isSelected = true
                    return
                }
            }
            val cc = n.childCount
            for (i in 0 until cc) {
                val ch = n.getChild(i) ?: continue
                dfs(ch, d + 1)
                ch.recycle()
            }
        }
        try {
            dfs(root, 0)
        } finally {
            root.recycle()
        }
        return isSelected
    }

    private fun findNodeByPhysicalKey(root: AccessibilityNodeInfo, targetKey: String): AccessibilityNodeInfo? {
        var found: AccessibilityNodeInfo? = null
        fun dfs(n: AccessibilityNodeInfo, d: Int) {
            if (found != null || d > MAX_ROOT_SCAN_DEPTH) return
            if (physicalKey(n) == targetKey) {
                found = AccessibilityNodeInfo.obtain(n)
                return
            }
            val cc = n.childCount
            for (i in 0 until cc) {
                val ch = n.getChild(i) ?: continue
                dfs(ch, d + 1)
                ch.recycle()
            }
        }
        dfs(root, 0)
        return found
    }

    private fun processClickOrLongClickInSelectionMode(node: AccessibilityNodeInfo, pkg: String) {
        if (pkg in WHATSAPP_PACKAGES) {
            val container = findMessageContainer(node) ?: return
            val key = physicalKey(container)
            val pair = getMessageTextAndValue(node)
            if (pair == null) {
                container.recycle()
                return
            }
            val text = pair.first
            val value = pair.second
            container.recycle()

            handler.postDelayed({
                if (!isSelectionMode) return@postDelayed
                val root = rootInActiveWindow
                if (root != null) {
                    try {
                        val foundNode = findNodeByPhysicalKey(root, key)
                        if (foundNode != null) {
                            try {
                                if (isNodeOrFamilySelected(foundNode)) {
                                    val tuple = selectionTuple(text, value)
                                    if (tuple !in committedSelectionTuples) {
                                        committedSelectionTuples.add(tuple)
                                        commitCapture(value, pkg)
                                    }
                                } else {
                                    val tuple = selectionTuple(text, value)
                                    if (tuple in committedSelectionTuples) {
                                        committedSelectionTuples.remove(tuple)
                                        HistoryManager.removeEntry(value)
                                    }
                                }
                            } finally {
                                foundNode.recycle()
                            }
                        }
                    } finally {
                        root.recycle()
                    }
                }
            }, 150L)
        } else {
            // Other apps: capture numeric value directly from the clicked node text
            val raw = collectAllText(node).trim()
            if (raw.isBlank()) return
            val value = parseNumericValue(raw) ?: return
            val key = physicalKey(node)
            val tuple = selectionTuple(key, value)
            if (tuple !in committedSelectionTuples) {
                committedSelectionTuples.add(tuple)
                commitCapture(value, pkg)
            }
        }
    }
}

