package com.example.smartcalculator

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

/**
 * Settings screen.
 *
 * Appearance:       Light / Dark / System app-wide theme (3 cards)
 * Floating Windows: Light / Dark toggles for Manual and Smart popups independently
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var cardLight:  LinearLayout
    private lateinit var cardDark:   LinearLayout
    private lateinit var cardSystem: LinearLayout
    private lateinit var tvDesc:     TextView
    private lateinit var dotLight:   View
    private lateinit var dotDark:    View
    private lateinit var dotSystem:  View

    // Popup theme toggles
    private lateinit var manualThemeLight: LinearLayout
    private lateinit var manualThemeDark:  LinearLayout
    private lateinit var smartThemeLight:  LinearLayout
    private lateinit var smartThemeDark:   LinearLayout

    // Layout section
    private lateinit var rowMoveButtons: LinearLayout
    private lateinit var rowResetLayout: LinearLayout

    // Developer section
    private lateinit var tvDeveloperName: TextView
    private lateinit var btnDeveloperExpand: ImageButton
    private lateinit var panelDeveloperContacts: LinearLayout
    private lateinit var rowDevCall: LinearLayout
    private lateinit var rowDevWhatsApp: LinearLayout

    private var currentTheme = ThemeManager.SYSTEM

    companion object {
        private const val DEV_CALL_NUMBER     = "01614677619"
        private const val DEV_WHATSAPP_NUMBER = "01894086541"
        /** BD country code for wa.me deep links. */
        private const val WA_COUNTRY_CODE     = "880"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        currentTheme = ThemeManager.getSaved(this)

        bindViews()
        renderSelection(currentTheme)
        renderPopupToggles()
        wireListeners()
    }

    private fun bindViews() {
        cardLight  = findViewById(R.id.cardLight)
        cardDark   = findViewById(R.id.cardDark)
        cardSystem = findViewById(R.id.cardSystem)
        tvDesc     = findViewById(R.id.tvThemeDesc)
        dotLight   = findViewById(R.id.dotLight)
        dotDark    = findViewById(R.id.dotDark)
        dotSystem  = findViewById(R.id.dotSystem)

        manualThemeLight = findViewById(R.id.manualThemeLight)
        manualThemeDark  = findViewById(R.id.manualThemeDark)
        smartThemeLight  = findViewById(R.id.smartThemeLight)
        smartThemeDark   = findViewById(R.id.smartThemeDark)

        rowMoveButtons = findViewById(R.id.rowMoveButtons)
        rowResetLayout = findViewById(R.id.rowResetLayout)

        tvDeveloperName        = findViewById(R.id.tvDeveloperName)
        btnDeveloperExpand     = findViewById(R.id.btnDeveloperExpand)
        panelDeveloperContacts = findViewById(R.id.panelDeveloperContacts)
        rowDevCall             = findViewById(R.id.rowDevCall)
        rowDevWhatsApp         = findViewById(R.id.rowDevWhatsApp)

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }
    }

    private fun wireListeners() {
        cardLight.setOnClickListener  { selectTheme(ThemeManager.LIGHT) }
        cardDark.setOnClickListener   { selectTheme(ThemeManager.DARK) }
        cardSystem.setOnClickListener { selectTheme(ThemeManager.SYSTEM) }

        manualThemeLight.setOnClickListener {
            PopupThemeManager.setManualTheme(this, PopupThemeManager.LIGHT)
            renderPopupToggles()
        }
        manualThemeDark.setOnClickListener {
            PopupThemeManager.setManualTheme(this, PopupThemeManager.DARK)
            renderPopupToggles()
        }
        smartThemeLight.setOnClickListener {
            PopupThemeManager.setSmartTheme(this, PopupThemeManager.LIGHT)
            renderPopupToggles()
        }
        smartThemeDark.setOnClickListener {
            PopupThemeManager.setSmartTheme(this, PopupThemeManager.DARK)
            renderPopupToggles()
        }

        rowMoveButtons.setOnClickListener {
            // Go to MainActivity and activate drag-and-drop edit mode
            val intent = android.content.Intent(this, MainActivity::class.java).apply {
                putExtra(MainActivity.EXTRA_EDIT_MODE, true)
                addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or
                         android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            startActivity(intent)
            finish()
        }

        rowResetLayout.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Reset Layout")
                .setMessage("Restore the default OnePlus-style button order?")
                .setPositiveButton("Reset") { _, _ ->
                    ButtonLayout.reset(this)
                    setResult(RESULT_OK)
                    Toast.makeText(
                        this, "Layout reset to default", Toast.LENGTH_SHORT
                    ).show()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        // Arrow → single tap toggles the contact panel.
        btnDeveloperExpand.setOnClickListener { toggleDeveloperPanel() }

        // Name → double-tap toggles the contact panel.
        var lastClickTime = 0L
        tvDeveloperName.setOnClickListener {
            val now = System.currentTimeMillis()
            if (now - lastClickTime < 300L) toggleDeveloperPanel()
            lastClickTime = now
        }

        rowDevCall.setOnClickListener     { dialDeveloperNumber() }
        rowDevWhatsApp.setOnClickListener { openDeveloperWhatsApp() }
    }

    // ── Developer panel ───────────────────────────────────────────────────

    private fun toggleDeveloperPanel() {
        val expanded = panelDeveloperContacts.visibility == View.VISIBLE
        panelDeveloperContacts.visibility = if (expanded) View.GONE else View.VISIBLE
        // ic_arrow_back originally points left (←).
        //   collapsed: 180°  → points right (→) i.e. "tap to expand"
        //   expanded : 270°  → points down  (↓) i.e. "currently expanded"
        btnDeveloperExpand.animate().rotation(if (expanded) 180f else 270f).setDuration(180).start()
    }

    private fun dialDeveloperNumber() {
        try {
            startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$DEV_CALL_NUMBER")))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "No dialer app found", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openDeveloperWhatsApp() {
        // Convert local BD format (01XXXXXXXXX) to international (8801XXXXXXXXX).
        val intl = if (DEV_WHATSAPP_NUMBER.startsWith("0"))
            WA_COUNTRY_CODE + DEV_WHATSAPP_NUMBER.substring(1)
        else DEV_WHATSAPP_NUMBER
        val uri = Uri.parse("https://wa.me/$intl")
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "WhatsApp not installed", Toast.LENGTH_SHORT).show()
        }
    }

    private fun selectTheme(mode: Int) {
        if (mode == currentTheme) return
        currentTheme = mode
        ThemeManager.saveAndApply(this, mode)
        setResult(RESULT_OK)
        recreate()
    }

    private fun renderSelection(mode: Int) {
        cardLight.setBackgroundResource(R.drawable.bg_theme_unselected)
        cardDark.setBackgroundResource(R.drawable.bg_theme_unselected)
        cardSystem.setBackgroundResource(R.drawable.bg_theme_unselected)
        dotLight.visibility  = View.INVISIBLE
        dotDark.visibility   = View.INVISIBLE
        dotSystem.visibility = View.INVISIBLE

        val (card, dot, desc) = when (mode) {
            ThemeManager.LIGHT  -> Triple(cardLight,  dotLight,  "Classic bright interface")
            ThemeManager.DARK   -> Triple(cardDark,   dotDark,   "Easy on the eyes at night")
            else                -> Triple(cardSystem, dotSystem, "Follows your phone's setting")
        }
        card.setBackgroundResource(R.drawable.bg_theme_selected)
        dot.visibility = View.VISIBLE
        tvDesc.text = desc
    }

    private fun renderPopupToggles() {
        val manualMode = PopupThemeManager.getManualTheme(this)
        val smartMode  = PopupThemeManager.getSmartTheme(this)

        manualThemeLight.setBackgroundResource(
            if (manualMode == PopupThemeManager.LIGHT) R.drawable.bg_theme_selected
            else R.drawable.bg_theme_unselected
        )
        manualThemeDark.setBackgroundResource(
            if (manualMode == PopupThemeManager.DARK) R.drawable.bg_theme_selected
            else R.drawable.bg_theme_unselected
        )
        smartThemeLight.setBackgroundResource(
            if (smartMode == PopupThemeManager.LIGHT) R.drawable.bg_theme_selected
            else R.drawable.bg_theme_unselected
        )
        smartThemeDark.setBackgroundResource(
            if (smartMode == PopupThemeManager.DARK) R.drawable.bg_theme_selected
            else R.drawable.bg_theme_unselected
        )
    }
}
