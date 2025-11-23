package ui

import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

// Helper function to copy text to clipboard
fun copyToClipboard(text: String) {
    try {
        val clipboard = Toolkit.getDefaultToolkit().systemClipboard
        clipboard.setContents(StringSelection(text), null)
    } catch (e: Exception) {
        e.printStackTrace()
    }
}
