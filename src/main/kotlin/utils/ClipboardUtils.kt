package utils

import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

/**
 * כלים לעבודה עם clipboard
 */
object ClipboardUtils {
    /**
     * העתקת טקסט ל-clipboard
     */
    fun copyToClipboard(text: String) {
        try {
            val clipboard = Toolkit.getDefaultToolkit().systemClipboard
            val selection = StringSelection(text)
            clipboard.setContents(selection, null)
        } catch (e: Exception) {
            println("שגיאה בהעתקה ל-clipboard: ${e.message}")
        }
    }
}

/**
 * פונקציה גלובלית להעתקה ל-clipboard
 */
fun copyToClipboard(text: String) {
    ClipboardUtils.copyToClipboard(text)
}