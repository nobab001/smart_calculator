package com.example.smartcalculator

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.WindowManager.LayoutParams
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.widget.ImageViewCompat
import com.google.android.material.button.MaterialButton
import net.objecthunter.exp4j.ExpressionBuilder
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Foreground service that renders floating overlay windows via [WindowManager].
 *
 * Three states:
 *  1. MANUAL   – compact calculator, manual input
 *  2. SMART    – small total-display, populated by [SmartAccessibilityService]
 *  3. BUBBLE   – minimised circle at screen edge
 */
class FloatingWindowService : Service() {

    // ── Public API ────────────────────────────────
    companion object {
        const val ACTION_SHOW_MANUAL  = "com.example.smartcalculator.SHOW_MANUAL"
        const val ACTION_SHOW_SMART   = "com.example.smartcalculator.SHOW_SMART"
        const val ACTION_ADD_NUMBER   = "com.example.smartcalculator.ADD_NUMBER"
        const val ACTION_DOCK         = "com.example.smartcalculator.DOCK"
        const val ACTION_CLEAR_SMART  = "com.example.smartcalculator.CLEAR_SMART"
        const val ACTION_UNDO_SMART   = "com.example.smartcalculator.UNDO_SMART"
        const val EXTRA_NUMBER        = "extra_number"
        const val EXTRA_SOURCE        = "extra_source"

        private const val CHANNEL_ID = "smartcalc_float"
        private const val NOTIF_ID   = 9001
    }

    // ── Window state ──────────────────────────────
    private lateinit var wm: WindowManager
    private var floatView: View? = null
    private var bubbleView: View? = null
    private var currentMode = ""        // "manual" | "smart" | "bubble"
    private var preMinimiseMode = ""    // mode to restore when bubble is tapped
    private var bubbleLastX = 0
    private var bubbleLastY = 300

    // ── Multiple Manual Calculator Instances ──────────────────────────────
    private val manualInstances = mutableListOf<ManualInstance>()

    private class ManualInstance(val id: Int) {
        var floatView: View? = null
        var bubbleView: View? = null
        var params: WindowManager.LayoutParams? = null
        var bubbleParams: WindowManager.LayoutParams? = null
        var isMinimized = false
        var titleText = "Calculator"

        val floatExprDisplay = StringBuilder()
        val floatExprCalc = StringBuilder()
        var floatHasDecimal = false
        var floatJustEquals = false

        var bubbleLastX = 80 + (id - 1) * 100
        var bubbleLastY = 300 + (id - 1) * 150
        var bubblePositionSaved = false

        var widthPx: Int = -1
        var heightPx: Int = -1
        var lastX: Int = -1
        var lastY: Int = -1
    }

