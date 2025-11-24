import kotlinx.coroutines.*
import state.*
import services.*
import models.*
import device.DeviceManager
import logcat.LogcatReader

/**
 * ViewModel מחודש - קטן ומתמקד בתיאום בין השירותים
 */
class LogcatViewModelNew {
    // מנהלי מצב
    val state = LogcatState()
    val filterState = FilterState()
    
    // שירותים
    private val deviceManager = DeviceManager()
    private val logcatReader = LogcatReader()
    private val logService = LogService()
    private val databaseService = DatabaseService()
    private val exportService = ExportService(databaseService)
    
    // Coroutines
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    
    // חשיפת מצבים מהמודולים
    val devices = deviceManager.devices
    val selectedDevice = deviceManager.selectedDevice
    
    init {
        setupCallbacks()
    }
    
    private fun setupCallbacks() {
        // קישור בין FilterState לעדכון מונים
        filterState.onFiltersChanged = {
            scope.launch {
                updateLogCounts()
            }
        }
        
        // קישור LogService למסד נתונים
        logService.onBatchProcessed = { logs ->
            scope.launch {

                
                databaseService.insertLogsBatch(logs)
                updateLogCounts()
            }
        }
        
        logService.onError = { error ->
            state.statusMessage.value = error
        }
    }
    
    fun initialize() {
        scope.launch {
            try {
                // אתחול מסד נתונים
                val dbInitialized = databaseService.initialize()
                if (!dbInitialized) {
                    state.statusMessage.value = "שגיאה באתחול מסד נתונים"
                    return@launch
                }
                
                // אתחול מנהל מכשירים
                val deviceInitialized = deviceManager.initialize()
                if (!deviceInitialized) {
                    state.statusMessage.value = "שגיאה באתחול מנהל מכשירים"
                    return@launch
                }
                
                // עדכון מונים
                updateLogCounts()
                state.statusMessage.value = "מוכן"
                
            } catch (e: Exception) {
                state.statusMessage.value = "שגיאה: ${e.message}"
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
        
        if (state.isRunning.value) return
        
        state.setRunning(true, "מקבל לוגים...")
        
        // התחלת שירות עיבוד לוגים
        logService.startProcessing()
        
        // התחלת קריאת logcat
        scope.launch {
            logcatReader.startReading(
                deviceInfo = deviceInfo,
                onLogReceived = { logEntry ->

                    logService.addLog(logEntry)
                },
                onError = { error ->
                    state.statusMessage.value = error
                    stopLogcat()
                }
            )
        }
    }
    
    fun stopLogcat() {
        logcatReader.stopReading()
        logService.stopProcessing()
        state.setRunning(false, "עצר")
    }
    
    fun clearLogs() {
        scope.launch {
            databaseService.clearAllLogs()
            state.updateLogCounts(0, 0)
            state.statusMessage.value = "לוגים נוקו"
        }
    }
    
    suspend fun exportLogs() {
        try {
            val filePath = exportService.exportToText(filterState.getFilters())
            state.statusMessage.value = "יוצא ל: $filePath"
        } catch (e: Exception) {
            state.statusMessage.value = "שגיאה בייצוא: ${e.message}"
        }
    }
    
    suspend fun getLogsPage(offset: Int, limit: Int): List<LogEntry> {
        val filters = filterState.getFilters()
        return databaseService.getLogs(offset, limit, filters.searchText, filters.levels, filters.tagFilter, filters.packageFilter)
    }
    
    suspend fun getUniquePackageNames(): List<String> {
        return databaseService.getUniquePackageNames()
    }
    
    suspend fun getLogsBatch(requests: List<Pair<Int, Int>>): Map<Int, List<LogEntry>> {
        return databaseService.getLogsBatch(requests, filterState.getFilters())
    }
    
    private suspend fun updateLogCounts() {
        val filters = filterState.getFilters()
        val totalCount = databaseService.getTotalLogCount()
        val filteredCount = databaseService.getLogCount(
            filters.searchText,
            filters.levels,
            filters.tagFilter,
            filters.packageFilter
        )
        
        withContext(Dispatchers.Main) {
            state.updateLogCounts(totalCount, filteredCount)
        }
    }
    
    fun cleanup() {
        stopLogcat()
        logService.cleanup()
        databaseService.close()
        exportService.cleanup()
        deviceManager.cleanup()
        scope.cancel()
    }
}