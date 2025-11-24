package utils

/**
 * כלים לניהול זיכרון
 */
object MemoryUtils {
    /**
     * קבלת מידע על זיכרון נוכחי
     */
    fun getMemoryInfo(): MemoryInfo {
        val runtime = Runtime.getRuntime()
        val maxMemory = runtime.maxMemory()
        val totalMemory = runtime.totalMemory()
        val freeMemory = runtime.freeMemory()
        val usedMemory = totalMemory - freeMemory
        
        return MemoryInfo(
            maxMemory = maxMemory,
            totalMemory = totalMemory,
            usedMemory = usedMemory,
            freeMemory = freeMemory,
            availableMemory = maxMemory - usedMemory
        )
    }
    
    /**
     * בדיקה אם הזיכרון מתמלא
     */
    fun isMemoryLow(threshold: Double = 0.8): Boolean {
        val info = getMemoryInfo()
        val usageRatio = info.usedMemory.toDouble() / info.maxMemory.toDouble()
        return usageRatio > threshold
    }
    
    /**
     * ניקוי זיכרון
     */
    fun forceGarbageCollection() {
        System.gc()
        System.runFinalization()
    }
    
    /**
     * המרת bytes לפורמט קריא
     */
    fun formatBytes(bytes: Long): String {
        val units = arrayOf("B", "KB", "MB", "GB")
        var size = bytes.toDouble()
        var unitIndex = 0
        
        while (size >= 1024 && unitIndex < units.size - 1) {
            size /= 1024
            unitIndex++
        }
        
        return "%.1f %s".format(size, units[unitIndex])
    }
}

data class MemoryInfo(
    val maxMemory: Long,
    val totalMemory: Long,
    val usedMemory: Long,
    val freeMemory: Long,
    val availableMemory: Long
) {
    fun getUsagePercentage(): Double {
        return (usedMemory.toDouble() / maxMemory.toDouble()) * 100
    }
}