    // ── Receivers ─────────────────────────────────
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                ACTION_ADD_NUMBER -> {
                    refreshSmartDisplay()
                    flashSmartTotal()   // visual feedback on each new selection
                }
                ACTION_DOCK -> if (currentMode != "bubble") dockToBubble()
            }
        }
    }

    // ─────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification())

        val filter = IntentFilter().apply {
            addAction(ACTION_ADD_NUMBER)
            addAction(ACTION_DOCK)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            registerReceiver(receiver, filter, RECEIVER_NOT_EXPORTED)
        else
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(receiver, filter)

        HistoryManager.onChanged = { refreshSmartDisplay() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SHOW_MANUAL -> showManual()
            ACTION_SHOW_SMART  -> showSmart()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        SmartPopupState.isOpen = false
        manualInstances.forEach {
            removeInstanceFloat(it)
            removeInstanceBubble(it)
        }
        manualInstances.clear()
        removeFloat()
        removeBubble()
        unregisterReceiver(receiver)
        HistoryManager.onChanged = null
    }

    // ─────────────────────────────────────────────
    // Manual mode
    // ─────────────────────────────────────────────

    private fun showManual() {
        SmartPopupState.isOpen = false
        removeFloat() // removes smart mode float
        currentMode = "manual"
        preMinimiseMode = "manual"

        if (manualInstances.isEmpty()) {
            val first = ManualInstance(id = 1)
            manualInstances.add(first)
        }
        
        manualInstances.forEach {
            showManualInstance(it)
        }
    }

    private fun showManualInstance(instance: ManualInstance) {
        if (instance.floatView != null && !instance.isMinimized) return

        val restoringFromBubble = instance.isMinimized
        if (instance.isMinimized) {
            removeInstanceBubble(instance)
            instance.isMinimized = false
        }

        val view = inflate(R.layout.layout_floating_manual)
        instance.floatView = view

        applyPopupTheme(view, PopupThemeManager.getManualTheme(this), isManual = true)

        // Only update titles when creating (not restoring), to preserve custom titles
        if (!restoringFromBubble) {
            updateInstanceTitles()
        }

        // Set the title
        view.findViewById<TextView>(R.id.tvFloatTitle)?.text = instance.titleText

        val params = buildParams(300, 360).apply {
            if (instance.widthPx != -1) width = instance.widthPx
            if (instance.heightPx != -1) height = instance.heightPx
            
            x = if (instance.lastX != -1) instance.lastX else 80 + (instance.id - 1) * 60
            y = if (instance.lastY != -1) instance.lastY else 200 + (instance.id - 1) * 80
        }
        instance.params = params

        wireManualInstanceButtons(instance, view)
        makeDraggable(view.findViewById(R.id.floatManualHeader), view, params)
        
        val resizeBottom = view.findViewById<View>(R.id.resizeBottom)
        if (resizeBottom != null) {
            attachManualResizeHandles(resizeBottom, view, params)
        }
        
        wm.addView(view, params)
    }

    private fun updateInstanceTitles() {
        if (manualInstances.size > 1) {
            manualInstances.forEachIndexed { index, inst ->
                if (inst.titleText == "Calculator") {
                    inst.titleText = "Calculator${index + 1}"
                    inst.floatView?.findViewById<TextView>(R.id.tvFloatTitle)?.text = inst.titleText
                }
            }
        } else if (manualInstances.size == 1) {
            val inst = manualInstances[0]
            if (inst.titleText.matches(Regex("Calculator\\d+"))) {
                inst.titleText = "Calculator"
                inst.floatView?.findViewById<TextView>(R.id.tvFloatTitle)?.text = inst.titleText
            }
        }
    }

    private fun cloneManualInstance(sourceInstance: ManualInstance) {
        if (manualInstances.size >= 2) {
            Toast.makeText(this, "Maximum 2 calculators allowed", Toast.LENGTH_SHORT).show()
            return
        }
        val newId = if (manualInstances.any { it.id == 1 }) 2 else 1
        val newInstance = ManualInstance(newId)
        
        // Copy the size and offset position from sourceInstance
        sourceInstance.params?.let { p ->
            newInstance.widthPx = p.width
            newInstance.heightPx = p.height
            newInstance.lastX = p.x + dpToPx(30)
            newInstance.lastY = p.y + dpToPx(30)
        }
        
        manualInstances.add(newInstance)
        showManualInstance(newInstance)
    }

    private fun dockInstanceToBubble(instance: ManualInstance) {
        if (instance.isMinimized) return
        instance.isMinimized = true

        // Save current window size and coordinates before removing float view
        instance.params?.let { p ->
            instance.widthPx = p.width
            instance.heightPx = p.height
            instance.lastX = p.x
            instance.lastY = p.y
        }

        removeInstanceFloat(instance)

        val view = inflate(R.layout.layout_floating_bubble)
        instance.bubbleView = view

        val params = buildParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.TOP or Gravity.START
            x = instance.bubbleLastX
            y = instance.bubbleLastY
        }
        instance.bubbleParams = params

        val bubbleText = getBubbleText(instance)
        val tvBubble = view.findViewById<TextView>(R.id.tvBubbleText)
        tvBubble?.text = bubbleText

        // Dynamic text size based on digit count
        val digitCount = bubbleText.count { it.isDigit() }
        val textSizeSp = when {
            digitCount <= 3 -> 17f
            digitCount <= 5 -> 14f
            else -> 15f
        }
        tvBubble?.textSize = textSizeSp

        // Snap to right edge on first minimize
        if (!instance.bubblePositionSaved) {
            view.post {
                val screenW = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
                    wm.currentWindowMetrics.bounds.width()
                else
                    @Suppress("DEPRECATION") wm.defaultDisplay.width
                val bubbleW = if (view.width > 0) view.width else dpToPx(36)
                params.x = screenW - bubbleW
                try { wm.updateViewLayout(view, params) } catch (_: Exception) {}
                instance.bubbleLastX = params.x
                instance.bubbleLastY = params.y
                instance.bubblePositionSaved = true
            }
        }

        var initX = 0; var initY = 0; var initRx = 0f; var initRy = 0f; var moved = false

        view.setOnTouchListener { _, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    initX = params.x; initY = params.y
                    initRx = ev.rawX; initRy = ev.rawY
                    moved = false; true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initX + (ev.rawX - initRx).toInt()
                    params.y = initY + (ev.rawY - initRy).toInt()
                    try { wm.updateViewLayout(view, params) } catch (_: Exception) {}
                    if (Math.abs(ev.rawX - initRx) > 8 || Math.abs(ev.rawY - initRy) > 8) moved = true
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        instance.bubbleLastX = params.x
                        instance.bubbleLastY = params.y
                        removeInstanceBubble(instance)
                        showManualInstance(instance)
                    } else {
                        snapInstanceBubble(instance, params)
                    }
                    false
                }
                else -> false
            }
        }

        wm.addView(view, params)
    }

    private fun snapInstanceBubble(instance: ManualInstance, params: LayoutParams) {
        val bv = instance.bubbleView ?: return
        val screenW = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            wm.currentWindowMetrics.bounds.width()
        else
            @Suppress("DEPRECATION") wm.defaultDisplay.width
        val bubbleW = if (bv.width > 0) bv.width else dpToPx(36)
        params.x = if (params.x + bubbleW / 2 < screenW / 2) 0 else screenW - bubbleW
        try { wm.updateViewLayout(bv, params) } catch (_: Exception) {}
        instance.bubbleLastX = params.x
        instance.bubbleLastY = params.y
        instance.bubblePositionSaved = true
    }

    private fun getBubbleText(instance: ManualInstance): String {
        val calcExpr = instance.floatExprCalc.toString()

        fun formatWithLimit(value: String): String {
            val formatted = fmtNum(value)
            val digitCount = formatted.count { it.isDigit() }
            return if (digitCount > 5) instance.id.toString() else formatted
        }

        // No expression typed — show serial number
        if (calcExpr.isEmpty()) return instance.id.toString()

        // Equals pressed — show final result
        if (instance.floatJustEquals) {
            val total = instance.floatExprDisplay.toString().ifEmpty { "0" }
            return formatWithLimit(total)
        }

        // Expression in progress — evaluate what we can
        val endsWithOp = calcExpr.lastOrNull()?.let { it == '+' || it == '-' || it == '*' || it == '/' } ?: false
        val evalExpr = if (endsWithOp) calcExpr.dropLast(1) else calcExpr
        if (evalExpr.isNotEmpty()) {
            val result = CalculatorEngine.eval(evalExpr)
            if (!result.isNaN() && !result.isInfinite()) {
                return formatWithLimit(fmtResult(result))
            }
        }

        // Fallback: show current number segment
        val displayExpr = instance.floatExprDisplay.toString()
        val lastOp = displayExpr.indexOfLast { it == '+' || it == '\u2212' || it == '\u00d7' || it == '\u00f7' }
        val currentNum = if (lastOp < 0) displayExpr else displayExpr.substring(lastOp + 1)
        if (currentNum.isNotEmpty() && currentNum != "-") return formatWithLimit(currentNum)

        return instance.id.toString()
    }

    private fun removeInstanceFloat(instance: ManualInstance) {
        instance.floatView?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        instance.floatView = null
    }

    private fun removeInstanceBubble(instance: ManualInstance) {
        instance.bubbleView?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        instance.bubbleView = null
    }

    private fun resetCalcState(instance: ManualInstance) {
        instance.floatExprDisplay.clear()
        instance.floatExprCalc.clear()
        instance.floatHasDecimal = false
        instance.floatJustEquals = false
    }

    private fun stopSelfService() {
        removeFloat()
        removeBubble()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)
            stopForeground(STOP_FOREGROUND_REMOVE)
        else
            @Suppress("DEPRECATION") stopForeground(true)
        stopSelf()
    }

    private fun wireManualInstanceButtons(instance: ManualInstance, v: View) {
        val display  = v.findViewById<TextView>(R.id.tvFloatResult)
        val exprView = v.findViewById<TextView>(R.id.tvFloatExpression)
        val svExpr   = v.findViewById<android.widget.HorizontalScrollView>(R.id.svFloatExpression)
        val opView   = v.findViewById<TextView>(R.id.tvFloatOperator)
        
        val tvTitle  = v.findViewById<TextView>(R.id.tvFloatTitle)
        val etTitle  = v.findViewById<android.widget.EditText>(R.id.etFloatTitle)

        // ── helpers ──────────────────────────────────────────────────────

        fun currentNumber(): String {
            val expr = instance.floatExprDisplay.toString()
            val lastOp = expr.indexOfLast { it == '+' || it == '−' || it == '×' || it == '÷' }
            return if (lastOp < 0) expr else expr.substring(lastOp + 1)
        }

        fun endsWithOp() = instance.floatExprCalc.lastOrNull()
            ?.let { it == '+' || it == '-' || it == '*' || it == '/' } ?: false

        fun toCalcOp(op: String) = CalculatorEngine.toCalcOp(op)

        fun countBinaryOperators(expr: String): Int {
            var count = 0
            for (i in expr.indices) {
                val c = expr[i]
                if (c == '+' || c == '*' || c == '/') {
                    count++
                } else if (c == '-') {
                    if (i > 0) {
                        val prev = expr[i - 1]
                        if (prev.isDigit() || prev == '.') {
                            count++
                        }
                    }
                }
            }
            return count
        }

        fun update() {
            val expr = instance.floatExprDisplay.toString()
            val calcExpr = instance.floatExprCalc.toString()
            val lastOp = expr.indexOfLast { it == '+' || it == '−' || it == '×' || it == '÷' }

            if (instance.floatJustEquals) {
                display.text  = fmtNum(instance.floatExprDisplay.toString().ifEmpty { "0" })
                exprView.text = ""
                opView?.text  = "="
            } else {
                if (endsWithOp()) {
                    val opCount = countBinaryOperators(calcExpr)
                    if (opCount >= 2) {
                        val subExpr = calcExpr.substring(0, calcExpr.length - 1)
                        val result = CalculatorEngine.eval(subExpr)
                        if (result.isNaN() || result.isInfinite()) {
                            display.text = "Error"
                        } else {
                            display.text = fmtNum(fmtResult(result))
                        }
                        opView?.text = "="
                    } else {
                        val subExpr = calcExpr.substring(0, calcExpr.length - 1)
                        display.text = fmtNum(subExpr.ifEmpty { "0" })
                        opView?.text = if (lastOp >= 0) expr[lastOp].toString() else ""
                    }
                } else {
                    val cur = currentNumber()
                    display.text = fmtNum(cur.ifEmpty { "0" })
                    opView?.text = if (lastOp >= 0) expr[lastOp].toString() else ""
                }
                exprView.text = if (lastOp >= 0) expr.substring(0, lastOp + 1) else ""
            }
            svExpr?.post {
                svExpr.fullScroll(android.view.View.FOCUS_RIGHT)
            }
        }

        // ── input handlers ───────────────────────────────────────────────

        fun onDigit(d: String) {
            if (instance.floatJustEquals) {
                instance.floatExprDisplay.clear(); instance.floatExprCalc.clear()
                instance.floatHasDecimal = false; instance.floatJustEquals = false
            }
            if (instance.floatExprCalc.length < 1000) {
                instance.floatExprDisplay.append(d); instance.floatExprCalc.append(d)
            }
            update()
        }

        fun onOperator(op: String) {
            if (instance.floatExprCalc.isEmpty() && op == "−") {
                instance.floatExprDisplay.append("−"); instance.floatExprCalc.append("-")
                update(); return
            }
            if (instance.floatExprCalc.isEmpty()) return
            if (instance.floatJustEquals) instance.floatJustEquals = false
            if (endsWithOp()) {
                instance.floatExprDisplay.deleteCharAt(instance.floatExprDisplay.length - 1)
                instance.floatExprCalc.deleteCharAt(instance.floatExprCalc.length - 1)
            }
            instance.floatExprDisplay.append(op)
            instance.floatExprCalc.append(toCalcOp(op))
            instance.floatHasDecimal = false
            update()
        }

        fun onEquals() {
            if (instance.floatExprCalc.isEmpty()) return
            if (endsWithOp()) {
                instance.floatExprDisplay.deleteCharAt(instance.floatExprDisplay.length - 1)
                instance.floatExprCalc.deleteCharAt(instance.floatExprCalc.length - 1)
            }
            if (instance.floatExprCalc.isEmpty()) return
            val result = CalculatorEngine.eval(instance.floatExprCalc.toString())
            if (result.isNaN() || result.isInfinite()) {
                display.text = "Error"; exprView.text = ""; return
            }
            val resultStr = fmtResult(result)
            instance.floatExprDisplay.clear(); instance.floatExprDisplay.append(resultStr)
            instance.floatExprCalc.clear();    instance.floatExprCalc.append(resultStr)
            instance.floatHasDecimal = resultStr.contains('.')
            instance.floatJustEquals = true
            update()
        }

        fun onBackspace() {
            if (instance.floatJustEquals) {
                resetCalcState(instance); display.text = "0"; exprView.text = ""; return
            }
            if (instance.floatExprDisplay.isEmpty()) return
            val removed = instance.floatExprDisplay.last()
            instance.floatExprDisplay.deleteCharAt(instance.floatExprDisplay.length - 1)
            instance.floatExprCalc.deleteCharAt(instance.floatExprCalc.length - 1)
            instance.floatHasDecimal = if (removed == '.') false else currentNumber().contains('.')
            update()
        }

        // ── button wiring ─────────────────────────────────────────────────

        val digitMap = mapOf(
            R.id.btnFloat0 to "0", R.id.btnFloat1 to "1", R.id.btnFloat2 to "2",
            R.id.btnFloat3 to "3", R.id.btnFloat4 to "4", R.id.btnFloat5 to "5",
            R.id.btnFloat6 to "6", R.id.btnFloat7 to "7", R.id.btnFloat8 to "8",
            R.id.btnFloat9 to "9"
        )
        digitMap.forEach { (id, d) ->
            v.findViewById<MaterialButton>(id).setOnClickListener { onDigit(d) }
        }

        v.findViewById<MaterialButton>(R.id.btnFloatClear).setOnClickListener     { resetCalcState(instance); update() }
        v.findViewById<MaterialButton>(R.id.btnFloatBackspace).setOnClickListener { onBackspace() }
        v.findViewById<MaterialButton>(R.id.btnFloatAdd).setOnClickListener       { onOperator("+") }
        v.findViewById<MaterialButton>(R.id.btnFloatSubtract).setOnClickListener  { onOperator("−") }
        v.findViewById<MaterialButton>(R.id.btnFloatMultiply).setOnClickListener  { onOperator("×") }
        v.findViewById<MaterialButton>(R.id.btnFloatEquals).setOnClickListener    { onEquals() }

        v.findViewById<ImageButton>(R.id.btnFloatClone).setOnClickListener {
            cloneManualInstance(instance)
        }

        v.findViewById<ImageButton>(R.id.btnFloatMinimize).setOnClickListener { dockInstanceToBubble(instance) }
        v.findViewById<ImageButton>(R.id.btnFloatClose).setOnClickListener {
            removeInstanceFloat(instance)
            removeInstanceBubble(instance)
            manualInstances.remove(instance)
            if (manualInstances.isEmpty()) {
                stopSelfService()
            } else {
                updateInstanceTitles()
            }
        }

        // ── Title Editing Logic ──

        fun saveAndCloseEditor() {
            if (etTitle == null || tvTitle == null) return
            val newTitle = etTitle.text.toString().trim()
            if (newTitle.isNotEmpty()) {
                instance.titleText = newTitle
                tvTitle.text = newTitle
            }
            etTitle.visibility = View.GONE
            tvTitle.visibility = View.VISIBLE
            
            instance.params?.let { p ->
                p.flags = p.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                try { wm.updateViewLayout(v, p) } catch (_: Exception) {}
            }

            val imm = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            imm.hideSoftInputFromWindow(etTitle.windowToken, 0)
        }

        if (tvTitle != null && etTitle != null) {
            var initX = 0
            var initY = 0
            var initRx = 0f
            var initRy = 0f
            var lastClickTime = 0L
            var isDragging = false

            tvTitle.setOnTouchListener { _, ev ->
                when (ev.action) {
                    MotionEvent.ACTION_DOWN -> {
                        instance.params?.let { p ->
                            initX = p.x
                            initY = p.y
                        }
                        initRx = ev.rawX
                        initRy = ev.rawY
                        isDragging = false
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = ev.rawX - initRx
                        val dy = ev.rawY - initRy
                        if (!isDragging && (Math.abs(dx) > 8 || Math.abs(dy) > 8)) {
                            isDragging = true
                        }
                        if (isDragging) {
                            instance.params?.let { p ->
                                p.x = initX + dx.toInt()
                                p.y = initY + dy.toInt()
                                try { wm.updateViewLayout(v, p) } catch (_: Exception) {}
                            }
                        }
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        val clickTime = System.currentTimeMillis()
                        val dx = ev.rawX - initRx
                        val dy = ev.rawY - initRy
                        if (Math.abs(dx) <= 8 && Math.abs(dy) <= 8 && !isDragging) {
                            if (clickTime - lastClickTime < 300L) {
                                // 1. Make window focusable FIRST
                                instance.params?.let { p ->
                                    p.flags = p.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
                                    try { wm.updateViewLayout(v, p) } catch (_: Exception) {}
                                }

                                // 2. Show EditText with current text
                                tvTitle.visibility = View.GONE
                                etTitle.visibility = View.VISIBLE
                                etTitle.setText(instance.titleText)

                                // 3. After WindowManager processes flag change: focus + select all + keyboard
                                etTitle.postDelayed({
                                    etTitle.requestFocus()
                                    etTitle.selectAll()
                                    val imm = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                                    imm.showSoftInput(etTitle, android.view.inputmethod.InputMethodManager.SHOW_FORCED)
                                }, 120)
                            }
                            lastClickTime = clickTime
                        }
                        true
                    }
                    else -> false
                }
            }

            etTitle.setOnFocusChangeListener { _, hasFocus ->
                if (!hasFocus) {
                    saveAndCloseEditor()
                }
            }

            etTitle.setOnEditorActionListener { _, actionId, _ ->
                if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                    saveAndCloseEditor()
                    true
                } else false
            }

            val contentView = v.findViewById<View>(R.id.manualFloatContent)
            contentView?.setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_DOWN) {
                    if (etTitle.hasFocus()) {
                        contentView.requestFocus()
                    }
                }
                false
            }
        }

        update()
    }

    // ─────────────────────────────────────────────
    // Smart mode
    // ─────────────────────────────────────────────

    private fun showSmart() {
        SmartPopupState.isOpen = true
        removeFloat()
        currentMode = "smart"
        preMinimiseMode = "smart"

        val view = inflate(R.layout.layout_floating_smart)
        floatView = view

        applyPopupTheme(view, PopupThemeManager.getSmartTheme(this), isManual = false)

        val params = buildParams(210, 220)
        wireSmartButtons(view)
        makeDraggable(view.findViewById(R.id.smartHeader), view, params)

        val resizeRight = view.findViewById<View>(R.id.smartResizeRight)
        val resizeLeft = view.findViewById<View>(R.id.smartResizeLeft)
        if (resizeRight != null && resizeLeft != null) {
            attachSmartResizeHandles(resizeRight, resizeLeft, view, params)
        }

        wm.addView(view, params)

        refreshSmartDisplay()
    }

    private fun wireSmartButtons(v: View) {
        val histPanel = v.findViewById<LinearLayout>(R.id.layoutHistoryPanel)
        val histItems = v.findViewById<LinearLayout>(R.id.layoutHistoryItems)
        val tvHTotal  = v.findViewById<TextView>(R.id.tvHistoryTotal)

        // ── Inline expression editor (tap on history line opens keyboard) ──
        val scrollExpr = v.findViewById<android.widget.HorizontalScrollView>(R.id.scrollSmartExpression)
        val etExpr = v.findViewById<SmartExpressionEditText>(R.id.etSmartExpression)

        fun closeExpressionEditor(commit: Boolean) {
            if (etExpr.visibility != View.VISIBLE) return
            if (commit) {
                applyExpressionEdit(etExpr.text?.toString().orEmpty())
            }
            etExpr.visibility = View.GONE
            scrollExpr?.visibility = View.VISIBLE
            SmartPopupState.isEditorOpen = false

            // Restore non-focusable window so popup doesn't steal global focus.
            val params = v.layoutParams as? WindowManager.LayoutParams
            if (params != null) {
                params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                try { wm.updateViewLayout(v, params) } catch (_: Exception) {}
            }
            val imm = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            imm.hideSoftInputFromWindow(etExpr.windowToken, 0)

            refreshSmartDisplay()
        }

        fun openExpressionEditor() {
            if (etExpr.visibility == View.VISIBLE) return
            val current = HistoryManager.entries.joinToString("+") { HistoryManager.fmt(it.value) }
            etExpr.setText(current)
            etExpr.setSelection(etExpr.text?.length ?: 0)

            scrollExpr?.visibility = View.GONE
            etExpr.visibility = View.VISIBLE
            SmartPopupState.isEditorOpen = true

            // Make window focusable so the soft keyboard can deliver input here.
            val params = v.layoutParams as? WindowManager.LayoutParams
            if (params != null) {
                params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
                try { wm.updateViewLayout(v, params) } catch (_: Exception) {}
            }
            etExpr.postDelayed({
                etExpr.requestFocus()
                etExpr.setSelection(etExpr.text?.length ?: 0)
                val imm = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                imm.showSoftInput(etExpr, android.view.inputmethod.InputMethodManager.SHOW_FORCED)
            }, 120)
        }

        // Tapping anywhere in the expression area (after the last token, between
        // tokens, or in the surrounding padding) opens the inline editor.
        // HorizontalScrollView's own onClick is unreliable here, so use a
        // GestureDetector on the parent FrameLayout that wraps both the scroll
        // and the EditText overlay.
        val exprArea = v.findViewById<FrameLayout>(R.id.smartExpressionArea)
        val exprItems = v.findViewById<LinearLayout>(R.id.layoutSmartExpressionItems)
        val tapDetector = android.view.GestureDetector(
            this,
            object : android.view.GestureDetector.SimpleOnGestureListener() {
                override fun onSingleTapUp(e: android.view.MotionEvent): Boolean {
                    openExpressionEditor()
                    return true
                }
            }
        )
        val tapTouchListener = View.OnTouchListener { _, ev ->
            // Don't swallow events when the editor is already showing — let
            // the EditText receive its own touches.
            if (etExpr.visibility == View.VISIBLE) return@OnTouchListener false
            tapDetector.onTouchEvent(ev)
            // Returning false lets HorizontalScrollView still process scrolls;
            // the GestureDetector will only fire onSingleTapUp for taps.
            false
        }
        exprArea?.setOnTouchListener(tapTouchListener)
        scrollExpr?.setOnTouchListener(tapTouchListener)
        exprItems?.setOnTouchListener(tapTouchListener)

        etExpr.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                closeExpressionEditor(commit = true)
                true
            } else false
        }
        etExpr.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) closeExpressionEditor(commit = true)
        }


        // Copy: single click → compact "520+313+375" (paste into any calculator)
        //        long press   → full  "520 + 313 + 375 = 3643" (human-readable)
        val btnCopy = v.findViewById<MaterialButton>(R.id.btnSmartAction)
        btnCopy.setOnClickListener {
            val text = if (HistoryManager.hasEntries())
                HistoryManager.calcExpressionString()
            else
                HistoryManager.formattedTotal()
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("expr", text))
            Toast.makeText(this, "Copied: $text", Toast.LENGTH_SHORT).show()
        }
        btnCopy.setOnLongClickListener {
            val expr   = HistoryManager.expressionString()
            val result = HistoryManager.formattedTotal()
            val text   = if (HistoryManager.hasEntries()) "$expr = $result" else result
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("result", text))
            Toast.makeText(this, "Copied full: $result", Toast.LENGTH_SHORT).show()
            true
        }

        // Undo: removes the last captured selection
        v.findViewById<MaterialButton>(R.id.btnSmartUndo).setOnClickListener {
            val removed = HistoryManager.removeLast()
            if (removed == null) {
                Toast.makeText(this, "Nothing to undo", Toast.LENGTH_SHORT).show()
            } else {
                sendBroadcast(Intent(ACTION_UNDO_SMART))
                val fmt = java.math.BigDecimal(removed)
                    .setScale(4, java.math.RoundingMode.HALF_UP)
                    .stripTrailingZeros().toPlainString()
                Toast.makeText(this, "Removed: $fmt", Toast.LENGTH_SHORT).show()
            }
        }

        v.findViewById<MaterialButton>(R.id.btnSmartUndo).setOnLongClickListener {
            SmartPopupState.lastClearMs = System.currentTimeMillis()
            HistoryManager.clear()
            histPanel.visibility = View.GONE
            sendBroadcast(Intent(ACTION_CLEAR_SMART).setPackage(packageName))
            Toast.makeText(this, "Session cleared", Toast.LENGTH_SHORT).show()
            true
        }

        v.findViewById<MaterialButton>(R.id.btnClearHistory).setOnClickListener {
            SmartPopupState.lastClearMs = System.currentTimeMillis()
            HistoryManager.clear()
            histPanel.visibility = View.GONE
            sendBroadcast(Intent(ACTION_CLEAR_SMART).setPackage(packageName))
            Toast.makeText(this, "Session cleared", Toast.LENGTH_SHORT).show()
        }

        val tvSmartTotal = v.findViewById<TextView>(R.id.tvSmartTotal)
        val tvSmartCount = v.findViewById<TextView>(R.id.tvSmartCount)
        val copyTotalAction = {
            val result = HistoryManager.formattedTotal()
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("result", result))
            Toast.makeText(this, "Copied total: $result", Toast.LENGTH_SHORT).show()
        }
        if (tvSmartTotal != null) {
            setDoubleClickListener(tvSmartTotal, copyTotalAction)
        }
        if (tvSmartCount != null) {
            setDoubleClickListener(tvSmartCount, copyTotalAction)
        }


        v.findViewById<ImageButton>(R.id.btnSmartMinimize).setOnClickListener { dockToBubble() }
        v.findViewById<ImageButton>(R.id.btnSmartClose).setOnClickListener {
            SmartPopupState.isOpen = false
            removeFloat()
            removeBubble()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)
                stopForeground(STOP_FOREGROUND_REMOVE)
            else
                @Suppress("DEPRECATION") stopForeground(true)
            stopSelf()
        }
    }

    /**
     * Commits a manually-edited expression string back into [HistoryManager].
     * Pure additive/subtractive expressions are split into signed entries
     * (preserving the running breakdown). Expressions with `*` or `/` are
     * evaluated end-to-end and stored as a single result entry.
     */
    private fun applyExpressionEdit(raw: String) {
        val expr = raw.trim()
        if (expr.isEmpty()) {
            HistoryManager.clear()
            return
        }
        val hasMulDiv = expr.contains('*') || expr.contains('/')
        if (hasMulDiv) {
            val result = CalculatorEngine.eval(expr.trimEnd('+', '-', '*', '/', '.'))
            HistoryManager.clear()
            if (!result.isNaN() && !result.isInfinite()) {
                HistoryManager.addEntry(result)
            }
            return
        }
        // Split into signed addends: e.g. "744+655-100+30" -> [744, 655, -100, 30]
        val entries = mutableListOf<Double>()
        var sign = 1.0
        val buf = StringBuilder()
        fun flush() {
            if (buf.isEmpty()) return
            val v = buf.toString().toDoubleOrNull()
            if (v != null && v != 0.0) entries.add(sign * v)
            else if (v != null) entries.add(0.0)
            buf.clear()
        }
        for (c in expr) {
            when (c) {
                '+' -> { flush(); sign = 1.0 }
                '-' -> { flush(); sign = -1.0 }
                else -> buf.append(c)
            }
        }
        flush()
        HistoryManager.clear()
        for (v in entries) HistoryManager.addEntry(v)
    }

    private fun refreshSmartDisplay() {
        val v = floatView ?: return
        if (currentMode != "smart") return

        v.post {
            // Total
            v.findViewById<TextView>(R.id.tvSmartTotal)?.text = HistoryManager.formattedTotal()

            // Selection count label
            val n = HistoryManager.count
            v.findViewById<TextView>(R.id.tvSmartCount)?.text =
                if (n == 0) "" else "$n selection${if (n > 1) "s" else ""}"

            // Per-entry expression items inside HorizontalScrollView
            val exprContainer = v.findViewById<LinearLayout>(R.id.layoutSmartExpressionItems)
            val scrollExpr = v.findViewById<android.widget.HorizontalScrollView>(R.id.scrollSmartExpression)
            if (exprContainer != null) {
                exprContainer.removeAllViews()
                val entries = HistoryManager.entries
                val isDark = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
                val exprColor = if (isDark) android.graphics.Color.parseColor("#80FFFFFF") else android.graphics.Color.parseColor("#80000000")

                entries.forEachIndexed { i, entry ->
                    // Separator " + " before each item except the first
                    if (i > 0) {
                        val sep = TextView(this@FloatingWindowService).apply {
                            text = " + "
                            textSize = 18f
                            setTextColor(exprColor)
                            typeface = android.graphics.Typeface.create("sans-serif-light", android.graphics.Typeface.NORMAL)
                        }
                        exprContainer.addView(sep)
                    }

                    // Number token
                    val numTv = TextView(this@FloatingWindowService).apply {
                        text = HistoryManager.fmt(entry.value)
                        textSize = 18f
                        setTextColor(exprColor)
                        typeface = android.graphics.Typeface.create("sans-serif-light", android.graphics.Typeface.NORMAL)
                        setPadding(2, 0, 2, 0)

                        // Restore checked state
                        if (entry.isChecked) {
                            paintFlags = paintFlags or android.graphics.Paint.STRIKE_THRU_TEXT_FLAG
                        } else {
                            paintFlags = paintFlags and android.graphics.Paint.STRIKE_THRU_TEXT_FLAG.inv()
                        }

                        // Double-click: toggle this entry's strikethrough
                        setDoubleClickListener(this) {
                            entry.isChecked = !entry.isChecked
                            if (entry.isChecked) {
                                paintFlags = paintFlags or android.graphics.Paint.STRIKE_THRU_TEXT_FLAG
                            } else {
                                paintFlags = paintFlags and android.graphics.Paint.STRIKE_THRU_TEXT_FLAG.inv()
                            }
                        }

                        // Long-press: mark/unmark ALL entries
                        setOnLongClickListener {
                            val allChecked = entries.all { it.isChecked }
                            entries.forEach { it.isChecked = !allChecked }
                            refreshSmartDisplay()
                            true
                        }
                    }
                    exprContainer.addView(numTv)
                }

                // Auto-scroll to the right so newest value is visible
                scrollExpr?.post { scrollExpr.fullScroll(android.view.View.FOCUS_RIGHT) }
            }

            // Refresh history panel if it is open
            val histPanel = v.findViewById<LinearLayout>(R.id.layoutHistoryPanel)
            if (histPanel?.visibility == View.VISIBLE) {
                populateHistory(
                    v.findViewById(R.id.layoutHistoryItems),
                    v.findViewById(R.id.tvHistoryTotal)
                )
            }
        }
    }

    /**
     * Subtle scale-pulse on the TOTAL number to give visual feedback
     * each time a new selection is captured and added.
     */
    private fun flashSmartTotal() {
        val v  = floatView ?: return
        if (currentMode != "smart") return
        val tv = v.findViewById<TextView>(R.id.tvSmartTotal) ?: return
        tv.animate()
            .scaleX(1.15f).scaleY(1.15f)
            .setDuration(90)
            .withEndAction {
                tv.animate().scaleX(1f).scaleY(1f).setDuration(180).start()
            }
            .start()
    }

    private fun populateHistory(container: LinearLayout, tvTotal: TextView) {
        container.removeAllViews()
        val entries = HistoryManager.entries
        val isDark = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
        // Translucent faded colors: 50% opacity white in dark, 50% opacity black in light
        val textColor = if (isDark) Color.parseColor("#80FFFFFF") else Color.parseColor("#80000000")
        
        entries.forEachIndexed { i, entry ->
            val row = TextView(this).apply {
                text = if (i == 0) HistoryManager.fmt(entry.value)
                       else "+ ${HistoryManager.fmt(entry.value)}"
                textSize   = 14f
                setTextColor(textColor)
                setPadding(0, 4, 0, 4)

                // Restore checked/strikethrough state
                if (entry.isChecked) {
                    paintFlags = paintFlags or android.graphics.Paint.STRIKE_THRU_TEXT_FLAG
                } else {
                    paintFlags = paintFlags and android.graphics.Paint.STRIKE_THRU_TEXT_FLAG.inv()
                }

                // Toggle checked state on double click
                setDoubleClickListener(this) {
                    entry.isChecked = !entry.isChecked
                    if (entry.isChecked) {
                        paintFlags = paintFlags or android.graphics.Paint.STRIKE_THRU_TEXT_FLAG
                    } else {
                        paintFlags = paintFlags and android.graphics.Paint.STRIKE_THRU_TEXT_FLAG.inv()
                    }
                }
            }
            container.addView(row)
        }
        tvTotal.text = "Total: ${HistoryManager.formattedTotal()}"
        tvTotal.setTextColor(Color.parseColor("#FFFF9F0A"))
    }

    // ─────────────────────────────────────────────
    // Bubble (minimised)
    // ─────────────────────────────────────────────

    private fun dockToBubble() {
        if (currentMode == "bubble") return
        preMinimiseMode = currentMode
        removeFloat()
        currentMode = "bubble"

        val view = inflate(R.layout.layout_floating_bubble)
        bubbleView = view

        val params = buildParams(24, 24).apply {
            gravity = Gravity.TOP or Gravity.START
            x = bubbleLastX
            y = bubbleLastY
        }

        var initX = 0; var initY = 0; var initRx = 0f; var initRy = 0f; var moved = false

        view.setOnTouchListener { _, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    initX = params.x; initY = params.y
                    initRx = ev.rawX; initRy = ev.rawY
                    moved = false; true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initX + (ev.rawX - initRx).toInt()
                    params.y = initY + (ev.rawY - initRy).toInt()
                    wm.updateViewLayout(view, params)
                    if (Math.abs(ev.rawX - initRx) > 8 || Math.abs(ev.rawY - initRy) > 8) moved = true
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        bubbleLastX = params.x
                        bubbleLastY = params.y
                        removeBubble()
                        if (preMinimiseMode == "smart") showSmart() else showManual()
                    } else {
                        snapBubble(params)
                    }
                    false
                }
                else -> false
            }
        }

        wm.addView(view, params)
    }

    private fun snapBubble(params: LayoutParams) {
        val bv = bubbleView ?: return
        val screenW = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            wm.currentWindowMetrics.bounds.width()
        else
            @Suppress("DEPRECATION") wm.defaultDisplay.width
        params.x = if (params.x + dpToPx(12) < screenW / 2) 0 else screenW - dpToPx(24)
        wm.updateViewLayout(bv, params)
        bubbleLastX = params.x
        bubbleLastY = params.y
    }

    // ─────────────────────────────────────────────
    // Drag helper
    // ─────────────────────────────────────────────

    private fun makeDraggable(handle: View, root: View, params: LayoutParams) {
        var initX = 0; var initY = 0; var initRx = 0f; var initRy = 0f

        handle.setOnTouchListener { _, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    initX = params.x; initY = params.y
                    initRx = ev.rawX; initRy = ev.rawY; true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initX + (ev.rawX - initRx).toInt()
                    params.y = initY + (ev.rawY - initRy).toInt()
                    wm.updateViewLayout(root, params); true
                }
                else -> false
            }
        }
    }

    /**
     * Attaches a corner-drag resize listener to [handle].
     * Dragging the handle changes the window's width and height in real time.
     * Minimum: 240 × 330 dp  |  Maximum: 90 % of screen dimensions.
     */
    private fun attachManualResizeHandles(
        handleBottom: View,
        root: View,
        params: LayoutParams
    ) {
        val minW = dpToPx(180); val minH = dpToPx(240)
        var startW = 0; var startH = 0; var startX = 0
        var startRx = 0f; var startRy = 0f
        var resizeMode = 0 // 0=height only, 1=right edge, 2=left edge

        val screenW: Int; val screenH: Int
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            val bounds = wm.currentWindowMetrics.bounds
            screenW = bounds.width(); screenH = bounds.height()
        } else {
            @Suppress("DEPRECATION")
            screenW = wm.defaultDisplay.width
            @Suppress("DEPRECATION")
            screenH = wm.defaultDisplay.height
        }
        val maxW = (screenW * 0.9).toInt(); val maxH = (screenH * 0.9).toInt()

        handleBottom.setOnTouchListener { v, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    startW = params.width.takeIf { it > 0 } ?: root.width
                    startH = params.height.takeIf { it > 0 } ?: root.height
                    startX = params.x
                    startRx = ev.rawX; startRy = ev.rawY
                    // Determine resize mode from touch X position on the handle
                    val handleW = v.width.takeIf { it > 0 } ?: startW
                    resizeMode = when {
                        ev.x > handleW * 0.7f -> 1  // right 30% → resize right edge
                        ev.x < handleW * 0.3f -> 2  // left 30%  → resize left edge
                        else -> 0                    // middle 40% → height only
                    }
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (ev.rawX - startRx).toInt()
                    val dy = (ev.rawY - startRy).toInt()
                    val newH = (startH + dy).coerceIn(minH, maxH)
                    when (resizeMode) {
                        1 -> { // right edge: width grows right
                            val newW = (startW + dx).coerceIn(minW, maxW)
                            params.width = newW; params.height = newH
                        }
                        2 -> { // left edge: width grows left, x shifts
                            val newW = (startW - dx).coerceIn(minW, maxW)
                            params.x = startX + (startW - newW)
                            params.width = newW; params.height = newH
                        }
                        else -> params.height = newH // height only
                    }
                    wm.updateViewLayout(root, params); true
                }
                else -> false
            }
        }
    }

    private fun setDoubleClickListener(view: View, onDoubleClicked: () -> Unit) {
        var lastClickTime = 0L
        view.setOnClickListener {
            val clickTime = System.currentTimeMillis()
            if (clickTime - lastClickTime < 300L) {
                onDoubleClicked()
            }
            lastClickTime = clickTime
        }
    }

    private fun attachSmartResizeHandles(
        handleRight: View,
        handleLeft: View,
        root: View,
        params: LayoutParams
    ) {
        val minW = dpToPx(180); val minH = dpToPx(225)
        var startW = 0; var startH = 0; var startX = 0
        var startRx = 0f; var startRy = 0f

        val screenW: Int; val screenH: Int
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            val bounds = wm.currentWindowMetrics.bounds
            screenW = bounds.width(); screenH = bounds.height()
        } else {
            @Suppress("DEPRECATION")
            screenW = wm.defaultDisplay.width
            @Suppress("DEPRECATION")
            screenH = wm.defaultDisplay.height
        }
        val maxW = (screenW * 0.95).toInt(); val maxH = (screenH * 0.95).toInt()

        handleRight.setOnTouchListener { _, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    startW  = params.width.takeIf { it > 0 } ?: root.width
                    startH  = params.height.takeIf { it > 0 } ?: root.height
                    startRx = ev.rawX; startRy = ev.rawY; true
                }
                MotionEvent.ACTION_MOVE -> {
                    val newW = (startW + (ev.rawX - startRx).toInt()).coerceIn(minW, maxW)
                    val newH = (startH + (ev.rawY - startRy).toInt()).coerceIn(minH, maxH)
                    params.width = newW; params.height = newH
                    wm.updateViewLayout(root, params); true
                }
                else -> false
            }
        }

        handleLeft.setOnTouchListener { _, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    startW  = params.width.takeIf { it > 0 } ?: root.width
                    startH  = params.height.takeIf { it > 0 } ?: root.height
                    startX  = params.x
                    startRx = ev.rawX; startRy = ev.rawY; true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (ev.rawX - startRx).toInt()
                    val newW = (startW - dx).coerceIn(minW, maxW)
                    val newH = (startH + (ev.rawY - startRy).toInt()).coerceIn(minH, maxH)
                    
                    val actualDx = startW - newW
                    params.x = startX + actualDx
                    params.width = newW; params.height = newH
                    wm.updateViewLayout(root, params); true
                }
                else -> false
            }
        }
    }

    // ─────────────────────────────────────────────
    // Manual calc logic
    // ─────────────────────────────────────────────



    // ─────────────────────────────────────────────
    // Formatting
    // ─────────────────────────────────────────────

    private fun fmtResult(v: Double): String = CalculatorEngine.formatResult(v)

    private fun fmtNum(input: String): String = CalculatorEngine.formatNumber(input)

    // ─────────────────────────────────────────────
    // Utility
    // ─────────────────────────────────────────────

    // ─────────────────────────────────────────────
    // Popup theme application
    // ─────────────────────────────────────────────

    /**
     * Applies Light or Dark theme to an inflated floating popup view.
     * Dark theme = default layout colours (no-op).
     * Light theme = translucent white background, dark text.
     */
    private fun applyPopupTheme(root: View, theme: Int, isManual: Boolean) {
        val isDark = if (!isManual) {
            // For Smart mode, follow the system's night mode automatically
            (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
        } else {
            // For Manual mode, follow the manual preference
            theme == PopupThemeManager.DARK
        }

        val contentView: View = if (isManual)
            root.findViewById(R.id.manualFloatContent) ?: root
        else
            root.findViewById(R.id.smartFloatContent) ?: root

        val headerId = if (isManual) R.id.floatManualHeader else R.id.smartHeader

        if (isDark) {
            // Background – translucent dark rounded card
            contentView.background = ContextCompat.getDrawable(this, R.drawable.bg_float_window)
            contentView.clipToOutline = true

            val headerBg = Color.parseColor("#FF2C2C2E")
            root.findViewById<View>(headerId)?.setBackgroundColor(headerBg)

            val colorPrimary = Color.WHITE
            val colorSecondary = Color.parseColor("#FF8E8E93")
            val dividerColor = Color.parseColor("#33FFFFFF")

            // All TextViews inside root – set appropriate text colours
            setAllTextColors(root, colorPrimary, colorSecondary)

            // Divider lines
            setDividerColors(root, dividerColor)

            // Icon tints (ImageButtons in header)
            tintImageButtons(root, colorSecondary)

            root.findViewById<TextView>(R.id.tvFloatOperator)?.setTextColor(colorSecondary)
            root.findViewById<android.widget.EditText>(R.id.etFloatTitle)?.setTextColor(colorPrimary)

            if (!isManual) {
                // Custom text/button colors for Smart mode in Dark
                // (expression items are colored dynamically in refreshSmartDisplay)
                root.findViewById<TextView>(R.id.tvSmartLabel)?.setTextColor(colorSecondary)
                root.findViewById<TextView>(R.id.tvSmartCount)?.setTextColor(colorSecondary)

                root.findViewById<MaterialButton>(R.id.btnSmartUndo)?.apply {
                    setTextColor(Color.WHITE)
                    backgroundTintList = ColorStateList.valueOf(Color.parseColor("#FF636366"))
                }
                root.findViewById<MaterialButton>(R.id.btnSmartAction)?.apply {
                    setTextColor(Color.WHITE)
                    backgroundTintList = ColorStateList.valueOf(Color.parseColor("#FF0A84FF"))
                }
                root.findViewById<MaterialButton>(R.id.btnClearHistory)?.apply {
                    setTextColor(Color.WHITE)
                    backgroundTintList = ColorStateList.valueOf(Color.parseColor("#FF636366"))
                }
            } else {
                themeManualButtons(root, true)
            }
        } else {
            // Background – translucent white rounded card
            contentView.background = ContextCompat.getDrawable(this, R.drawable.bg_float_window_light)
            contentView.clipToOutline = true

            val headerBg = Color.parseColor("#CCEDF1F8")
            root.findViewById<View>(headerId)?.setBackgroundColor(headerBg)

            val colorPrimary = Color.parseColor("#1C1C1E")
            val colorSecondary = Color.parseColor("#8E8E93")
            val dividerColor = Color.parseColor("#22000000")

            // All TextViews inside root – set appropriate text colours
            setAllTextColors(root, colorPrimary, colorSecondary)

            // Divider lines
            setDividerColors(root, dividerColor)

            // Icon tints (ImageButtons in header)
            tintImageButtons(root, colorSecondary)

            root.findViewById<TextView>(R.id.tvFloatOperator)?.setTextColor(colorSecondary)
            root.findViewById<android.widget.EditText>(R.id.etFloatTitle)?.setTextColor(colorPrimary)

            if (!isManual) {
                // Custom text/button colors for Smart mode in Light
                // (expression items are colored dynamically in refreshSmartDisplay)
                root.findViewById<TextView>(R.id.tvSmartLabel)?.setTextColor(colorSecondary)
                root.findViewById<TextView>(R.id.tvSmartCount)?.setTextColor(colorSecondary)

                root.findViewById<MaterialButton>(R.id.btnSmartUndo)?.apply {
                    setTextColor(colorPrimary)
                    backgroundTintList = ColorStateList.valueOf(Color.parseColor("#FFD1D1D6"))
                }
                root.findViewById<MaterialButton>(R.id.btnSmartAction)?.apply {
                    setTextColor(Color.WHITE)
                    backgroundTintList = ColorStateList.valueOf(Color.parseColor("#FF0A84FF"))
                }
                root.findViewById<MaterialButton>(R.id.btnClearHistory)?.apply {
                    setTextColor(colorPrimary)
                    backgroundTintList = ColorStateList.valueOf(Color.parseColor("#FFD1D1D6"))
                }
            } else {
                themeManualButtons(root, false)
            }
        }
    }

    private fun themeManualButtons(root: View, isDark: Boolean) {
        val numberIds = listOf(
            R.id.btnFloat0, R.id.btnFloat1, R.id.btnFloat2, R.id.btnFloat3,
            R.id.btnFloat4, R.id.btnFloat5, R.id.btnFloat6, R.id.btnFloat7,
            R.id.btnFloat8, R.id.btnFloat9
        )
        val funcIds = listOf(R.id.btnFloatClear, R.id.btnFloatBackspace)
        val opIds = listOf(R.id.btnFloatMultiply, R.id.btnFloatSubtract, R.id.btnFloatAdd)
        val eqId = R.id.btnFloatEquals

        val numBg = Color.parseColor(if (isDark) "#FF3A3A3C" else "#FFFFFFFF")
        val numTxt = if (isDark) Color.WHITE else Color.parseColor("#FF1C1C1E")

        val funcBg = Color.parseColor(if (isDark) "#FF636366" else "#FFD1D1D6")
        val funcTxt = if (isDark) Color.WHITE else Color.parseColor("#FF1C1C1E")

        val opBg = Color.parseColor(if (isDark) "#FFFF9F0A" else "#FFFF9F0A")
        val opTxt = Color.WHITE

        val eqBg = Color.parseColor(if (isDark) "#FF30D158" else "#FF30D158")
        val eqTxt = Color.WHITE

        numberIds.forEach { id ->
            root.findViewById<MaterialButton>(id)?.apply {
                backgroundTintList = ColorStateList.valueOf(numBg)
                setTextColor(numTxt)
            }
        }
        funcIds.forEach { id ->
            root.findViewById<MaterialButton>(id)?.apply {
                backgroundTintList = ColorStateList.valueOf(funcBg)
                setTextColor(funcTxt)
            }
        }
        opIds.forEach { id ->
            root.findViewById<MaterialButton>(id)?.apply {
                backgroundTintList = ColorStateList.valueOf(opBg)
                setTextColor(opTxt)
            }
        }
        root.findViewById<MaterialButton>(eqId)?.apply {
            backgroundTintList = ColorStateList.valueOf(eqBg)
            setTextColor(eqTxt)
        }
    }

    private fun setAllTextColors(parent: View, primary: Int, secondary: Int) {
        if (parent !is android.view.ViewGroup) return
        for (i in 0 until parent.childCount) {
            val child = parent.getChildAt(i)
            when (child) {
                is TextView -> {
                    val size = child.textSize
                    child.setTextColor(if (size >= 28f) primary else secondary)
                }
                is android.view.ViewGroup -> setAllTextColors(child, primary, secondary)
            }
        }
    }

    private fun setDividerColors(parent: View, color: Int) {
        if (parent !is android.view.ViewGroup) return
        for (i in 0 until parent.childCount) {
            val child = parent.getChildAt(i)
            if (child.javaClass.simpleName == "View" &&
                child !is android.view.ViewGroup &&
                child.layoutParams?.height == 1
            ) {
                child.setBackgroundColor(color)
            } else if (child is android.view.ViewGroup) {
                setDividerColors(child, color)
            }
        }
    }

    private fun tintImageButtons(parent: View, tint: Int) {
        if (parent !is android.view.ViewGroup) return
        for (i in 0 until parent.childCount) {
            val child = parent.getChildAt(i)
            if (child is ImageButton) {
                ImageViewCompat.setImageTintList(child, ColorStateList.valueOf(tint))
            } else if (child is android.view.ViewGroup) {
                tintImageButtons(child, tint)
            }
        }
    }

    private fun buildParams(widthDp: Int, heightDp: Int): LayoutParams {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") LayoutParams.TYPE_PHONE

        val w = if (widthDp == LayoutParams.WRAP_CONTENT) LayoutParams.WRAP_CONTENT else dpToPx(widthDp)
        val h = if (heightDp == LayoutParams.WRAP_CONTENT) LayoutParams.WRAP_CONTENT else dpToPx(heightDp)

        return LayoutParams(w, h, type,
            LayoutParams.FLAG_NOT_FOCUSABLE or LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 80; y = 200
        }
    }

    private fun inflate(layoutRes: Int): View {
        val ctx = android.view.ContextThemeWrapper(applicationContext, R.style.Theme_SmartCalculator)
        return LayoutInflater.from(ctx).inflate(layoutRes, null)
    }

    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()

    private fun removeFloat() {
        floatView?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        floatView = null
    }

    private fun removeBubble() {
        bubbleView?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        bubbleView = null
    }

    // ─────────────────────────────────────────────
    // Notification (required for foreground service)
    // ─────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = getString(R.string.channel_desc) }
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text))
            .setSmallIcon(R.drawable.ic_float_mode)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .build()
    }
}

// Extension to expose fmt() from HistoryManager
fun HistoryManager.fmt(v: Double): String {
    if (v.isNaN() || v.isInfinite()) return "Error"
    val bd = java.math.BigDecimal(v).setScale(10, java.math.RoundingMode.HALF_UP).stripTrailingZeros()
    val plain = bd.toPlainString()
    return if (plain.contains('.') && plain.length > 10)
        java.math.BigDecimal(v).setScale(4, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
    else plain
}
