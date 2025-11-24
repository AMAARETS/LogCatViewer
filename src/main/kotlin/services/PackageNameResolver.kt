package services

import models.DeviceInfo
import se.vidstige.jadb.JadbDevice
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.ConcurrentHashMap

/**
 * מחלקה לפתרון שמות חבילות על פי PID
 * משתמשת ב-cache חכם ובדיקות תקופתיות
 */
class PackageNameResolver {
    private val pidToPackageCache = ConcurrentHashMap<String, String>()
    private var lastUpdateTime = 0L
    private var updateJob: Job? = null
    private var currentDevice: DeviceInfo? = null
    private val updateIntervalMs = 30000L // עדכון כל 30 שניות
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    /**
     * התחלת מעקב אחר PID לשם חבילה
     */
    fun startTracking(deviceInfo: DeviceInfo) {
        currentDevice = deviceInfo
        updateJob?.cancel()
        
        updateJob = scope.launch {
            while (isActive) {
                try {
                    updatePidToPackageMapping()
                    delay(updateIntervalMs)
                } catch (e: Exception) {
                    println("Error updating PID to package mapping: ${e.message}")
                    delay(5000) // המתן 5 שניות במקרה של שגיאה
                }
            }
        }
    }
    
    /**
     * עצירת מעקב
     */
    fun stopTracking() {
        updateJob?.cancel()
        updateJob = null
        currentDevice = null
    }
    
    /**
     * קבלת שם חבילה על פי PID
     */
    fun getPackageName(pid: String): String {
        return pidToPackageCache[pid] ?: ""
    }
    
    /**
     * כפיית עדכון מיידי של המיפוי
     */
    suspend fun forceUpdate() {
        updatePidToPackageMapping()
    }
    
    /**
     * בדיקה אם יש צורך בעדכון (למקרה של הפסקה ברצף הלוגים)
     */
    fun shouldUpdate(): Boolean {
        val now = System.currentTimeMillis()
        return now - lastUpdateTime > updateIntervalMs
    }
    
    /**
     * עדכון המיפוי PID לשם חבילה
     */
    private suspend fun updatePidToPackageMapping() = withContext(Dispatchers.IO) {
        val device = currentDevice?.jadbDevice as? JadbDevice ?: return@withContext
        
        try {
            // נסה קודם עם ps (יותר מהיר)
            val psResult = getPidMappingFromPs(device)
            if (psResult.isNotEmpty()) {
                updateCache(psResult)
                return@withContext
            }
            
            // אם ps לא עבד, נסה עם dumpsys
            val dumpsysResult = getPidMappingFromDumpsys(device)
            if (dumpsysResult.isNotEmpty()) {
                updateCache(dumpsysResult)
            }
            
        } catch (e: Exception) {
            println("Failed to update PID mapping: ${e.message}")
        }
    }
    
    /**
     * קבלת מיפוי PID באמצעות ps
     */
    private suspend fun getPidMappingFromPs(device: JadbDevice): Map<String, String> = withContext(Dispatchers.IO) {
        val mapping = mutableMapOf<String, String>()
        
        try {
            val stream = device.executeShell("ps", "-A", "-o", "PID,NAME")
            val reader = BufferedReader(InputStreamReader(stream))
            
            reader.use {
                // דלג על כותרת
                reader.readLine()
                var line = reader.readLine()
                
                while (line != null) {
                    val parts = line.trim().split(Regex("\\s+"), 2)
                    if (parts.size >= 2) {
                        val pid = parts[0]
                        val processName = parts[1]
                        
                        // אם שם התהליך נראה כמו שם חבילה (מכיל נקודות)
                        if (processName.contains(".") && processName.matches(Regex("^[a-zA-Z][a-zA-Z0-9_.]*$"))) {
                            mapping[pid] = processName
                        }
                    }
                    line = reader.readLine()
                }
            }
        } catch (e: Exception) {
            println("Error getting PID mapping from ps: ${e.message}")
        }
        
        mapping
    }
    
    /**
     * קבלת מיפוי PID באמצעות dumpsys activity processes
     */
    private suspend fun getPidMappingFromDumpsys(device: JadbDevice): Map<String, String> = withContext(Dispatchers.IO) {
        val mapping = mutableMapOf<String, String>()
        
        try {
            val stream = device.executeShell("dumpsys", "activity", "processes")
            val reader = BufferedReader(InputStreamReader(stream))
            
            reader.use {
                var line = reader.readLine()
                var currentPackage = ""
                
                while (line != null) {
                    val trimmed = line.trim()
                    
                    // חפש שורות שמתחילות בשם חבילה
                    if (trimmed.matches(Regex("^[a-zA-Z][a-zA-Z0-9_.]*:.*"))) {
                        val colonIndex = trimmed.indexOf(':')
                        if (colonIndex > 0) {
                            currentPackage = trimmed.substring(0, colonIndex)
                        }
                    }
                    
                    // חפש PID בשורות הבאות
                    if (currentPackage.isNotEmpty() && trimmed.contains("pid=")) {
                        val pidMatch = Regex("pid=(\\d+)").find(trimmed)
                        if (pidMatch != null) {
                            val pid = pidMatch.groupValues[1]
                            mapping[pid] = currentPackage
                        }
                    }
                    
                    line = reader.readLine()
                }
            }
        } catch (e: Exception) {
            println("Error getting PID mapping from dumpsys: ${e.message}")
        }
        
        mapping
    }
    
    /**
     * עדכון ה-cache עם מיפוי חדש
     */
    private fun updateCache(newMapping: Map<String, String>) {
        // נקה PIDs ישנים שלא קיימים יותר
        val currentPids = newMapping.keys
        pidToPackageCache.keys.retainAll(currentPids)
        
        // הוסף/עדכן PIDs חדשים
        pidToPackageCache.putAll(newMapping)
        
        lastUpdateTime = System.currentTimeMillis()
        println("Updated PID to package mapping: ${newMapping.size} processes")
    }
    
    /**
     * ניקוי משאבים
     */
    fun cleanup() {
        updateJob?.cancel()
        pidToPackageCache.clear()
        scope.cancel()
    }
}