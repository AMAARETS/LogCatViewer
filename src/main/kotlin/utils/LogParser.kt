package utils

import models.*
import java.util.regex.Pattern

/**
 * כלי לפיענוח שורות logcat
 */
object LogParser {
    private val LOGCAT_PATTERN = Pattern.compile(
        "^(\\d{2}-\\d{2}\\s+\\d{2}:\\d{2}:\\d{2}\\.\\d{3})\\s+" + // timestamp
        "(\\d+)\\s+" +                                              // pid
        "(\\d+)\\s+" +                                              // tid
        "([VDIWEA])\\s+" +                                          // level
        "([^:]+):\\s*" +                                            // tag
        "(.*)$"                                                     // message
    )
    
    /**
     * פיענוח שורת logcat לאובייקט LogEntry
     */
    fun parseLogLine(line: String, id: Long = 0): LogEntry? {
        val matcher = LOGCAT_PATTERN.matcher(line.trim())
        
        if (!matcher.matches()) {
            // אם השורה לא תואמת לפורמט, נסה פורמט פשוט יותר
            return parseSimpleLogLine(line, id)
        }
        
        try {
            val timestamp = matcher.group(1) ?: ""
            val pid = matcher.group(2) ?: ""
            val tid = matcher.group(3) ?: ""
            val levelStr = matcher.group(4) ?: ""
            val tag = matcher.group(5) ?: ""
            val message = matcher.group(6) ?: ""
            
            val level = LogLevel.fromString(levelStr) ?: LogLevel.VERBOSE
            
            return LogEntry(
                id = id,
                timestamp = timestamp,
                pid = pid,
                tid = tid,
                level = level,
                tag = tag,
                message = message
            )
        } catch (e: Exception) {
            return null
        }
    }
    
    /**
     * פיענוח פשוט יותר לשורות שלא תואמות לפורמט הרגיל
     */
    private fun parseSimpleLogLine(line: String, id: Long): LogEntry? {
        if (line.isBlank()) return null
        
        return LogEntry(
            id = id,
            timestamp = System.currentTimeMillis().toString(),
            pid = "0",
            tid = "0",
            level = LogLevel.INFO,
            tag = "Unknown",
            message = line
        )
    }
    
    /**
     * בדיקה אם השורה היא שורת logcat תקינה
     */
    fun isValidLogLine(line: String): Boolean {
        return LOGCAT_PATTERN.matcher(line.trim()).matches() || line.trim().isNotEmpty()
    }
}