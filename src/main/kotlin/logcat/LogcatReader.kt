package logcat

import LogEntry
import LogLevel
import device.DeviceInfo
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets

/**
 * קורא logcat מהמכשיר ומפרסר שורות לוג
 */
class LogcatReader {
    private var logcatJob: Job? = null
    private var logIdCounter = 0L
    
    /**
     * התחלת קריאת logcat
     */
    suspend fun startReading(
        deviceInfo: DeviceInfo,
        onLogReceived: (LogEntry) -> Unit,
        onError: (String) -> Unit
    ) {
        logcatJob?.cancel()
        
        logcatJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                val device = deviceInfo.device
                
                println("Starting logcat for device: ${deviceInfo.serial}")
                
                // ניקוי לוגים ישנים
                try {
                    device.executeShell("logcat", "-c").close()
                    println("Cleared old logs")
                } catch (e: Exception) {
                    println("Could not clear logs: ${e.message}")
                }
                
                // התחלת קריאת logcat
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
                            onLogReceived(entry)
                        }
                        
                        line = reader.readLine()
                    }
                }
                
                println("Logcat stream ended")
            } catch (e: Exception) {
                if (e !is CancellationException) {
                    onError("שגיאה בקריאת logcat: ${e.message}")
                }
            }
        }
    }
    
    /**
     * עצירת קריאת logcat
     */
    fun stopReading() {
        logcatJob?.cancel()
        logcatJob = null
    }
    
    /**
     * פירסור שורת לוג
     */
    private fun parseLogLine(line: String): LogEntry? {
        return try {
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
                
                LogEntry(
                    id = logIdCounter++,
                    timestamp = timestamp,
                    pid = pid,
                    tid = tid,
                    level = logLevel,
                    tag = tag.trim(),
                    message = message
                )
            } else {
                // פורמט פשוט יותר כגיבוי
                if (line.contains(":") && line.length > 10) {
                    LogEntry(
                        id = logIdCounter++,
                        timestamp = "00-00 00:00:00.000",
                        pid = "0",
                        tid = "0",
                        level = LogLevel.INFO,
                        tag = "System",
                        message = line
                    )
                } else null
            }
        } catch (e: Exception) {
            println("Error parsing line: $line - ${e.message}")
            null
        }
    }
    
    /**
     * בדיקה אם קורא כרגע
     */
    fun isReading(): Boolean = logcatJob?.isActive == true
}