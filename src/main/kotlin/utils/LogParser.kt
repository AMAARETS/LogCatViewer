package utils

import models.*
import services.PackageNameResolver
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
    fun parseLogLine(line: String, id: Long = 0, packageResolver: PackageNameResolver? = null): LogEntry? {
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
            
            // נסה לקבל שם חבילה מה-resolver, אחרת השתמש בשיטה הישנה
            val packageName = packageResolver?.getPackageName(pid) ?: extractPackageName(tag, message)
            
            return LogEntry(
                id = id,
                timestamp = timestamp,
                pid = pid,
                tid = tid,
                level = level,
                tag = tag,
                message = message,
                packageName = packageName
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
            message = line,
            packageName = ""
        )
    }
    
    /**
     * חילוץ שם החבילה מה-tag או מההודעה
     */
    private fun extractPackageName(tag: String, message: String): String {
        // נסה לחלץ שם חבילה מה-tag אם הוא נראה כמו שם חבילה
        if (tag.contains(".") && tag.matches(Regex("^[a-zA-Z][a-zA-Z0-9_.]*$"))) {
            return tag
        }
        
        // נסה לחלץ מההודעה אם יש דפוס של שם חבילה
        val packagePattern = Regex("\\b([a-zA-Z][a-zA-Z0-9_]*(?:\\.[a-zA-Z][a-zA-Z0-9_]*)+)\\b")
        val match = packagePattern.find(message)
        if (match != null) {
            val packageName = match.groupValues[1]
            // וודא שזה נראה כמו שם חבילה אמיתי (לא URL או משהו אחר)
            if (packageName.split(".").size >= 2 && !packageName.startsWith("http")) {
                return packageName
            }
        }
        
        return ""
    }

    /**
     * בדיקה אם השורה היא שורת logcat תקינה
     */
    fun isValidLogLine(line: String): Boolean {
        return LOGCAT_PATTERN.matcher(line.trim()).matches() || line.trim().isNotEmpty()
    }
}