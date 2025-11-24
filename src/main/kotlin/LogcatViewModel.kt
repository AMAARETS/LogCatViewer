import androidx.compose.runtime.mutableStateOf
import device.DeviceManager
import logcat.LogcatReader
import filters.LogFilter
import kotlinx.coroutines.*
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

data class LogEntry(
    val id: Long,
    val timestamp: String,
    val pid: String,
    val tid: String,
    val level: LogLevel,
    val tag: String,
    val message: String
)

enum class LogLevel(val displayName: String, val color: androidx.compose.ui.graphics.Color) {
    VERBOSE("V", androidx.compose.ui.graphics.Color(0xFF9E9E9E)),
    DEBUG("D", androidx.compose.ui.graphics.Color(0xFF2196F3)),
    INFO("I", androidx.compose.ui.graphics.Color(0xFF4CAF50)),
    WARN("W", androidx.compose.ui.graphics.Color(0xFFFFC107)),
    ERROR("E", androidx.compose.ui.graphics.Color(0xFFF44336)),
    ASSERT("A", androidx.compose.ui.graphics.Color(0xFF9C27B0))
}

/**
 * ViewModel מרכזי מפוצל למודולים קטנים
 */
class LogcatViewModel {
    // מודולים
    private val deviceManager = DeviceManager()
    private val logcatReader = LogcatReader()
    private val logFilter = LogFilter()
    private val database = LogDatabase()
    
    // מצב כללי
    val isRunning = mutableStateOf(false)
    val totalLogCount = mutableStateOf(0)
    val filteredLogCount = mutableStateOf(0)
    val autoScroll = mutableStateOf(true)
    val lastLogUpdate = mutableStateOf(0L)
    
    // חשיפת מודולים לUI
    val devices = deviceManager.devices
    val selectedDevice = deviceManager.selectedDevice
    val statusMessage = deviceManager.statusMessage
    val searchText = logFilter.searchText
    val selectedLevels = logFilter.selectedLevels
    val tagFilter = logFilter.tagFilter
    
    // ניהול זיכרון - הקטנה נוספת
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val logBuffer = mutableListOf<LogEntry>()
    private val batchSize = 25  // הקטנה נוספת לחיסכון בזיכרון
    private var flushJob: Job? = null
    
    fun initialize() {
        scope.launch {
            try {
                // אתחול מסד נתונים
                database.initialize()
                
                // אתחול מנהל מכשירים
                val deviceInitialized = deviceManager.initialize()
                if (!deviceInitialized) {
                    return@launch
                }
                
                // עדכון מספר לוגים
                updateLogCount()
                
            } catch (e: Exception) {
                statusMessage.value = "שגיאה: ${e.message}"
                e.printStackTrace()
            }
        }
    }
    

    fun refreshDevices() {
        scope.launch {
            deviceManager.refreshDevices()
        }
    }
    
    suspend fun startLogcat() {
        val deviceInfo = deviceManager.getSelectedDevice() ?: return
        
        if (isRunning.value) return
        
        isRunning.value = true
        statusMessage.value = "מקבל לוגים..."
        
        // התחלת flush תקופתי (חיסכון בזיכרון)
        startPeriodicFlush()
        
        // התחלת קריאת logcat
        logcatReader.startReading(
            deviceInfo = deviceInfo,
            onLogReceived = { logEntry ->
                handleNewLog(logEntry)
            },
            onError = { error ->
                statusMessage.value = error
                isRunning.value = false
            }
        )
    }
    
    fun stopLogcat() {
        logcatReader.stopReading()
        flushJob?.cancel()
        
        // שטיפה אחרונה של לוגים
        scope.launch {
            flushRemainingLogs()
        }
        
        isRunning.value = false
        statusMessage.value = "עצר"
    }
    
    fun clearLogs() {
        scope.launch {
            database.clearAllLogs()
            withContext(Dispatchers.Main) {
                totalLogCount.value = 0
                statusMessage.value = "לוגים נוקו"
            }
        }
    }
    
    /**
     * התחלת flush תקופתי עם חיסכון בזיכרון
     */
    private fun startPeriodicFlush() {
        flushJob = scope.launch {
            while (isActive) {
                delay(100) // תדירות גבוהה יותר לחיסכון בזיכרון
                flushLogBuffer()
                
                // ניקוי זיכרון תקופתי
                if (System.currentTimeMillis() % 10000 < 100) { // כל 10 שניות בערך
                    System.gc()
                }
            }
        }
    }
    
