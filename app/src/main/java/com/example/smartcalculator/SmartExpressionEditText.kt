package com.example.smartcalculator

import android.content.Context
import android.os.Build
import android.util.AttributeSet
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import androidx.appcompat.widget.AppCompatEditText

/**
 * EditText used for inline editing the Smart popup expression line.
 * Disables paste / copy / cut / share / autofill so only direct keyboard
 * typing (further filtered to digits & operators by `android:digits`) is allowed.
 */
class SmartExpressionEditText @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = androidx.appcompat.R.attr.editTextStyle
) : AppCompatEditText(context, attrs, defStyleAttr) {

    init {
        isLongClickable = false
        customSelectionActionModeCallback = NoActionModeCallback()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            customInsertionActionModeCallback = NoActionModeCallback()
        }
    }

    override fun onTextContextMenuItem(id: Int): Boolean {
        return when (id) {
            android.R.id.paste,
            android.R.id.pasteAsPlainText,
            android.R.id.cut,
            android.R.id.copy,
            android.R.id.shareText,
            android.R.id.selectAll -> false
            else -> super.onTextContextMenuItem(id)
        }
    }

    override fun isSuggestionsEnabled(): Boolean = false

    private class NoActionModeCallback : ActionMode.Callback {
        override fun onCreateActionMode(mode: ActionMode?, menu: Menu?) = false
        override fun onPrepareActionMode(mode: ActionMode?, menu: Menu?) = false
        override fun onActionItemClicked(mode: ActionMode?, item: MenuItem?) = false
        override fun onDestroyActionMode(mode: ActionMode?) {}
    }
}
