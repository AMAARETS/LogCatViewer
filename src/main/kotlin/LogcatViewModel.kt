import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import se.vidstige.jadb.JadbConnection
import se.vidstige.jadb.JadbDevice
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.nio.charset.StandardCharsets

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

data class DeviceInfo(
    val device: JadbDevice,
    val serial: String,
    val model: String
)

class LogcatViewModel {
    val devices = mutableStateListOf<DeviceInfo>()
    val selectedDevice = mutableStateOf<DeviceInfo?>(null)
    val isRunning = mutableStateOf(false)
    val statusMessage = mutableStateOf("מוכן")
    val totalLogCount = mutableStateOf(0)
    val filteredLogCount = mutableStateOf(0)
    
    // Filters
    val searchText = mutableStateOf("")
    val selectedLevels = mutableStateOf(setOf<LogLevel>())
    val tagFilter = mutableStateOf("")
    val autoScroll = mutableStateOf(true)
    
    // For triggering UI updates on new logs
    val lastLogUpdate = mutableStateOf(0L)
    
    private var jadb: JadbConnection? = null
    private var logcatJob: Job? = null
    private var deviceScanJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val database = LogDatabase()
    private val logBuffer = mutableListOf<LogEntry>()
    private var logIdCounter = 0L
    private val batchSize = 100  // Smaller batches for more frequent UI updates
    
    // Periodic flush job
    private var flushJob: Job? = null
    
    fun initialize() {
        scope.launch {
            try {
                statusMessage.value = "מאתחל..."
                
                // Initialize database
                database.initialize()
                
                // Ensure ADB is installed and running
                val adbStarted = EmbeddedAdb.startAdbServer { progress ->
                    statusMessage.value = progress
                }
                
                if (!adbStarted) {
                    statusMessage.value = "שגיאה: לא ניתן להפעיל ADB"
                    return@launch
                }
                
                // Get ADB version
                EmbeddedAdb.getAdbVersion()?.let { version ->
                    println("ADB Version: $version")
                }
                
                statusMessage.value = "מתחבר ל-ADB..."
                
                // Connect to ADB server
                jadb = try {
                    JadbConnection()
                } catch (e: Exception) {
                    statusMessage.value = "שגיאה בחיבור ל-ADB: ${e.message}"
                    e.printStackTrace()
                    return@launch
                }
                
                statusMessage.value = "מחובר ל-ADB"
                
                // Start periodic device scanning
                startDeviceScanning()
                
                // Initial scan
                refreshDevices()
                
                // Update log count
                updateLogCount()
                
            } catch (e: Exception) {
                statusMessage.value = "שגיאה: ${e.message}"
                e.printStackTrace()
            }
        }
    }
    

    
    private fun startDeviceScanning() {
        deviceScanJob = scope.launch {
            while (isActive) {
                try {
                    refreshDevices()
                    delay(3000) // Scan every 3 seconds
                } catch (e: Exception) {
                    // Ignore errors during scanning
                }
            }
        }
    }
    
    fun refreshDevices() {
        scope.launch {
            try {
                val connection = jadb ?: return@launch
                val deviceList = connection.devices
                
                val deviceInfos = deviceList.mapNotNull { device ->
                    try {
                        val serial = device.serial
                        val model = try {
                            val stream = device.executeShell("getprop", "ro.product.model")
                            BufferedReader(InputStreamReader(stream, StandardCharsets.UTF_8)).use { reader ->
                                reader.readLine()?.trim() ?: "Unknown"
                            }
                        } catch (e: Exception) {
                            "Unknown"
                        }
                        DeviceInfo(device, serial, model)
                    } catch (e: Exception) {
                        null
                    }
                }
                
                devices.clear()
                devices.addAll(deviceInfos)
                
                // Auto-select first device if none selected
                if (selectedDevice.value == null && devices.isNotEmpty()) {
                    selectedDevice.value = devices.first()
                }
                
                statusMessage.value = "נמצאו ${devices.size} מכשירים"
            } catch (e: Exception) {
                statusMessage.value = "שגיאה בסריקת מכשירים: ${e.message}"
            }
        }
    }
    
