import kotlinx.coroutines.*
import state.*
import services.*
import models.*
import device.DeviceManager
import logcat.LogcatReader
import settings.PerformanceSettings
import androidx.compose.runtime.*

/**
 * ViewModel מחודש - קטן ומתמקד בתיאום בין השירותים
 */
class LogcatViewModelNew {
    // מנהלי מצב
    val state = LogcatState()
    val filterState = FilterState()
    
    // הגדרות ביצועים
    var performanceSettings by mutableStateOf(PerformanceSettings.load())
        private set
    
    // מצב מסך הגדרות
    var isSettingsOpen by mutableStateOf(false)
        private set
    
    // מערכת חלונות לוגים
    var currentWindowIndex by mutableStateOf(0)
        private set
    
    var totalWindows by mutableStateOf(1)
        private set
    
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
        // חישוב offset אמיתי לפי החלון הנוכחי
        val windowOffset = currentWindowIndex * performanceSettings.batchSize
        val actualOffset = windowOffset + offset
        return databaseService.getLogs(actualOffset, limit, filters.searchText, filters.levels, filters.tagFilter, filters.packageFilter)
    }
    
    suspend fun getUniquePackageNames(): List<String> {
        return databaseService.getUniquePackageNames()
    }
    
    suspend fun getLogsBatch(requests: List<Pair<Int, Int>>): Map<Int, List<LogEntry>> {
        return databaseService.getLogsBatch(requests, filterState.getFilters())
    }
    
    // פונקציות הגדרות
    fun openSettings() {
        isSettingsOpen = true
    }
    
    fun closeSettings() {
        isSettingsOpen = false
    }
    
    fun updatePerformanceSettings(newSettings: PerformanceSettings) {
        performanceSettings = newSettings.validate()
        PerformanceSettings.save(performanceSettings)
        
        // עדכן את הגבלת הלוגים
        val maxLogs = performanceSettings.batchSize
        state.filteredLogCount.value = minOf(state.filteredLogCount.value, maxLogs)
        
        // הודע למסך על השינוי
        state.statusMessage.value = "הגדרות עודכנו - מקסימום ${performanceSettings.batchSize} שורות"
    }
    
    // פונקציות ניווט בחלונות
    fun goToNextWindow() {
        if (currentWindowIndex < totalWindows - 1) {
            currentWindowIndex++
            scope.launch {
                updateLogCounts()
                state.statusMessage.value = "עבר לחלון ${currentWindowIndex + 1} מתוך $totalWindows"
            }
        }
    }
    
    fun goToPreviousWindow() {
        if (currentWindowIndex > 0) {
            currentWindowIndex--
            scope.launch {
                updateLogCounts()
                state.statusMessage.value = "עבר לחלון ${currentWindowIndex + 1} מתוך $totalWindows"
            }
        }
    }
    
    fun goToLatestWindow() {
        currentWindowIndex = totalWindows - 1
        scope.launch {
            updateLogCounts()
            state.statusMessage.value = "עבר לחלון האחרון (${totalWindows})"
        }
    }
    
    fun goToFirstWindow() {
        currentWindowIndex = 0
        scope.launch {
            updateLogCounts()
            state.statusMessage.value = "עבר לחלון הראשון"
        }
    }
    
    suspend fun getLogsForCurrentWindow(): List<LogEntry> {
        val batchSize = performanceSettings.batchSize
        val offset = currentWindowIndex * batchSize
        return getLogsPage(offset, batchSize)
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
        
        // חישוב מספר החלונות
        val batchSize = performanceSettings.batchSize
        val newTotalWindows = if (filteredCount > 0) {
            ((filteredCount - 1) / batchSize) + 1
        } else {
            1
        }
        
        // עדכון מספר החלונות
        totalWindows = newTotalWindows
        
        // וודא שהחלון הנוכחי תקין
        if (currentWindowIndex >= totalWindows) {
            currentWindowIndex = maxOf(0, totalWindows - 1)
        }
        
        // החלון הנוכחי מציג תמיד עד batchSize שורות
        val currentWindowSize = if (currentWindowIndex == totalWindows - 1) {
            // החלון האחרון - עלול להיות קטן יותר
            val remainingLogs = filteredCount - (currentWindowIndex * batchSize)
            minOf(remainingLogs, batchSize)
        } else {
            // חלון מלא
            batchSize
        }
        
        withContext(Dispatchers.Main) {
            state.updateLogCounts(filteredCount, currentWindowSize)
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