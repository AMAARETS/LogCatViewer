package models

/**
 * מודל עבור רשומת לוג
 */
data class LogEntry(
    val id: Long,
    val timestamp: String,
    val pid: String,
    val tid: String,
    val level: LogLevel,
    val tag: String,
    val message: String
) {
    /**
     * המרה לטקסט לייצוא
     */
    fun toDisplayString(): String {
        return "$timestamp ${level.displayName}/$tag($pid): $message"
    }
    
    /**
     * בדיקה אם הלוג מכיל טקסט מסוים
     */
    fun contains(searchText: String, ignoreCase: Boolean = true): Boolean {
        if (searchText.isEmpty()) return true
        
        val text = if (ignoreCase) searchText.lowercase() else searchText
        
        return (if (ignoreCase) tag.lowercase() else tag).contains(text) ||
               (if (ignoreCase) message.lowercase() else message).contains(text) ||
               (if (ignoreCase) pid.lowercase() else pid).contains(text)
    }
    
    /**
     * בדיקה אם הלוג תואם לפילטר tag
     */
    fun matchesTagFilter(tagFilter: String): Boolean {
        if (tagFilter.isEmpty()) return true
        return tag.contains(tagFilter, ignoreCase = true)
    }
}