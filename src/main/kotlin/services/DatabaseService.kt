package services

import kotlinx.coroutines.*
import java.io.File
import models.*

/**
 * שירות מסד נתונים - אחראי על כל הפעולות עם מסד הנתונים
 */
class DatabaseService {
    private val database = LogDatabase()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
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
        }
    }
    
    suspend fun getLogs(
        offset: Int,
        limit: Int,
        searchText: String = "",
        levels: Set<LogLevel> = emptySet(),
        tagFilter: String = ""
    ): List<LogEntry> {
        return withContext(Dispatchers.IO) {
            try {
                database.getLogs(offset, limit, searchText, levels, tagFilter)
            } catch (e: Exception) {
                // נסה עם limit קטן יותר
                val safeLimit = minOf(limit, 50)
                try {
                    database.getLogs(offset, safeLimit, searchText, levels, tagFilter)
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
                            tagFilter = filters.tagFilter
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
    
    suspend fun getLogCount(searchText: String, levels: Set<LogLevel>, tagFilter: String): Int {
        return withContext(Dispatchers.IO) {
            database.getLogCount(searchText, levels, tagFilter)
        }
    }
    
    suspend fun clearAllLogs() {
        withContext(Dispatchers.IO) {
            database.clearAllLogs()
        }
    }
    
    suspend fun exportLogs(filePath: String, filters: LogFilters) {
        withContext(Dispatchers.IO) {
            database.exportLogs(filePath, filters)
        }
    }
    
    fun close() {
        database.close()
        scope.cancel()
    }
}

data class LogFilters(
    val searchText: String = "",
    val levels: Set<LogLevel> = emptySet(),
    val tagFilter: String = ""
)