    suspend fun startLogcat() {
        val deviceInfo = selectedDevice.value ?: return
        
        if (isRunning.value) return
        
        isRunning.value = true
        statusMessage.value = "מקבל לוגים..."
        
        // Start periodic flush job (every 300ms for more responsive UI)
        flushJob = scope.launch {
            while (isActive) {
                delay(300)
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
        }
        
        logcatJob = scope.launch {
            try {
                val device = deviceInfo.device
                
                println("Starting logcat for device: ${deviceInfo.serial}")
                
                // Clear old logs first
                try {
                    device.executeShell("logcat", "-c").close()
                    println("Cleared old logs")
                } catch (e: Exception) {
                    println("Could not clear logs: ${e.message}")
                }
                
                // Start reading logcat with threadtime format for better parsing
                println("Executing logcat command...")
                val stream = device.executeShell("logcat", "-v", "threadtime")
                val reader = BufferedReader(InputStreamReader(stream, StandardCharsets.UTF_8))
                
                println("Reading logcat stream...")
                var lineCount = 0
                
                reader.use {
                    var line = reader.readLine()
                    while (isActive && line != null) {
                        lineCount++
                        if (lineCount % 1000 == 0) {
                            println("Read $lineCount lines")
                        }
                        
                        parseLogLine(line)?.let { entry ->
                            val toFlush = synchronized(logBuffer) {
                                logBuffer.add(entry)
                                
                                // Immediate flush if buffer is too large
                                if (logBuffer.size >= batchSize) {
                                    val list = logBuffer.toList()
                                    logBuffer.clear()
                                    list
                                } else null
                            }
                            
                            if (toFlush != null) {
                                launch {
                                    database.insertLogsBatch(toFlush)
                                    withContext(Dispatchers.Main) {
                                        updateLogCount()
                                    }
                                }
                            }
                        }
                        line = reader.readLine()
                    }
                    
                    // Insert remaining logs
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
                
                println("Logcat stream ended")
            } catch (e: Exception) {
                if (e !is CancellationException) {
                    statusMessage.value = "שגיאה: ${e.message}"
                    e.printStackTrace()
                }
                isRunning.value = false
            }
        }
    }
    
    fun stopLogcat() {
        logcatJob?.cancel()
        flushJob?.cancel()
        
        // Flush remaining logs
        scope.launch {
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
        // Query database directly without caching for better real-time updates
        return database.getLogs(
            offset = offset,
            limit = limit,
            searchText = searchText.value,
            levels = selectedLevels.value,
            tagFilter = tagFilter.value
        )
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
    
    private fun parseLogLine(line: String): LogEntry? {
        try {
            // threadtime format: MM-DD HH:MM:SS.mmm PID TID LEVEL TAG: MESSAGE
            val regex = """(\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2}\.\d{3})\s+(\d+)\s+(\d+)\s+([VDIWEA])\s+(.+?):\s+(.*)""".toRegex()
            val match = regex.find(line)
            
            if (match != null) {
                val (timestamp, pid, tid, level, tag, message) = match.destructured
                
                val logLevel = when (level) {
                    "V" -> LogLevel.VERBOSE
                    "D" -> LogLevel.DEBUG
                    "I" -> LogLevel.INFO
                    "W" -> LogLevel.WARN
                    "E" -> LogLevel.ERROR
                    "A" -> LogLevel.ASSERT
                    else -> LogLevel.VERBOSE
                }
                
                return LogEntry(
                    id = logIdCounter++,
                    timestamp = timestamp,
                    pid = pid,
                    tid = tid,
                    level = logLevel,
                    tag = tag.trim(),
                    message = message
                )
            }
            
            // Try simpler format as fallback
            if (line.contains(":") && line.length > 10) {
                // Just show it as info log
                return LogEntry(
                    id = logIdCounter++,
                    timestamp = "00-00 00:00:00.000",
                    pid = "0",
                    tid = "0",
                    level = LogLevel.INFO,
                    tag = "System",
                    message = line
                )
            }
            
            return null
        } catch (e: Exception) {
            println("Error parsing line: $line - ${e.message}")
            return null
        }
    }
    
    fun onFiltersChanged() {
        scope.launch {
            updateLogCount()
        }
    }
    
    fun cleanup() {
        stopLogcat()
        deviceScanJob?.cancel()
        database.close()
        scope.cancel()
        // Note: We don't stop ADB server here as other apps might be using it
    }
}
