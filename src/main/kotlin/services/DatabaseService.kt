package services

import kotlinx.coroutines.*
import java.io.File
import models.*
import LogDatabase

/**
 * שירות מסד נתונים - אחראי על כל הפעולות עם מסד הנתונים
 */
class DatabaseService {
    private val database = LogDatabase()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // Cache for package names with timestamp
    private var packageNamesCache: List<String>? = null
    private var packageNamesCacheTime = 0L
    private val cacheValidityMs = 30000L // 30 seconds
    
    suspend fun initialize(): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                database.initialize()
                true
            } catch (e: Exception) {
                false
            }
        }
    }
    
    suspend fun insertLogsBatch(logs: List<LogEntry>) {
        withContext(Dispatchers.IO) {
            database.insertLogsBatch(logs)
            // Invalidate cache only if new packages might have been added
            if (logs.any { it.packageName.isNotEmpty() }) {
                invalidatePackageNamesCache()
            }
        }
    }
    
    suspend fun getLogs(
        offset: Int,
        limit: Int,
        searchText: String = "",
        levels: Set<LogLevel> = emptySet(),
        tagFilter: String = "",
        packageFilter: Set<String> = emptySet()
    ): List<LogEntry> {
        return withContext(Dispatchers.IO) {
            try {
                database.getLogs(offset, limit, searchText, levels, tagFilter, packageFilter)
            } catch (e: Exception) {
                // נסה עם limit קטן יותר
                val safeLimit = minOf(limit, 50)
                try {
                    database.getLogs(offset, safeLimit, searchText, levels, tagFilter, packageFilter)
                } catch (e2: Exception) {
                    emptyList()
                }
            }
        }
    }
    
    suspend fun getLogsBatch(requests: List<Pair<Int, Int>>, filters: LogFilters): Map<Int, List<LogEntry>> {
        return withContext(Dispatchers.IO) {
            val results = mutableMapOf<Int, List<LogEntry>>()
            
            requests.map { (offset, limit) ->
                async {
                    try {
                        val logs = database.getLogs(
                            offset = offset,
                            limit = limit,
                            searchText = filters.searchText,
                            levels = filters.levels,
                            tagFilter = filters.tagFilter,
                            packageFilter = filters.packageFilter
                        )
                        offset to logs
                    } catch (e: Exception) {
                        offset to emptyList<LogEntry>()
                    }
                }
            }.awaitAll().forEach { (requestOffset, logs) ->
                results[requestOffset] = logs
            }
            
            results
        }
    }
    
    suspend fun getTotalLogCount(): Int {
        return withContext(Dispatchers.IO) {
            database.getTotalLogCount()
        }
    }
    
    suspend fun getLogCount(searchText: String, levels: Set<LogLevel>, tagFilter: String, packageFilter: Set<String> = emptySet()): Int {
        return withContext(Dispatchers.IO) {
            database.getLogCount(searchText, levels, tagFilter, packageFilter)
        }
    }
    
    suspend fun clearAllLogs() {
        withContext(Dispatchers.IO) {
            database.clearAllLogs()
            // Clear cache when all logs are deleted
            invalidatePackageNamesCache()
        }
    }
    
    suspend fun exportLogs(filePath: String, filters: LogFilters) {
        withContext(Dispatchers.IO) {
            database.exportLogs(filePath, filters)
        }
    }
    
    suspend fun getUniquePackageNames(): List<String> {
        return withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            
            // Return cached result if still valid
            packageNamesCache?.let { cached ->
                if (now - packageNamesCacheTime < cacheValidityMs) {
                    return@withContext cached
                }
            }
            
            // Fetch fresh data and cache it
            val freshData = database.getUniquePackageNames()
            packageNamesCache = freshData
            packageNamesCacheTime = now
            
            freshData
        }
    }
    
    suspend fun invalidatePackageNamesCache() {
        packageNamesCache = null
        packageNamesCacheTime = 0L
    }
    
    fun close() {
        database.close()
        scope.cancel()
    }
}

data class LogFilters(
    val searchText: String = "",
    val levels: Set<LogLevel> = emptySet(),
    val tagFilter: String = "",
    val packageFilter: Set<String> = emptySet()
)