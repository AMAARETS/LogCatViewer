package state

import androidx.compose.runtime.mutableStateOf
import models.LogLevel

/**
 * מצב פילטרים
 */
class FilterState {
    val searchText = mutableStateOf("")
    val selectedLevels = mutableStateOf<Set<LogLevel>>(emptySet())
    val tagFilter = mutableStateOf("")
    val showOnlyErrors = mutableStateOf(false)
    
    // Callbacks
    var onFiltersChanged: (() -> Unit)? = null
    
    fun setSearchText(text: String) {
        searchText.value = text
        onFiltersChanged?.invoke()
    }
    
    fun setSelectedLevels(levels: Set<LogLevel>) {
        selectedLevels.value = levels
        onFiltersChanged?.invoke()
    }
    
    fun setTagFilter(tag: String) {
        tagFilter.value = tag
        onFiltersChanged?.invoke()
    }
    
    fun toggleLevel(level: LogLevel) {
        val current = selectedLevels.value.toMutableSet()
        if (current.contains(level)) {
            current.remove(level)
        } else {
            current.add(level)
        }
        setSelectedLevels(current)
    }
    
    fun clearAllFilters() {
        searchText.value = ""
        selectedLevels.value = emptySet()
        tagFilter.value = ""
        showOnlyErrors.value = false
        onFiltersChanged?.invoke()
    }
    
    fun hasActiveFilters(): Boolean {
        return searchText.value.isNotEmpty() || 
               selectedLevels.value.isNotEmpty() || 
               tagFilter.value.isNotEmpty() ||
               showOnlyErrors.value
    }
    
    fun getFilters(): services.LogFilters {
        return services.LogFilters(
            searchText = searchText.value,
            levels = if (showOnlyErrors.value) {
                setOf(LogLevel.ERROR, LogLevel.ASSERT)
            } else {
                selectedLevels.value
            },
            tagFilter = tagFilter.value
        )
    }
}