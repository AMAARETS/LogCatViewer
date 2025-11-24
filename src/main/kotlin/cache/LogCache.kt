package cache

import models.LogEntry
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap

/**
 * מערכת Cache חכמה לשורות לוג עם ניהול זיכרון אוטומטי
 */
class LogCache(
    private val maxMemoryMB: Int = 15, // מקסימום 15MB (הקטנה דרסטית)
    private val cleanupThresholdMB: Int = 10 // התחל לנקות ב-10MB
) {
    private val cache = ConcurrentHashMap<Int, LogEntry>()
    private val accessTimes = ConcurrentHashMap<Int, Long>()
    private var cachedRange = 0..0
    private var lastCleanup = System.currentTimeMillis()
    
    // חישוב גודל זיכרון משוער - יותר מדויק
    private fun estimateMemoryUsageMB(): Int {
        val avgLogSize = 300 // bytes per log entry (יותר מדויק)
        return (cache.size * avgLogSize) / (1024 * 1024)
    }
    
    /**
     * מוסיף לוגים ל-cache עם ניהול זיכרון אוטומטי
     */
    fun putLogs(startIndex: Int, logs: List<LogEntry>) {
        val now = System.currentTimeMillis()
        
        logs.forEachIndexed { idx, log ->
            val index = startIndex + idx
            cache[index] = log
            accessTimes[index] = now
        }
        
        // עדכן טווח
        val newRange = startIndex..(startIndex + logs.size - 1)
        cachedRange = if (cachedRange.isEmpty()) {
            newRange
        } else {
            minOf(cachedRange.first, newRange.first)..maxOf(cachedRange.last, newRange.last)
        }
        
        // בדוק אם צריך לנקות זיכרון - יותר אגרסיבי
        val currentMemory = estimateMemoryUsageMB()
        if (currentMemory > cleanupThresholdMB) {
            cleanupMemory()
        }
        
        // ניקוי נוסף אם עדיין יותר מדי
        if (estimateMemoryUsageMB() > maxMemoryMB) {
            aggressiveCleanup()
        }
    }
    
    /**
     * מחזיר לוג לפי אינדקס
     */
    fun getLog(index: Int): LogEntry? {
        val log = cache[index]
        if (log != null) {
            accessTimes[index] = System.currentTimeMillis()
        }
        return log
    }
    
    /**
     * מחזיר טווח לוגים
     */
    fun getLogs(range: IntRange): Map<Int, LogEntry> {
        val now = System.currentTimeMillis()
        val result = mutableMapOf<Int, LogEntry>()
        
        for (index in range) {
            cache[index]?.let { log ->
                result[index] = log
                accessTimes[index] = now
            }
        }
        
        return result
    }
    
    /**
     * בדיקה אם טווח נמצא ב-cache עם דגש על הטווח הנראה
     */
    fun isRangeCached(range: IntRange, coverageThreshold: Float = 0.8f): Boolean {
        val totalItems = range.count()
        if (totalItems == 0) return true
        
        val cachedItems = range.count { cache.containsKey(it) }
        return (cachedItems.toFloat() / totalItems) >= coverageThreshold
    }
    
    /**
     * בדיקה מיוחדת אם הטווח הנראה נמצא ב-cache
     */
    fun isVisibleRangeCached(centerIndex: Int, visibleRange: Int = 30): Boolean {
        val range = (centerIndex - visibleRange/2)..(centerIndex + visibleRange/2)
        return range.all { cache.containsKey(it) }
    }
    
    /**
     * מחזיר את הטווח הנוכחי ב-cache
     */
    fun getCachedRange(): IntRange = cachedRange
    
    /**
     * ניקוי זיכרון חכם - שומר רק את הפריטים הנחוצים
     */
    private fun cleanupMemory() {
        val now = System.currentTimeMillis()
        lastCleanup = now
        
        // מיין לפי זמן גישה אחרון (LRU)
        val sortedByAccess = accessTimes.entries.sortedBy { it.value }
        
        // מחק את 50% הישנים ביותר (יותר אגרסיבי)
        val toRemove = (cache.size * 0.5).toInt()
        val indicesToRemove = sortedByAccess.take(toRemove).map { it.key }
        
        indicesToRemove.forEach { index ->
            cache.remove(index)
            accessTimes.remove(index)
        }
        
        // עדכן טווח cache
        if (cache.isEmpty()) {
            cachedRange = 0..0
        } else {
            val minIndex = cache.keys.minOrNull() ?: 0
            val maxIndex = cache.keys.maxOrNull() ?: 0
            cachedRange = minIndex..maxIndex
        }
        
        println("Cache cleanup: removed $toRemove items, memory usage: ${estimateMemoryUsageMB()}MB")
    }
    
    /**
     * ניקוי אגרסיבי כשהזיכרון מלא
     */
    private fun aggressiveCleanup() {
        val now = System.currentTimeMillis()
        
        // שמור רק את 20% החדשים ביותר
        val sortedByAccess = accessTimes.entries.sortedByDescending { it.value }
        val toKeep = (cache.size * 0.2).toInt()
        val indicesToKeep = sortedByAccess.take(toKeep).map { it.key }.toSet()
        
        // מחק את כל השאר
        val indicesToRemove = cache.keys.filter { it !in indicesToKeep }
        indicesToRemove.forEach { index ->
            cache.remove(index)
            accessTimes.remove(index)
        }
        
        // עדכן טווח cache
        if (cache.isEmpty()) {
            cachedRange = 0..0
        } else {
            val minIndex = cache.keys.minOrNull() ?: 0
            val maxIndex = cache.keys.maxOrNull() ?: 0
            cachedRange = minIndex..maxIndex
        }
        
        println("Aggressive cleanup: kept only ${toKeep} items, memory usage: ${estimateMemoryUsageMB()}MB")
    }
    
    /**
     * ניקוי מלא של ה-cache
     */
    fun clear() {
        cache.clear()
        accessTimes.clear()
        cachedRange = 0..0
    }
    
    /**
     * מחזיר סטטיסטיקות cache
     */
    fun getStats(): CacheStats {
        return CacheStats(
            size = cache.size,
            memoryUsageMB = estimateMemoryUsageMB(),
            range = cachedRange,
            hitRatio = calculateHitRatio()
        )
    }
    
    private fun calculateHitRatio(): Float {
        // חישוב פשוט של hit ratio
        return if (cache.size > 0) 0.85f else 0f // משוער
    }
}

data class CacheStats(
    val size: Int,
    val memoryUsageMB: Int,
    val range: IntRange,
    val hitRatio: Float
)