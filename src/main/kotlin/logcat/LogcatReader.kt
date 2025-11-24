package logcat

import models.LogEntry
import models.LogLevel
import models.DeviceInfo
import services.PackageNameResolver
import se.vidstige.jadb.JadbDevice
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
    private val packageResolver = PackageNameResolver()
    private var lastLogTime = 0L
    private val logGapThresholdMs = 5000L // 5 שניות - אם יש הפסקה יותר ארוכה, נעדכן את המיפוי
    
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
                val device = deviceInfo.jadbDevice as? JadbDevice
                    ?: throw IllegalArgumentException("Invalid device")
                
                println("Starting logcat for device: ${deviceInfo.id}")
                
                // התחל מעקב אחר PID לשם חבילה
                packageResolver.startTracking(deviceInfo)
                
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
                            // בדוק אם יש הפסקה ברצף הלוגים
                            val currentTime = System.currentTimeMillis()
                            if (lastLogTime > 0 && currentTime - lastLogTime > logGapThresholdMs) {
                                println("Detected log gap, forcing PID mapping update")
                                launch { packageResolver.forceUpdate() }
                            }
                            lastLogTime = currentTime
                            

                            
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
        packageResolver.stopTracking()
    }
    
    /**
     * פירסור שורת לוג
     */
    private fun parseLogLine(line: String): LogEntry? {
        return try {
            if (line.isBlank()) return null
            

            
            // threadtime format: MM-DD HH:MM:SS.mmm PID TID LEVEL TAG: MESSAGE
            // נסה כמה וריאציות של הרגקס
            val regexPatterns = listOf(
                // פורמט רגיל
                """(\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2}\.\d{3})\s+(\d+)\s+(\d+)\s+([VDIWEA])\s+([^:]+):\s*(.*)""".toRegex(),
                // פורמט עם רווחים נוספים
                """(\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2}\.\d{3})\s+(\d+)\s+(\d+)\s+([VDIWEA])\s+([^:]+?):\s*(.*)""".toRegex(),
                // פורמט עם שנה
                """(\d{4}-\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2}\.\d{3})\s+(\d+)\s+(\d+)\s+([VDIWEA])\s+([^:]+):\s*(.*)""".toRegex()
            )
            
            for (regex in regexPatterns) {
                val match = regex.find(line)
                if (match != null) {
                    val groups = match.groupValues
                    if (groups.size >= 7) {
                        val timestamp = groups[1]
                        val pid = groups[2]
                        val tid = groups[3]
                        val level = groups[4]
                        val tag = groups[5]
                        val message = groups[6]
                        
                        // וודא שה-timestamp לא ריק
                        if (timestamp.isBlank()) {
                            println("Warning: Empty timestamp in log line: $line")
                            continue
                        }
                        
                        val logLevel = when (level) {
                            "V" -> LogLevel.VERBOSE
                            "D" -> LogLevel.DEBUG
                            "I" -> LogLevel.INFO
                            "W" -> LogLevel.WARN
                            "E" -> LogLevel.ERROR
                            "A" -> LogLevel.ASSERT
                            else -> LogLevel.VERBOSE
                        }
                        
                        // קבל שם חבילה על פי PID
                        val packageName = packageResolver.getPackageName(pid)
                        

                        
                        return LogEntry(
                            id = logIdCounter++,
                            timestamp = timestamp.trim(),
                            pid = pid.trim(),
                            tid = tid.trim(),
                            level = logLevel,
                            tag = tag.trim(),
                            message = message.trim(),
                            packageName = packageName
                        )
                    }
                }
            }
            
            // אם שום רגקס לא עבד, נסה פורמט פשוט
            if (line.contains(":") && line.length > 10) {
                return LogEntry(
                    id = logIdCounter++,
                    timestamp = "00-00 00:00:00.000",
                    pid = "0",
                    tid = "0",
                    level = LogLevel.INFO,
                    tag = "System",
                    message = line.trim(),
                    packageName = ""
                )
            }
            
            return null
            
        } catch (e: Exception) {
            println("Error parsing line: $line - ${e.message}")
            null
        }
    }
    


    /**
     * בדיקה אם קורא כרגע
     */
    fun isReading(): Boolean = logcatJob?.isActive == true
    
    /**
     * ניקוי משאבים
     */
    fun cleanup() {
        stopReading()
        packageResolver.cleanup()
    }
}