package models

import androidx.compose.ui.graphics.Color

/**
 * רמות לוג עם צבעים מתאימים
 */
enum class LogLevel(val displayName: String, val color: Color, val priority: Int) {
    VERBOSE("V", Color(0xFF9E9E9E), 2),
    DEBUG("D", Color(0xFF2196F3), 3),
    INFO("I", Color(0xFF4CAF50), 4),
    WARN("W", Color(0xFFFFC107), 5),
    ERROR("E", Color(0xFFF44336), 6),
    ASSERT("A", Color(0xFF9C27B0), 7);
    
    companion object {
        /**
         * המרה מטקסט לרמת לוג
         */
        fun fromString(level: String): LogLevel? {
            return values().find { it.displayName.equals(level, ignoreCase = true) }
        }
        
        /**
         * קבלת כל הרמות מעל רמה מסוימת
         */
        fun getAboveLevel(minLevel: LogLevel): Set<LogLevel> {
            return values().filter { it.priority >= minLevel.priority }.toSet()
        }
        
        /**
         * קבלת רמות לפי חשיבות
         */
        fun getByPriority(): List<LogLevel> {
            return values().sortedBy { it.priority }
        }
    }
}