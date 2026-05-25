package com.example.smartcalculator
 
import android.content.ClipboardManager
import android.content.Context
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatEditText
 
/**
 * A custom EditText that intercepts the context menu 'Paste' action,
 * sanitizes the clipboard text to keep only digits and math operators,
 * and passes it to the listener. The listener is responsible for
 * updating the display to avoid conflicts.
 */
class PasteEditText @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.editTextStyle
) : AppCompatEditText(context, attrs, defStyleAttr) {
 
    var onPasteListener: ((String) -> Unit)? = null
 
    override fun onTextContextMenuItem(id: Int): Boolean {
        if (id == android.R.id.paste) {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = clipboard.primaryClip
            if (clip != null && clip.itemCount > 0) {
                val pastedText = clip.getItemAt(0).text?.toString() ?: ""
                val sanitized = pastedText.filter {
                    it.isDigit() || it in "+-*/.×÷−"
                }
                if (sanitized.isNotEmpty()) {
                    onPasteListener?.invoke(sanitized)
                }
            }
            return true
        }
        return super.onTextContextMenuItem(id)
    }
}