    /**
     * טיפול בלוג חדש
     */
    private fun handleNewLog(logEntry: LogEntry) {
        synchronized(logBuffer) {
            logBuffer.add(logEntry)
            
            // flush מיידי אם הbuffer מלא
            if (logBuffer.size >= batchSize) {
                scope.launch {
                    flushLogBuffer()
                }
            }
        }
    }
    
    /**
     * שטיפת buffer הלוגים
     */
    private suspend fun flushLogBuffer() {
        val toFlush = synchronized(logBuffer) {
            if (logBuffer.isNotEmpty()) {
                val list = logBuffer.toList()
                logBuffer.clear()
                list
            } else null
        }
        
        if (toFlush != null) {
            database.insertLogsBatch(toFlush)
            withContext(Dispatchers.Main) {
                updateLogCount()
            }
        }
    }
    
    /**
     * שטיפה אחרונה של לוגים
     */
    private suspend fun flushRemainingLogs() {
        val remaining = synchronized(logBuffer) {
            if (logBuffer.isNotEmpty()) {
                val list = logBuffer.toList()
                logBuffer.clear()
                list
            } else null
        }
        
        if (remaining != null) {
            database.insertLogsBatch(remaining)
            withContext(Dispatchers.Main) {
                updateLogCount()
            }
        }
    }
    
    /**
     * עדכון מספר לוגים
     */
    private suspend fun updateLogCount() {
        val totalCount = database.getTotalLogCount()
        val filteredCount = database.getLogCount(
            searchText.value,
            selectedLevels.value,
            tagFilter.value
        )
        withContext(Dispatchers.Main) {
            totalLogCount.value = totalCount
            filteredLogCount.value = filteredCount
            lastLogUpdate.value = System.currentTimeMillis()
        }
    }
    
    suspend fun getLogsPage(offset: Int, limit: Int): List<LogEntry> {
        // Ultra-optimized database query with connection pooling
        return withContext(Dispatchers.IO) {
            try {
                database.getLogs(
                    offset = offset,
                    limit = limit,
                    searchText = searchText.value,
                    levels = selectedLevels.value,
                    tagFilter = tagFilter.value
                )
            } catch (e: Exception) {
                // במקום להחזיר רשימה ריקה, נסה שוב עם פרמטרים מותאמים
                println("שגיאה בטעינת לוגים: ${e.message}, מנסה שוב...")
                try {
                    // נסה עם limit קטן יותר
                    val safeLimit = minOf(limit, 50)
                    database.getLogs(
                        offset = offset,
                        limit = safeLimit,
                        searchText = searchText.value,
                        levels = selectedLevels.value,
                        tagFilter = tagFilter.value
                    )
                } catch (e2: Exception) {
                    // רק אם גם הניסיון השני נכשל, החזר רשימה ריקה
                    println("שגיאה חוזרת בטעינת לוגים: ${e2.message}")
                    emptyList()
                }
            }
        }
    }
    
    // Batch loading for ultra-fast scrolling
    suspend fun getLogsBatch(requests: List<Pair<Int, Int>>): Map<Int, List<LogEntry>> {
        return withContext(Dispatchers.IO) {
            val results = mutableMapOf<Int, List<LogEntry>>()
            
            // Process requests in parallel for maximum speed
            requests.map { (offset, limit) ->
                async {
                    try {
                        val logs = database.getLogs(
                            offset = offset,
                            limit = limit,
                            searchText = searchText.value,
                            levels = selectedLevels.value,
                            tagFilter = tagFilter.value
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
    
    suspend fun exportLogs() {
        withContext(Dispatchers.IO) {
            try {
                val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
                val file = File("logcat_export_$timestamp.txt")
                
                val filters = LogFilters(
                    searchText = searchText.value,
                    levels = selectedLevels.value,
                    tagFilter = tagFilter.value
                )
                
                database.exportLogs(file.absolutePath, filters)
                
                statusMessage.value = "יוצא ל: ${file.absolutePath}"
            } catch (e: Exception) {
                statusMessage.value = "שגיאה בייצוא: ${e.message}"
            }
        }
    }
    

    
    fun onFiltersChanged() {
        scope.launch {
            updateLogCount()
        }
    }
    
    fun cleanup() {
        stopLogcat()
        deviceManager.cleanup()
        database.close()
        scope.cancel()
    }
}
