package scroll

import models.LogEntry
import LogcatViewModelNew
import cache.LogCache
import kotlinx.coroutines.*

/**
 * מנהל גלילה חכם עם אופטימיזציות זיכרון
 */
class ScrollManager(
    private val viewModel: LogcatViewModelNew,
    private val scope: CoroutineScope
) {
    private val cache = LogCache(maxMemoryMB = 8, cleanupThresholdMB = 6) // הקטנה דרסטית לחיסכון בזיכרון
    
    // הגדרות גלילה מותאמות לחיסכון בזיכרון - הקטנה נוספת
    private val baseWindowSize = 100  // הקטנה נוספת
    private val maxWindowSize = 300   // הקטנה נוספת
    private val ultraFastWindowSize = 500 // הקטנה נוספת
    
    private var currentWindowSize = baseWindowSize
    private var isLoading = false
    private var loadingJob: Job? = null
    
    // מעקב מהירות גלילה
    private var lastScrollTime = 0L
    private var lastScrollIndex = 0
    private var scrollVelocity = 0f
    private val scrollHistory = mutableListOf<Pair<Long, Int>>()
    private var isUltraFastScrolling = false
    
    /**
     * טוען לוגים לטווח נתון עם אופטימיזציות זיכרון ומניעת דף ריק
     */
    suspend fun loadLogsForRange(
        centerIndex: Int, 
        displayCount: Int,
        isScrollInProgress: Boolean,
        force: Boolean = false,
        isDragScrolling: Boolean = false
    ): Map<Int, LogEntry> {
        
        updateScrollMetrics(centerIndex, isScrollInProgress)
        
        val windowSize = calculateOptimalWindowSize(isScrollInProgress, isDragScrolling)
        val range = calculateLoadRange(centerIndex, displayCount, windowSize)
        
        // בדוק אם צריך לטעון
        if (!force && cache.isRangeCached(range, 0.7f) && !isLoading) {
            val cachedLogs = cache.getLogs(range)
            
            // וודא שיש לוגים בטווח הנראה
            val visibleRange = (centerIndex - 15)..(centerIndex + 15)
            val hasVisibleLogs = visibleRange.any { cachedLogs.containsKey(it) }
            
            if (hasVisibleLogs) {
                return cachedLogs
            }
            // אם אין לוגים בטווח הנראה, המשך לטעינה
        }
        
        // ביטול job קודם אם צריך - רק אם באמת צריך
        if ((isScrollInProgress || force) && loadingJob?.isActive == true) {
            loadingJob?.cancel()
        }
        
        // טעינה מיידית לטווח הנראה - תמיד טען מיידית לחלקות מקסימלית
        val visibleRange = if (isDragScrolling) {
            // בגלילה בעת גרירה, טען טווח מתון למניעת הפסקות אך חסכוני
            (centerIndex - 30)..(centerIndex + 30)
        } else if (isScrollInProgress) {
            // בגלילה רגילה, טען טווח קטן
            (centerIndex - 20)..(centerIndex + 20)
        } else {
            // במצב רגיל, טען טווח קטן
            (centerIndex - 15)..(centerIndex + 15)
        }
        
        val visibleLogs = loadVisibleRangeImmediate(visibleRange, displayCount)
        
        // טעינה ברקע לשאר הטווח - רק אם אין job פעיל
        if (loadingJob?.isActive != true) {
            loadingJob = scope.launch {
                if (isActive) {
                    loadRangeWithPriority(range, centerIndex, isScrollInProgress)
                }
            }
        }
        
        // החזר את הלוגים הנראים מיד + מה שיש ב-cache
        val allLogs = cache.getLogs(range).toMutableMap()
        allLogs.putAll(visibleLogs)
        
        return allLogs
    }
    
    /**
     * עדכון מטריקות גלילה
     */
    private fun updateScrollMetrics(centerIndex: Int, isScrollInProgress: Boolean) {
        val now = System.currentTimeMillis()
        val scrollDistance = centerIndex - lastScrollIndex
        val timeDelta = now - lastScrollTime
        
        if (timeDelta > 0 && scrollDistance != 0 && isScrollInProgress) {
            scrollVelocity = kotlin.math.abs(scrollDistance.toFloat() / timeDelta.toFloat())
            scrollHistory.add(now to centerIndex)
            
            // זיהוי גלילה מהירה מאוד (יותר מ-100 שורות בשנייה)
            isUltraFastScrolling = scrollVelocity > 0.1f
            
            // שמור רק 3 מדידות אחרונות (חיסכון בזיכרון)
            if (scrollHistory.size > 3) {
                scrollHistory.removeAt(0)
            }
        } else if (!isScrollInProgress) {
            // כשהגלילה נעצרת, אפס את הדגל
            isUltraFastScrolling = false
        }
        
        lastScrollTime = now
        lastScrollIndex = centerIndex
    }
    
    /**
     * חישוב גודל חלון אופטימלי לפי מהירות גלילה
     */
    private fun calculateOptimalWindowSize(isScrollInProgress: Boolean, isDragScrolling: Boolean = false): Int {
        if (!isScrollInProgress) return baseWindowSize
        
        // בגלילה בעת גרירה לבחירה, השתמש בחלון מתון לחיסכון במשאבים
        if (isDragScrolling) {
            return maxWindowSize // חלון מתון לגלילה חלקה אך חסכונית
        }
        
        val avgVelocity = calculateAverageVelocity()
        
        return when {
            avgVelocity > 1.5f -> ultraFastWindowSize
            avgVelocity > 0.6f -> maxWindowSize
            avgVelocity > 0.2f -> baseWindowSize * 2
            else -> baseWindowSize
        }
    }
    
    /**
     * חישוב מהירות ממוצעת
     */
    private fun calculateAverageVelocity(): Float {
        if (scrollHistory.size < 2) return scrollVelocity
        
        val recent = scrollHistory.takeLast(3)
        val totalDistance = kotlin.math.abs(recent.last().second - recent.first().second)
        val totalTime = recent.last().first - recent.first().first
        
        return if (totalTime > 0) totalDistance.toFloat() / totalTime.toFloat() else 0f
    }
    
    /**
     * חישוב טווח טעינה עם bias לכיוון גלילה
     */
    private fun calculateLoadRange(centerIndex: Int, displayCount: Int, windowSize: Int): IntRange {
        val scrollDirection = if (lastScrollIndex != 0) {
            if (centerIndex > lastScrollIndex) 1 else -1
        } else 0
        
        // bias קטן יותר לחיסכון בזיכרון
        val bias = if (scrollDirection != 0) scrollDirection * (windowSize / 6) else 0
        
        val halfWindow = windowSize / 2
        val startIndex = maxOf(0, centerIndex - halfWindow + bias)
        val endIndex = minOf(displayCount - 1, centerIndex + halfWindow + bias)
        
        return startIndex..endIndex
    }
    
    /**
     * טעינה מיידית לטווח הנראה למניעת דף ריק
     */
    private suspend fun loadVisibleRangeImmediate(
        visibleRange: IntRange,
        displayCount: Int
    ): Map<Int, LogEntry> = withContext(Dispatchers.IO) {
        val result = mutableMapOf<Int, LogEntry>()
        
        try {
            val startIdx = maxOf(0, visibleRange.first)
            val endIdx = minOf(displayCount - 1, visibleRange.last)
            val count = endIdx - startIdx + 1
            
            if (count > 0) {
                // טען בחלקים קטנים למהירות מקסימלית
                val chunkSize = 50
                for (chunkStart in startIdx..endIdx step chunkSize) {
                    val chunkEnd = minOf(chunkStart + chunkSize - 1, endIdx)
                    val chunkCount = chunkEnd - chunkStart + 1
                    
                    if (chunkCount > 0) {
                        val logs = viewModel.getLogsPage(chunkStart, chunkCount)
                        logs.forEachIndexed { idx, log ->
                            result[chunkStart + idx] = log
                        }
                        
                        // שמור ב-cache מיידית
                        cache.putLogs(chunkStart, logs)
                    }
                }
            }
        } catch (e: Exception) {
            // במקרה של שגיאה, לא נחזיר רשימה ריקה אלא נתעלם מהשגיאה
        }
        
        return@withContext result
    }

    /**
     * טעינה בעדיפויות עם חיסכון בזיכרון
     */
    private suspend fun loadRangeWithPriority(
        range: IntRange, 
        centerIndex: Int, 
        isScrollInProgress: Boolean
    ) = withContext(Dispatchers.IO) {
        if (!coroutineContext.isActive) return@withContext
        
        isLoading = true
        try {
            // עדיפות 1: טווח נראה קטן (מיידי) - רק אם לא כבר נטען
            val visibleRange = (centerIndex - 15)..(centerIndex + 15)
            val priorityRange = range.intersect(visibleRange.toSet()).let { 
                if (it.isEmpty()) range.take(30) else it.toList() 
            }
            
            if (priorityRange.isNotEmpty()) {
                val startIdx = priorityRange.minOrNull() ?: range.first
                val endIdx = priorityRange.maxOrNull() ?: range.first + 29
                
                // בדוק אם הטווח הזה כבר נטען
                val needsLoading = (startIdx..endIdx).any { cache.getLog(it) == null }
                
                if (needsLoading) {
                    val logs = viewModel.getLogsPage(startIdx, endIdx - startIdx + 1)
                    if (logs.isNotEmpty()) {
                        cache.putLogs(startIdx, logs)
                    }
                }
            }
            
            // עדיפות 2: שאר הטווח בחלקים קטנים (רקע)
            if (isScrollInProgress) {
                delay(10) // עיכוב מינימלי
            } else {
                delay(50) // עיכוב גדול יותר כשלא גוללים
            }
            
            val remainingRange = range.subtract(priorityRange.toSet())
            val chunkSize = if (isScrollInProgress) 100 else 50 // חלקים קטנים יותר
            
            remainingRange.chunked(chunkSize).forEach { chunk ->
                if (!coroutineContext.isActive) return@forEach
                
                val chunkStart = chunk.minOrNull() ?: return@forEach
                val chunkEnd = chunk.maxOrNull() ?: return@forEach
                
                // בדוק אם החלק הזה צריך טעינה
                val chunkNeedsLoading = (chunkStart..chunkEnd).any { cache.getLog(it) == null }
                
                if (chunkNeedsLoading) {
                    val logs = viewModel.getLogsPage(chunkStart, chunkEnd - chunkStart + 1)
                    if (logs.isNotEmpty()) {
                        cache.putLogs(chunkStart, logs)
                    }
                }
                
                // עיכוב קטן בין chunks
                if (isScrollInProgress) {
                    delay(5)
                } else {
                    delay(20)
                }
            }
            
        } finally {
            isLoading = false
        }
    }
    
    /**
     * מחזיר לוג לפי אינדקס
     */
    fun getLog(index: Int): LogEntry? = cache.getLog(index)
    
    /**
     * ניקוי cache
     */
    fun clearCache() = cache.clear()
    
    /**
     * סטטיסטיקות
     */
    fun getCacheStats() = cache.getStats()
    
    /**
     * ניקוי משאבים
     */
    fun cleanup() {
        loadingJob?.cancel()
        cache.clear()
    }
}

// Extension function לחישוב intersection
private fun IntRange.intersect(other: Set<Int>): Set<Int> {
    return this.filter { it in other }.toSet()
}