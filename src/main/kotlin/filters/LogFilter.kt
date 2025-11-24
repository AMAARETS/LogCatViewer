package filters

import models.LogEntry
import models.LogLevel
import androidx.compose.runtime.mutableStateOf

/**
 * מנהל פילטרים ללוגים
 */
class LogFilter {
    val searchText = mutableStateOf("")
    val selectedLevels = mutableStateOf(setOf<LogLevel>())
    val tagFilter = mutableStateOf("")
    
    /**
     * בדיקה אם לוג עובר את הפילטרים
     */
    fun passesFilter(log: LogEntry): Boolean {
        // פילטר רמת לוג
        if (selectedLevels.value.isNotEmpty() && log.level !in selectedLevels.value) {
            return false
        }
        
        // פילטר טקסט חיפוש
        val searchQuery = searchText.value.trim()
        if (searchQuery.isNotEmpty()) {
            val searchLower = searchQuery.lowercase()
            val passesSearch = log.message.lowercase().contains(searchLower) ||
                             log.tag.lowercase().contains(searchLower) ||
                             log.pid.contains(searchQuery) ||
                             log.tid.contains(searchQuery)
            
            if (!passesSearch) return false
        }
        
        // פילטר tag
        val tagQuery = tagFilter.value.trim()
        if (tagQuery.isNotEmpty()) {
            val tagLower = tagQuery.lowercase()
            if (!log.tag.lowercase().contains(tagLower)) {
                return false
            }
        }
        
        return true
    }
    
    /**
     * איפוס כל הפילטרים
     */
    fun clearAll() {
        searchText.value = ""
        selectedLevels.value = emptySet()
        tagFilter.value = ""
    }
    
    /**
     * בדיקה אם יש פילטרים פעילים
     */
    fun hasActiveFilters(): Boolean {
        return searchText.value.isNotEmpty() || 
               selectedLevels.value.isNotEmpty() || 
               tagFilter.value.isNotEmpty()
    }
    
    /**
     * קבלת תיאור הפילטרים הפעילים
     */
    fun getActiveFiltersDescription(): String {
        val parts = mutableListOf<String>()
        
        if (searchText.value.isNotEmpty()) {
            parts.add("חיפוש: '${searchText.value}'")
        }
        
        if (selectedLevels.value.isNotEmpty()) {
            val levels = selectedLevels.value.joinToString(", ") { it.displayName }
            parts.add("רמות: $levels")
        }
        
        if (tagFilter.value.isNotEmpty()) {
            parts.add("תג: '${tagFilter.value}'")
        }
        
        return if (parts.isEmpty()) {
            "אין פילטרים פעילים"
        } else {
            parts.joinToString(" | ")
        }
    }
}